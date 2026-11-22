import Foundation
import Observation
import CryptoKit

struct ManagedAuthorization {
    static let scheme = "net.lmbrt.recipes.pinch"
    static let redirect = scheme + "://oauth"
    private static let clientID = "pinch-mobile"
    private let state = Self.randomValue()
    private let verifier = Self.randomValue()

    var url: URL {
        var parts = URLComponents(url: ServerSync.managedURL.appending(path: "v1/managed/oauth/authorize"), resolvingAgainstBaseURL: false)!
        parts.queryItems = [
            URLQueryItem(name: "client_id", value: Self.clientID), URLQueryItem(name: "redirect_uri", value: Self.redirect),
            URLQueryItem(name: "response_type", value: "code"), URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "code_challenge", value: Self.encoded(Data(SHA256.hash(data: Data(verifier.utf8))))),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
        ]
        return parts.url!
    }

    func exchange(_ callback: URL) throws -> Data {
        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        guard callback.scheme == Self.scheme, callback.host == "oauth", callback.path.isEmpty,
              items.first(where: { $0.name == "state" })?.value == state,
              let code = items.first(where: { $0.name == "code" })?.value else {
            throw ServerFailure(message: "Sign-in expired. Start again from Pinch.")
        }
        var form = URLComponents()
        form.queryItems = [
            URLQueryItem(name: "grant_type", value: "authorization_code"), URLQueryItem(name: "client_id", value: Self.clientID),
            URLQueryItem(name: "redirect_uri", value: Self.redirect), URLQueryItem(name: "code", value: code),
            URLQueryItem(name: "code_verifier", value: verifier),
        ]
        return Data((form.percentEncodedQuery ?? "").utf8)
    }

    private static func randomValue() -> String { encoded(Data((0..<32).map { _ in UInt8.random(in: .min ... .max) })) }

    private static func encoded(_ value: Data) -> String {
        value.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

struct ServerGroup: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let isOwner: Bool
    var memberCount: Int? = nil
    var memberNames: [String]? = nil
    var recipeCount: Int? = nil
    var latestActivity: Double? = nil
    var previews: [Recipe]? = nil
    var mySharedRecipeIds: [String]? = nil
    var role: String = "owner"
    var ownerName: String = ""
    var document: Cookbook? = nil
    var canEdit: Bool { isOwner || role == "editor" }
}

struct SharedRecipe: Decodable, Identifiable {
    let id: String
    let sharedBy: String
    let canRemove: Bool
    let recipe: Recipe
    var sharedAt: Double? = nil
    var savedRecipeId: String? = nil
}

struct GroupMember: Decodable, Identifiable {
    let id: String
    let username: String
    let isOwner: Bool
    let isYou: Bool
    let role: String
}

struct GroupInvitation: Identifiable, Equatable {
    let server: String
    let code: String
    var id: String { server + code }
    static func parse(_ text: String) throws -> GroupInvitation {
        guard let url = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)) else { throw ServerFailure(message: "Paste a complete invitation link.") }
        let address: String
        let code: String
        if url.scheme == "pinch", url.host == "join" {
            address = url.queryItems?.first(where: { $0.name == "server" })?.value ?? ""
            code = url.queryItems?.first(where: { $0.name == "code" })?.value ?? ""
        } else if url.scheme == "https", url.path.hasSuffix("/join") {
            var base = url; base.fragment = nil; base.path = String(base.path.dropLast(5))
            address = base.string ?? ""; code = url.fragment ?? ""
        } else { throw ServerFailure(message: "Paste a complete Pinch invitation link.") }
        let valid = try ServerAPI.serverURL(address)
        guard code.range(of: "^[A-Za-z0-9_-]{1,128}$", options: .regularExpression) != nil else { throw ServerFailure(message: "This invitation is incomplete.") }
        return GroupInvitation(server: valid.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")), code: code)
    }
}

struct InvitationDetails: Decodable {
    let id: String
    let name: String
    let invitedBy: String
    let role: String
}

