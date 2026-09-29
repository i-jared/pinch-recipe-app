import Foundation

/// One line in a recipe's ingredient list or method. `isHeader` marks a group
/// heading such as "For the frosting".
struct RecipeItem: Codable, Hashable, Identifiable, Sendable {
    var id: String
    var text: String
    var isHeader: Bool

    init(id: String = RecipeItem.newID(), text: String, isHeader: Bool = false) {
        self.id = id
        self.text = text
        self.isHeader = isHeader
    }

    static func newID() -> String {
        String(UUID().uuidString.lowercased().replacingOccurrences(of: "-", with: "").prefix(10))
    }

    private enum CodingKeys: String, CodingKey { case id, text, isHeader }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? RecipeItem.newID()
        text = try c.decodeIfPresent(String.self, forKey: .text) ?? ""
        isHeader = try c.decodeIfPresent(Bool.self, forKey: .isHeader) ?? false
    }
}

/// A saved recipe. The JSON encoding of this type is the on-disk and Google Drive
/// format, shared with the Android app (see docs/recipe-format.md).
struct Recipe: Codable, Hashable, Identifiable, Sendable {
    static let schemaVersion = 1

    var id: String
    var title: String
    var summary: String
    var source: String
    /// The web page a recipe was imported from. Empty for scanned or typed recipes.
    var sourceURL: String
    var servings: String
    var prepTime: String
    var cookTime: String
    var totalTime: String
    var ingredients: [RecipeItem]
    var steps: [RecipeItem]
    var notes: String
    var tags: [String]
    var emoji: String
    var palette: CoverPalette
    var pattern: CoverPattern
    /// File names of scanned pages, in page order. Files live in the photo store.
    var photos: [String]
    var isFavorite: Bool
    var createdAt: Date
    var updatedAt: Date
    var lastCookedAt: Date?
    /// The text exactly as it was read from the scans, kept for search and re-parsing.
    var rawText: String
    /// Set when the recipe is deleted, so the deletion can sync to other devices.
    var deletedAt: Date?

    init(
        id: String = UUID().uuidString.lowercased(),
        title: String = "",
        summary: String = "",
        source: String = "",
        sourceURL: String = "",
        servings: String = "",
        prepTime: String = "",
        cookTime: String = "",
        totalTime: String = "",
        ingredients: [RecipeItem] = [],
        steps: [RecipeItem] = [],
        notes: String = "",
        tags: [String] = [],
        emoji: String = "🍽️",
        palette: CoverPalette = .jade,
        pattern: CoverPattern = .gingham,
        photos: [String] = [],
        isFavorite: Bool = false,
        createdAt: Date = .now,
        updatedAt: Date = .now,
        lastCookedAt: Date? = nil,
        rawText: String = "",
        deletedAt: Date? = nil
    ) {
        self.id = id
        self.title = title
        self.summary = summary
        self.source = source
        self.sourceURL = sourceURL
        self.servings = servings
        self.prepTime = prepTime
        self.cookTime = cookTime
        self.totalTime = totalTime
        self.ingredients = ingredients
        self.steps = steps
        self.notes = notes
        self.tags = tags
        self.emoji = emoji
        self.palette = palette
        self.pattern = pattern
        self.photos = photos
        self.isFavorite = isFavorite
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.lastCookedAt = lastCookedAt
        self.rawText = rawText
        self.deletedAt = deletedAt
    }

