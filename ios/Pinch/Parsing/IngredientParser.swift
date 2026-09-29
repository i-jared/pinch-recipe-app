import Foundation

/// Understands the quantity and unit at the front of an ingredient line so it can be
/// highlighted and scaled: "1 1/2 cups flour" × 2 → "3 cups flour".
enum IngredientParser {
    struct Quantity: Equatable {
        /// Character offsets into the line (grapheme clusters), end exclusive.
        let start: Int
        let end: Int
        let low: Double
        let high: Double?
        let separator: String
        let isDecimal: Bool
        let isWord: Bool
    }

    struct Parts: Equatable {
        let quantity: Quantity?
        /// The measuring word ("cups", "tbsp"), if one follows the quantity.
        let unit: String?
        let unitStart: Int?
        let unitEnd: Int?
        /// End of the size-and-unit span ("large", "large cans") used for highlighting.
        let measureEnd: Int?
        /// Offset where the ingredient's name begins, after quantity and unit.
        let nameStart: Int

        static func none(nameStart: Int) -> Parts {
            Parts(quantity: nil, unit: nil, unitStart: nil, unitEnd: nil, measureEnd: nil, nameStart: nameStart)
        }
    }

    // Measuring words mapped to their plural. Abbreviations map to themselves.
    static let unitPlurals: [String: String] = [
        "cup": "cups", "tablespoon": "tablespoons", "teaspoon": "teaspoons", "ounce": "ounces",
        "pound": "pounds", "gram": "grams", "kilogram": "kilograms", "liter": "liters", "litre": "litres",
        "milliliter": "milliliters", "millilitre": "millilitres", "pint": "pints", "quart": "quarts",
        "gallon": "gallons", "stick": "sticks", "clove": "cloves", "can": "cans", "tin": "tins",
        "jar": "jars", "bottle": "bottles", "package": "packages", "packet": "packets", "box": "boxes",
        "bag": "bags", "carton": "cartons", "container": "containers", "envelope": "envelopes",
        "slice": "slices", "piece": "pieces", "pinch": "pinches", "dash": "dashes", "drop": "drops",
        "handful": "handfuls", "bunch": "bunches", "sprig": "sprigs", "stalk": "stalks", "rib": "ribs",
        "head": "heads", "leaf": "leaves", "fillet": "fillets", "knob": "knobs", "cube": "cubes",
        "scoop": "scoops", "sheet": "sheets", "strip": "strips", "wedge": "wedges", "ear": "ears",
        "inch": "inches", "splash": "splashes", "square": "squares", "bar": "bars", "loaf": "loaves",
        "pat": "pats", "sachet": "sachets", "shot": "shots", "glass": "glasses", "kilo": "kilos",
        "lb": "lbs", "dozen": "dozen",
    ]
    static let unitAbbreviations: Set<String> = [
        "tbsp", "tbsps", "tbs", "tbl", "tbls", "tb", "tsp", "tsps", "ts", "oz", "lbs", "g", "gr", "kg", "mg",
        "ml", "cl", "dl", "l", "pt", "pts", "qt", "qts", "gal", "c", "pkg", "pkgs", "lg", "med", "sm", "fl",
    ]
    static let sizeWords: Set<String> = ["large", "medium", "small", "big", "heaping", "heaped", "level", "scant", "generous", "whole"]
    /// Things you count rather than measure; scaled amounts round to halves and wholes.
    static let countUnits: Set<String> = [
        "slice", "slices", "clove", "cloves", "can", "cans", "tin", "tins", "jar", "jars", "bottle", "bottles",
        "package", "packages", "packet", "packets", "pkg", "pkgs", "box", "boxes", "bag", "bags", "carton",
        "cartons", "container", "containers", "envelope", "envelopes", "piece", "pieces", "stick", "sticks",
        "sprig", "sprigs", "stalk", "stalks", "rib", "ribs", "head", "heads", "leaf", "leaves", "fillet",
        "fillets", "knob", "knobs", "cube", "cubes", "scoop", "scoops", "sheet", "sheets", "strip", "strips",
        "wedge", "wedges", "ear", "ears", "bunch", "bunches", "handful", "handfuls", "pinch", "pinches",
        "dash", "dashes", "drop", "drops", "splash", "splashes", "square", "squares", "bar", "bars", "loaf",
        "loaves", "pat", "pats", "sachet", "sachets", "shot", "shots", "glass", "glasses", "dozen",
    ]
    static let spoonUnits: Set<String> = [
        "tablespoon", "tablespoons", "tbsp", "tbsps", "tbs", "tbl", "tbls", "tb", "teaspoon", "teaspoons",
        "tsp", "tsps", "ts", "t",
    ]

