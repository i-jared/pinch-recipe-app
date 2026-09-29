import CoreGraphics
import Foundation

/// A line of recognized or pasted text, with layout hints from OCR.
struct TextLine: Hashable, Sendable {
    var text: String
    /// Normalized bounding box with a top-left origin. Nil for pasted text.
    var box: CGRect?
    var page: Int
    /// True when this line begins a new paragraph (a visual gap, or the previous line ended short).
    var paragraphStart: Bool
    /// True when the previous line ran to the right edge of its column, so this one may continue it.
    var previousLineFull: Bool

    init(text: String, box: CGRect? = nil, page: Int = 0, paragraphStart: Bool = true, previousLineFull: Bool = false) {
        self.text = text
        self.box = box
        self.page = page
        self.paragraphStart = paragraphStart
        self.previousLineFull = previousLineFull
    }
}

struct ParsedRecipe: Sendable {
    var title = ""
    var summary = ""
    var source = ""
    var servings = ""
    var prepTime = ""
    var cookTime = ""
    var totalTime = ""
    var ingredients: [RecipeItem] = []
    var steps: [RecipeItem] = []
    var notes = ""
    var rawText = ""
    /// Where parsing stopped, so a later page can pick up in the same section.
    var endSection: RecipeParser.Section = .preamble

    var isEmpty: Bool { title.isEmpty && ingredients.isEmpty && steps.isEmpty && summary.isEmpty && notes.isEmpty }
}

/// Turns recognized text into a structured recipe using layout and wording cues.
/// Mirrored line for line by the Android app's `RecipeParser.kt`.
enum RecipeParser {
    enum Section: String, Sendable { case preamble, ingredients, steps, notes }

    // MARK: Entry points

    static func parse(text: String) -> ParsedRecipe {
        var result = parse(lines: lines(fromPlainText: text))
        result.rawText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return result
    }

    /// Plain text has no geometry, so each line starts a paragraph; soft wraps are
    /// recovered from lowercase continuations.
    static func lines(fromPlainText text: String) -> [TextLine] {
        text.components(separatedBy: .newlines)
            .map(TextKit.cleanLine)
            .filter { !$0.isEmpty }
            .map { TextLine(text: $0) }
    }