struct ServerFailure: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Refuse redirects so an entered server cannot forward credentials to another host.
final class ServerSessionDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

struct ServerAPI {
    let base: URL
    let token: String
    private static let defaultSession = URLSession(configuration: .ephemeral, delegate: ServerSessionDelegate(), delegateQueue: nil)

    var session: URLSession = Self.defaultSession

    static func serverURL(_ text: String) throws -> URL {
        guard let parts = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              parts.scheme == "https", let host = parts.host, !host.isEmpty,
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              let url = parts.url else {
            throw ServerFailure(message: "Enter an HTTPS server address, such as https://recipes.example.com.")
        }
        return url
    }

    static func validateCredentials(username: String, password: String, registrationKey: String, requiresRegistrationKey: Bool) throws {
        let name = username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { throw ServerFailure(message: "Enter your username.") }
        guard name.range(of: "^[A-Za-z0-9_.-]{3,64}$", options: .regularExpression) != nil else {
            throw ServerFailure(message: "Use a username with 3–64 letters, numbers, periods, underscores or hyphens.")
        }
        guard !password.isEmpty else { throw ServerFailure(message: "Enter your password.") }
        // Match the server's Unicode code-point count; never trim a password.
        guard (10...256).contains(password.unicodeScalars.count) else {
            throw ServerFailure(message: "Your password must contain 10–256 characters.")
        }
        if requiresRegistrationKey {
            guard !registrationKey.isEmpty else {
                throw ServerFailure(message: "Enter the registration key from your server host to create an account. If you already have an account, turn off Create an account.")
            }
            guard registrationKey.unicodeScalars.count <= 256 else {
                throw ServerFailure(message: "The registration key is too long. Copy the key from your server host again.")
            }
        }
    }

