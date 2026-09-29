import Foundation

/// Small text helpers shared by the parser, search and cook mode.
enum TextKit {
    static let unicodeFractions: [Character: Double] = [
        "½": 1.0 / 2, "⅓": 1.0 / 3, "⅔": 2.0 / 3, "¼": 1.0 / 4, "¾": 3.0 / 4,
        "⅕": 1.0 / 5, "⅖": 2.0 / 5, "⅗": 3.0 / 5, "⅘": 4.0 / 5, "⅙": 1.0 / 6,
        "⅚": 5.0 / 6, "⅛": 1.0 / 8, "⅜": 3.0 / 8, "⅝": 5.0 / 8, "⅞": 7.0 / 8,
        "⅐": 1.0 / 7, "⅑": 1.0 / 9, "⅒": 1.0 / 10,
    ]

    static let numberWords: [String: Double] = [
        "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7,
        "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12, "fifteen": 15,
        "twenty": 20, "thirty": 30, "forty": 40, "fifty": 50, "sixty": 60, "ninety": 90,
        "a": 1, "an": 1, "half": 0.5, "dozen": 12,
    ]

    /// Lowercased with accents removed: "Crème Brûlée" → "creme brulee".
    static func fold(_ s: String) -> String {
        s.folding(options: [.diacriticInsensitive, .caseInsensitive, .widthInsensitive], locale: nil).lowercased()
    }

    /// Folded alphanumeric words. Apostrophes are dropped so "grandma's" → "grandmas".
    static func words(_ s: String) -> [String] {
        var result: [String] = []
        var current = ""
        for ch in fold(s) {
            if ch.isLetter || ch.isNumber {
                current.append(ch)
            } else if ch == "'" || ch == "’" {
                continue
            } else if !current.isEmpty {
                result.append(current)
                current = ""
            }
        }
        if !current.isEmpty { result.append(current) }
        return result
    }

    private static let irregularSingulars: [String: String] = [
        "leaves": "leaf", "halves": "half", "loaves": "loaf", "knives": "knife",
        "potatoes": "potato", "tomatoes": "tomato", "mangoes": "mango", "cookies": "cookie",
        "anchovies": "anchovy", "radishes": "radish", "dishes": "dish",
    ]

    private static let keepAsIs: Set<String> = [
        "molasses", "hummus", "couscous", "asparagus", "swiss", "citrus", "grits", "oats",
        "brussels", "chips", "series", "species", "bass", "glass", "grass", "cress", "less",
    ]

    /// A rough English singular, good enough for matching "tomatoes" with "tomato".
    static func singular(_ word: String) -> String {
        let w = word.lowercased()
        if let irregular = irregularSingulars[w] { return irregular }
        if keepAsIs.contains(w) || w.count <= 3 { return w }
        if w.hasSuffix("ies") && w.count > 4 { return String(w.dropLast(3)) + "y" }
        if w.hasSuffix("ches") || w.hasSuffix("shes") || w.hasSuffix("sses") || w.hasSuffix("xes") || w.hasSuffix("zes") {
            return String(w.dropLast(2))
        }
        if w.hasSuffix("oes") { return String(w.dropLast(2)) }
        if w.hasSuffix("ss") || w.hasSuffix("us") || w.hasSuffix("is") { return w }
        if w.hasSuffix("s") { return String(w.dropLast()) }
        return w
    }

    /// Turns "CHOCOLATE CHIP COOKIES" into "Chocolate Chip Cookies". Mixed-case text is left alone.
    static func titleCasedIfShouting(_ s: String) -> String {
        let letters = s.filter(\.isLetter)
        guard letters.count >= 3, letters.allSatisfy({ $0.isUppercase }) else { return s }
        let small: Set<String> = ["a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "with", "from"]
        let parts = s.lowercased().split(separator: " ", omittingEmptySubsequences: false)
        return parts.enumerated().map { index, part in
            let word = String(part)
            if index > 0 && small.contains(word) { return word }
            return capitalizeFirstLetter(word)
        }.joined(separator: " ")
    }

    static func capitalizeFirstLetter(_ word: String) -> String {
        guard let i = word.firstIndex(where: { $0.isLetter }) else { return word }
        return word.replacingCharacters(in: i...i, with: String(word[i]).uppercased())
    }

    /// Larger numbers are misread text, not amounts or times, and would overflow when shown.
    static let maxNumber = 1_000_000.0

    /// Parses "2", "1.5", "1,5", "1/2", "1 1/2", "1½", "½", "one" into a number.
    static func numberValue(_ raw: String) -> Double? {
        guard let value = anyNumberValue(raw), value.isFinite, value <= maxNumber else { return nil }
        return value
    }

    private static func anyNumberValue(_ raw: String) -> Double? {
        let s = raw.trimmingCharacters(in: .whitespaces).lowercased()
        if s.isEmpty { return nil }
        if let word = numberWords[s] { return word }
        if s == "half a" || s == "half an" || s == "a half" { return 0.5 }
        if s.count == 1, let ch = s.first, let frac = unicodeFractions[ch] { return frac }
        if let last = s.last, let frac = unicodeFractions[last] {
            let head = s.dropLast().trimmingCharacters(in: .whitespaces)
            if head.isEmpty { return frac }
            if let whole = Double(head) { return whole + frac }
            return nil
        }
        let parts = s.split(separator: " ").map(String.init)
        if parts.count == 2, let whole = Double(parts[0]), let frac = simpleFraction(parts[1]) {
            return whole + frac
        }
        if let frac = simpleFraction(s) { return frac }
        return Double(s.replacingOccurrences(of: ",", with: "."))
    }

    private static func simpleFraction(_ s: String) -> Double? {
        let pieces = s.split(separator: "/")
        guard pieces.count == 2, let n = Double(pieces[0]), let d = Double(pieces[1]), d != 0 else { return nil }
        return n / d
    }

    /// Replaces typographic variants that OCR and web pages produce.
    static func cleanLine(_ s: String) -> String {
        var t = s
        let replacements: [(String, String)] = [
            ("\u{2044}", "/"), ("\u{2215}", "/"), ("ﬁ", "fi"), ("ﬂ", "fl"), ("ﬀ", "ff"),
            ("\u{00A0}", " "), ("\t", " "), ("’", "'"), ("‘", "'"), ("“", "\""), ("”", "\""),
        ]
        for (from, to) in replacements { t = t.replacingOccurrences(of: from, with: to) }
        while t.contains("  ") { t = t.replacingOccurrences(of: "  ", with: " ") }
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Runs an ICU regular expression and returns every match. Patterns are shared
    /// verbatim with the Android app, so they stick to syntax both engines support.
    static func regex(_ pattern: String) -> NSRegularExpression {
        // Patterns are compile-time constants; a failure here is a programming error.
        try! NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
    }

    static func matches(_ regex: NSRegularExpression, in s: String) -> [NSTextCheckingResult] {
        regex.matches(in: s, range: NSRange(s.startIndex..., in: s))
    }

    static func group(_ match: NSTextCheckingResult, _ index: Int, in s: String) -> String? {
        let r = match.range(at: index)
        guard r.location != NSNotFound, let range = Range(r, in: s) else { return nil }
        return String(s[range])
    }
}