    /// How finely a scaled amount is rounded, by what's being measured.
    enum Rounding {
        /// ⅛ steps, for spoons.
        case eighths
        /// ¼ and ⅓ steps, for cups, pounds and most things.
        case quartersAndThirds
        /// ½ steps (and whole numbers from 3 up), for things you count.
        case halves
    }

    static func rounding(forUnit unit: String?) -> Rounding {
        guard let unit else { return .halves }
        let u = unit.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        if unit == "t" || unit == "T" || spoonUnits.contains(u) { return .eighths }
        if countUnits.contains(u) { return .halves }
        return .quartersAndThirds
    }

    static let metricUnits: Set<String> = [
        "g", "gr", "gram", "grams", "kg", "kilogram", "kilograms", "kilo", "kilos", "mg", "ml", "milliliter",
        "milliliters", "millilitre", "millilitres", "cl", "dl", "l", "liter", "liters", "litre", "litres",
    ]
    /// Everyday things counted by the piece, pluralized when scaling ("1 egg" → "2 eggs").
    static let countableNouns: [String: String] = [
        "egg": "eggs", "onion": "onions", "lemon": "lemons", "lime": "limes", "orange": "oranges",
        "apple": "apples", "banana": "bananas", "carrot": "carrots", "potato": "potatoes",
        "tomato": "tomatoes", "shallot": "shallots", "avocado": "avocados", "cucumber": "cucumbers",
        "peach": "peaches", "pear": "pears", "jalapeño": "jalapeños", "jalapeno": "jalapenos",
        "tortilla": "tortillas", "bun": "buns", "roll": "rolls", "bagel": "bagels", "yolk": "yolks",
        "white": "whites", "chicken breast": "chicken breasts", "breast": "breasts", "thigh": "thighs",
        "zucchini": "zucchini", "leek": "leeks", "date": "dates", "fig": "figs", "biscuit": "biscuits",
        "cracker": "crackers", "cookie": "cookies", "chili": "chilies", "chile": "chiles", "pepper": "peppers",
    ]

    private static let quantityPrefixes = ["about", "approximately", "approx.", "approx", "around", "roughly", "~", "scant", "heaping", "generous"]

    static func isUnitWord(_ raw: String) -> Bool {
        if raw == "T" || raw == "t" || raw == "C" { return true }
        let w = raw.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        if w.isEmpty { return false }
        return unitPlurals[w] != nil || unitPlurals.values.contains(w) || unitAbbreviations.contains(w)
    }

    // MARK: Parsing

