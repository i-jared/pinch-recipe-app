package com.example.pinch.parsing

import com.example.pinch.model.RecipeItem

/** A normalized rectangle with a top-left origin, like CGRect on iOS. */
data class Box(val x: Double, val y: Double, val width: Double, val height: Double) {
    val minX get() = x
    val minY get() = y
    val maxX get() = x + width
    val maxY get() = y + height
    val midY get() = y + height / 2

    fun union(other: Box): Box {
        val minX = minOf(minX, other.minX)
        val minY = minOf(minY, other.minY)
        val maxX = maxOf(maxX, other.maxX)
        val maxY = maxOf(maxY, other.maxY)
        return Box(minX, minY, maxX - minX, maxY - minY)
    }
}

/** A line of recognized or pasted text, with layout hints from OCR. */
data class TextLine(
    val text: String,
    /** Normalized bounding box with a top-left origin. Null for pasted text. */
    val box: Box? = null,
    val page: Int = 0,
    /** True when this line begins a new paragraph. */
    val paragraphStart: Boolean = true,
    /** True when the previous line ran to the right edge of its column. */
    val previousLineFull: Boolean = false,
)

data class ParsedRecipe(
    var title: String = "",
    var summary: String = "",
    var source: String = "",
    var servings: String = "",
    var prepTime: String = "",
    var cookTime: String = "",
    var totalTime: String = "",
    var ingredients: MutableList<RecipeItem> = mutableListOf(),
    var steps: MutableList<RecipeItem> = mutableListOf(),
    var notes: String = "",
    var rawText: String = "",
    /** Where parsing stopped, so a later page can pick up in the same section. */
    var endSection: RecipeParser.Section = RecipeParser.Section.PREAMBLE,
)

/**
 * Turns recognized text into a structured recipe using layout and wording cues.
 * Mirrors the iOS `RecipeParser` rule for rule, so both apps read recipes the same way.
 */
object RecipeParser {
    enum class Section { PREAMBLE, INGREDIENTS, STEPS, NOTES }

    fun parse(text: String): ParsedRecipe {
        val result = parse(linesFromPlainText(text))
        result.rawText = text.trim()
        return result
    }

    /** Plain text has no geometry, so each line starts a paragraph. */
    fun linesFromPlainText(text: String): List<TextLine> =
        text.split("\r\n", "\n", "\r").map { TextKit.cleanLine(it) }.filter { it.isNotEmpty() }.map { TextLine(it) }

