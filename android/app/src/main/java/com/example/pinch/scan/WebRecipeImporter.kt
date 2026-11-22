package com.example.pinch.scan

import com.example.pinch.ai.RecipeAI
import com.example.pinch.model.RecipeItem
import com.example.pinch.parsing.DurationParser
import com.example.pinch.parsing.ParsedRecipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** A fetched recipe page, boiled down to what's needed to read it. */
data class ScrapedPage(
    val url: String,
    val title: String,
    val siteName: String,
    /** The page's schema.org Recipe markup, if it has any. Most recipe sites do. */
    val structured: StructuredRecipe?,
    /** The page's readable text, trimmed around the recipe when the page is very long. */
    val text: String,
)

/** A recipe from a page's schema.org markup (JSON-LD). */
data class StructuredRecipe(
    val name: String = "",
    val description: String = "",
    val yield: String = "",
    val prepTime: String = "",
    val cookTime: String = "",
    val totalTime: String = "",
    val ingredients: List<String> = emptyList(),
    /** Steps, with section names (HowToSection) as header items. */
    val instructions: List<RecipeItem> = emptyList(),
    val author: String = "",
    val publisher: String = "",
    val categories: List<String> = emptyList(),
) {
    val isUseful: Boolean get() = ingredients.isNotEmpty() || instructions.isNotEmpty()

    /** A plain-text rendering for the AI prompt. */
    val promptText: String
        get() = buildList {
            fun add(label: String, value: String) { if (value.isNotEmpty()) add("$label: $value") }
            add("Name", name)
            add("Description", description)
            add("Yield", yield)
            add("Prep time", prepTime)
            add("Cook time", cookTime)
            add("Total time", totalTime)
            add("Author", author)
            add("Categories", categories.joinToString(", "))
            if (ingredients.isNotEmpty()) {
                add("Ingredients:")
                ingredients.forEach { add("- $it") }
            }
            if (instructions.isNotEmpty()) {
                add("Instructions:")
                instructions.forEach { add(if (it.isHeader) "## ${it.text}" else "- ${it.text}") }
            }
        }.joinToString("\n")

    /** The markup mapped straight into a recipe, for when AI reading isn't set up. */
    val parsed: ParsedRecipe
        get() = ParsedRecipe(
            title = name,
            summary = description,
            source = publisher.ifEmpty { author },
            servings = yield,
            prepTime = prepTime,
            cookTime = cookTime,
            totalTime = totalTime,
            ingredients = ingredients.map { RecipeItem(text = it) }.toMutableList(),
            steps = instructions.toMutableList(),
            rawText = promptText,
        )
}

/** Why a website couldn't be read. Messages are shown to the cook. */
sealed class WebImportException(message: String) : Exception(message) {
    data object BadAddress : WebImportException("That doesn't look like a web address. Check it and try again.")
    data object Offline : WebImportException("You're offline. Connect to the internet to add a recipe from a website.")
    data object Blocked : WebImportException("That website didn't let Pinch in. Open it in your browser, copy the recipe, and paste it in instead.")
    data object NotFound : WebImportException("That page doesn't exist anymore. Check the address and try again.")
    data object NotAWebPage : WebImportException("That link isn't a web page Pinch can read.")
    data object NoRecipe : WebImportException("Couldn't find a recipe on that page. If you can see it in your browser, copy it and paste it in instead.")
    data object NeedsAI : WebImportException("That page doesn’t have a recipe card Pinch can copy. Sign in to Pinch to read it, or copy and paste the recipe text.")
    data class Unreachable(val detail: String) : WebImportException("Couldn't open that page ($detail).")
}

/** Fetches a recipe web page and finds the recipe in it. Mirrors iOS's `WebRecipeImporter.swift`. */
object WebRecipeImporter {
    const val TEXT_LIMIT = 60_000
    private const val MAX_BYTES = 6_000_000

    // Addresses