    static func parse(_ text: String) -> Parts {
        let chars = Array(text)
        var i = 0
        func skipSpaces() { while i < chars.count, chars[i] == " " { i += 1 } }

        // Bullets and dashes people type before list items.
        while i < chars.count, "-•*·▪●○◦–—".contains(chars[i]) { i += 1 }
        skipSpaces()

        // "about 2 cups", "scant 1/2 cup"
        let lowered = String(chars[i...]).lowercased()
        for prefix in quantityPrefixes where lowered.hasPrefix(prefix + " ") {
            i += prefix.count
            skipSpaces()
            break
        }

        guard let first = scanNumber(chars, from: i) else {
            return .none(nameStart: i)
        }

        // Number words only count when a unit or countable noun follows: "a pinch", "two eggs",
        // but not "One of my favorites".
        if first.isWord {
            var j = first.end
            while j < chars.count, chars[j] == " " { j += 1 }
            let next = readWord(chars, from: j)
            let nextLower = next.word.lowercased()
            if !(isUnitWord(next.word) || sizeWords.contains(nextLower) || countableNouns[nextLower] != nil) {
                return .none(nameStart: i)
            }
        }

        var quantity = Quantity(start: i, end: first.end, low: first.value, high: nil, separator: "", isDecimal: first.isDecimal, isWord: first.isWord)
        i = first.end

        // Ranges: "2-3", "2 to 3", "2 or 3"
        var j = i
        while j < chars.count, chars[j] == " " { j += 1 }
        var separatorEnd: Int?
        if j < chars.count, "-–—".contains(chars[j]) {
            separatorEnd = j + 1
        } else if j + 2 < chars.count, String(chars[j..<j + 2]).lowercased() == "to" || String(chars[j..<j + 2]).lowercased() == "or", chars[j + 2] == " " {
            separatorEnd = j + 2
        }
        if let sepEnd = separatorEnd {
            var k = sepEnd
            while k < chars.count, chars[k] == " " { k += 1 }
            if let second = scanNumber(chars, from: k), !second.isWord, second.value > first.value {
                quantity = Quantity(
                    start: quantity.start, end: second.end, low: first.value, high: second.value,
                    separator: String(chars[i..<k]), isDecimal: first.isDecimal || second.isDecimal, isWord: false
                )
                i = second.end
            }
        }

        // Optional size word then optional unit, possibly after a parenthetical: "1 (14 oz) can".
        var unit: String?
        var unitStart: Int?
        var unitEnd: Int?
        var measureEnd: Int?
        var nameStart = i
        var k = i
        while k < chars.count, chars[k] == " " { k += 1 }
        if k < chars.count, chars[k] == "(", let close = chars[k...].firstIndex(of: ")") {
            k = close + 1
            while k < chars.count, chars[k] == " " { k += 1 }
        }
        for _ in 0..<2 {
            let word = readWord(chars, from: k)
            guard !word.word.isEmpty else { break }
            let lower = word.word.lowercased()
            let isSize = sizeWords.contains(lower)
            guard isSize || isUnitWord(word.word) else { break }
            var end = word.end
            if !isSize {
                // "fl oz" / "fl. oz."
                if lower == "fl" {
                    var m = end
                    while m < chars.count, chars[m] == " " { m += 1 }
                    let oz = readWord(chars, from: m)
                    if oz.word.lowercased().hasPrefix("oz") { end = oz.end }
                }
                unit = String(chars[word.start..<end])
                unitStart = word.start
                unitEnd = end
            }
            measureEnd = end
            nameStart = end
            k = end
            while k < chars.count, chars[k] == " " { k += 1 }
            if !isSize { break }
        }
        while nameStart < chars.count, chars[nameStart] == " " { nameStart += 1 }
        return Parts(quantity: quantity, unit: unit, unitStart: unitStart, unitEnd: unitEnd, measureEnd: measureEnd, nameStart: nameStart)
    }

    private struct ScannedNumber { let value: Double; let end: Int; let isDecimal: Bool; let isWord: Bool }

    private static func scanNumber(_ chars: [Character], from start: Int) -> ScannedNumber? {
        guard let number = scanAnyNumber(chars, from: start), number.value.isFinite, number.value <= TextKit.maxNumber else { return nil }
        return number
    }

