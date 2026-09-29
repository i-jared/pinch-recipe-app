import Foundation

/// Typo-tolerant recipe search. Every query word must match something (a word, a
/// prefix, a near-miss spelling, or an abbreviation like "chkn"), and matches in the
/// title count more than matches in the method. Mirrored by Android's `FuzzySearch.kt`.
struct FuzzySearch {
    struct Result: Identifiable, Equatable {
        var id: String { recipe.id }
        let recipe: Recipe
        let score: Double
        /// A short "why this matched" line, like the ingredient that contained the word.
        let snippet: String?
    }

    enum Field: Int {
        case title, tags, ingredients, summary, source, steps, notes, rawText

        var weight: Double {
            switch self {
            case .title: return 3.0
            case .tags: return 2.2
            case .ingredients: return 1.6
            case .summary, .source: return 1.0
            case .steps, .notes: return 0.7
            case .rawText: return 0.4
            }
        }
    }

    private struct IndexedLine {
        let text: String
        let words: [String]
        let singulars: [String]
    }

    private struct IndexedRecipe {
        let updatedAt: Date
        let fields: [(Field, [IndexedLine])]
        let foldedTitle: String
    }

    private static let stopWords: Set<String> = ["a", "an", "and", "the", "of", "with", "in", "on", "for", "to", "or", "recipe", "recipes"]

    private var cache: [String: IndexedRecipe] = [:]

    mutating func search(_ query: String, in recipes: [Recipe]) -> [Result] {
        let rawTokens = TextKit.words(query)
        var tokens = rawTokens.filter { !Self.stopWords.contains($0) }
        if tokens.isEmpty { tokens = rawTokens }
        guard !tokens.isEmpty else { return [] }
        let foldedQuery = TextKit.words(query).joined(separator: " ")

        var results: [Result] = []
        for recipe in recipes {
            let indexed = index(recipe)
            var total = 0.0
            var snippet: String?
            var bestSnippetScore = 0.0
            var matchedAll = true

            for token in tokens {
                var best = 0.0
                var bestField: Field?
                var bestLine: String?
                let singular = TextKit.singular(token)
                for (field, lines) in indexed.fields {
                    for line in lines {
                        let s = Self.bestWordScore(token, singular: singular, in: line) * field.weight
                        if s > best {
                            best = s
                            bestField = field
                            bestLine = line.text
                        }
                    }
                }
                if best == 0 { matchedAll = false; break }
                total += best
                if let field = bestField, field != .title, best > bestSnippetScore {
                    bestSnippetScore = best
                    snippet = Self.snippet(for: field, line: bestLine ?? "", token: token)
                }
            }
            guard matchedAll else { continue }

            if indexed.foldedTitle.contains(foldedQuery) { total += 2.5 }
            if indexed.foldedTitle.hasPrefix(foldedQuery) { total += 1.0 }
            // Titles that fully match shouldn't show a snippet from elsewhere.
            let titleOnly = tokens.allSatisfy { token in
                indexed.fields.first { $0.0 == .title }?.1.contains { Self.bestWordScore(token, singular: TextKit.singular(token), in: $0) >= 0.7 } ?? false
            }
            results.append(Result(recipe: recipe, score: total, snippet: titleOnly ? nil : snippet))
        }

        return results.sorted { a, b in
            if abs(a.score - b.score) > 0.0001 { return a.score > b.score }
            return a.recipe.updatedAt > b.recipe.updatedAt
        }
    }

    private mutating func index(_ recipe: Recipe) -> IndexedRecipe {
        if let cached = cache[recipe.id], cached.updatedAt == recipe.updatedAt { return cached }
        func lines(_ texts: [String]) -> [IndexedLine] {
            texts.filter { !$0.isEmpty }.map { text in
                let words = TextKit.words(text)
                return IndexedLine(text: text, words: words, singulars: words.map(TextKit.singular))
            }
        }
        let fields: [(Field, [IndexedLine])] = [
            (.title, lines([recipe.title])),
            (.tags, lines(recipe.tags)),
            (.ingredients, lines(recipe.ingredients.map(\.text))),
            (.summary, lines([recipe.summary])),
            (.source, lines([recipe.source])),
            (.steps, lines(recipe.steps.map(\.text))),
            (.notes, lines([recipe.notes])),
            (.rawText, lines([recipe.rawText])),
        ]
        let indexed = IndexedRecipe(
            updatedAt: recipe.updatedAt,
            fields: fields,
            foldedTitle: TextKit.words(recipe.title).joined(separator: " ")
        )
        cache[recipe.id] = indexed
        return indexed
    }