    /// - Parameter continuing: the section a previous page ended in. When set, no
    ///   title is detected and unlabeled lines continue that section.
    static func parse(lines input: [TextLine], continuing: Section? = nil) -> ParsedRecipe {
        var result = ParsedRecipe()
        result.rawText = rawText(from: input)

        var lines = input
            .map { line -> TextLine in var l = line; l.text = TextKit.cleanLine(l.text); return l }
            .filter { !$0.text.isEmpty && !isNoise($0) }

        let shouting = mostlyUppercase(lines)
        var consumed = Set<Int>()
        var titleIndex = -1
        if continuing == nil {
            let found = findTitle(in: lines, shouting: shouting)
            result.title = found.title
            consumed.formUnion(found.indices)
            titleIndex = found.indices.min() ?? -1
        }

        // Cards written entirely in capitals read better in sentence case.
        if shouting {
            lines = lines.map { line in var l = line; l.text = sentenceCased(l.text); return l }
        }

        let hasStepsHeader = lines.contains { header(for: $0.text)?.section == .steps }
        var section: Section = continuing ?? .preamble
        var stepsNumbered = false
        var pendingNewStep = false
        var summary: [String] = []
        var notes: [String] = []

        for (index, line) in lines.enumerated() where !consumed.contains(index) {
            var text = line.text

            // Running headers above the title ("DESSERTS", "Page 12") and printed card labels are dropped.
            if titleIndex >= 0 && index < titleIndex && text.count <= 30 && section == .preamble { continue }
            if notTitles.contains(TextKit.fold(text).trimmingCharacters(in: CharacterSet(charactersIn: " :"))) { continue }

            let meta = extractMetadata(from: text, allowBareCookTime: section == .preamble)
            if meta.matched {
                if result.servings.isEmpty, let s = meta.servings { result.servings = s }
                if result.prepTime.isEmpty, let p = meta.prep { result.prepTime = p }
                if result.cookTime.isEmpty, let c = meta.cook { result.cookTime = c }
                if result.totalTime.isEmpty, let t = meta.total { result.totalTime = t }
                if meta.remainder.isEmpty { continue }
                text = meta.remainder
            }

            if let found = header(for: text) {
                section = found.section
                pendingNewStep = false
                guard let rest = found.remainder, !rest.isEmpty else { continue }
                text = rest
            }

            if let source = sourceText(text, allowBareBy: section == .preamble && result.ingredients.isEmpty) {
                if result.source.isEmpty { result.source = source }
                continue
            }

            switch section {
            case .preamble:
                if isSubheader(text, shouting: shouting), nextLineStartsWithQuantity(after: index, in: lines, skipping: consumed) {
                    // "For the cake:" right before a list starts the ingredients.
                    section = .ingredients
                    result.ingredients.append(RecipeItem(text: subheaderText(text), isHeader: true))
                } else if let rest = numberedStep(text), !startsWithQuantity(rest) {
                    section = .steps
                    stepsNumbered = true
                    result.steps.append(RecipeItem(text: rest))
                } else if bulletStripped(text) != nil || startsWithQuantity(text) {
                    section = .ingredients
                    result.ingredients.append(RecipeItem(text: cleanIngredient(text)))
                } else if let previous = summary.last, continuesParagraph(line, text: text, previous: previous) {
                    summary[summary.count - 1] = join(previous, text)
                } else {
                    summary.append(text)
                }

            case .ingredients:
                if isSubheader(text, shouting: shouting) {
                    result.ingredients.append(RecipeItem(text: subheaderText(text), isHeader: true))
                } else if let rest = numberedStep(text) {
                    if startsWithQuantity(rest) {
                        result.ingredients.append(RecipeItem(text: cleanIngredient(rest)))
                    } else if !hasStepsHeader {
                        section = .steps
                        stepsNumbered = true
                        result.steps.append(RecipeItem(text: rest))
                    } else {
                        result.ingredients.append(RecipeItem(text: cleanIngredient(text)))
                    }
                } else if let last = result.ingredients.last, !last.isHeader,
                          continuesIngredient(line, text: text, previous: last.text) {
                    result.ingredients[result.ingredients.count - 1].text = cleanIngredient(join(last.text, text))
                } else if !hasStepsHeader, !result.ingredients.isEmpty, looksLikeInstruction(text) {
                    section = .steps
                    result.steps.append(RecipeItem(text: text))
                } else {
                    result.ingredients.append(RecipeItem(text: cleanIngredient(text)))
                }

            case .steps:
                if isBareStepNumber(text) {
                    stepsNumbered = true
                    pendingNewStep = true
                } else if let rest = numberedStep(text) {
                    stepsNumbered = true
                    pendingNewStep = false
                    result.steps.append(RecipeItem(text: rest))
                } else if pendingNewStep {
                    pendingNewStep = false
                    result.steps.append(RecipeItem(text: text))
                } else if isSubheader(text, shouting: shouting),
                          !(result.steps.last.map { endsWithJoiner($0.text) } ?? false) {
                    result.steps.append(RecipeItem(text: subheaderText(text), isHeader: true))
                } else if let bulleted = bulletStripped(text) {
                    result.steps.append(RecipeItem(text: bulleted))
                } else if let last = result.steps.last, !last.isHeader,
                          stepsNumbered || continuesParagraph(line, text: text, previous: last.text) {
                    result.steps[result.steps.count - 1].text = join(last.text, text)
                } else {
                    result.steps.append(RecipeItem(text: text))
                }

            case .notes:
                if let previous = notes.last, continuesParagraph(line, text: text, previous: previous) {
                    notes[notes.count - 1] = join(previous, text)
                } else {
                    notes.append(bulletStripped(text) ?? text)
                }
            }
        }

        // No steps found but a long preamble: the method probably came first on the page.
        if result.steps.isEmpty && !summary.isEmpty && (result.ingredients.isEmpty || summary.joined().count > 160) {
            result.steps = summary.map { RecipeItem(text: $0) }
            summary = []
        }

        if !stepsNumbered {
            result.steps = result.steps.flatMap { item -> [RecipeItem] in
                item.isHeader ? [item] : splitLongStep(item.text).map { RecipeItem(text: $0) }
            }
        }

        result.summary = summary.joined(separator: "\n")
        result.notes = notes.joined(separator: "\n")
        result.endSection = section
        return result
    }

