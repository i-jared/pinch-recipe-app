package com.example.pinch.parsing

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Understands the quantity and unit at the front of an ingredient line so it can be
 * highlighted and scaled: "1 1/2 cups flour" × 2 → "3 cups flour". Mirrors iOS `IngredientParser`.
 * Offsets are UTF-16 indices into the line.
 */
object IngredientParser {
    data class Quantity(
        val start: Int,
        val end: Int,
        val low: Double,
        val high: Double?,
        val separator: String,
        val isDecimal: Boolean,
        val isWord: Boolean,
    )

    data class Parts(
        val quantity: Quantity?,
        /** The measuring word ("cups", "tbsp"), if one follows the quantity. */
        val unit: String?,
        val unitStart: Int?,
        val unitEnd: Int?,
        /** End of the size-and-unit span ("large", "large cans") used for highlighting. */
        val measureEnd: Int?,
        /** Offset where the ingredient's name begins, after quantity and unit. */
        val nameStart: Int,
    ) {
        companion object {
            fun none(nameStart: Int) = Parts(null, null, null, null, null, nameStart)
        }
    }

    val unitPlurals: Map<String, String> = mapOf(
        "cup" to "cups", "tablespoon" to "tablespoons", "teaspoon" to "teaspoons", "ounce" to "ounces",
        "pound" to "pounds", "gram" to "grams", "kilogram" to "kilograms", "liter" to "liters", "litre" to "litres",
        "milliliter" to "milliliters", "millilitre" to "millilitres", "pint" to "pints", "quart" to "quarts",
        "gallon" to "gallons", "stick" to "sticks", "clove" to "cloves", "can" to "cans", "tin" to "tins",
        "jar" to "jars", "bottle" to "bottles", "package" to "packages", "packet" to "packets", "box" to "boxes",
        "bag" to "bags", "carton" to "cartons", "container" to "containers", "envelope" to "envelopes",
        "slice" to "slices", "piece" to "pieces", "pinch" to "pinches", "dash" to "dashes", "drop" to "drops",
        "handful" to "handfuls", "bunch" to "bunches", "sprig" to "sprigs", "stalk" to "stalks", "rib" to "ribs",
        "head" to "heads", "leaf" to "leaves", "fillet" to "fillets", "knob" to "knobs", "cube" to "cubes",
        "scoop" to "scoops", "sheet" to "sheets", "strip" to "strips", "wedge" to "wedges", "ear" to "ears",
        "inch" to "inches", "splash" to "splashes", "square" to "squares", "bar" to "bars", "loaf" to "loaves",
        "pat" to "pats", "sachet" to "sachets", "shot" to "shots", "glass" to "glasses", "kilo" to "kilos",
        "lb" to "lbs", "dozen" to "dozen",
    )
    private val unitPluralValues = unitPlurals.values.toSet()

    val unitAbbreviations: Set<String> = setOf(
        "tbsp", "tbsps", "tbs", "tbl", "tbls", "tb", "tsp", "tsps", "ts", "oz", "lbs", "g", "gr", "kg", "mg",
        "ml", "cl", "dl", "l", "pt", "pts", "qt", "qts", "gal", "c", "pkg", "pkgs", "lg", "med", "sm", "fl",
    )
    val sizeWords: Set<String> = setOf("large", "medium", "small", "big", "heaping", "heaped", "level", "scant", "generous", "whole")

    /** Things you count rather than measure; scaled amounts round to halves and wholes. */
    val countUnits: Set<String> = setOf(
        "slice", "slices", "clove", "cloves", "can", "cans", "tin", "tins", "jar", "jars", "bottle", "bottles",
        "package", "packages", "packet", "packets", "pkg", "pkgs", "box", "boxes", "bag", "bags", "carton",
        "cartons", "container", "containers", "envelope", "envelopes", "piece", "pieces", "stick", "sticks",
        "sprig", "sprigs", "stalk", "stalks", "rib", "ribs", "head", "heads", "leaf", "leaves", "fillet",
        "fillets", "knob", "knobs", "cube", "cubes", "scoop", "scoops", "sheet", "sheets", "strip", "strips",
        "wedge", "wedges", "ear", "ears", "bunch", "bunches", "handful", "handfuls", "pinch", "pinches",
        "dash", "dashes", "drop", "drops", "splash", "splashes", "square", "squares", "bar", "bars", "loaf",
        "loaves", "pat", "pats", "sachet", "sachets", "shot", "shots", "glass", "glasses", "dozen",
    )
    val spoonUnits: Set<String> = setOf(
        "tablespoon", "tablespoons", "tbsp", "tbsps", "tbs", "tbl", "tbls", "tb", "teaspoon", "teaspoons",
        "tsp", "tsps", "ts", "t",
    )
    val metricUnits: Set<String> = setOf(
        "g", "gr", "gram", "grams", "kg", "kilogram", "kilograms", "kilo", "kilos", "mg", "ml", "milliliter",
        "milliliters", "millilitre", "millilitres", "cl", "dl", "l", "liter", "liters", "litre", "litres",
    )

