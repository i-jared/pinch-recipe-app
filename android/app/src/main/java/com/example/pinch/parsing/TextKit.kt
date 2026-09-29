package com.example.pinch.parsing

import java.text.Normalizer

/** Small text helpers shared by the parser, search and cook mode. Mirrors iOS `TextKit`. */
object TextKit {
    val unicodeFractions: Map<Char, Double> = mapOf(
        '½' to 1.0 / 2, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '¼' to 1.0 / 4, '¾' to 3.0 / 4,
        '⅕' to 1.0 / 5, '⅖' to 2.0 / 5, '⅗' to 3.0 / 5, '⅘' to 4.0 / 5, '⅙' to 1.0 / 6,
        '⅚' to 5.0 / 6, '⅛' to 1.0 / 8, '⅜' to 3.0 / 8, '⅝' to 5.0 / 8, '⅞' to 7.0 / 8,
        '⅐' to 1.0 / 7, '⅑' to 1.0 / 9, '⅒' to 1.0 / 10,
    )

    val numberWords: Map<String, Double> = mapOf(
        "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0,
        "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0, "twelve" to 12.0,
        "fifteen" to 15.0, "twenty" to 20.0, "thirty" to 30.0, "forty" to 40.0, "fifty" to 50.0,
        "sixty" to 60.0, "ninety" to 90.0, "a" to 1.0, "an" to 1.0, "half" to 0.5, "dozen" to 12.0,
    )

    private val combiningMarks = Regex("\\p{Mn}+")

    /** Lowercased with accents removed: "Crème Brûlée" → "creme brulee". */
    fun fold(s: String): String =
        combiningMarks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()

    /** Folded alphanumeric words. Apostrophes are dropped so "grandma's" → "grandmas". */
    fun words(s: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        for (ch in fold(s)) {
            when {
                ch.isLetterOrDigit() -> current.append(ch)
                ch == '\'' || ch == '’' -> Unit
                current.isNotEmpty() -> {
                    result.add(current.toString())
                    current.setLength(0)
                }
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    private val irregularSingulars = mapOf(
        "leaves" to "leaf", "halves" to "half", "loaves" to "loaf", "knives" to "knife",
        "potatoes" to "potato", "tomatoes" to "tomato", "mangoes" to "mango", "cookies" to "cookie",
        "anchovies" to "anchovy", "radishes" to "radish", "dishes" to "dish",
    )

    private val keepAsIs = setOf(
        "molasses", "hummus", "couscous", "asparagus", "swiss", "citrus", "grits", "oats",
        "brussels", "chips", "series", "species", "bass", "glass", "grass", "cress", "less",
    )

    /** A rough English singular, good enough for matching "tomatoes" with "tomato". */
    fun singular(word: String): String {
        val w = word.lowercase()
        irregularSingulars[w]?.let { return it }
        if (w in keepAsIs || w.length <= 3) return w
        if (w.endsWith("ies") && w.length > 4) return w.dropLast(3) + "y"
        if (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses") || w.endsWith("xes") || w.endsWith("zes")) {
            return w.dropLast(2)
        }
        if (w.endsWith("oes")) return w.dropLast(2)
        if (w.endsWith("ss") || w.endsWith("us") || w.endsWith("is")) return w
        if (w.endsWith("s")) return w.dropLast(1)
        return w
    }

    private val smallWords = setOf("a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "with", "from")

    /** Turns "CHOCOLATE CHIP COOKIES" into "Chocolate Chip Cookies". Mixed-case text is left alone. */
    fun titleCasedIfShouting(s: String): String {
        val letters = s.filter { it.isLetter() }
        if (letters.length < 3 || !letters.all { it.isUpperCase() }) return s
        return s.lowercase().split(" ").mapIndexed { index, word ->
            if (index > 0 && word in smallWords) word else capitalizeFirstLetter(word)
        }.joinToString(" ")
    }

    fun capitalizeFirstLetter(word: String): String {
        val i = word.indexOfFirst { it.isLetter() }
        if (i < 0) return word
        return word.substring(0, i) + word[i].uppercaseChar() + word.substring(i + 1)
    }

    /** Parses "2", "1.5", "1,5", "1/2", "1 1/2", "1½", "½", "one" into a number. */
    /** Larger numbers are misread text, not amounts or times. */
    const val MAX_NUMBER = 1_000_000.0

    fun numberValue(raw: String): Double? = anyNumberValue(raw)?.takeIf { it.isFinite() && it <= MAX_NUMBER }

    private fun anyNumberValue(raw: String): Double? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        numberWords[s]?.let { return it }
        if (s == "half a" || s == "half an" || s == "a half") return 0.5
        if (s.length == 1) unicodeFractions[s[0]]?.let { return it }
        unicodeFractions[s.last()]?.let { frac ->
            val head = s.dropLast(1).trim()
            if (head.isEmpty()) return frac
            return head.toDoubleOrNull()?.plus(frac)
        }
        val parts = s.split(" ").filter { it.isNotEmpty() }
        if (parts.size == 2) {
            val whole = parts[0].toDoubleOrNull()
            val frac = simpleFraction(parts[1])
            if (whole != null && frac != null) return whole + frac
        }
        simpleFraction(s)?.let { return it }
        return s.replace(',', '.').toDoubleOrNull()
    }

    private fun simpleFraction(s: String): Double? {
        val pieces = s.split("/")
        if (pieces.size != 2) return null
        val n = pieces[0].toDoubleOrNull() ?: return null
        val d = pieces[1].toDoubleOrNull() ?: return null
        return if (d == 0.0) null else n / d
    }

    private val replacements = listOf(
        "⁄" to "/", "∕" to "/", "ﬁ" to "fi", "ﬂ" to "fl", "ﬀ" to "ff",
        " " to " ", "\t" to " ", "’" to "'", "‘" to "'", "“" to "\"", "”" to "\"",
    )
    private val spaces = Regex(" {2,}")

    /** Replaces typographic variants that OCR and web pages produce. */
    fun cleanLine(s: String): String {
        var t = s
        for ((from, to) in replacements) t = t.replace(from, to)
        t = spaces.replace(t, " ")
        return t.trim()
    }

    /** Case-insensitive regex. Patterns are shared verbatim with iOS. */
    fun regex(pattern: String): Regex = Regex(pattern, RegexOption.IGNORE_CASE)
}
