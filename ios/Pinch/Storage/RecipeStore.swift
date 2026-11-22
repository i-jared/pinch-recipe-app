import Foundation
import Observation

/// The recipe library. Each recipe is one JSON file, which keeps saves small and makes
/// server sync a file-by-file affair. Deleted recipes stay as tombstones so the
/// deletion reaches other devices.
@Observable
@MainActor
final class RecipeStore {
    /// Visible recipes, newest first.
    private(set) var recipes: [Recipe] = []
    private var byID: [String: Recipe] = [:]
    private(set) var cookbooks: [Cookbook] = []
    private var cookbookDirectory: URL { directory.appendingPathComponent("_cookbooks", isDirectory: true) }

    @ObservationIgnored let directory: URL
    @ObservationIgnored let photos: PhotoStore
    /// Called after any local edit; the sync engine uses it to schedule an upload.
    @ObservationIgnored var didChangeLocally: (() -> Void)?

    init(directory: URL = AppPaths.recipes, photos: PhotoStore = .shared) {
        self.directory = directory
        self.photos = photos
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        load()
        try? FileManager.default.createDirectory(at: cookbookDirectory, withIntermediateDirectories: true)
        cookbooks = ((try? FileManager.default.contentsOfDirectory(at: cookbookDirectory, includingPropertiesForKeys: nil)) ?? []).compactMap { file in
            guard file.pathExtension == "json", let data = try? Data(contentsOf: file) else { return nil }
            return try? PinchJSON.decoder.decode(Cookbook.self, from: data)
        }
    }

    func load() {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        var loaded: [String: Recipe] = [:]
        for file in files where file.pathExtension == "json" {
            guard let data = try? Data(contentsOf: file),
                  let recipe = try? PinchJSON.decoder.decode(Recipe.self, from: data) else { continue }
            loaded[recipe.id] = recipe
        }
        byID = loaded
        refresh()
    }

    func recipe(_ id: String) -> Recipe? {
        guard let recipe = byID[id], !recipe.isDeleted else { return nil }
        return recipe
    }

    var allIncludingDeleted: [Recipe] { Array(byID.values) }

    /// Tags in use, most common first.
    var allTags: [String] {
        var counts: [String: Int] = [:]
        for recipe in recipes {
            for tag in recipe.tags { counts[tag, default: 0] += 1 }
        }
        return counts.sorted { $0.value == $1.value ? $0.key < $1.key : $0.value > $1.value }.map(\.key)
    }

    // MARK: Local edits

    func save(_ recipe: Recipe) {
        var updated = recipe
        updated.updatedAt = .now
        commit(updated)
        didChangeLocally?()
    }

    func delete(_ id: String) {
        guard var recipe = byID[id] else { return }
        let files = recipe.photoFiles
        recipe.deletedAt = .now
        recipe.updatedAt = .now
        recipe.photos = []
        recipe.recipePhotos = []
        recipe.bannerPhoto = nil
        recipe.ingredients = []
        recipe.steps = []
        recipe.rawText = ""
        recipe.notes = ""
        commit(recipe)
        for book in cookbooks where book.recipeIDs.contains(id) { setMembership(id, in: book.id, included: false) }
        files.forEach(photos.delete)
        didChangeLocally?()
    }

    func toggleFavorite(_ id: String) {
        guard var recipe = recipe(id) else { return }
        recipe.isFavorite.toggle()
        save(recipe)
    }

    func markCooked(_ id: String) {
        guard var recipe = recipe(id) else { return }
        recipe.lastCookedAt = .now
        save(recipe)
    }

    // MARK: Sync

    @discardableResult
    func createCookbook(_ name: String) -> Cookbook {
        let book = Cookbook(name: name.trimmingCharacters(in: .whitespacesAndNewlines))
        applyCookbook(book); didChangeLocally?()
        return book
    }

    func renameCookbook(_ id: String, name: String) {
        guard var book = cookbooks.first(where: { $0.id == id }) else { return }
        book.name = name.trimmingCharacters(in: .whitespacesAndNewlines); book.updatedAt = nextCookbookDate(after: book.updatedAt)
        applyCookbook(book); didChangeLocally?()
    }

    func deleteCookbook(_ id: String) {
        guard var book = cookbooks.first(where: { $0.id == id }) else { return }
        book.updatedAt = nextCookbookDate(after: book.updatedAt); book.deletedAt = book.updatedAt
        applyCookbook(book); didChangeLocally?()
    }

    func setMembership(_ recipeID: String, in id: String, included: Bool) {
        guard var book = cookbooks.first(where: { $0.id == id && $0.deletedAt == nil }) else { return }
        let changed = nextCookbookDate(after: book.entries.first { $0.recipeId == recipeID }?.updatedAt ?? .distantPast)
        book.entries.removeAll { $0.recipeId == recipeID }
        book.entries.append(CookbookEntry(recipeId: recipeID, updatedAt: changed, removed: !included))
        applyCookbook(book); didChangeLocally?()
    }

    func applyCookbook(_ incoming: Cookbook) {
        let book = cookbooks.first(where: { $0.id == incoming.id })?.merging(incoming) ?? incoming
        guard book.id.range(of: "^[a-zA-Z0-9_-]{1,80}$", options: .regularExpression) != nil else { return }
        cookbooks.removeAll { $0.id == book.id }; cookbooks.append(book)
        do { try PinchJSON.encoder.encode(book).write(to: cookbookDirectory.appendingPathComponent(book.id + ".json"), options: .atomic) }
        catch { assertionFailure("Couldn't save cookbook: \(error)") }
    }

    private func nextCookbookDate(after previous: Date) -> Date {
        max(.now, Date(timeIntervalSince1970: Double(PinchJSON.millis(previous) + 1) / 1000))
    }

    /// Stores a version that came from server, keeping its timestamps.
    func applySynced(_ recipe: Recipe) {
        if let existing = byID[recipe.id] {
            let kept = recipe.isDeleted ? [] : Set(recipe.photoFiles)
            existing.photoFiles.filter { !kept.contains($0) }.forEach(photos.delete)
        }
        commit(recipe)
    }

    // MARK: Private

    private func commit(_ recipe: Recipe) {
        byID[recipe.id] = recipe
        write(recipe)
        refresh()
    }

    private func refresh() {
        recipes = byID.values.filter { !$0.isDeleted }.sorted { $0.createdAt > $1.createdAt }
    }

    private func write(_ recipe: Recipe) {
        do {
            let data = try PinchJSON.encoder.encode(recipe)
            try data.write(to: directory.appendingPathComponent("\(recipe.id).json"), options: .atomic)
        } catch {
            assertionFailure("Couldn't save recipe \(recipe.id): \(error)")
        }
    }
}