    /**
     * @param continuing the section a previous page ended in. When set, no title is
     *   detected and unlabeled lines continue that section.
     */
    fun parse(input: List<TextLine>, continuing: Section? = null): ParsedRecipe {
        val result = ParsedRecipe()
        result.rawText = rawText(input)

        var lines = input.map { it.copy(text = TextKit.cleanLine(it.text)) }.filter { it.text.isNotEmpty() && !isNoise(it) }

        val shouting = mostlyUppercase(lines)
        val consumed = HashSet<Int>()
        var titleIndex = -1
        if (continuing == null) {
            val found = findTitle(lines, shouting)
            result.title = found.first
            consumed.addAll(found.second)
            titleIndex = found.second.minOrNull() ?: -1
        }

        // Cards written entirely in capitals read better in sentence case.
        if (shouting) lines = lines.map { it.copy(text = sentenceCased(it.text)) }

        val hasStepsHeader = lines.any { header(it.text)?.section == Section.STEPS }
        var section = continuing ?: Section.PREAMBLE
        var stepsNumbered = false
        var pendingNewStep = false
        val summary = ArrayList<String>()
        val notes = ArrayList<String>()
        val ingredients = result.ingredients
        val steps = result.steps

        for ((index, line) in lines.withIndex()) {
            if (index in consumed) continue
            var text = line.text

            // Running headers above the title and printed card labels are dropped.
            if (titleIndex >= 0 && index < titleIndex && text.length <= 30 && section == Section.PREAMBLE) continue
            if (TextKit.fold(text).trim(' ', ':') in notTitles) continue

            val meta = extractMetadata(text, allowBareCookTime = section == Section.PREAMBLE)
            if (meta.matched) {
                if (result.servings.isEmpty()) meta.servings?.let { result.servings = it }
                if (result.prepTime.isEmpty()) meta.prep?.let { result.prepTime = it }
                if (result.cookTime.isEmpty()) meta.cook?.let { result.cookTime = it }
                if (result.totalTime.isEmpty()) meta.total?.let { result.totalTime = it }
                if (meta.remainder.isEmpty()) continue
                text = meta.remainder
            }

            val found = header(text)
            if (found != null) {
                section = found.section
                pendingNewStep = false
                val rest = found.remainder
                if (rest.isNullOrEmpty()) continue
                text = rest
            }

            val source = sourceText(text, allowBareBy = section == Section.PREAMBLE && ingredients.isEmpty())
            if (source != null) {
                if (result.source.isEmpty()) result.source = source
                continue
            }

            when (section) {
                Section.PREAMBLE -> {
                    val numbered = numberedStep(text)
                    if (isSubheader(text, shouting) && nextLineStartsWithQuantity(index, lines, consumed)) {
                        // "For the cake:" right before a list starts the ingredients.
                        section = Section.INGREDIENTS
                        ingredients.add(RecipeItem(text = subheaderText(text), isHeader = true))
                    } else if (numbered != null && !startsWithQuantity(numbered)) {
                        section = Section.STEPS
                        stepsNumbered = true
                        steps.add(RecipeItem(text = numbered))
                    } else if (bulletStripped(text) != null || startsWithQuantity(text)) {
                        section = Section.INGREDIENTS
                        ingredients.add(RecipeItem(text = cleanIngredient(text)))
                    } else if (summary.isNotEmpty() && continuesParagraph(line, text, summary.last())) {
                        summary[summary.size - 1] = join(summary.last(), text)
                    } else {
                        summary.add(text)
                    }
                }

                Section.INGREDIENTS -> {
                    val numbered = numberedStep(text)
                    val last = ingredients.lastOrNull()
                    if (isSubheader(text, shouting)) {
                        ingredients.add(RecipeItem(text = subheaderText(text), isHeader = true))
                    } else if (numbered != null) {
                        if (startsWithQuantity(numbered)) {
                            ingredients.add(RecipeItem(text = cleanIngredient(numbered)))
                        } else if (!hasStepsHeader) {
                            section = Section.STEPS
                            stepsNumbered = true
                            steps.add(RecipeItem(text = numbered))
                        } else {
                            ingredients.add(RecipeItem(text = cleanIngredient(text)))
                        }
                    } else if (last != null && !last.isHeader && continuesIngredient(line, text, last.text)) {
                        ingredients[ingredients.size - 1] = last.copy(text = cleanIngredient(join(last.text, text)))
                    } else if (!hasStepsHeader && ingredients.isNotEmpty() && looksLikeInstruction(text)) {
                        section = Section.STEPS
                        steps.add(RecipeItem(text = text))
                    } else {
                        ingredients.add(RecipeItem(text = cleanIngredient(text)))
                    }
                }

                Section.STEPS -> {
                    val numbered = numberedStep(text)
                    val last = steps.lastOrNull()
                    val bulleted = bulletStripped(text)
                    if (isBareStepNumber(text)) {
                        stepsNumbered = true
                        pendingNewStep = true
                    } else if (numbered != null) {
                        stepsNumbered = true
                        pendingNewStep = false
                        steps.add(RecipeItem(text = numbered))
                    } else if (pendingNewStep) {
                        pendingNewStep = false
                        steps.add(RecipeItem(text = text))
                    } else if (isSubheader(text, shouting) && !(last?.let { endsWithJoiner(it.text) } ?: false)) {
                        steps.add(RecipeItem(text = subheaderText(text), isHeader = true))
                    } else if (bulleted != null) {
                        steps.add(RecipeItem(text = bulleted))
                    } else if (last != null && !last.isHeader && (stepsNumbered || continuesParagraph(line, text, last.text))) {
                        steps[steps.size - 1] = last.copy(text = join(last.text, text))
                    } else {
                        steps.add(RecipeItem(text = text))
                    }
                }

                Section.NOTES -> {
                    if (notes.isNotEmpty() && continuesParagraph(line, text, notes.last())) {
                        notes[notes.size - 1] = join(notes.last(), text)
                    } else {
                        notes.add(bulletStripped(text) ?: text)
                    }
                }
            }
        }

        // No steps found but a long preamble: the method probably came first on the page.
        if (steps.isEmpty() && summary.isNotEmpty() && (ingredients.isEmpty() || summary.joinToString("").length > 160)) {
            steps.addAll(summary.map { RecipeItem(text = it) })
            summary.clear()
        }

        if (!stepsNumbered) {
            val split = steps.flatMap { item ->
                if (item.isHeader) listOf(item) else splitLongStep(item.text).map { RecipeItem(text = it) }
            }
            steps.clear()
            steps.addAll(split)
        }

        result.summary = summary.joinToString("\n")
        result.notes = notes.joinToString("\n")
        result.endSection = section
        return result
    }