    // MARK: Scoring

    private static func bestWordScore(_ token: String, singular: String, in line: IndexedLine) -> Double {
        var best = 0.0
        for (i, word) in line.words.enumerated() {
            let s = wordScore(token, singular: singular, word: word, wordSingular: line.singulars[i])
            if s > best {
                best = s
                if best >= 1 { break }
            }
        }
        return best
    }

    static func wordScore(_ token: String, singular: String, word: String, wordSingular: String) -> Double {
        if word == token || wordSingular == singular { return 1.0 }
        let tokenCount = token.count
        if word.hasPrefix(token) { return tokenCount >= 2 ? 0.9 : 0.6 }
        if tokenCount >= 3 && word.contains(token) { return 0.72 }
        if tokenCount >= 4 {
            let allowed = tokenCount <= 5 ? 1 : 2
            if let d = boundedDistance(token, word, max: allowed) { return d == 1 ? 0.66 : 0.52 }
            // Typo while still typing: "chocl" vs "choco…"
            if word.count > tokenCount, let d = boundedDistance(token, String(word.prefix(tokenCount)), max: 1), d <= 1 { return 0.58 }
        }
        // Abbreviations like "chkn" → "chicken".
        if tokenCount >= 4, token.first == word.first, word.count <= tokenCount * 3, isSubsequence(token, of: word) {
            return 0.42
        }
        return 0
    }

    /// Damerau–Levenshtein (optimal string alignment) distance, or nil if above `max`.
    static func boundedDistance(_ a: String, _ b: String, max limit: Int) -> Int? {
        let a = Array(a), b = Array(b)
        if abs(a.count - b.count) > limit { return nil }
        if a.isEmpty { return b.count <= limit ? b.count : nil }
        if b.isEmpty { return a.count <= limit ? a.count : nil }
        var previousPrevious = [Int](repeating: 0, count: b.count + 1)
        var previous = Array(0...b.count)
        var current = [Int](repeating: 0, count: b.count + 1)
        for i in 1...a.count {
            current[0] = i
            var rowMin = current[0]
            for j in 1...b.count {
                let cost = a[i - 1] == b[j - 1] ? 0 : 1
                var value = Swift.min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                if i > 1, j > 1, a[i - 1] == b[j - 2], a[i - 2] == b[j - 1] {
                    value = Swift.min(value, previousPrevious[j - 2] + 1)
                }
                current[j] = value
                rowMin = Swift.min(rowMin, value)
            }
            if rowMin > limit { return nil }
            previousPrevious = previous
            previous = current
        }
        let distance = previous[b.count]
        return distance <= limit ? distance : nil
    }

    static func isSubsequence(_ needle: String, of haystack: String) -> Bool {
        var it = haystack.makeIterator()
        for ch in needle {
            var found = false
            while let next = it.next() {
                if next == ch { found = true; break }
            }
            if !found { return false }
        }
        return true
    }

    private static func snippet(for field: Field, line: String, token: String) -> String? {
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        switch field {
        case .ingredients, .tags, .source:
            return String(trimmed.prefix(80))
        case .title:
            return nil
        default:
            // Show the words around the match, not the whole paragraph.
            let words = trimmed.split(separator: " ").map(String.init)
            let folded = words.map { TextKit.words($0).joined() }
            let hit = folded.firstIndex { $0.hasPrefix(token) || $0.contains(token) } ?? 0
            let start = Swift.max(0, hit - 4)
            let end = Swift.min(words.count, hit + 8)
            var text = words[start..<end].joined(separator: " ")
            if start > 0 { text = "…" + text }
            if end < words.count { text += "…" }
            return text
        }
    }
}