    // MARK: Title

    static func findTitle(in lines: [TextLine], shouting: Bool) -> (title: String, indices: Set<Int>) {
        guard let firstPage = lines.first?.page else { return ("", []) }
        let heights = lines.compactMap { $0.box?.height }.sorted()

        if heights.count >= 3 {
            let median = heights[heights.count / 2]
            let candidates = lines.indices.prefix(14).filter { i in
                lines[i].page == firstPage && (lines[i].box?.minY ?? 1) < 0.5 && isTitleCandidate(lines[i].text)
            }
            if let best = candidates.max(by: { (lines[$0].box?.height ?? 0) < (lines[$1].box?.height ?? 0) }),
               let height = lines[best].box?.height, height >= median * 1.3 {
                var used = [best]
                var next = best + 1
                while next < lines.count, used.count < 3, lines[next].page == firstPage,
                      let h = lines[next].box?.height, h >= height * 0.8, isTitleCandidate(lines[next].text) {
                    used.append(next)
                    next += 1
                }
                let title = used.map { lines[$0].text }.joined(separator: " ")
                return (cleanTitle(title, shouting: shouting), Set(used))
            }
        }

        for i in lines.indices.prefix(6) {
            let text = TextKit.cleanLine(lines[i].text)
            if isTitleCandidate(text) && text.count <= 70 && !text.hasSuffix(".") {
                return (cleanTitle(text, shouting: shouting), [i])
            }
        }
        return ("", [])
    }

    private static let notTitles: Set<String> = ["recipe", "recipes", "recipe card", "from the kitchen of", "a recipe from", "menu"]

    static func isTitleCandidate(_ raw: String) -> Bool {
        let text = TextKit.cleanLine(raw)
        guard text.filter(\.isLetter).count >= 3 else { return false }
        let folded = TextKit.fold(text).trimmingCharacters(in: CharacterSet(charactersIn: " :"))
        if notTitles.contains(folded) { return false }
        if header(for: text) != nil { return false }
        if extractMetadata(from: text, allowBareCookTime: true).matched { return false }
        if startsWithQuantity(text) || bulletStripped(text) != nil || numberedStep(text) != nil { return false }
        if sourceText(text, allowBareBy: false) != nil { return false }
        return true
    }

    private static func cleanTitle(_ title: String, shouting: Bool) -> String {
        var t = title.trimmingCharacters(in: CharacterSet(charactersIn: " :-–—*#"))
        t = TextKit.titleCasedIfShouting(t)
        if shouting { t = titleCased(t) }
        return t
    }

    // MARK: Headers

    struct Header: Equatable {
        let section: Section
        let remainder: String?
    }

    private static let headerPhrases: [(Section, Set<String>)] = [
        (.ingredients, [
            "ingredients", "ingredient", "ingredient list", "ingredients list", "the ingredients",
            "you will need", "you'll need", "youll need", "what you'll need", "what you need",
            "what you will need", "shopping list", "for the recipe",
        ]),
        (.steps, [
            "directions", "direction", "instructions", "instruction", "method", "methods", "the method",
            "preparation", "steps", "how to make", "how to make it", "to make", "procedure",
            "cooking instructions", "cooking directions", "baking instructions", "make it", "let's cook",
            "lets cook", "to prepare", "how to prepare", "what to do",
        ]),
        (.notes, [
            "notes", "note", "tips", "tip", "cook's note", "cook's notes", "cooks note", "cooks notes",
            "chef's note", "chef's notes", "chefs note", "chefs notes", "cook's tip", "cook's tips",
            "variations", "variation", "storage", "to store", "make ahead", "make-ahead",
            "serving suggestions", "serving suggestion", "recipe notes", "helpful hints", "hints",
            "tips and tricks", "kitchen notes", "notes and tips",
        ]),
    ]