    /** Everyday things counted by the piece, pluralized when scaling ("1 egg" → "2 eggs"). */
    val countableNouns: Map<String, String> = mapOf(
        "egg" to "eggs", "onion" to "onions", "lemon" to "lemons", "lime" to "limes", "orange" to "oranges",
        "apple" to "apples", "banana" to "bananas", "carrot" to "carrots", "potato" to "potatoes",
        "tomato" to "tomatoes", "shallot" to "shallots", "avocado" to "avocados", "cucumber" to "cucumbers",
        "peach" to "peaches", "pear" to "pears", "jalapeño" to "jalapeños", "jalapeno" to "jalapenos",
        "tortilla" to "tortillas", "bun" to "buns", "roll" to "rolls", "bagel" to "bagels", "yolk" to "yolks",
        "white" to "whites", "chicken breast" to "chicken breasts", "breast" to "breasts", "thigh" to "thighs",
        "zucchini" to "zucchini", "leek" to "leeks", "date" to "dates", "fig" to "figs", "biscuit" to "biscuits",
        "cracker" to "crackers", "cookie" to "cookies", "chili" to "chilies", "chile" to "chiles", "pepper" to "peppers",
    )
    private val countableValues = countableNouns.values.toSet()

    private val quantityPrefixes = listOf("about", "approximately", "approx.", "approx", "around", "roughly", "~", "scant", "heaping", "generous")

    /** How finely a scaled amount is rounded, by what's being measured. */
    enum class Rounding { EIGHTHS, QUARTERS_AND_THIRDS, HALVES }

    fun rounding(unit: String?): Rounding {
        if (unit == null) return Rounding.HALVES
        val u = unit.lowercase().trim('.')
        if (unit == "t" || unit == "T" || u in spoonUnits) return Rounding.EIGHTHS
        if (u in countUnits) return Rounding.HALVES
        return Rounding.QUARTERS_AND_THIRDS
    }

    fun isUnitWord(raw: String): Boolean {
        if (raw == "T" || raw == "t" || raw == "C") return true
        val w = raw.lowercase().trim('.')
        if (w.isEmpty()) return false
        return w in unitPlurals || w in unitPluralValues || w in unitAbbreviations
    }

    // Parsing