    private enum CodingKeys: String, CodingKey {
        case schema, id, title, summary, source, servings, prepTime, cookTime, totalTime
        case sourceURL = "sourceUrl"
        case ingredients, steps, notes, tags, emoji, palette, pattern, photos, isFavorite
        case createdAt, updatedAt, lastCookedAt, rawText, deletedAt
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id).lowercased()
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        summary = try c.decodeIfPresent(String.self, forKey: .summary) ?? ""
        source = try c.decodeIfPresent(String.self, forKey: .source) ?? ""
        sourceURL = try c.decodeIfPresent(String.self, forKey: .sourceURL) ?? ""
        servings = try c.decodeIfPresent(String.self, forKey: .servings) ?? ""
        prepTime = try c.decodeIfPresent(String.self, forKey: .prepTime) ?? ""
        cookTime = try c.decodeIfPresent(String.self, forKey: .cookTime) ?? ""
        totalTime = try c.decodeIfPresent(String.self, forKey: .totalTime) ?? ""
        ingredients = try c.decodeIfPresent([RecipeItem].self, forKey: .ingredients) ?? []
        steps = try c.decodeIfPresent([RecipeItem].self, forKey: .steps) ?? []
        notes = try c.decodeIfPresent(String.self, forKey: .notes) ?? ""
        tags = try c.decodeIfPresent([String].self, forKey: .tags) ?? []
        emoji = try c.decodeIfPresent(String.self, forKey: .emoji) ?? "🍽️"
        palette = CoverPalette(rawValue: try c.decodeIfPresent(String.self, forKey: .palette) ?? "") ?? .jade
        pattern = CoverPattern(rawValue: try c.decodeIfPresent(String.self, forKey: .pattern) ?? "") ?? .gingham
        photos = try c.decodeIfPresent([String].self, forKey: .photos) ?? []
        isFavorite = try c.decodeIfPresent(Bool.self, forKey: .isFavorite) ?? false
        createdAt = try c.decodeIfPresent(Date.self, forKey: .createdAt) ?? .now
        updatedAt = try c.decodeIfPresent(Date.self, forKey: .updatedAt) ?? createdAt
        lastCookedAt = try c.decodeIfPresent(Date.self, forKey: .lastCookedAt)
        rawText = try c.decodeIfPresent(String.self, forKey: .rawText) ?? ""
        deletedAt = try c.decodeIfPresent(Date.self, forKey: .deletedAt)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(Recipe.schemaVersion, forKey: .schema)
        try c.encode(id, forKey: .id)
        try c.encode(title, forKey: .title)
        try c.encode(summary, forKey: .summary)
        try c.encode(source, forKey: .source)
        if !sourceURL.isEmpty { try c.encode(sourceURL, forKey: .sourceURL) }
        try c.encode(servings, forKey: .servings)
        try c.encode(prepTime, forKey: .prepTime)
        try c.encode(cookTime, forKey: .cookTime)
        try c.encode(totalTime, forKey: .totalTime)
        try c.encode(ingredients, forKey: .ingredients)
        try c.encode(steps, forKey: .steps)
        try c.encode(notes, forKey: .notes)
        try c.encode(tags, forKey: .tags)
        try c.encode(emoji, forKey: .emoji)
        try c.encode(palette.rawValue, forKey: .palette)
        try c.encode(pattern.rawValue, forKey: .pattern)
        try c.encode(photos, forKey: .photos)
        try c.encode(isFavorite, forKey: .isFavorite)
        try c.encode(createdAt, forKey: .createdAt)
        try c.encode(updatedAt, forKey: .updatedAt)
        try c.encodeIfPresent(lastCookedAt, forKey: .lastCookedAt)
        try c.encode(rawText, forKey: .rawText)
        try c.encodeIfPresent(deletedAt, forKey: .deletedAt)
    }
}

extension Recipe {
    var isDeleted: Bool { deletedAt != nil }

    var displayTitle: String {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Untitled recipe" : trimmed
    }

    /// The best single "how long does this take" figure for cards and pills.
    var displayTime: String? {
        if let total = DurationParser.minutes(in: totalTime) { return DurationParser.format(minutes: total) }
        let prep = DurationParser.minutes(in: prepTime)
        let cook = DurationParser.minutes(in: cookTime)
        switch (prep, cook) {
        case let (p?, c?): return DurationParser.format(minutes: p + c)
        case let (p?, nil): return DurationParser.format(minutes: p)
        case let (nil, c?): return DurationParser.format(minutes: c)
        default:
            let raw = [totalTime, cookTime, prepTime].first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            return raw?.trimmingCharacters(in: .whitespaces)
        }
    }

    /// Minutes used for sorting and "quick" filters.
    var totalMinutes: Int? {
        if let total = DurationParser.minutes(in: totalTime) { return total }
        let parts = [DurationParser.minutes(in: prepTime), DurationParser.minutes(in: cookTime)].compactMap { $0 }
        return parts.isEmpty ? nil : parts.reduce(0, +)
    }

    /// The numeric serving count, if the servings text starts with one ("4", "4-6", "about 24 cookies").
    var servingsCount: Double? {
        IngredientParser.leadingNumber(in: servings)
    }

    /// What the servings count counts: "biscuits" for "12 biscuits", nil for a bare "4".
    var servingsNoun: String? {
        let words = servings.split(separator: " ").map(String.init)
        guard let index = words.firstIndex(where: { IngredientParser.leadingNumber(in: $0) != nil }) else { return nil }
        var rest = Array(words[(index + 1)...])
        // Skip the other end of a range: "4 to 6 people".
        if let first = rest.first, ["-", "–", "to", "or"].contains(first.lowercased()) { rest = Array(rest.dropFirst(2)) }
        if let first = rest.first, first.allSatisfy({ $0.isNumber || $0 == "-" || $0 == "–" }) { rest = Array(rest.dropFirst()) }
        let noun = rest.joined(separator: " ").trimmingCharacters(in: CharacterSet(charactersIn: " .,()"))
        guard !noun.isEmpty, noun.count <= 24 else { return nil }
        return noun.lowercased() == "servings" || noun.lowercased() == "serving" ? nil : noun
    }

    /// Human-friendly servings text for pills: "4 servings", "Makes 24 cookies".
    var servingsLabel: String? {
        let s = servings.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        if s.allSatisfy({ $0.isNumber || $0 == "-" || $0 == "–" || $0 == " " }) {
            return s == "1" ? "1 serving" : "\(s) servings"
        }
        return s
    }

    var ingredientLines: [RecipeItem] { ingredients.filter { !$0.isHeader } }
    var stepLines: [RecipeItem] { steps.filter { !$0.isHeader } }
}

/// Colors for the patterned covers. Names are shared with Android.
enum CoverPalette: String, CaseIterable, Codable, Sendable {
    case tomato, jade, butter, blueberry, plum, saffron, beet, sky, sage, cocoa
}

/// Tablecloth and tin patterns for covers. Names are shared with Android.
enum CoverPattern: String, CaseIterable, Codable, Sendable {
    case gingham, stripes, dots, waves, tiles
}