    private static func scanAnyNumber(_ chars: [Character], from start: Int) -> ScannedNumber? {
        guard start < chars.count else { return nil }
        var i = start
        if let frac = TextKit.unicodeFractions[chars[i]] {
            return ScannedNumber(value: frac, end: i + 1, isDecimal: false, isWord: false)
        }
        if chars[i].isASCII && chars[i].isNumber {
            var whole = 0.0
            while i < chars.count, chars[i].isASCII, chars[i].isNumber {
                whole = whole * 10 + Double(chars[i].wholeNumberValue ?? 0)
                i += 1
            }
            // Decimal "1.5" / "1,5" (but "1,000" is a thousands separator).
            if i + 1 < chars.count, chars[i] == "." || chars[i] == ",", chars[i + 1].isASCII, chars[i + 1].isNumber {
                var j = i + 1
                var digits = ""
                while j < chars.count, chars[j].isASCII, chars[j].isNumber { digits.append(chars[j]); j += 1 }
                if chars[i] == "," && digits.count == 3 {
                    return ScannedNumber(value: whole * 1000 + (Double(digits) ?? 0), end: j, isDecimal: false, isWord: false)
                }
                return ScannedNumber(value: whole + (Double("0." + digits) ?? 0), end: j, isDecimal: true, isWord: false)
            }
            // Fraction "1/2"
            if i + 1 < chars.count, chars[i] == "/", chars[i + 1].isNumber {
                var j = i + 1
                var den = 0.0
                while j < chars.count, chars[j].isASCII, chars[j].isNumber { den = den * 10 + Double(chars[j].wholeNumberValue ?? 0); j += 1 }
                guard den > 0 else { return nil }
                return ScannedNumber(value: whole / den, end: j, isDecimal: false, isWord: false)
            }
            // "1½"
            if i < chars.count, let frac = TextKit.unicodeFractions[chars[i]] {
                return ScannedNumber(value: whole + frac, end: i + 1, isDecimal: false, isWord: false)
            }
            // Mixed numbers "1 1/2", "1-1/2", "1 ½"
            if i + 1 < chars.count, chars[i] == " " || chars[i] == "-" {
                let j = i + 1
                if let frac = TextKit.unicodeFractions[chars[j]] {
                    return ScannedNumber(value: whole + frac, end: j + 1, isDecimal: false, isWord: false)
                }
                var m = j
                var num = 0.0
                while m < chars.count, chars[m].isASCII, chars[m].isNumber { num = num * 10 + Double(chars[m].wholeNumberValue ?? 0); m += 1 }
                if m > j, m + 1 < chars.count, chars[m] == "/", chars[m + 1].isNumber {
                    var n = m + 1
                    var den = 0.0
                    while n < chars.count, chars[n].isASCII, chars[n].isNumber { den = den * 10 + Double(chars[n].wholeNumberValue ?? 0); n += 1 }
                    if den > 0, num < den {
                        return ScannedNumber(value: whole + num / den, end: n, isDecimal: false, isWord: false)
                    }
                }
            }
            return ScannedNumber(value: whole, end: i, isDecimal: false, isWord: false)
        }
        // Number words: "one", "two", "a", "an", "half"
        let word = readWord(chars, from: i)
        let lower = word.word.lowercased()
        if let value = TextKit.numberWords[lower], lower != "dozen" {
            return ScannedNumber(value: value, end: word.end, isDecimal: false, isWord: true)
        }
        return nil
    }

    private static func readWord(_ chars: [Character], from start: Int) -> (word: String, start: Int, end: Int) {
        var i = start
        while i < chars.count, chars[i].isLetter || chars[i] == "." || chars[i] == "'" { i += 1 }
        var end = i
        // Drop trailing periods from the word itself but keep them in the span for "tbsp."
        var word = String(chars[start..<end])
        while word.hasSuffix(".") && word.count > 1 { word.removeLast() }
        if word == "." { word = ""; end = start }
        return (word, start, end)
    }

    /// The first number in text such as "Serves 4-6" or "about 24 cookies".
    static func leadingNumber(in text: String) -> Double? {
        let chars = Array(text)
        var i = 0
        while i < chars.count {
            if chars[i].isNumber || TextKit.unicodeFractions[chars[i]] != nil {
                return scanNumber(chars, from: i)?.value
            }
            if chars[i].isLetter, i == 0 || !chars[i - 1].isLetter {
                let word = readWord(chars, from: i)
                let lower = word.word.lowercased()
                if lower != "a", lower != "an", lower != "half", let value = TextKit.numberWords[lower] { return value }
                i = max(word.end, i + 1)
                continue
            }
            i += 1
        }
        return nil
    }

    // MARK: Scaling