    func send(_ path: String, method: String = "GET", data: Data? = nil, photo: Bool = false, form: Bool = false) async throws -> Data {
        var request = URLRequest(url: base.appendingPathComponent("v1/" + path))
        request.httpMethod = method
        request.httpBody = data
        request.timeoutInterval = 60
        request.setValue(form ? "application/x-www-form-urlencoded" : photo ? "image/jpeg" : "application/json", forHTTPHeaderField: "Content-Type")
        if method == "PUT", path.hasPrefix("recipes/") { request.setValue("1", forHTTPHeaderField: "X-Pinch-Recipe-Photos") }
        if !token.isEmpty { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (body, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else { throw ServerFailure(message: "Invalid server response.") }
        guard (200..<300).contains(response.statusCode) else {
            let object = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
            throw ServerFailure(message: object?["detail"] as? String ?? "Server request failed (\(response.statusCode)).")
        }
        return body
    }

    func json(_ path: String, method: String = "POST", fields: [String: String] = [:]) async throws -> Data {
        try await send(path, method: method, data: JSONSerialization.data(withJSONObject: fields))
    }

    static func validate(_ recipe: Recipe) throws {
        guard recipe.id.range(of: "^[a-zA-Z0-9_-]{1,80}$", options: .regularExpression) != nil,
              recipe.photoFiles.allSatisfy({ $0.range(of: "^[a-zA-Z0-9_-]{1,80}\\.jpg$", options: .regularExpression) != nil }),
              recipe.bannerPhoto.map(recipe.recipePhotos.contains) ?? true else {
            throw ServerFailure(message: "The server returned an invalid recipe or photo name.")
        }
    }
}

@Observable
@MainActor
final class ServerSync {
    nonisolated static let managedURL = URL(string: "https://recipes.lmbrt.net")!
    private(set) var server = ""
    private(set) var username = ""
    private(set) var isConnected = false
    private(set) var isManaged = false
    private(set) var isSyncing = false
    private(set) var lastSynced: Date?
    private(set) var message: String?
    private(set) var groups: [ServerGroup] = []
    var pendingInvitation: GroupInvitation?
    @ObservationIgnored private let store: RecipeStore
    @ObservationIgnored private var token = ""
    @ObservationIgnored private var scheduled: Task<Void, Never>?
    @ObservationIgnored private var syncTask: Task<Void, Error>?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var needsSync = false
    private static let service = "Pinch.Server"

    init(store: RecipeStore) {
        self.store = store
        if let saved = Keychain.read("session", service: Self.service),
           let data = saved.data(using: .utf8),
           let values = try? JSONDecoder().decode([String: String].self, from: data),
           let address = values["server"], (try? ServerAPI.serverURL(address)) != nil,
           let name = values["username"], let secret = values["token"] {
            server = address; username = name; token = secret; isConnected = true
            isManaged = values["managed"] == "true" && address == Self.managedURL.absoluteString
        }
    }

    var aiClient: OpenRouterClient? {
        if isManaged {
            return OpenRouterClient(apiKey: token, endpoint: Self.managedURL.appending(path: "v1/managed/ai"))
        }
        return AIAccount.shared.client
    }

    private func api() throws -> ServerAPI {
        guard isConnected else { throw ServerFailure(message: "Connect to a server first.") }
        return ServerAPI(base: try ServerAPI.serverURL(server), token: token)
    }

    func connect(address: String, username: String, password: String, registrationKey: String, register: Bool, invitationCode: String? = nil) async throws {
        let url = try ServerAPI.serverURL(address)
        try ServerAPI.validateCredentials(username: username, password: password, registrationKey: registrationKey, requiresRegistrationKey: register && invitationCode == nil)
        let response = try await ServerAPI(base: url, token: "").json("auth/\(register ? (invitationCode == nil ? "register" : "register-cookbook-invite") : "login")", fields: [
            "username": username.trimmingCharacters(in: .whitespacesAndNewlines), "password": password, "registrationKey": registrationKey, "code": invitationCode ?? "",
        ])
        try await saveSession(response, address: url, managed: false)
    }

    func connectManaged(username: String, password: String, register: Bool) async throws {
        try ServerAPI.validateCredentials(username: username, password: password, registrationKey: "", requiresRegistrationKey: false)
        let response = try await ServerAPI(base: Self.managedURL, token: "").json(register ? "managed/register" : "managed/login", fields: [
            "username": username.trimmingCharacters(in: .whitespacesAndNewlines), "password": password,
        ])
        try await saveSession(response, address: Self.managedURL, managed: true)
    }

    func completeManagedAuthorization(_ request: ManagedAuthorization, callback: URL) async throws {
        let response = try await ServerAPI(base: Self.managedURL, token: "").send("managed/oauth/token", method: "POST",
                                                                                 data: request.exchange(callback), form: true)
        try await saveSession(response, address: Self.managedURL, managed: true)
    }

    private func saveSession(_ response: Data, address: URL, managed: Bool) async throws {
        struct Session: Decodable { let token: String?; let access_token: String?; let username: String }
        let session = try JSONDecoder().decode(Session.self, from: response)
        guard let secret = session.token ?? session.access_token, !secret.isEmpty else {
            throw ServerFailure(message: "The sign-in response was incomplete. Try again.")
        }
        let saved = try JSONEncoder().encode(["server": address.absoluteString, "username": session.username, "token": secret,
                                             "managed": managed ? "true" : "false"])
        let value = String(decoding: saved, as: UTF8.self)
        Keychain.save(value, for: "session", service: Self.service)
        guard Keychain.read("session", service: Self.service) == value else {
            throw ServerFailure(message: "Couldn't securely save the server connection. Try again.")
        }
        generation += 1
        server = address.absoluteString; self.username = session.username; token = secret; isConnected = true; isManaged = managed
        message = nil; lastSynced = nil; groups = []
        await syncIfConnected()
    }

    func disconnect() async {
        let old = try? api()
        generation += 1
        scheduled?.cancel(); syncTask?.cancel(); syncTask = nil
        isConnected = false; isManaged = false; isSyncing = false; token = ""; groups = []; message = nil; lastSynced = nil
        Keychain.delete("session", service: Self.service)
        if let old { _ = try? await old.json("auth/logout") }
    }

    func deleteAccount(password: String) async throws {
        _ = try await api().json("auth/account", method: "DELETE", fields: ["password": password])
        await disconnect()
    }

    func scheduleSync() {
        needsSync = true
        scheduled?.cancel()
        guard isConnected else { return }
        scheduled = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(2)) } catch { return }
            await self?.syncIfConnected()
        }
    }

    func syncIfConnected() async {
        guard isConnected else { return }
        do { try await sync() }
        catch is CancellationError { }
        catch { message = error.localizedDescription }
    }

    func sync() async throws {
        if let task = syncTask { return try await task.value }
        let api = try api()
        let current = generation
        isSyncing = true; message = nil
        let task = Task { [self] in
            repeat {
                needsSync = false
                try await performSync(api, generation: current)
            } while needsSync && current == generation
        }
        syncTask = task
        defer { if current == generation { syncTask = nil; isSyncing = false } }
        do {
            try await task.value
            guard current == generation else { return }
            lastSynced = .now
        } catch {
            if current == generation { message = error.localizedDescription }
            throw error
        }
    }

    private func checkSession(_ expected: Int) throws {
        try Task.checkCancellation()
        guard expected == generation, isConnected else { throw CancellationError() }
    }

    private func performSync(_ api: ServerAPI, generation: Int) async throws {
        let remote = try PinchJSON.decoder.decode([Recipe].self, from: await api.send("recipes"))
        try checkSession(generation)
        for recipe in remote { try ServerAPI.validate(recipe) }
        let remoteByID = Dictionary(remote.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let local = store.allIncludingDeleted
        let localIDs = Set(local.map(\.id))
        for recipe in local {
            try checkSession(generation)
            if let other = remoteByID[recipe.id], PinchJSON.millis(other.updatedAt) >= PinchJSON.millis(recipe.updatedAt) {
                try await receive(other, api: api, generation: generation)
            } else {
                try ServerAPI.validate(recipe)
                for name in recipe.photoFiles {
                    guard let data = store.photos.data(for: name) else { throw ServerFailure(message: "A photo is missing from \(recipe.displayTitle).") }
                    _ = try await api.send("photos/\(name)", method: "PUT", data: data, photo: true)
                    try checkSession(generation)
                }
                let response = try await api.send("recipes/\(recipe.id)", method: "PUT", data: PinchJSON.encoder.encode(recipe))
                let winner = try PinchJSON.decoder.decode(Recipe.self, from: response)
                try await receive(winner, api: api, generation: generation)
            }
        }
        for recipe in remote where !localIDs.contains(recipe.id) {
            try await receive(recipe, api: api, generation: generation)
        }
        let books = try PinchJSON.decoder.decode([ServerGroup].self, from: await api.send("cookbooks"))
        try checkSession(generation)
        for book in books { if let document = book.document { store.applyCookbook(document) } }
        for document in store.cookbooks {
            let response = try await api.send("cookbooks/\(document.id)", method: "PUT", data: PinchJSON.encoder.encode(document))
            try checkSession(generation)
            if let merged = try PinchJSON.decoder.decode(ServerGroup.self, from: response).document { store.applyCookbook(merged) }
        }
        groups = try PinchJSON.decoder.decode([ServerGroup].self, from: await api.send("cookbooks"))
        try checkSession(generation)
    }

    private func receive(_ recipe: Recipe, api: ServerAPI, generation: Int) async throws {
        try ServerAPI.validate(recipe)
        try checkSession(generation)
        for name in recipe.photoFiles where !store.photos.exists(name) {
            let data = try await api.send("photos/\(name)")
            try checkSession(generation)
            try store.photos.save(data: data, name: name)
        }
        // A user may edit or delete a recipe while a request is in flight.
        if let current = store.allIncludingDeleted.first(where: { $0.id == recipe.id }),
           PinchJSON.millis(current.updatedAt) > PinchJSON.millis(recipe.updatedAt) { return }
        store.applySynced(recipe)
    }

    var allCookbooks: [ServerGroup] {
        let localIDs = Set(store.cookbooks.map(\.id))
        let personal = store.cookbooks.filter { $0.deletedAt == nil }.map { book in
            let recipes = store.recipes.filter { book.recipeIDs.contains($0.id) }
            var group = groups.first { $0.id == book.id } ?? ServerGroup(id: book.id, name: book.name, isOwner: true)
            group = ServerGroup(id: book.id, name: book.name, isOwner: true, memberCount: group.memberCount ?? 1, memberNames: group.memberNames ?? [], recipeCount: recipes.count, latestActivity: group.latestActivity, previews: Array(recipes.prefix(3)), mySharedRecipeIds: Array(book.recipeIDs), role: "owner", ownerName: username, document: book)
            return group
        }
        return (personal + groups.filter { !localIDs.contains($0.id) && $0.document?.deletedAt == nil }).sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    func loadGroups() async throws { if isConnected { try await sync() } }

    func createGroup(_ name: String) async throws -> ServerGroup {
        let book = store.createCookbook(name)
        return ServerGroup(id: book.id, name: book.name, isOwner: true, memberCount: 1, recipeCount: 0, role: "owner", document: book)
    }

    func joinGroup(_ code: String) async throws {
        _ = try await api().json("cookbooks/join", fields: ["code": code.trimmingCharacters(in: .whitespacesAndNewlines)])
        try await loadGroups()
    }

    func invite(_ group: ServerGroup) async throws -> String {
        _ = try await api().send("cookbooks/\(group.id)/invitations", method: "DELETE")
        return ""
    }

    func leave(_ group: ServerGroup) async throws {
        if group.isOwner { store.deleteCookbook(group.id) }
        else { _ = try await api().send("cookbooks/\(group.id)/membership", method: "DELETE"); try await loadGroups() }
    }

    func sharedRecipes(_ group: ServerGroup) async throws -> [SharedRecipe] {
        if group.isOwner, let book = store.cookbooks.first(where: { $0.id == group.id }) {
            return store.recipes.filter { book.recipeIDs.contains($0.id) }.map { recipe in
                SharedRecipe(id: recipe.id, sharedBy: recipe.origin?.sharedBy ?? "You", canRemove: true, recipe: recipe, sharedAt: book.entries.first { $0.recipeId == recipe.id }?.updatedAt.timeIntervalSince1970, savedRecipeId: recipe.id)
            }
        }
        let recipes = try PinchJSON.decoder.decode([SharedRecipe].self, from: await api().send("cookbooks/\(group.id)/recipes"))
        for item in recipes { try ServerAPI.validate(item.recipe) }
        return recipes
    }

    func share(_ recipe: Recipe, with group: ServerGroup) async throws {
        if group.isOwner { store.setMembership(recipe.id, in: group.id, included: true); return }
        let api = try api(), expected = generation
        let latest = store.recipe(recipe.id) ?? recipe
        try ServerAPI.validate(latest)
        for name in latest.photoFiles {
            guard let data = store.photos.data(for: name) else { throw ServerFailure(message: "A photo is missing from this recipe.") }
            _ = try await api.send("photos/\(name)", method: "PUT", data: data, photo: true)
            try checkSession(expected)
        }
        let data = try await api.send("recipes/\(latest.id)", method: "PUT", data: PinchJSON.encoder.encode(latest))
        let winner = try PinchJSON.decoder.decode(Recipe.self, from: data)
        try await receive(winner, api: api, generation: expected)
        try checkSession(expected)
        _ = try await api.json("cookbooks/\(group.id)/recipes", fields: ["recipeId": latest.id])
    }

    func remove(_ item: SharedRecipe, from group: ServerGroup) async throws {
        if group.isOwner { store.setMembership(item.id, in: group.id, included: false) }
        else { _ = try await api().send("cookbooks/\(group.id)/recipes/\(item.id)", method: "DELETE") }
    }

    @discardableResult
    func saveCopy(_ item: SharedRecipe, from group: ServerGroup) async throws -> String {
        if group.isOwner { return item.recipe.id }
        let api = try api(), expected = generation
        for deleted in store.allIncludingDeleted where deleted.isDeleted && deleted.origin?.shareId == item.id {
            _ = try await api.send("recipes/\(deleted.id)", method: "PUT", data: PinchJSON.encoder.encode(deleted))
            try checkSession(expected)
        }
        let recipe = try PinchJSON.decoder.decode(Recipe.self, from: await api.json("cookbooks/\(group.id)/recipes/\(item.id)/save"))
        try await receive(recipe, api: api, generation: expected)
        return recipe.id
    }

    func preview(_ item: SharedRecipe, in group: ServerGroup) async throws -> (SharedRecipe, Recipe) {
        let api = try api(), expected = generation
        let data = try await api.send("cookbooks/\(group.id)/recipes/\(item.id)")
        let fresh = try PinchJSON.decoder.decode(SharedRecipe.self, from: data)
        try ServerAPI.validate(fresh.recipe)
        var recipe = fresh.recipe
        var copied: [String: String] = [:]
        do {
            for name in recipe.photoFiles where copied[name] == nil {
                let data = try await api.send("cookbooks/\(group.id)/recipes/\(item.id)/photos/\(name)")
                try checkSession(expected)
                let local = UUID().uuidString.lowercased() + ".jpg"
                try store.photos.save(data: data, name: local)
                copied[name] = local
            }
            try checkSession(expected)
            recipe.id = "shared-" + item.id
            recipe.photos = recipe.photos.compactMap { copied[$0] }
            recipe.recipePhotos = recipe.recipePhotos.compactMap { copied[$0] }
            recipe.bannerPhoto = recipe.bannerPhoto.flatMap { copied[$0] }
            return (fresh, recipe)
        } catch { copied.values.forEach(store.photos.delete); throw error }
    }

    func members(_ group: ServerGroup) async throws -> [GroupMember] {
        try JSONDecoder().decode([GroupMember].self, from: await api().send("cookbooks/\(group.id)/members"))
    }
    func rename(_ group: ServerGroup, name: String) async throws { store.renameCookbook(group.id, name: name) }
    func removeMember(_ member: GroupMember, from group: ServerGroup) async throws {
        _ = try await api().send("cookbooks/\(group.id)/members/\(member.id)", method: "DELETE"); try await loadGroups()
    }
    func changeRole(_ member: GroupMember, in group: ServerGroup, role: String) async throws {
        _ = try await api().json("cookbooks/\(group.id)/members/\(member.id)", method: "PATCH", fields: ["role": role]); try await loadGroups()
    }
    func invitationLink(_ group: ServerGroup, role: String = "viewer") async throws -> URL {
        try await sync()
        let data = try await api().json("cookbooks/\(group.id)/invitations", fields: ["role": role])
        guard let code = try JSONDecoder().decode([String: String].self, from: data)["inviteCode"] else { throw ServerFailure(message: "Couldn’t create the invitation. Try again.") }
        var url = URLComponents(url: try ServerAPI.serverURL(server).appendingPathComponent("join"), resolvingAgainstBaseURL: false)!
        url.fragment = code
        return url.url!
    }
    func invitationDetails(_ invitation: GroupInvitation) async throws -> InvitationDetails {
        let api = ServerAPI(base: try ServerAPI.serverURL(invitation.server), token: "")
        return try JSONDecoder().decode(InvitationDetails.self, from: await api.send("cookbook-invitations/\(invitation.code)"))
    }
    func savedCopy(of item: SharedRecipe) -> Recipe? { store.recipes.first { $0.origin?.shareId == item.id } }
}