    /** Finds a web address in typed or shared text ("Look at this! https://…") and makes it https. */
    fun normalizedUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        var text = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).find(trimmed)?.value
            ?: if (!trimmed.contains(' ')) "https://$trimmed" else return null
        // Trailing punctuation from a sentence isn't part of the link.
        text = text.trimEnd { it in ".,;:!?)]}" }
        val match = Regex("^https?://([^/:?#\\s]+)(.*)$", RegexOption.IGNORE_CASE).matchEntire(text) ?: return null
        val host = match.groupValues[1]
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.') || host.contains("..")) return null
        return "https://$host${match.groupValues[2]}"
    }

    /** Pasted or shared text that is nothing but a link should be opened, not parsed. */
    fun link(text: String): String? {
        val trimmed = text.trim()
        if (!Regex("^(https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE).matches(trimmed)) return null
        return normalizedUrl(trimmed)
    }

    fun displayHost(url: String): String =
        (runCatching { URI(url).host }.getOrNull() ?: url).removePrefix("www.")

    // Fetching

    /** What Chrome on this phone would call itself: the WebView's user agent without the WebView parts. */
    fun browserUserAgent(webViewUserAgent: String): String =
        webViewUserAgent.replace("; wv)", ")").replace(Regex("Version/\\d+(\\.\\d+)* "), "")

    suspend fun fetch(url: String, userAgent: String): Pair<String, String> {
        var current = url
        repeat(6) {
            val response = try {
                get(current, userAgent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw when (e) {
                    is UnknownHostException -> WebImportException.Unreachable("couldn't find that website; check the address and your connection")
                    is SocketTimeoutException -> WebImportException.Unreachable("it took too long to load")
                    is ConnectException -> WebImportException.Offline
                    else -> WebImportException.Unreachable(e.message ?: "network error")
                }
            }
            if (response.code in 300..399 && response.location != null) {
                current = URL(URL(current), response.location).toString()
                return@repeat
            }
            when (response.code) {
                in 200..299 -> Unit
                401, 403, 429, 503 -> throw WebImportException.Blocked
                404, 410 -> throw WebImportException.NotFound
                else -> throw WebImportException.Unreachable("error ${response.code}")
            }
            val type = response.contentType?.lowercase().orEmpty()
            if (type.isNotEmpty() && !type.contains("html") && !type.contains("xml") && !type.startsWith("text/")) {
                throw WebImportException.NotAWebPage
            }
            val html = decode(response.body, response.contentType)
            if (isChallengePage(html)) throw WebImportException.Blocked
            return html to current
        }
        throw WebImportException.Unreachable("too many redirects")
    }

    private class Response(val code: Int, val location: String?, val contentType: String?, val body: ByteArray)

    private suspend fun get(url: String, userAgent: String): Response = coroutineScope {
        val connection = URL(url).openConnection() as HttpURLConnection
        val exchange = async(Dispatchers.IO) {
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                connection.setRequestProperty("Accept-Language", acceptLanguage())
                val code = connection.responseCode
                val type = connection.contentType?.lowercase().orEmpty()
                val wanted = code in 200..299 && (type.isEmpty() || type.contains("html") || type.contains("xml") || type.startsWith("text/"))
                // Only a web page's body is read, so a link to a video or PDF isn't downloaded.
                val body = (if (wanted) connection.inputStream else null)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (out.size() < MAX_BYTES) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                Response(code, connection.getHeaderField("Location"), connection.contentType, body)
            } finally {
                connection.disconnect()
            }
        }
        try {
            exchange.await()
        } catch (e: CancellationException) {
            connection.disconnect()
            throw e
        }
    }

    private fun acceptLanguage(): String {
        val tag = Locale.getDefault().toLanguageTag()
        return if (tag.startsWith("en")) "$tag,en;q=0.9" else "$tag,en;q=0.8"
    }

    fun decode(bytes: ByteArray, contentType: String?): String {
        val declared = contentType?.let { Regex("charset=([\\w.:-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?: Regex("<meta[^>]+charset=[\"']?([\\w.:-]+)", RegexOption.IGNORE_CASE)
                .find(String(bytes, 0, min(bytes.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    /** Bot checks ("Just a moment…") come back as ordinary pages with no recipe in them. */
    fun isChallengePage(html: String): Boolean {
        val head = html.take(6000).lowercase()
        return head.contains("<title>just a moment") || head.contains("cf-browser-verification") ||
            (head.contains("challenge-platform") && !html.contains("application/ld+json"))
    }

    // Parsing

    fun parse(html: String, url: String): ScrapedPage {
        val metas = metaTags(html)
        fun meta(key: String): String? = metas
            .filter { (it["property"] ?: it["name"])?.lowercase() == key }
            .map { clean(it["content"].orEmpty()) }
            .firstOrNull { it.isNotEmpty() }
        val structured = structuredRecipe(html)
        val htmlTitle = Regex("<title\\b[^>]*>([\\s\\S]*?)</title\\s*>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::clean)
        val host = displayHost(url)
        val title = listOf(structured?.name, meta("og:title"), htmlTitle).firstOrNull { !it.isNullOrEmpty() }.orEmpty()
        val siteName = listOf(meta("og:site_name"), structured?.publisher, meta("application-name")).firstOrNull { !it.isNullOrEmpty() } ?: host
        return ScrapedPage(url, title, siteName, structured, focusedText(readableText(html), TEXT_LIMIT))
    }

    // Structured data

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun structuredRecipe(html: String): StructuredRecipe? {
        val blocks = Regex("<script\\b[^>]*application/ld\\+json[^>]*>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE).findAll(html)
        for (block in blocks) {
            val body = block.groupValues[1].trim()
                .replace(Regex("^(<!--|<!\\[CDATA\\[)"), "")
                .replace(Regex("(-->|\\]\\]>)$"), "")
            val parsed = parseJson(body) ?: continue
            val node = findRecipe(parsed, 0) ?: continue
            val recipe = map(node)
            if (recipe.isUseful) return recipe
        }
        return null
    }

    private fun parseJson(text: String): JsonElement? =
        runCatching { json.parseToJsonElement(text) }.getOrNull()
            // Hand-written markup often has raw line breaks or tabs inside strings.
            ?: runCatching { json.parseToJsonElement(text.replace(Regex("[\\n\\r\\t]"), " ")) }.getOrNull()

    private fun findRecipe(value: JsonElement, depth: Int): JsonObject? {
        if (depth >= 10) return null
        when (value) {
            is JsonObject -> {
                if (isType(value["@type"], "recipe")) return value
                val preferred = listOf("@graph", "mainEntity", "mainEntityOfPage", "itemListElement", "item", "hasPart")
                val others = value.keys.filter { it !in preferred }.sorted()
                for (key in preferred + others) {
                    val child = value[key] ?: continue
                    if (child is JsonObject || child is JsonArray) findRecipe(child, depth + 1)?.let { return it }
                }
            }
            is JsonArray -> for (element in value) findRecipe(element, depth + 1)?.let { return it }
            else -> Unit
        }
        return null
    }

    /** Matches "Recipe", "schema:Recipe", "https://schema.org/Recipe" or an array containing one. */
    private fun isType(value: JsonElement?, type: String): Boolean = when (value) {
        is JsonArray -> value.any { isType(it, type) }
        is JsonPrimitive -> typeName(value) == type
        else -> false
    }

    private fun typeName(value: JsonElement?): String = when (value) {
        is JsonArray -> value.firstOrNull { it is JsonPrimitive }?.let { typeName(it) }.orEmpty()
        is JsonPrimitive -> value.contentOrNull.orEmpty().split('/', ':', '#').last().lowercase()
        else -> ""
    }

    private fun map(node: JsonObject): StructuredRecipe {
        val categories = listOf("recipeCategory", "recipeCuisine", "keywords")
            .flatMap { strings(node[it]) }
            .flatMap { it.split(',', ';') }
            .map(::clean)
        val seen = HashSet<String>()
        return StructuredRecipe(
            name = clean(text(node["name"])),
            description = clean(text(node["description"])),
            yield = yieldText(node["recipeYield"] ?: node["yield"]),
            prepTime = isoDurationText(text(node["prepTime"])),
            cookTime = isoDurationText(text(node["cookTime"])),
            totalTime = isoDurationText(text(node["totalTime"])),
            ingredients = strings(node["recipeIngredient"] ?: node["ingredients"])
                .map { clean(it).replace(Regex("^[•·▪▢☐□*\\-–]+\\s*"), "") }
                .filter { it.isNotEmpty() },
            instructions = instructionItems(node["recipeInstructions"], 0),
            author = clean(personName(node["author"])),
            publisher = clean(personName(node["publisher"])),
            categories = categories.filter { it.isNotEmpty() && seen.add(it.lowercase()) }.take(8),
        )
    }

    /** A readable value from whatever shape JSON-LD used: text, number, list or object. */
    private fun text(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonArray -> value.firstOrNull()?.let { text(it) }.orEmpty()
        is JsonObject -> text(value["@value"] ?: value["text"] ?: value["name"])
    }

    private fun strings(value: JsonElement?): List<String> = when (value) {
        null, JsonNull -> emptyList()
        is JsonPrimitive -> listOf(value.contentOrNull.orEmpty())
        is JsonArray -> value.flatMap { strings(it) }
        is JsonObject -> listOf(text(value))
    }

    private fun personName(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonArray -> value.firstOrNull()?.let { personName(it) }.orEmpty()
        is JsonObject -> text(value["name"])
    }

    /** Picks the most useful yield from values like ["4", "4 servings"] or "Makes 24 cookies". */
    private fun yieldText(value: JsonElement?): String {
        val options = strings(value).map(::clean).filter { it.isNotEmpty() }
        val startsWithNumber = options.filter { it.first().isDigit() }
        return startsWithNumber.firstOrNull { s -> s.any { it.isLetter() } } ?: startsWithNumber.firstOrNull() ?: options.firstOrNull().orEmpty()
    }

    private fun instructionItems(value: JsonElement?, depth: Int): List<RecipeItem> {
        if (value == null || value is JsonNull || depth >= 8) return emptyList()
        return when (value) {
            // A single block of text, sometimes with HTML paragraphs or numbered lines.
            is JsonPrimitive -> readableText(value.contentOrNull.orEmpty())
                .split("\n")
                .map { RecipeAI.cleanedItemText(it, isStep = true) }
                .filter { it.isNotEmpty() }
                .map { RecipeItem(text = it) }
            is JsonArray -> value.flatMap { instructionItems(it, depth + 1) }
            is JsonObject -> {
                val type = typeName(value["@type"])
                if (type == "howtosection" || type == "itemlist" || (value["text"] == null && value["itemListElement"] != null)) {
                    val name = clean(text(value["name"]))
                    (if (name.isEmpty()) emptyList() else listOf(RecipeItem(text = name, isHeader = true))) +
                        instructionItems(value["itemListElement"], depth + 1)
                } else {
                    val step = RecipeAI.cleanedItemText(clean(text(value["text"] ?: value["description"] ?: value["name"])), isStep = true)
                    if (step.isEmpty()) emptyList() else listOf(RecipeItem(text = step))
                }
            }
        }
    }

    /** "PT1H30M" → "1 hr 30 min". Text that isn't an ISO 8601 duration is tidied instead. */
    fun isoDurationText(raw: String): String {
        val trimmed = raw.trim()
        val minutes = isoMinutes(trimmed) ?: return DurationParser.normalized(trimmed)
        return if (minutes > 0) DurationParser.formatMinutes(minutes) else ""
    }

    private val isoPattern = Regex(
        "^P(?:(\\d+(?:[.,]\\d+)?)Y)?(?:(\\d+(?:[.,]\\d+)?)M)?(?:(\\d+(?:[.,]\\d+)?)W)?(?:(\\d+(?:[.,]\\d+)?)D)?" +
            "(?:T(?:(\\d+(?:[.,]\\d+)?)H)?(?:(\\d+(?:[.,]\\d+)?)M)?(?:(\\d+(?:[.,]\\d+)?)S)?)?$",
        RegexOption.IGNORE_CASE,
    )

    fun isoMinutes(text: String): Int? {
        if (text.length < 2) return null
        val match = isoPattern.matchEntire(text) ?: return null
        fun value(group: Int) = match.groupValues[group].replace(',', '.').toDoubleOrNull() ?: 0.0
        val minutes = value(3) * 10_080 + value(4) * 1440 + value(5) * 60 + value(6) + value(7) / 60
        // Anything past 1000 hours is garbage in the markup.
        if (!minutes.isFinite() || minutes > 60_000) return 0
        return Math.round(minutes).toInt()
    }

    // Page text

    private val removedTags = listOf("script", "style", "noscript", "svg", "template", "iframe", "head", "nav", "footer", "form", "select", "button")
        .map { Regex("<$it\\b[^>]*>[\\s\\S]*?</$it\\s*>", RegexOption.IGNORE_CASE) }
    private val comment = Regex("<!--[\\s\\S]*?-->")
    private val lineBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val listItem = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val blockTag = Regex(
        "</?(?:p|div|section|article|header|main|aside|h[1-6]|ul|ol|li|tr|table|tbody|thead|tfoot|figure|figcaption|blockquote|dd|dt|dl|pre|hr)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val cellTag = Regex("<t[dh]\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("<[^>]+>")
    private val spaces = Regex("[\\s\\u00A0\\u200B]+")

    /** The page as plain text, one block per line, without scripts, menus or markup. */
    fun readableText(html: String): String {
        var s = comment.replace(html, " ")
        for (tag in removedTags) s = tag.replace(s, " ")
        s = lineBreak.replace(s, "\n")
        s = listItem.replace(s, "\n• ")
        s = blockTag.replace(s, "\n")
        s = cellTag.replace(s, " ")
        s = anyTag.replace(s, "")
        s = decodeEntities(s)
        val lines = ArrayList<String>()
        for (raw in s.split('\n', '\r')) {
            val line = spaces.replace(raw, " ").trim()
            if (line.isEmpty() || line == "•" || line == lines.lastOrNull()) continue
            lines.add(line)
        }
        return lines.joinToString("\n")
    }

    /**
     * Long pages are cut down to a window around the recipe card: the "Ingredients" heading
     * followed by the most lines that start with an amount.
     */
    fun focusedText(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val lines = text.split("\n")
        val offsets = IntArray(lines.size)
        var running = 0
        lines.forEachIndexed { index, line ->
            offsets[index] = running
            running += line.length + 1
        }
        var bestLine = -1
        var bestScore = 0
        lines.forEachIndexed { index, line ->
            if (line.length <= 40 && line.lowercase().contains("ingredient")) {
                val score = lines.subList(index + 1, min(lines.size, index + 30)).count(::startsWithAmount)
                if (score > bestScore) {
                    bestScore = score
                    bestLine = index
                }
            }
        }
        if (bestLine < 0) return text.take(limit)
        val start = max(0, min(offsets[bestLine] - limit / 4, text.length - limit))
        return text.substring(start, min(text.length, start + limit))
    }

    private fun startsWithAmount(line: String): Boolean {
        val trimmed = line.removePrefix("• ")
        val first = trimmed.firstOrNull() ?: return false
        return first.isDigit() || first in "½⅓⅔¼¾⅛⅜⅝⅞"
    }

    // HTML helpers

    private val metaTag = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val attribute = Regex("([a-zA-Z_:.-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")

    fun metaTags(html: String): List<Map<String, String>> {
        val headEnd = html.indexOf("</head>", ignoreCase = true)
        val head = if (headEnd >= 0) html.substring(0, headEnd) else html.take(200_000)
        return metaTag.findAll(head).map { tag ->
            attribute.findAll(tag.value).associate { match ->
                val value = match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value.orEmpty()
                match.groupValues[1].lowercase() to value
            }
        }.toList()
    }

    /** Decodes tags and entities out of a markup value and collapses its whitespace. */
    fun clean(text: String): String {
        var s = text
        if (s.contains('<')) s = anyTag.replace(s, " ")
        s = decodeEntities(s)
        return spaces.replace(s, " ").trim()
    }

    val namedEntities: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ensp" to " ", "emsp" to " ",
        "thinsp" to " ", "shy" to "", "zwj" to "", "zwnj" to "", "ndash" to "–", "mdash" to "—", "minus" to "−", "hellip" to "…",
        "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„", "laquo" to "«",
        "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›", "prime" to "′", "Prime" to "″", "bull" to "•", "middot" to "·",
        "deg" to "°", "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "frac13" to "⅓", "frac23" to "⅔", "frac15" to "⅕",
        "frac16" to "⅙", "frac18" to "⅛", "frac38" to "⅜", "frac58" to "⅝", "frac78" to "⅞", "frasl" to "⁄", "times" to "×",
        "divide" to "÷", "plusmn" to "±", "copy" to "©", "reg" to "®", "trade" to "™", "cent" to "¢", "pound" to "£",
        "euro" to "€", "yen" to "¥", "sect" to "§", "para" to "¶", "micro" to "µ", "ordm" to "º", "ordf" to "ª",
        "iexcl" to "¡", "iquest" to "¿", "aacute" to "á", "Aacute" to "Á", "agrave" to "à", "Agrave" to "À",
        "acirc" to "â", "Acirc" to "Â", "auml" to "ä", "Auml" to "Ä", "atilde" to "ã", "Atilde" to "Ã", "aring" to "å",
        "Aring" to "Å", "aelig" to "æ", "AElig" to "Æ", "ccedil" to "ç", "Ccedil" to "Ç", "eacute" to "é", "Eacute" to "É",
        "egrave" to "è", "Egrave" to "È", "ecirc" to "ê", "Ecirc" to "Ê", "euml" to "ë", "Euml" to "Ë", "iacute" to "í",
        "Iacute" to "Í", "igrave" to "ì", "icirc" to "î", "iuml" to "ï", "ntilde" to "ñ", "Ntilde" to "Ñ", "oacute" to "ó",
        "Oacute" to "Ó", "ograve" to "ò", "ocirc" to "ô", "Ocirc" to "Ô", "ouml" to "ö", "Ouml" to "Ö", "otilde" to "õ",
        "oslash" to "ø", "Oslash" to "Ø", "oelig" to "œ", "OElig" to "Œ", "szlig" to "ß", "uacute" to "ú", "Uacute" to "Ú",
        "ugrave" to "ù", "ucirc" to "û", "uuml" to "ü", "Uuml" to "Ü", "yacute" to "ý", "yuml" to "ÿ",
    )

    /** Windows-1252 characters that old pages write as numeric entities in the 128–159 range. */
    private val cp1252 = mapOf(
        0x80 to "€", 0x82 to "‚", 0x84 to "„", 0x85 to "…", 0x86 to "†", 0x87 to "‡", 0x89 to "‰", 0x8B to "‹", 0x8C to "Œ",
        0x91 to "‘", 0x92 to "’", 0x93 to "“", 0x94 to "”", 0x95 to "•", 0x96 to "–", 0x97 to "—", 0x98 to "˜", 0x99 to "™",
        0x9B to "›", 0x9C to "œ",
    )

    private val entity = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});")
    private val anyEntity = Regex("&(#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")

    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        var result = decodeOnce(text)
        // Markup is sometimes encoded twice ("&amp;#8217;").
        if (result.contains('&') && anyEntity.containsMatchIn(result)) result = decodeOnce(result)
        return result
    }

    private fun decodeOnce(text: String): String = entity.replace(text) { match ->
        replacement(match.groupValues[1]) ?: match.value
    }

    private fun replacement(name: String): String? {
        if (name.startsWith("#")) {
            val digits = name.substring(1)
            val value = if (digits.startsWith("x") || digits.startsWith("X")) digits.substring(1).toIntOrNull(16) else digits.toIntOrNull()
            value ?: return null
            cp1252[value]?.let { return it }
            if (value == 0 || value > 0x10FFFF || value in 0xD800..0xDFFF) return ""
            return String(Character.toChars(value))
        }
        return namedEntities[name]
    }
}