    // Title

    fun findTitle(lines: List<TextLine>, shouting: Boolean): Pair<String, Set<Int>> {
        val firstPage = lines.firstOrNull()?.page ?: return "" to emptySet()
        val heights = lines.mapNotNull { it.box?.height }.sorted()

        if (heights.size >= 3) {
            val median = heights[heights.size / 2]
            val candidates = lines.indices.take(14).filter { i ->
                lines[i].page == firstPage && (lines[i].box?.minY ?: 1.0) < 0.5 && isTitleCandidate(lines[i].text)
            }
            // First of the tallest lines wins ties, like Swift's max(by:).
            var best: Int? = null
            for (c in candidates) {
                if (best == null || (lines[c].box?.height ?: 0.0) > (lines[best].box?.height ?: 0.0)) best = c
            }
            val height = best?.let { lines[it].box?.height }
            if (best != null && height != null && height >= median * 1.3) {
                val used = mutableListOf(best)
                var next = best + 1
                while (next < lines.size && used.size < 3 && lines[next].page == firstPage) {
                    val h = lines[next].box?.height ?: break
                    if (h < height * 0.8 || !isTitleCandidate(lines[next].text)) break
                    used.add(next)
                    next++
                }
                val title = used.joinToString(" ") { lines[it].text }
                return cleanTitle(title, shouting) to used.toSet()
            }
        }

        for (i in lines.indices.take(6)) {
            val text = TextKit.cleanLine(lines[i].text)
            if (isTitleCandidate(text) && text.length <= 70 && !text.endsWith(".")) {
                return cleanTitle(text, shouting) to setOf(i)
            }
        }
        return "" to emptySet()
    }

    private val notTitles = setOf("recipe", "recipes", "recipe card", "from the kitchen of", "a recipe from", "menu")

    fun isTitleCandidate(raw: String): Boolean {
        val text = TextKit.cleanLine(raw)
        if (text.count { it.isLetter() } < 3) return false
        if (TextKit.fold(text).trim(' ', ':') in notTitles) return false
        if (header(text) != null) return false
        if (extractMetadata(text, allowBareCookTime = true).matched) return false
        if (startsWithQuantity(text) || bulletStripped(text) != null || numberedStep(text) != null) return false
        if (sourceText(text, allowBareBy = false) != null) return false
        return true
    }

    private fun cleanTitle(title: String, shouting: Boolean): String {
        var t = title.trim(' ', ':', '-', '–', '—', '*', '#')
        t = TextKit.titleCasedIfShouting(t)
        if (shouting) t = titleCased(t)
        return t
    }

    // Headers

    data class Header(val section: Section, val remainder: String?)