    fun parse(text: String): Parts {
        val chars = text
        var i = 0
        fun skipSpaces() { while (i < chars.length && chars[i] == ' ') i++ }

        while (i < chars.length && chars[i] in "-•*·▪●○◦–—") i++
        skipSpaces()

        val lowered = chars.substring(i).lowercase()
        for (prefix in quantityPrefixes) {
            if (lowered.startsWith("$prefix ")) {
                i += prefix.length
                skipSpaces()
                break
            }
        }

        val first = scanNumber(chars, i) ?: return Parts.none(i)

        // Number words only count when a unit or countable noun follows: "a pinch", "two eggs",
        // but not "One of my favorites".
        if (first.isWord) {
            var j = first.end
            while (j < chars.length && chars[j] == ' ') j++
            val next = readWord(chars, j)
            val nextLower = next.word.lowercase()
            if (!(isUnitWord(next.word) || nextLower in sizeWords || nextLower in countableNouns)) return Parts.none(i)
        }

        var quantity = Quantity(i, first.end, first.value, null, "", first.isDecimal, first.isWord)
        i = first.end

        // Ranges: "2-3", "2 to 3", "2 or 3"
        var j = i
        while (j < chars.length && chars[j] == ' ') j++
        var separatorEnd: Int? = null
        if (j < chars.length && chars[j] in "-–—") {
            separatorEnd = j + 1
        } else if (j + 2 < chars.length && chars.substring(j, j + 2).lowercase().let { it == "to" || it == "or" } && chars[j + 2] == ' ') {
            separatorEnd = j + 2
        }
        if (separatorEnd != null) {
            var k = separatorEnd
            while (k < chars.length && chars[k] == ' ') k++
            val second = scanNumber(chars, k)
            if (second != null && !second.isWord && second.value > first.value) {
                quantity = Quantity(
                    quantity.start, second.end, first.value, second.value,
                    chars.substring(i, k), first.isDecimal || second.isDecimal, false,
                )
                i = second.end
            }
        }

        // Optional size word then optional unit, possibly after a parenthetical: "1 (14 oz) can".
        var unit: String? = null
        var unitStart: Int? = null
        var unitEnd: Int? = null
        var measureEnd: Int? = null
        var nameStart = i
        var k = i
        while (k < chars.length && chars[k] == ' ') k++
        if (k < chars.length && chars[k] == '(') {
            val close = chars.indexOf(')', k)
            if (close >= 0) {
                k = close + 1
                while (k < chars.length && chars[k] == ' ') k++
            }
        }
        repeat(2) {
            val word = readWord(chars, k)
            if (word.word.isEmpty()) return@repeat
            val lower = word.word.lowercase()
            val isSize = lower in sizeWords
            if (!isSize && !isUnitWord(word.word)) return@repeat
            if (unit != null) return@repeat
            var end = word.end
            if (!isSize) {
                if (lower == "fl") {
                    var m = end
                    while (m < chars.length && chars[m] == ' ') m++
                    val oz = readWord(chars, m)
                    if (oz.word.lowercase().startsWith("oz")) end = oz.end
                }
                unit = chars.substring(word.start, end)
                unitStart = word.start
                unitEnd = end
            }
            measureEnd = end
            nameStart = end
            k = end
            while (k < chars.length && chars[k] == ' ') k++
        }
        while (nameStart < chars.length && chars[nameStart] == ' ') nameStart++
        return Parts(quantity, unit, unitStart, unitEnd, measureEnd, nameStart)
    }

    private data class ScannedNumber(val value: Double, val end: Int, val isDecimal: Boolean, val isWord: Boolean)

    private fun isDigit(c: Char) = c in '0'..'9'

    private fun scanNumber(chars: String, start: Int): ScannedNumber? =
        scanAnyNumber(chars, start)?.takeIf { it.value.isFinite() && it.value <= TextKit.MAX_NUMBER }

    private fun scanAnyNumber(chars: String, start: Int): ScannedNumber? {
        if (start >= chars.length) return null
        var i = start
        TextKit.unicodeFractions[chars[i]]?.let { return ScannedNumber(it, i + 1, false, false) }
        if (isDigit(chars[i])) {
            var whole = 0.0
            while (i < chars.length && isDigit(chars[i])) {
                whole = whole * 10 + (chars[i] - '0')
                i++
            }
            // Decimal "1.5" / "1,5" (but "1,000" is a thousands separator).
            if (i + 1 < chars.length && (chars[i] == '.' || chars[i] == ',') && isDigit(chars[i + 1])) {
                var jj = i + 1
                val digits = StringBuilder()
                while (jj < chars.length && isDigit(chars[jj])) { digits.append(chars[jj]); jj++ }
                if (chars[i] == ',' && digits.length == 3) {
                    return ScannedNumber(whole * 1000 + digits.toString().toDouble(), jj, false, false)
                }
                return ScannedNumber(whole + "0.$digits".toDouble(), jj, true, false)
            }
            // Fraction "1/2"
            if (i + 1 < chars.length && chars[i] == '/' && isDigit(chars[i + 1])) {
                var jj = i + 1
                var den = 0.0
                while (jj < chars.length && isDigit(chars[jj])) { den = den * 10 + (chars[jj] - '0'); jj++ }
                if (den <= 0) return null
                return ScannedNumber(whole / den, jj, false, false)
            }
            // "1½"
            if (i < chars.length) TextKit.unicodeFractions[chars[i]]?.let { return ScannedNumber(whole + it, i + 1, false, false) }
            // Mixed numbers "1 1/2", "1-1/2", "1 ½"
            if (i + 1 < chars.length && (chars[i] == ' ' || chars[i] == '-')) {
                val jj = i + 1
                TextKit.unicodeFractions[chars[jj]]?.let { return ScannedNumber(whole + it, jj + 1, false, false) }
                var m = jj
                var num = 0.0
                while (m < chars.length && isDigit(chars[m])) { num = num * 10 + (chars[m] - '0'); m++ }
                if (m > jj && m + 1 < chars.length && chars[m] == '/' && isDigit(chars[m + 1])) {
                    var n = m + 1
                    var den = 0.0
                    while (n < chars.length && isDigit(chars[n])) { den = den * 10 + (chars[n] - '0'); n++ }
                    if (den > 0 && num < den) return ScannedNumber(whole + num / den, n, false, false)
                }
            }
            return ScannedNumber(whole, i, false, false)
        }
        val word = readWord(chars, i)
        val lower = word.word.lowercase()
        val value = TextKit.numberWords[lower]
        if (value != null && lower != "dozen") return ScannedNumber(value, word.end, false, true)
        return null
    }

