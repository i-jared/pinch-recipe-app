import Foundation
import Observation

/// Two-way sync between the local recipe files and a "Pinch Recipes" folder in Google
/// Drive. Each recipe is one JSON file tagged with its ID and last-edit time; the newer
/// side wins. Deletions travel as tombstones. Everything works without it: when the
/// phone is offline, sync simply waits.
@Observable
@MainActor
final class DriveSync {
    enum State: Equatable {
        /// This build has no Google client ID configured.
        case unavailable
        case disconnected
        case connecting
        case idle
        case syncing
        case offline
        case failed(String)
    }

    private(set) var state: State
    private(set) var accountEmail: String?
    private(set) var lastSynced: Date?

    @ObservationIgnored private let store: RecipeStore
    @ObservationIgnored private let auth: GoogleAuth?
    @ObservationIgnored private var pending: Task<Void, Never>?
    @ObservationIgnored private let defaults = UserDefaults.standard

    private enum Keys {
        static let email = "drive.email"
        static let lastSynced = "drive.lastSynced"
        static let rootFolder = "drive.rootFolder"
        static let photosFolder = "drive.photosFolder"
    }

    static let rootFolderName = "Pinch Recipes"
    static let photosFolderName = "Photos"

    init(store: RecipeStore) {
        self.store = store
        let defaults = UserDefaults.standard
        if let config = GoogleConfig.current {
            let auth = GoogleAuth(config: config)
            let email = defaults.string(forKey: Keys.email)
            self.auth = auth
            self.accountEmail = email
            self.lastSynced = defaults.object(forKey: Keys.lastSynced) as? Date
            self.state = auth.isSignedIn && email != nil ? .idle : .disconnected
        } else {
            self.auth = nil
            self.accountEmail = nil
            self.lastSynced = nil
            self.state = .unavailable
        }
    }

    var isConnected: Bool {
        guard let auth else { return false }
        return auth.isSignedIn && accountEmail != nil
    }

    // MARK: Connecting

    func connect() async {
        guard let auth else { return }
        state = .connecting
        do {
            try await auth.signIn()
            let email = try await api(auth).userEmail()
            accountEmail = email ?? "Google account"
            defaults.set(accountEmail, forKey: Keys.email)
            state = .idle
            await sync()
        } catch GoogleAuthError.cancelled {
            state = .disconnected
        } catch {
            state = isOffline(error) ? .offline : .failed(error.localizedDescription)
            if !auth.isSignedIn { state = .disconnected }
        }
    }

    func disconnect() async {
        pending?.cancel()
        await auth?.signOut()
        accountEmail = nil
        lastSynced = nil
        for key in [Keys.email, Keys.lastSynced, Keys.rootFolder, Keys.photosFolder] { defaults.removeObject(forKey: key) }
        state = auth == nil ? .unavailable : .disconnected
    }

    // MARK: Syncing

