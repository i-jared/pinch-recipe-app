import Foundation
import Observation

/// The recipe library. Each recipe is one JSON file, which keeps saves small and makes
/// Google Drive sync a file-by-file affair. Deleted recipes stay as tombstones so the
/// deletion reaches other devices.
@Observable
@MainActor
final class RecipeStore {
    /// Visible recipes, newest first.
    private(set) var recipes: [Recipe] = []
    private var byID: [String: Recipe] = [:]

    @ObservationIgnored let directory: URL
    @ObservationIgnored let photos: PhotoStore
    /// Called after any local edit; the sync engine uses it to schedule an upload.
    @ObservationIgnored var didChangeLocally: (() -> Void)?

    init(directory: URL = AppPaths.recipes, photos: PhotoStore = .shared) {
        self.directory = directory
        self.photos = photos
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        load()
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
        let pages = recipe.photos
        recipe.deletedAt = .now
        recipe.updatedAt = .now
        recipe.photos = []
        recipe.ingredients = []
        recipe.steps = []
        recipe.rawText = ""
        recipe.notes = ""
        commit(recipe)
        pages.forEach(photos.delete)
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

    /// Stores a version that came from Google Drive, keeping its timestamps.
    func applySynced(_ recipe: Recipe) {
        if let existing = byID[recipe.id] {
            // Pages removed on another device (or the whole recipe) go from this one too.
            let kept = recipe.isDeleted ? [] : Set(recipe.photos)
            existing.photos.filter { !kept.contains($0) }.forEach(photos.delete)
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