    private val headerPhrases: List<Pair<Section, Set<String>>> = listOf(
        Section.INGREDIENTS to setOf(
            "ingredients", "ingredient", "ingredient list", "ingredients list", "the ingredients",
            "you will need", "you'll need", "youll need", "what you'll need", "what you need",
            "what you will need", "shopping list", "for the recipe",
        ),
        Section.STEPS to setOf(
            "directions", "direction", "instructions", "instruction", "method", "methods", "the method",
            "preparation", "steps", "how to make", "how to make it", "to make", "procedure",
            "cooking instructions", "cooking directions", "baking instructions", "make it", "let's cook",
            "lets cook", "to prepare", "how to prepare", "what to do",
        ),
        Section.NOTES to setOf(
            "notes", "note", "tips", "tip", "cook's note", "cook's notes", "cooks note", "cooks notes",
            "chef's note", "chef's notes", "chefs note", "chefs notes", "cook's tip", "cook's tips",
            "variations", "variation", "storage", "to store", "make ahead", "make-ahead",
            "serving suggestions", "serving suggestion", "recipe notes", "helpful hints", "hints",
            "tips and tricks", "kitchen notes", "notes and tips",
        ),
    )

    private val leadingDecoration = charArrayOf('#', '*', '=', '_', '~', '•', '·', '-', '–', '—', ' ')
    private val trailingDecoration = charArrayOf(':', '.', '-', '–', '—', ' ')