    /// Debounced sync after local edits.
    func scheduleSync() {
        guard isConnected else { return }
        pending?.cancel()
        pending = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            await self?.sync()
        }
    }

    func syncIfConnected() async {
        if isConnected { await sync() }
    }

    func sync() async {
        guard let auth, isConnected, state != .syncing else { return }
        state = .syncing
        do {
            try await performSync(api: api(auth))
            lastSynced = .now
            defaults.set(lastSynced, forKey: Keys.lastSynced)
            state = .idle
        } catch GoogleAuthError.revoked {
            await disconnect()
            state = .failed(GoogleAuthError.revoked.localizedDescription)
        } catch {
            if isOffline(error) {
                state = .offline
            } else {
                // The folder may have been deleted in Drive; look it up fresh next time.
                defaults.removeObject(forKey: Keys.rootFolder)
                defaults.removeObject(forKey: Keys.photosFolder)
                state = .failed("Couldn't sync with Google Drive. Pinch will try again later.")
            }
        }
    }

    private func api(_ auth: GoogleAuth) -> DriveAPI {
        DriveAPI(token: { try await auth.validAccessToken() }, onUnauthorized: { auth.invalidateAccessToken() })
    }

    private func performSync(api: DriveAPI) async throws {
        let root = try await folder(api: api, key: Keys.rootFolder, name: Self.rootFolderName, marker: ("pinchRoot", "1"), parent: nil)
        let photosFolder = try await folder(api: api, key: Keys.photosFolder, name: Self.photosFolderName, marker: ("pinchPhotos", "1"), parent: root)

        let remoteFiles = try await api.files(in: root)
        var remoteRecipes: [String: (file: DriveFile, updated: Int64)] = [:]
        for file in remoteFiles {
            guard let id = file.appProperties?["recipeId"] else { continue }
            let updated = Int64(file.appProperties?["updatedAt"] ?? "") ?? 0
            if let existing = remoteRecipes[id], existing.updated >= updated { continue }
            remoteRecipes[id] = (file, updated)
        }
        let remotePhotos = try await api.files(in: photosFolder)
        var photoIDs: [String: String] = [:]
        for photo in remotePhotos { photoIDs[photo.name] = photo.id }

        func uploadPhotos(of recipe: Recipe) async throws {
            for name in recipe.photos where photoIDs[name] == nil {
                guard let data = store.photos.data(for: name) else { continue }
                photoIDs[name] = try await api.upload(
                    name: name, mimeType: "image/jpeg", data: data, parent: photosFolder,
                    appProperties: ["recipeId": recipe.id, "kind": "photo"], existingID: nil
                )
            }
            // Pages removed from the recipe are removed from Drive too.
            for photo in remotePhotos where photo.appProperties?["recipeId"] == recipe.id && !recipe.photos.contains(photo.name) {
                try? await api.delete(photo.id)
                photoIDs[photo.name] = nil
            }
        }

        func downloadPhotos(of recipe: Recipe) async throws {
            for name in recipe.photos where !store.photos.exists(name) {
                guard let id = photoIDs[name] else { continue }
                try store.photos.save(data: try await api.download(id), name: name)
            }
        }

        func upload(_ recipe: Recipe, existingID: String?) async throws {
            if recipe.isDeleted {
                for photo in remotePhotos where photo.appProperties?["recipeId"] == recipe.id {
                    try? await api.delete(photo.id)
                }
            } else {
                try await uploadPhotos(of: recipe)
            }
            let data = try PinchJSON.encoder.encode(recipe)
            try await api.upload(
                name: Self.fileName(for: recipe), mimeType: "application/json", data: data, parent: root,
                appProperties: ["recipeId": recipe.id, "updatedAt": String(PinchJSON.millis(recipe.updatedAt)), "kind": "recipe"],
                existingID: existingID
            )
        }

        func downloadRecipe(_ file: DriveFile) async throws {
            let data = try await api.download(file.id)
            let recipe = try PinchJSON.decoder.decode(Recipe.self, from: data)
            if !recipe.isDeleted { try await downloadPhotos(of: recipe) }
            store.applySynced(recipe)
        }

        let local = store.allIncludingDeleted
        let localIDs = Set(local.map(\.id))
        for recipe in local {
            let localUpdated = PinchJSON.millis(recipe.updatedAt)
            if let remote = remoteRecipes[recipe.id] {
                if localUpdated > remote.updated {
                    try await upload(recipe, existingID: remote.file.id)
                } else if localUpdated < remote.updated {
                    try await downloadRecipe(remote.file)
                } else if !recipe.isDeleted {
                    try await downloadPhotos(of: recipe)
                }
            } else if !recipe.isDeleted {
                try await upload(recipe, existingID: nil)
            }
        }
        for (id, remote) in remoteRecipes where !localIDs.contains(id) {
            try await downloadRecipe(remote.file)
        }
    }

    private func folder(api: DriveAPI, key: String, name: String, marker: (String, String), parent: String?) async throws -> String {
        // A folder trashed in Drive would swallow new uploads, so look it up again.
        if let cached = defaults.string(forKey: key), try await api.isUsableFolder(cached) { return cached }
        let id: String
        if let found = try await api.findFolder(appProperty: marker, parent: parent) {
            id = found
        } else {
            let created = try await api.createFolder(name: name, parent: parent, appProperties: [marker.0: marker.1])
            // Another device may have created one at the same moment. Everyone settles on the
            // oldest (search results are ordered by creation time) and the newer, empty one goes.
            let oldest = try await api.findFolder(appProperty: marker, parent: parent) ?? created
            if oldest != created { try? await api.delete(created) }
            id = oldest
        }
        defaults.set(id, forKey: key)
        return id
    }

    static func fileName(for recipe: Recipe) -> String {
        let cleaned = recipe.displayTitle.replacingOccurrences(of: "/", with: "-")
        return String(cleaned.prefix(80)) + ".json"
    }

    private func isOffline(_ error: Error) -> Bool {
        guard let urlError = error as? URLError else { return false }
        let offlineCodes: [URLError.Code] = [
            .notConnectedToInternet, .networkConnectionLost, .timedOut, .cannotFindHost,
            .cannotConnectToHost, .dataNotAllowed, .internationalRoamingOff, .dnsLookupFailed,
        ]
        return offlineCodes.contains(urlError.code)
    }
}
