import Foundation

struct CookbookEntry: Codable, Hashable, Sendable {
    var recipeId: String
    var updatedAt: Date
    var removed = false
}

struct Cookbook: Codable, Hashable, Identifiable, Sendable {
    var id = UUID().uuidString.lowercased()
    var name: String
    var updatedAt = Date.now
    var deletedAt: Date?
    var entries: [CookbookEntry] = []

    var recipeIDs: Set<String> { Set(entries.filter { !$0.removed }.map(\.recipeId)) }

    /// Membership changes merge independently so an offline edit cannot erase a contribution.
    func merging(_ other: Cookbook) -> Cookbook {
        var result = PinchJSON.millis(other.updatedAt) > PinchJSON.millis(updatedAt) ? other : self
        result.deletedAt = deletedAt ?? other.deletedAt
        var items = Dictionary(entries.map { ($0.recipeId, $0) }, uniquingKeysWith: { first, _ in first })
        for entry in other.entries {
            if let current = items[entry.recipeId], PinchJSON.millis(current.updatedAt) > PinchJSON.millis(entry.updatedAt) || (PinchJSON.millis(current.updatedAt) == PinchJSON.millis(entry.updatedAt) && current.removed) { continue }
            items[entry.recipeId] = entry
        }
        result.entries = items.values.sorted { $0.recipeId < $1.recipeId }
        return result
    }
}