    static func scale(_ text: String, by factor: Double) -> String {
        guard abs(factor - 1) > 0.001 else { return text }
        let parts = parse(text)
        guard let q = parts.quantity else { return text }
        var chars = Array(text)
        let decimal = q.isDecimal || (parts.unit.map { metricUnits.contains($0.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))) } ?? false)
        let newLow = q.low * factor
        let newHigh = q.high.map { $0 * factor }
        let roundedTop = roundedValue(newHigh ?? newLow, rounding: rounding(forUnit: parts.unit), decimal: decimal)
        let plural = roundedTop > 1.0001

        // Fix up grammar from the end backward so earlier offsets stay valid.
        if let unitStart = parts.unitStart, let unitEnd = parts.unitEnd {
            let unitText = String(chars[unitStart..<unitEnd])
            let adjusted = adjustWord(unitText, plural: plural, table: unitPlurals)
            if adjusted != unitText { chars.replaceSubrange(unitStart..<unitEnd, with: Array(adjusted)) }
        } else {
            // "1 egg" → "2 eggs"
            let nameEnd = nextNounEnd(chars, from: parts.nameStart)
            if let nameEnd {
                let noun = String(chars[parts.nameStart..<nameEnd])
                let adjusted = adjustNounGrammar(noun, plural: plural)
                if adjusted != noun { chars.replaceSubrange(parts.nameStart..<nameEnd, with: Array(adjusted)) }
            }
        }

        let rounding = rounding(forUnit: parts.unit)
        var formatted = format(newLow, decimal: decimal, rounding: rounding)
        if let newHigh { formatted += q.separator + format(newHigh, decimal: decimal, rounding: rounding) }
        chars.replaceSubrange(q.start..<q.end, with: Array(formatted))
        return String(chars)
    }

    /// End offset of the ingredient noun when it stands alone ("egg", "onion,"), else nil.
    private static func nextNounEnd(_ chars: [Character], from start: Int) -> Int? {
        let word = readWord(chars, from: start)
        guard !word.word.isEmpty else { return nil }
        let lower = word.word.lowercased()
        let isKnown = countableNouns[lower] != nil || countableNouns.values.contains(lower)
        guard isKnown else { return nil }
        // Only when the noun ends the name or is followed by punctuation: skip "egg yolks".
        var j = word.end
        if j < chars.count, chars[j] == " " {
            j += 1
            if j < chars.count, chars[j].isLetter { return nil }
        }
        return word.end
    }

    private static func adjustNounGrammar(_ noun: String, plural: Bool) -> String {
        adjustWord(noun, plural: plural, table: countableNouns)
    }

    private static func adjustWord(_ word: String, plural: Bool, table: [String: String]) -> String {
        let lower = word.lowercased()
        let capitalized = word.first?.isUppercase ?? false
        var result: String?
        if plural, let p = table[lower], p != lower {
            result = p
        } else if !plural, let singular = table.first(where: { $0.value == lower && $0.key != lower })?.key {
            result = singular
        }
        guard var r = result else { return word }
        if capitalized { r = TextKit.capitalizeFirstLetter(r) }
        return r
    }

    private static let eighths: [(Double, String)] = [
        (0, ""), (1.0 / 8, "⅛"), (1.0 / 4, "¼"), (1.0 / 3, "⅓"), (3.0 / 8, "⅜"), (1.0 / 2, "½"),
        (5.0 / 8, "⅝"), (2.0 / 3, "⅔"), (3.0 / 4, "¾"), (7.0 / 8, "⅞"), (1, ""),
    ]
    private static let quartersAndThirds: [(Double, String)] = [
        (0, ""), (1.0 / 4, "¼"), (1.0 / 3, "⅓"), (1.0 / 2, "½"), (2.0 / 3, "⅔"), (3.0 / 4, "¾"), (1, ""),
    ]
    private static let halves: [(Double, String)] = [(0, ""), (1.0 / 2, "½"), (1, "")]

    private static func glyphs(for rounding: Rounding) -> [(Double, String)] {
        switch rounding {
        case .eighths: eighths
        case .quartersAndThirds: quartersAndThirds
        case .halves: halves
        }
    }

    /// The amount after rounding to kitchen-friendly steps, never rounding down to nothing.
    static func roundedValue(_ value: Double, rounding: Rounding, decimal: Bool) -> Double {
        if decimal { return value }
        if rounding == .halves && value >= 3 { return value.rounded() }
        let table = glyphs(for: rounding)
        let whole = value.rounded(.down)
        let frac = value - whole
        var best = table[0]
        for candidate in table where abs(candidate.0 - frac) < abs(best.0 - frac) { best = candidate }
        let result = whole + best.0
        return result > 0 ? result : table[1].0
    }

    /// Formats a quantity the way a cook would write it: "1½", "⅓", "2", or "2.5" for metric.
    static func format(_ value: Double, decimal: Bool, rounding: Rounding = .eighths) -> String {
        guard value > 0 else { return "0" }
        if decimal {
            if value >= 100 { return String(Int((value / 5).rounded() * 5)) }
            if value >= 10 { return String(Int(value.rounded())) }
            let r = (value * 10).rounded() / 10
            if r == r.rounded() { return String(Int(r)) }
            return String(format: "%.1f", r)
        }
        if value < 1.0 / 16 && rounding == .eighths { return String(format: "%.2f", value) }
        var steps = rounding
        // Big amounts don't need eighths: "10⅛ cups" reads as noise.
        if value >= 10 && steps == .eighths { steps = .quartersAndThirds }
        let rounded = roundedValue(value, rounding: steps, decimal: false)
        let whole = rounded.rounded(.down)
        let frac = rounded - whole
        let glyph = glyphs(for: steps).first { abs($0.0 - frac) < 0.001 }?.1 ?? ""
        if whole == 0 { return glyph.isEmpty ? "0" : glyph }
        return "\(Int(whole))\(glyph)"
    }

    /// Offsets covering quantity and unit, for bold highlighting.
    static func highlightSpan(_ text: String) -> (start: Int, end: Int)? {
        let parts = parse(text)
        guard let q = parts.quantity else { return nil }
        return (q.start, parts.measureEnd ?? q.end)
    }

    // MARK: Names, for matching ingredients to steps

    private static let descriptorWords: Set<String> = [
        "fresh", "freshly", "chopped", "minced", "diced", "sliced", "ground", "whole", "dried", "finely",
        "roughly", "coarsely", "thinly", "thickly", "packed", "softened", "melted", "room", "temperature",
        "divided", "optional", "taste", "plus", "extra", "virgin", "unsalted", "salted", "boneless",
        "skinless", "ripe", "cold", "warm", "hot", "beaten", "grated", "shredded", "peeled", "seeded",
        "cored", "trimmed", "rinsed", "drained", "large", "medium", "small", "cup", "cups", "about",
        "into", "cut", "pieces", "piece", "and", "or", "of", "for", "the", "a", "an", "to", "at", "in",
        "with", "more", "needed", "as", "such", "your", "favorite", "good", "quality", "store", "bought",
        "homemade", "lightly", "well", "firmly", "loosely", "crushed", "toasted", "cooked", "uncooked",
        "raw", "frozen", "thawed", "canned", "halved", "quartered", "cubed", "julienned", "zested",
        "juiced", "squeezed", "pitted", "stemmed", "torn", "picked", "leaves", "heaping", "level",
    ]

    /// Key words that identify the ingredient in step text: "2 cloves garlic, minced" → ["garlic"].
    static func keyWords(_ text: String) -> [String] {
        let parts = parse(text)
        let chars = Array(text)
        var name = parts.nameStart < chars.count ? String(chars[parts.nameStart...]) : ""
        if let paren = name.firstIndex(of: "(") {
            let close = name[paren...].firstIndex(of: ")")
            name.removeSubrange(paren...(close ?? name.index(before: name.endIndex)))
        }
        if let comma = name.firstIndex(of: ",") { name = String(name[..<comma]) }
        let words = TextKit.words(name).filter { word in
            word.count >= 3 && !descriptorWords.contains(word) && !isUnitWord(word) && !word.allSatisfy(\.isNumber)
        }
        return words.map(TextKit.singular)
    }
}