    private data class Word(val word: String, val start: Int, val end: Int)

    private fun readWord(chars: String, start: Int): Word {
        var i = start
        while (i < chars.length && (chars[i].isLetter() || chars[i] == '.' || chars[i] == '\'')) i++
        var end = i
        var word = chars.substring(start, end)
        while (word.endsWith(".") && word.length > 1) word = word.dropLast(1)
        if (word == ".") { word = ""; end = start }
        return Word(word, start, end)
    }

    /** The first number in text such as "Serves 4-6" or "about 24 cookies". */
    fun leadingNumber(text: String): Double? {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isDigit(c) || c in TextKit.unicodeFractions) return scanNumber(text, i)?.value
            if (c.isLetter() && (i == 0 || !text[i - 1].isLetter())) {
                val word = readWord(text, i)
                val lower = word.word.lowercase()
                val value = TextKit.numberWords[lower]
                if (lower != "a" && lower != "an" && lower != "half" && value != null) return value
                i = max(word.end, i + 1)
                continue
            }
            i++
        }
        return null
    }

    // Scaling

    fun scale(text: String, factor: Double): String {
        if (abs(factor - 1) <= 0.001) return text
        val parts = parse(text)
        val q = parts.quantity ?: return text
        val chars = StringBuilder(text)
        val decimal = q.isDecimal || (parts.unit?.let { it.lowercase().trim('.') in metricUnits } ?: false)
        val newLow = q.low * factor
        val newHigh = q.high?.let { it * factor }
        val rounding = rounding(parts.unit)
        val roundedTop = roundedValue(newHigh ?: newLow, rounding, decimal)
        val plural = roundedTop > 1.0001

        // Fix up grammar from the end backward so earlier offsets stay valid.
        val unitStart = parts.unitStart
        val unitEnd = parts.unitEnd
        if (unitStart != null && unitEnd != null) {
            val unitText = chars.substring(unitStart, unitEnd)
            val adjusted = adjustWord(unitText, plural, unitPlurals)
            if (adjusted != unitText) chars.replace(unitStart, unitEnd, adjusted)
        } else {
            val nameEnd = nextNounEnd(text, parts.nameStart)
            if (nameEnd != null) {
                val noun = chars.substring(parts.nameStart, nameEnd)
                val adjusted = adjustWord(noun, plural, countableNouns)
                if (adjusted != noun) chars.replace(parts.nameStart, nameEnd, adjusted)
            }
        }

        var formatted = format(newLow, decimal, rounding)
        if (newHigh != null) formatted += q.separator + format(newHigh, decimal, rounding)
        chars.replace(q.start, q.end, formatted)
        return chars.toString()
    }

    /** End offset of the ingredient noun when it stands alone ("egg", "onion,"), else null. */
    private fun nextNounEnd(chars: String, start: Int): Int? {
        val word = readWord(chars, start)
        if (word.word.isEmpty()) return null
        val lower = word.word.lowercase()
        if (lower !in countableNouns && lower !in countableValues) return null
        var j = word.end
        if (j < chars.length && chars[j] == ' ') {
            j++
            if (j < chars.length && chars[j].isLetter()) return null
        }
        return word.end
    }

    private fun adjustWord(word: String, plural: Boolean, table: Map<String, String>): String {
        val lower = word.lowercase()
        val capitalized = word.firstOrNull()?.isUpperCase() ?: false
        var result: String? = null
        if (plural) {
            val p = table[lower]
            if (p != null && p != lower) result = p
        } else {
            result = table.entries.firstOrNull { it.value == lower && it.key != lower }?.key
        }
        var r = result ?: return word
        if (capitalized) r = TextKit.capitalizeFirstLetter(r)
        return r
    }

    private val eighths = listOf(
        0.0 to "", 1.0 / 8 to "⅛", 1.0 / 4 to "¼", 1.0 / 3 to "⅓", 3.0 / 8 to "⅜", 1.0 / 2 to "½",
        5.0 / 8 to "⅝", 2.0 / 3 to "⅔", 3.0 / 4 to "¾", 7.0 / 8 to "⅞", 1.0 to "",
    )
    private val quartersAndThirds = listOf(
        0.0 to "", 1.0 / 4 to "¼", 1.0 / 3 to "⅓", 1.0 / 2 to "½", 2.0 / 3 to "⅔", 3.0 / 4 to "¾", 1.0 to "",
    )
    private val halves = listOf(0.0 to "", 1.0 / 2 to "½", 1.0 to "")

    private fun glyphs(rounding: Rounding) = when (rounding) {
        Rounding.EIGHTHS -> eighths
        Rounding.QUARTERS_AND_THIRDS -> quartersAndThirds
        Rounding.HALVES -> halves
    }

    /** The amount after rounding to kitchen-friendly steps, never rounding down to nothing. */
    fun roundedValue(value: Double, rounding: Rounding, decimal: Boolean): Double {
        if (decimal) return value
        if (rounding == Rounding.HALVES && value >= 3) return Math.round(value).toDouble()
        val table = glyphs(rounding)
        val whole = floor(value)
        val frac = value - whole
        var best = table[0]
        for (candidate in table) if (abs(candidate.first - frac) < abs(best.first - frac)) best = candidate
        val result = whole + best.first
        return if (result > 0) result else table[1].first
    }

    /** Formats a quantity the way a cook would write it: "1½", "⅓", "2", or "2.5" for metric. */
    fun format(value: Double, decimal: Boolean, rounding: Rounding = Rounding.EIGHTHS): String {
        if (value <= 0) return "0"
        if (decimal) {
            if (value >= 100) return ((value / 5).roundToInt() * 5).toString()
            if (value >= 10) return value.roundToInt().toString()
            val r = Math.round(value * 10) / 10.0
            if (r == Math.round(r).toDouble()) return Math.round(r).toString()
            return String.format(Locale.US, "%.1f", r)
        }
        if (value < 1.0 / 16 && rounding == Rounding.EIGHTHS) return String.format(Locale.US, "%.2f", value)
        var steps = rounding
        if (value >= 10 && steps == Rounding.EIGHTHS) steps = Rounding.QUARTERS_AND_THIRDS
        val rounded = roundedValue(value, steps, false)
        val whole = floor(rounded)
        val frac = rounded - whole
        val glyph = glyphs(steps).firstOrNull { abs(it.first - frac) < 0.001 }?.second ?: ""
        if (whole == 0.0) return glyph.ifEmpty { "0" }
        return "${whole.toLong()}$glyph"
    }

    /** Offsets covering quantity and unit, for bold highlighting. */
    fun highlightSpan(text: String): IntRange? {
        val parts = parse(text)
        val q = parts.quantity ?: return null
        return q.start until (parts.measureEnd ?: q.end)
    }

    // Names, for matching ingredients to steps

    private val descriptorWords = setOf(
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
    )

    /** Key words that identify the ingredient in step text: "2 cloves garlic, minced" → ["garlic"]. */
    fun keyWords(text: String): List<String> {
        val parts = parse(text)
        var name = if (parts.nameStart < text.length) text.substring(parts.nameStart) else ""
        val paren = name.indexOf('(')
        if (paren >= 0) {
            val close = name.indexOf(')', paren)
            name = name.removeRange(paren, if (close >= 0) close + 1 else name.length)
        }
        val comma = name.indexOf(',')
        if (comma >= 0) name = name.substring(0, comma)
        return TextKit.words(name)
            .filter { it.length >= 3 && it !in descriptorWords && !isUnitWord(it) && !it.all { c -> c.isDigit() } }
            .map { TextKit.singular(it) }
    }
}