    static func header(for raw: String) -> Header? {
        let text = raw.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty, text.count <= 60 else { return nil }
        let stripped = text
            .trimmingCharacters(in: CharacterSet(charactersIn: "#*=_~•·-–— "))
            .trimmingCharacters(in: CharacterSet(charactersIn: ":.-–— "))
        let folded = TextKit.fold(stripped)
        for (section, phrases) in headerPhrases where phrases.contains(folded) {
            return Header(section: section, remainder: nil)
        }
        if let colon = text.firstIndex(of: ":") {
            let head = TextKit.fold(String(text[..<colon]))
                .trimmingCharacters(in: CharacterSet(charactersIn: "#*=_~•·-–— "))
            let rest = String(text[text.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            for (section, phrases) in headerPhrases where phrases.contains(head) {
                return Header(section: section, remainder: rest.isEmpty ? nil : rest)
            }
        }
        return nil
    }

    static func isSubheader(_ text: String, shouting: Bool) -> Bool {
        let t = text.trimmingCharacters(in: .whitespaces)
        guard t.count <= 45, !startsWithQuantity(t), numberedStep(t) == nil else { return false }
        let letters = t.filter(\.isLetter)
        guard letters.count >= 3 else { return false }
        if t.hasSuffix(":") { return true }
        let folded = TextKit.fold(t)
        if folded.hasPrefix("for the ") && t.count <= 40 { return true }
        if folded.hasPrefix("for ") && t.count <= 28 && !t.contains(",") { return true }
        if !shouting && letters.allSatisfy(\.isUppercase) && t.count <= 30 && !t.contains(where: \.isNumber) { return true }
        return false
    }

    private static func subheaderText(_ text: String) -> String {
        let t = text.trimmingCharacters(in: CharacterSet(charactersIn: " :-–—*#"))
        return TextKit.titleCasedIfShouting(t)
    }

    // MARK: Metadata

    struct Metadata: Equatable {
        var servings: String?
        var prep: String?
        var cook: String?
        var total: String?
        var remainder: String
        var matched: Bool
    }

    private static let servingsPattern = TextKit.regex(#"^(?:serves|serving size|servings|yield|yields|makes|portions|feeds)\b\s*[:\-–]?\s*(.+)$"#)
    private static let servingsTrailingPattern = TextKit.regex(#"^(?:about\s+)?(\d+(?:\s*(?:-|–|to)\s*\d+)?)\s+(?:servings|portions|people)\.?$"#)
    private static let timePattern = TextKit.regex(#"^(prep(?:aration)?(?:\s+time)?|active(?:\s+time)?|hands[- ]on(?:\s+time)?|cook(?:ing)?(?:\s+time)?|bake(?:\s+time)?|baking(?:\s+time)?|total(?:\s+time)?|ready\s+in|start\s+to\s+finish)\b\s*([:\-–]?)\s*(.+)$"#)
    private static let chunkSeparators = TextKit.regex(#"\s*(?:\||•|·|;|\s/\s)\s*"#)
    private static let keywordBoundary = TextKit.regex(#"\s(?=(?:prep|preparation|cook|cooking|bake|baking|total|ready in|serves|servings|yield|yields|makes|active)\b)"#)
    private static let startsWithKeyword = TextKit.regex(#"^(?:prep|preparation|cook|cooking|bake|baking|total|ready in|serves|servings|yield|yields|makes|active|hands)\b"#)
    private static let fillerWords: Set<String> = ["about", "approx", "approximately", "around", "roughly", "plus", "or", "so", "just", "under", "over"]

    static func extractMetadata(from text: String, allowBareCookTime: Bool) -> Metadata {
        var meta = Metadata(servings: nil, prep: nil, cook: nil, total: nil, remainder: text, matched: false)
        let chunks = metadataChunks(text)
        var leftovers: [String] = []

        for chunk in chunks {
            if let m = servingsPattern.firstMatch(in: chunk, range: NSRange(chunk.startIndex..., in: chunk)),
               let value = TextKit.group(m, 1, in: chunk)?.trimmingCharacters(in: CharacterSet(charactersIn: " .")),
               value.count <= 40, containsNumber(value) {
                meta.servings = meta.servings ?? value
                continue
            }
            if let m = servingsTrailingPattern.firstMatch(in: chunk, range: NSRange(chunk.startIndex..., in: chunk)),
               let value = TextKit.group(m, 1, in: chunk) {
                meta.servings = meta.servings ?? value
                continue
            }
            if let m = timePattern.firstMatch(in: chunk, range: NSRange(chunk.startIndex..., in: chunk)),
               let keyword = TextKit.group(m, 1, in: chunk)?.lowercased(),
               let value = TextKit.group(m, 3, in: chunk) {
                let colon = TextKit.group(m, 2, in: chunk) ?? ""
                let durations = DurationParser.matches(in: value)
                if let first = durations.first {
                    let onlyDuration = isOnlyDurations(value, durations)
                    let explicit = keyword.contains("time") || !colon.isEmpty || keyword.hasPrefix("ready") || keyword.hasPrefix("start")
                    let softKeyword = keyword.hasPrefix("prep") || keyword.hasPrefix("total") || keyword.hasPrefix("active") || keyword.hasPrefix("hands")
                    if explicit || (onlyDuration && (softKeyword || allowBareCookTime || chunks.count > 1)) {
                        let trimmed = value.trimmingCharacters(in: CharacterSet(charactersIn: " ."))
                        let normalized = onlyDuration ? first.label : String(trimmed.prefix(40))
                        if keyword.hasPrefix("prep") || keyword.hasPrefix("active") || keyword.hasPrefix("hands") {
                            meta.prep = meta.prep ?? normalized
                        } else if keyword.hasPrefix("cook") || keyword.hasPrefix("bak") {
                            meta.cook = meta.cook ?? normalized
                        } else {
                            meta.total = meta.total ?? normalized
                        }
                        continue
                    }
                }
            }
            leftovers.append(chunk)
        }

        meta.matched = leftovers.count < chunks.count
        meta.remainder = leftovers.joined(separator: " ")
        return meta
    }

    // OCR often reads a "|" separator as a lone "I" or "l".
    private static let strayPipe = TextKit.regex(#"^[|Il]\s+|\s+[|Il]$"#)

    private static func metadataChunks(_ text: String) -> [String] {
        var chunks = split(text, by: chunkSeparators)
        chunks = chunks.flatMap { chunk -> [String] in
            let range = NSRange(chunk.startIndex..., in: chunk)
            guard startsWithKeyword.firstMatch(in: chunk, range: range) != nil else { return [chunk] }
            return split(chunk, by: keywordBoundary).map { piece in
                strayPipe.stringByReplacingMatches(in: piece, range: NSRange(piece.startIndex..., in: piece), withTemplate: "")
            }
        }
        return chunks.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    private static func split(_ text: String, by regex: NSRegularExpression) -> [String] {
        let ns = text as NSString
        var pieces: [String] = []
        var start = 0
        for m in regex.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            pieces.append(ns.substring(with: NSRange(location: start, length: m.range.location - start)))
            start = m.range.location + m.range.length
        }
        pieces.append(ns.substring(from: start))
        return pieces
    }

    private static func isOnlyDurations(_ value: String, _ durations: [DurationParser.Match]) -> Bool {
        let ns = NSMutableString(string: value)
        for d in durations.reversed() {
            ns.replaceCharacters(in: NSRange(location: d.location, length: d.length), with: " ")
        }
        let rest = TextKit.words(ns as String).filter { !fillerWords.contains($0) }
        return rest.isEmpty
    }

    private static func containsNumber(_ s: String) -> Bool {
        if s.contains(where: { $0.isNumber }) { return true }
        return TextKit.words(s).contains { TextKit.numberWords[$0] != nil && $0 != "a" && $0 != "an" }
    }

    // MARK: Source lines

    private static let sourcePattern = TextKit.regex(#"^(?:from the kitchen of|recipe from|recipe by|recipe courtesy of|courtesy of|adapted from|source\s*:|from:)\s*(.+)$"#)
    private static let bylinePattern = TextKit.regex(#"^by\s+([A-Z][\w.'-]*(?:\s+[A-Z][\w.'-]*){0,3})$"#)

    static func sourceText(_ text: String, allowBareBy: Bool) -> String? {
        guard text.count <= 70 else { return nil }
        let range = NSRange(text.startIndex..., in: text)
        if let m = sourcePattern.firstMatch(in: text, range: range) {
            let folded = TextKit.fold(text)
            if folded.hasPrefix("source") || folded.hasPrefix("from:") {
                return TextKit.group(m, 1, in: text)?.trimmingCharacters(in: .whitespaces)
            }
            return text.trimmingCharacters(in: CharacterSet(charactersIn: " ."))
        }
        if allowBareBy, bylinePattern.firstMatch(in: text, range: range) != nil {
            return text
        }
        return nil
    }

    // MARK: Line classification

    private static let numberedPattern = TextKit.regex(#"^(?:step\s*)?(\d{1,2})\s*[.):\]]\s+(.+)$"#)
    private static let numberedTightPattern = TextKit.regex(#"^(?:step\s*)?(\d{1,2})[.)]([A-Za-z].*)$"#)
    private static let stepWordPattern = TextKit.regex(#"^step\s*(\d{1,2})\s*[-–:.]?\s*(.+)$"#)
    // Case-sensitive: "1 Preheat the oven" but not "1 cup flour".
    private static let numberedCapitalPattern = try! NSRegularExpression(pattern: #"^(\d{1,2})\s+([A-Z][a-z].{12,})$"#)
    private static let bareNumberPattern = TextKit.regex(#"^(?:step\s*)?\d{1,2}\s*[.):]?$"#)
    private static let bulletPattern = TextKit.regex(#"^(?:[•·▪●○◦■□☐✓✔➢►>*~]|[-–—](?=\s)|[oe°](?=\s+\d))\s*(.+)$"#)

    /// The text of a numbered step without its number, or nil.
    static func numberedStep(_ text: String) -> String? {
        let range = NSRange(text.startIndex..., in: text)
        for pattern in [numberedPattern, numberedTightPattern, stepWordPattern] {
            if let m = pattern.firstMatch(in: text, range: range), let rest = TextKit.group(m, 2, in: text) {
                return rest.trimmingCharacters(in: .whitespaces)
            }
        }
        // "1 Preheat the oven" is a step; "12 Graham crackers" is not.
        if let m = numberedCapitalPattern.firstMatch(in: text, range: range), let rest = TextKit.group(m, 2, in: text),
           let firstWord = TextKit.words(rest).first, instructionVerbs.contains(firstWord) {
            return rest
        }
        return nil
    }

    static func isBareStepNumber(_ text: String) -> Bool {
        bareNumberPattern.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }

    static func bulletStripped(_ text: String) -> String? {
        let range = NSRange(text.startIndex..., in: text)
        guard let m = bulletPattern.firstMatch(in: text, range: range) else { return nil }
        return TextKit.group(m, 1, in: text)?.trimmingCharacters(in: .whitespaces)
    }

    static func startsWithQuantity(_ text: String) -> Bool {
        let stripped = bulletStripped(text) ?? text
        return IngredientParser.parse(stripped).quantity != nil
    }

    private static let instructionVerbs: Set<String> = [
        "preheat", "heat", "mix", "combine", "stir", "whisk", "add", "bake", "cook", "place", "put", "pour",
        "beat", "bring", "remove", "serve", "cut", "chop", "slice", "melt", "simmer", "boil", "fry", "saute",
        "roast", "grill", "blend", "fold", "spread", "sprinkle", "season", "cover", "let", "transfer", "drain",
        "rinse", "toss", "arrange", "top", "garnish", "refrigerate", "chill", "freeze", "knead", "roll", "shape",
        "form", "divide", "grease", "line", "spray", "prepare", "make", "set", "allow", "return", "reduce",
        "increase", "turn", "cream", "sift", "whip", "marinate", "brush", "layer", "cool", "store", "wash",
        "peel", "dice", "mince", "grate", "squeeze", "scoop", "press", "repeat", "finish", "enjoy", "in",
        "using", "meanwhile", "once", "when", "while", "after", "then", "finally", "first", "next", "gently",
    ]

    private static let instructionGlue = [" the ", " a ", " an ", " until ", " into ", " to ", " with ", " over ", " in ", " on "]

    static func looksLikeInstruction(_ text: String) -> Bool {
        guard !startsWithQuantity(text) else { return false }
        if text.count >= 80 { return true }
        if text.count >= 45 && (text.hasSuffix(".") || text.contains(". ")) { return true }
        // "Cream the butter and sugar" but not "Cream cheese frosting (see below)".
        if let first = TextKit.words(text).first, instructionVerbs.contains(first), text.count >= 25 {
            let folded = " " + TextKit.fold(text) + " "
            return instructionGlue.contains { folded.contains($0) }
        }
        return false
    }

    private static let joinerWords: Set<String> = ["and", "or", "of", "with", "plus", "into", "to", "the", "a", "for", "in", "at", "about"]

    static func endsWithJoiner(_ text: String) -> Bool {
        guard let last = text.last else { return false }
        if ",-/&(".contains(last) { return true }
        if let word = TextKit.words(text).last, joinerWords.contains(word), !text.hasSuffix(".") { return true }
        return false
    }

    private static func endsWithTerminal(_ text: String) -> Bool {
        guard let last = text.last else { return false }
        return ".!?:)".contains(last)
    }

    private static func startsLowercase(_ text: String) -> Bool {
        text.first(where: { $0.isLetter || $0.isNumber })?.isLowercase ?? false
    }

    static func continuesIngredient(_ line: TextLine, text: String, previous: String) -> Bool {
        if startsWithQuantity(text) || bulletStripped(text) != nil { return false }
        if endsWithJoiner(previous) { return true }
        if text.hasPrefix("(") { return true }
        if line.previousLineFull && !line.paragraphStart && startsLowercase(text) { return true }
        return false
    }

    static func continuesParagraph(_ line: TextLine, text: String, previous: String) -> Bool {
        if numberedStep(text) != nil || bulletStripped(text) != nil { return false }
        if endsWithJoiner(previous) { return true }
        if line.box != nil { return !line.paragraphStart }
        return startsLowercase(text) && !endsWithTerminal(previous)
    }

    private static let compoundPrefixes: Set<String> = [
        "all", "self", "half", "semi", "non", "extra", "low", "high", "well", "sugar", "fat", "gluten", "dairy",
        "store", "sun", "bite", "medium", "deep", "pan", "stir", "slow", "pre", "re", "co", "double", "single",
        "long", "short", "quick", "bone", "skin", "thick", "thin", "full", "part", "room", "oven", "egg",
        "reduced", "sodium", "lemon", "lime", "no", "one", "two", "three", "fine", "coarse", "rock", "sea",
    ]

    /// Joins a wrapped line onto the previous one, undoing end-of-line hyphenation.
    static func join(_ previous: String, _ next: String) -> String {
        let prev = previous.trimmingCharacters(in: .whitespaces)
        let nxt = next.trimmingCharacters(in: .whitespaces)
        if prev.hasSuffix("-"), let firstChar = nxt.first, firstChar.isLowercase {
            let beforeHyphen = String(prev.dropLast())
            let lastWord = TextKit.words(beforeHyphen).last ?? ""
            if compoundPrefixes.contains(lastWord) { return prev + nxt }
            if let lastChar = beforeHyphen.last, lastChar.isLetter { return beforeHyphen + nxt }
        }
        return prev + " " + nxt
    }

    private static func cleanIngredient(_ text: String) -> String {
        var t = bulletStripped(text) ?? text
        if let rest = numberedStep(t), startsWithQuantity(rest) { t = rest }
        t = t.trimmingCharacters(in: CharacterSet(charactersIn: " ,;"))
        return t
    }

    private static func isNoise(_ line: TextLine) -> Bool {
        let t = line.text.trimmingCharacters(in: .whitespaces)
        if t.isEmpty { return true }
        if t.count == 1, !(t.first?.isLetter ?? false), !(t.first?.isNumber ?? false), TextKit.unicodeFractions[t.first!] == nil { return true }
        let folded = TextKit.fold(t).trimmingCharacters(in: CharacterSet(charactersIn: " -–—()[]*."))
        if folded.range(of: #"^page\s+\d+(\s+of\s+\d+)?$"#, options: .regularExpression) != nil { return true }
        // Page-turn markers: "— continued on next page —", "(continued)", "cont'd"
        if folded.range(of: #"^(continued|cont'?d)(\s+(on|from)\s+(the\s+)?(next|previous|following|page|reverse|back|other side).*)?$"#, options: .regularExpression) != nil { return true }
        if folded.range(of: #"^(see|turn)\s+(over|next page|page \d+|reverse|back)$"#, options: .regularExpression) != nil { return true }
        if t.allSatisfy(\.isNumber), t.count <= 3, let box = line.box, box.minY < 0.06 || box.maxY > 0.94 { return true }
        return false
    }

    // MARK: Case helpers

    private static func mostlyUppercase(_ lines: [TextLine]) -> Bool {
        let lettered = lines.filter { $0.text.filter(\.isLetter).count >= 3 }
        guard lettered.count >= 4 else { return false }
        let upper = lettered.filter { $0.text.filter(\.isLetter).allSatisfy(\.isUppercase) }
        return Double(upper.count) / Double(lettered.count) > 0.6
    }

    private static func sentenceCased(_ text: String) -> String {
        let letters = text.filter(\.isLetter)
        guard !letters.isEmpty, letters.allSatisfy(\.isUppercase) else { return text }
        let lowered = text.lowercased()
        // "2 CUPS FLOUR" → "2 cups flour", "MIX WELL." → "Mix well."
        guard let first = lowered.first(where: { !$0.isWhitespace }), first.isLetter else { return lowered }
        return TextKit.capitalizeFirstLetter(lowered)
    }

    private static func nextLineStartsWithQuantity(after index: Int, in lines: [TextLine], skipping consumed: Set<Int>) -> Bool {
        var next = index + 1
        while next < lines.count, consumed.contains(next) { next += 1 }
        return next < lines.count && startsWithQuantity(lines[next].text)
    }

    private static func titleCased(_ text: String) -> String {
        let small: Set<String> = ["a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "with", "from"]
        return text.split(separator: " ").enumerated().map { index, part in
            let word = String(part)
            if index > 0 && small.contains(word.lowercased()) { return word.lowercased() }
            return TextKit.capitalizeFirstLetter(word)
        }.joined(separator: " ")
    }

    // MARK: Long paragraphs

    private static let abbreviations: Set<String> = [
        "tsp", "tbsp", "oz", "lb", "lbs", "min", "mins", "approx", "eg", "ie", "no", "st", "dr", "mr", "mrs",
        "ms", "vs", "deg", "temp", "pkg", "qt", "pt", "gal", "fl", "c", "t", "hr", "hrs", "sec", "secs", "etc",
    ]

    /// Breaks a very long unnumbered paragraph into cook-mode-friendly steps at sentence boundaries.
    static func splitLongStep(_ text: String) -> [String] {
        guard text.count > 360 else { return [text] }
        var sentences: [String] = []
        var current = ""
        let chars = Array(text)
        var i = 0
        while i < chars.count {
            current.append(chars[i])
            if ".!?".contains(chars[i]), i + 2 < chars.count, chars[i + 1] == " ", chars[i + 2].isUppercase {
                let lastWord = TextKit.words(current).last ?? ""
                if !abbreviations.contains(lastWord) {
                    sentences.append(current.trimmingCharacters(in: .whitespaces))
                    current = ""
                }
            }
            i += 1
        }
        if !current.trimmingCharacters(in: .whitespaces).isEmpty {
            sentences.append(current.trimmingCharacters(in: .whitespaces))
        }
        var chunks: [String] = []
        var chunk = ""
        for sentence in sentences {
            if chunk.isEmpty {
                chunk = sentence
            } else if chunk.count + sentence.count + 1 <= 240 {
                chunk += " " + sentence
            } else {
                chunks.append(chunk)
                chunk = sentence
            }
        }
        if !chunk.isEmpty { chunks.append(chunk) }
        return chunks
    }

    // MARK: Raw text

    static func rawText(from lines: [TextLine]) -> String {
        var pages: [[String]] = []
        var currentPage: Int?
        for line in lines {
            if line.page != currentPage {
                pages.append([])
                currentPage = line.page
            }
            pages[pages.count - 1].append(line.text)
        }
        return pages.map { $0.joined(separator: "\n") }.joined(separator: "\n\n")
    }
}