    fun header(raw: String): Header? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 60) return null
        val stripped = text.trim(*leadingDecoration).trim(*trailingDecoration)
        val folded = TextKit.fold(stripped)
        for ((section, phrases) in headerPhrases) if (folded in phrases) return Header(section, null)
        val colon = text.indexOf(':')
        if (colon >= 0) {
            val head = TextKit.fold(text.substring(0, colon)).trim(*leadingDecoration)
            val rest = text.substring(colon + 1).trim()
            for ((section, phrases) in headerPhrases) {
                if (head in phrases) return Header(section, rest.ifEmpty { null })
            }
        }
        return null
    }

    fun isSubheader(text: String, shouting: Boolean): Boolean {
        val t = text.trim()
        if (t.length > 45 || startsWithQuantity(t) || numberedStep(t) != null) return false
        val letters = t.filter { it.isLetter() }
        if (letters.length < 3) return false
        if (t.endsWith(":")) return true
        val folded = TextKit.fold(t)
        if (folded.startsWith("for the ") && t.length <= 40) return true
        if (folded.startsWith("for ") && t.length <= 28 && !t.contains(",")) return true
        if (!shouting && letters.all { it.isUpperCase() } && t.length <= 30 && t.none { it.isDigit() }) return true
        return false
    }

    private fun subheaderText(text: String): String =
        TextKit.titleCasedIfShouting(text.trim(' ', ':', '-', '–', '—', '*', '#'))

    // Metadata

    data class Metadata(
        var servings: String? = null,
        var prep: String? = null,
        var cook: String? = null,
        var total: String? = null,
        var remainder: String = "",
        var matched: Boolean = false,
    )

    private val servingsPattern = TextKit.regex("""^(?:serves|serving size|servings|yield|yields|makes|portions|feeds)\b\s*[:\-–]?\s*(.+)$""")
    private val servingsTrailingPattern = TextKit.regex("""^(?:about\s+)?(\d+(?:\s*(?:-|–|to)\s*\d+)?)\s+(?:servings|portions|people)\.?$""")
    private val timePattern = TextKit.regex("""^(prep(?:aration)?(?:\s+time)?|active(?:\s+time)?|hands[- ]on(?:\s+time)?|cook(?:ing)?(?:\s+time)?|bake(?:\s+time)?|baking(?:\s+time)?|total(?:\s+time)?|ready\s+in|start\s+to\s+finish)\b\s*([:\-–]?)\s*(.+)$""")
    private val chunkSeparators = TextKit.regex("""\s*(?:\||•|·|;|\s/\s)\s*""")
    private val keywordBoundary = TextKit.regex("""\s(?=(?:prep|preparation|cook|cooking|bake|baking|total|ready in|serves|servings|yield|yields|makes|active)\b)""")
    private val startsWithKeyword = TextKit.regex("""^(?:prep|preparation|cook|cooking|bake|baking|total|ready in|serves|servings|yield|yields|makes|active|hands)\b""")
    private val fillerWords = setOf("about", "approx", "approximately", "around", "roughly", "plus", "or", "so", "just", "under", "over")

    fun extractMetadata(text: String, allowBareCookTime: Boolean): Metadata {
        val meta = Metadata(remainder = text)
        val chunks = metadataChunks(text)
        val leftovers = ArrayList<String>()

        for (chunk in chunks) {
            val servings = servingsPattern.find(chunk)?.groups?.get(1)?.value?.trim(' ', '.')
            if (servings != null && servings.length <= 40 && containsNumber(servings)) {
                if (meta.servings == null) meta.servings = servings
                continue
            }
            val trailing = servingsTrailingPattern.find(chunk)?.groups?.get(1)?.value
            if (trailing != null) {
                if (meta.servings == null) meta.servings = trailing
                continue
            }
            val m = timePattern.find(chunk)
            val keyword = m?.groups?.get(1)?.value?.lowercase()
            val value = m?.groups?.get(3)?.value
            if (keyword != null && value != null) {
                val colon = m.groups[2]?.value ?: ""
                val durations = DurationParser.matches(value)
                val first = durations.firstOrNull()
                if (first != null) {
                    val onlyDuration = isOnlyDurations(value, durations)
                    val explicit = keyword.contains("time") || colon.isNotEmpty() || keyword.startsWith("ready") || keyword.startsWith("start")
                    val softKeyword = keyword.startsWith("prep") || keyword.startsWith("total") || keyword.startsWith("active") || keyword.startsWith("hands")
                    if (explicit || (onlyDuration && (softKeyword || allowBareCookTime || chunks.size > 1))) {
                        val trimmed = value.trim(' ', '.')
                        val normalized = if (onlyDuration) first.label else trimmed.take(40)
                        when {
                            keyword.startsWith("prep") || keyword.startsWith("active") || keyword.startsWith("hands") ->
                                if (meta.prep == null) meta.prep = normalized
                            keyword.startsWith("cook") || keyword.startsWith("bak") ->
                                if (meta.cook == null) meta.cook = normalized
                            else -> if (meta.total == null) meta.total = normalized
                        }
                        continue
                    }
                }
            }
            leftovers.add(chunk)
        }

        meta.matched = leftovers.size < chunks.size
        meta.remainder = leftovers.joinToString(" ")
        return meta
    }

    // OCR often reads a "|" separator as a lone "I" or "l".
    private val strayPipe = TextKit.regex("""^[|Il]\s+|\s+[|Il]$""")

    private fun metadataChunks(text: String): List<String> =
        splitBy(text, chunkSeparators)
            .flatMap { chunk ->
                if (startsWithKeyword.find(chunk) == null) listOf(chunk)
                else splitBy(chunk, keywordBoundary).map { strayPipe.replace(it, "") }
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun splitBy(text: String, regex: Regex): List<String> {
        val pieces = ArrayList<String>()
        var start = 0
        for (m in regex.findAll(text)) {
            pieces.add(text.substring(start, m.range.first))
            start = m.range.last + 1
        }
        pieces.add(text.substring(start))
        return pieces
    }

    private fun isOnlyDurations(value: String, durations: List<DurationParser.Match>): Boolean {
        val sb = StringBuilder(value)
        for (d in durations.reversed()) sb.replace(d.location, d.location + d.length, " ")
        return TextKit.words(sb.toString()).none { it !in fillerWords }
    }

    private fun containsNumber(s: String): Boolean {
        if (s.any { it.isDigit() }) return true
        return TextKit.words(s).any { it in TextKit.numberWords && it != "a" && it != "an" }
    }

    // Source lines

    private val sourcePattern = TextKit.regex("""^(?:from the kitchen of|recipe from|recipe by|recipe courtesy of|courtesy of|adapted from|source\s*:|from:)\s*(.+)$""")
    private val bylinePattern = TextKit.regex("""^by\s+([A-Z][\w.'-]*(?:\s+[A-Z][\w.'-]*){0,3})$""")

    fun sourceText(text: String, allowBareBy: Boolean): String? {
        if (text.length > 70) return null
        val m = sourcePattern.find(text)
        if (m != null) {
            val folded = TextKit.fold(text)
            if (folded.startsWith("source") || folded.startsWith("from:")) return m.groups[1]?.value?.trim()
            return text.trim(' ', '.')
        }
        if (allowBareBy && bylinePattern.find(text) != null) return text
        return null
    }

    // Line classification

    private val numberedPattern = TextKit.regex("""^(?:step\s*)?(\d{1,2})\s*[.):\]]\s+(.+)$""")
    private val numberedTightPattern = TextKit.regex("""^(?:step\s*)?(\d{1,2})[.)]([A-Za-z].*)$""")
    private val stepWordPattern = TextKit.regex("""^step\s*(\d{1,2})\s*[-–:.]?\s*(.+)$""")
    // Case-sensitive: "1 Preheat the oven" but not "1 cup flour".
    private val numberedCapitalPattern = Regex("""^(\d{1,2})\s+([A-Z][a-z].{12,})$""")
    private val bareNumberPattern = TextKit.regex("""^(?:step\s*)?\d{1,2}\s*[.):]?$""")
    private val bulletPattern = TextKit.regex("""^(?:[•·▪●○◦■□☐✓✔➢►>*~]|[-–—](?=\s)|[oe°](?=\s+\d))\s*(.+)$""")

    /** The text of a numbered step without its number, or null. */
    fun numberedStep(text: String): String? {
        for (pattern in listOf(numberedPattern, numberedTightPattern, stepWordPattern)) {
            val rest = pattern.find(text)?.groups?.get(2)?.value
            if (rest != null) return rest.trim()
        }
        // "1 Preheat the oven" is a step; "12 Graham crackers" is not.
        val rest = numberedCapitalPattern.find(text)?.groups?.get(2)?.value
        if (rest != null && TextKit.words(rest).firstOrNull() in instructionVerbs) return rest
        return null
    }

    fun isBareStepNumber(text: String): Boolean = bareNumberPattern.find(text) != null

    fun bulletStripped(text: String): String? = bulletPattern.find(text)?.groups?.get(1)?.value?.trim()

    fun startsWithQuantity(text: String): Boolean =
        IngredientParser.parse(bulletStripped(text) ?: text).quantity != null

    private val instructionVerbs = setOf(
        "preheat", "heat", "mix", "combine", "stir", "whisk", "add", "bake", "cook", "place", "put", "pour",
        "beat", "bring", "remove", "serve", "cut", "chop", "slice", "melt", "simmer", "boil", "fry", "saute",
        "roast", "grill", "blend", "fold", "spread", "sprinkle", "season", "cover", "let", "transfer", "drain",
        "rinse", "toss", "arrange", "top", "garnish", "refrigerate", "chill", "freeze", "knead", "roll", "shape",
        "form", "divide", "grease", "line", "spray", "prepare", "make", "set", "allow", "return", "reduce",
        "increase", "turn", "cream", "sift", "whip", "marinate", "brush", "layer", "cool", "store", "wash",
        "peel", "dice", "mince", "grate", "squeeze", "scoop", "press", "repeat", "finish", "enjoy", "in",
        "using", "meanwhile", "once", "when", "while", "after", "then", "finally", "first", "next", "gently",
    )

    private val instructionGlue = listOf(" the ", " a ", " an ", " until ", " into ", " to ", " with ", " over ", " in ", " on ")

    fun looksLikeInstruction(text: String): Boolean {
        if (startsWithQuantity(text)) return false
        if (text.length >= 80) return true
        if (text.length >= 45 && (text.endsWith(".") || text.contains(". "))) return true
        // "Cream the butter and sugar" but not "Cream cheese frosting (see below)".
        val first = TextKit.words(text).firstOrNull()
        if (first != null && first in instructionVerbs && text.length >= 25) {
            val folded = " " + TextKit.fold(text) + " "
            return instructionGlue.any { folded.contains(it) }
        }
        return false
    }

    private val joinerWords = setOf("and", "or", "of", "with", "plus", "into", "to", "the", "a", "for", "in", "at", "about")

    fun endsWithJoiner(text: String): Boolean {
        val last = text.lastOrNull() ?: return false
        if (last in ",-/&(") return true
        val word = TextKit.words(text).lastOrNull()
        return word != null && word in joinerWords && !text.endsWith(".")
    }

    private fun endsWithTerminal(text: String): Boolean = text.lastOrNull()?.let { it in ".!?:)" } ?: false

    private fun startsLowercase(text: String): Boolean =
        text.firstOrNull { it.isLetterOrDigit() }?.isLowerCase() ?: false

    fun continuesIngredient(line: TextLine, text: String, previous: String): Boolean {
        if (startsWithQuantity(text) || bulletStripped(text) != null) return false
        if (endsWithJoiner(previous)) return true
        if (text.startsWith("(")) return true
        if (line.previousLineFull && !line.paragraphStart && startsLowercase(text)) return true
        return false
    }

    fun continuesParagraph(line: TextLine, text: String, previous: String): Boolean {
        if (numberedStep(text) != null || bulletStripped(text) != null) return false
        if (endsWithJoiner(previous)) return true
        if (line.box != null) return !line.paragraphStart
        return startsLowercase(text) && !endsWithTerminal(previous)
    }

    private val compoundPrefixes = setOf(
        "all", "self", "half", "semi", "non", "extra", "low", "high", "well", "sugar", "fat", "gluten", "dairy",
        "store", "sun", "bite", "medium", "deep", "pan", "stir", "slow", "pre", "re", "co", "double", "single",
        "long", "short", "quick", "bone", "skin", "thick", "thin", "full", "part", "room", "oven", "egg",
        "reduced", "sodium", "lemon", "lime", "no", "one", "two", "three", "fine", "coarse", "rock", "sea",
    )

    /** Joins a wrapped line onto the previous one, undoing end-of-line hyphenation. */
    fun join(previous: String, next: String): String {
        val prev = previous.trim()
        val nxt = next.trim()
        if (prev.endsWith("-") && nxt.firstOrNull()?.isLowerCase() == true) {
            val beforeHyphen = prev.dropLast(1)
            val lastWord = TextKit.words(beforeHyphen).lastOrNull() ?: ""
            if (lastWord in compoundPrefixes) return prev + nxt
            if (beforeHyphen.lastOrNull()?.isLetter() == true) return beforeHyphen + nxt
        }
        return "$prev $nxt"
    }

    private fun cleanIngredient(text: String): String {
        var t = bulletStripped(text) ?: text
        val rest = numberedStep(t)
        if (rest != null && startsWithQuantity(rest)) t = rest
        return t.trim(' ', ',', ';')
    }

    private val pageNumber = Regex("""^page\s+\d+(\s+of\s+\d+)?$""")
    private val continuedMarker = Regex("""^(continued|cont'?d)(\s+(on|from)\s+(the\s+)?(next|previous|following|page|reverse|back|other side).*)?$""")
    private val turnOver = Regex("""^(see|turn)\s+(over|next page|page \d+|reverse|back)$""")

    private fun isNoise(line: TextLine): Boolean {
        val t = line.text.trim()
        if (t.isEmpty()) return true
        if (t.length == 1 && !t[0].isLetterOrDigit() && t[0] !in TextKit.unicodeFractions) return true
        val folded = TextKit.fold(t).trim(' ', '-', '–', '—', '(', ')', '[', ']', '*', '.')
        if (pageNumber.matches(folded)) return true
        // Page-turn markers: "— continued on next page —", "(continued)", "cont'd"
        if (continuedMarker.matches(folded)) return true
        if (turnOver.matches(folded)) return true
        val box = line.box
        if (t.all { it.isDigit() } && t.length <= 3 && box != null && (box.minY < 0.06 || box.maxY > 0.94)) return true
        return false
    }

    // Case helpers

    private fun mostlyUppercase(lines: List<TextLine>): Boolean {
        val lettered = lines.filter { l -> l.text.count { it.isLetter() } >= 3 }
        if (lettered.size < 4) return false
        val upper = lettered.count { l -> l.text.filter { it.isLetter() }.all { it.isUpperCase() } }
        return upper.toDouble() / lettered.size > 0.6
    }

    private fun sentenceCased(text: String): String {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty() || !letters.all { it.isUpperCase() }) return text
        val lowered = text.lowercase()
        // "2 CUPS FLOUR" → "2 cups flour", "MIX WELL." → "Mix well."
        val first = lowered.firstOrNull { !it.isWhitespace() }
        if (first == null || !first.isLetter()) return lowered
        return TextKit.capitalizeFirstLetter(lowered)
    }

    private fun nextLineStartsWithQuantity(index: Int, lines: List<TextLine>, consumed: Set<Int>): Boolean {
        var next = index + 1
        while (next < lines.size && next in consumed) next++
        return next < lines.size && startsWithQuantity(lines[next].text)
    }

    private val smallWords = setOf("a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "with", "from")

    private fun titleCased(text: String): String =
        text.split(" ").filter { it.isNotEmpty() }.mapIndexed { index, word ->
            if (index > 0 && word.lowercase() in smallWords) word.lowercase() else TextKit.capitalizeFirstLetter(word)
        }.joinToString(" ")

    // Long paragraphs

    private val abbreviations = setOf(
        "tsp", "tbsp", "oz", "lb", "lbs", "min", "mins", "approx", "eg", "ie", "no", "st", "dr", "mr", "mrs",
        "ms", "vs", "deg", "temp", "pkg", "qt", "pt", "gal", "fl", "c", "t", "hr", "hrs", "sec", "secs", "etc",
    )

    /** Breaks a very long unnumbered paragraph into cook-mode-friendly steps at sentence boundaries. */
    fun splitLongStep(text: String): List<String> {
        if (text.length <= 360) return listOf(text)
        val sentences = ArrayList<String>()
        val current = StringBuilder()
        for (i in text.indices) {
            current.append(text[i])
            if (text[i] in ".!?" && i + 2 < text.length && text[i + 1] == ' ' && text[i + 2].isUpperCase()) {
                val lastWord = TextKit.words(current.toString()).lastOrNull() ?: ""
                if (lastWord !in abbreviations) {
                    sentences.add(current.toString().trim())
                    current.setLength(0)
                }
            }
        }
        if (current.isNotBlank()) sentences.add(current.toString().trim())
        val chunks = ArrayList<String>()
        var chunk = ""
        for (sentence in sentences) {
            chunk = when {
                chunk.isEmpty() -> sentence
                chunk.length + sentence.length + 1 <= 240 -> "$chunk $sentence"
                else -> {
                    chunks.add(chunk)
                    sentence
                }
            }
        }
        if (chunk.isNotEmpty()) chunks.add(chunk)
        return chunks
    }

    // Raw text

    fun rawText(lines: List<TextLine>): String {
        val pages = ArrayList<MutableList<String>>()
        var currentPage: Int? = null
        for (line in lines) {
            if (line.page != currentPage) {
                pages.add(ArrayList())
                currentPage = line.page
            }
            pages.last().add(line.text)
        }
        return pages.joinToString("\n\n") { it.joinToString("\n") }
    }
}
