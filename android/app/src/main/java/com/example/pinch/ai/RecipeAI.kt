package com.example.pinch.ai

import com.example.pinch.model.Recipe
import com.example.pinch.model.RecipeItem
import com.example.pinch.parsing.DurationParser
import com.example.pinch.parsing.ParsedRecipe
import com.example.pinch.scan.ScrapedPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What the AI read, ready to become a recipe. */
data class AIReading(val parsed: ParsedRecipe, val emoji: String?, val tags: List<String>)

class NotARecipeException : Exception("The AI couldn't find a recipe there.")

/**
 * Reads recipes from photos, web pages and pasted text with the model on OpenRouter, and
 * turns its answer into Pinch's recipe format. Mirrors iOS's `RecipeAI.swift`.
 */
class RecipeAI(private val client: OpenRouterClient) {
    /** Reads photographed pages. [ocrText] is what the phone's text recognition found, sent to help with small print. */
    suspend fun readPages(jpegs: List<ByteArray>, ocrText: String): AIReading = read(pagesPrompt(jpegs.size, ocrText), jpegs)

    /** Reads extra pages for a recipe that's already underway and returns only what they add. */
    suspend fun readMorePages(jpegs: List<ByteArray>, ocrText: String, soFar: Recipe): AIReading =
        read(morePagesPrompt(jpegs.size, ocrText, soFar), jpegs)

    suspend fun readWebPage(page: ScrapedPage): AIReading = read(webPrompt(page), emptyList())

    suspend fun readText(text: String): AIReading = read(textPrompt(text), emptyList())

    private suspend fun read(prompt: String, images: List<ByteArray>): AIReading {
        val parts = listOf(OpenRouterClient.Part.Text(prompt)) + images.map { OpenRouterClient.Part.Jpeg(it) }
        return decode(client.completeJson(SYSTEM_PROMPT, parts, "recipe", SCHEMA))
    }

    companion object {
        val TAG_CHOICES = listOf(
            "Breakfast", "Lunch", "Dinner", "Dessert", "Baking", "Snack", "Drinks", "Soup", "Salad",
            "Pasta", "Chicken", "Seafood", "Vegetarian", "Sides", "Holidays",
        )

        val SYSTEM_PROMPT = """
            You are the recipe reader in Pinch, a recipe box app. You turn photos of recipe cards and cookbook pages, text from web pages, or pasted text into one clean, structured recipe.

            Rules:
            - Copy the recipe faithfully. Keep every ingredient, amount, unit, temperature, time and step exactly as written. Never invent, estimate, convert or leave anything out.
            - Fix obvious reading mistakes (such as "f1our" for "flour" or "3S0°F" for "350°F") and rejoin lines that were split across columns or pages. Read handwriting carefully.
            - ingredients: one ingredient per item, as written, such as "2 cups all-purpose flour, sifted". A group heading such as "For the frosting" is its own item with isHeader true and no colon.
            - steps: one step per item, in order, without step numbers or bullets. Keep the recipe's wording. A heading such as "Make the sauce" is its own item with isHeader true.
            - title: the recipe's name in title case.
            - summary: the recipe's own short introduction or headnote, at most three sentences. Empty if there isn't one. Never write your own.
            - servings: the yield as written, such as "4", "6 to 8" or "24 cookies".
            - prepTime, cookTime, totalTime: short forms such as "15 min" or "1 hr 30 min". Empty when not stated. Don't work out a total that isn't given.
            - notes: tips, variations, storage or make-ahead notes from the recipe, one per line. Empty if none.
            - tags: up to three that fit, chosen from: ${TAG_CHOICES.joinToString(", ")}.
            - emoji: one food emoji that fits the dish.
            - source: the cookbook, website, magazine or person the recipe is credited to, if shown. Otherwise empty.
            - If there are several recipes, read the main one. Ignore ads, navigation, comments, reviews, author bios, nutrition facts and "jump to recipe" links.
            - Keep the recipe's language.
            - If there's no recipe at all, set isRecipe to false and leave everything else empty.

            Answer with only a JSON object with these keys: isRecipe (boolean), title, summary, servings, prepTime, cookTime, totalTime (strings), ingredients and steps (arrays of objects with text (string) and isHeader (boolean)), notes (string), tags (array of strings), emoji, source (strings).
        """.trimIndent()

        private val REQUIRED = listOf(
            "isRecipe", "title", "summary", "servings", "prepTime", "cookTime", "totalTime",
            "ingredients", "steps", "notes", "tags", "emoji", "source",
        )

        val SCHEMA: JsonObject = buildJsonObject {
            val item = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                putJsonArray("required") { add("text"); add("isHeader") }
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string") }
                    putJsonObject("isHeader") {
                        put("type", "boolean")
                        put("description", "True for a group heading such as 'For the sauce'.")
                    }
                }
            }
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { REQUIRED.forEach { add(it) } }
            putJsonObject("properties") {
                putJsonObject("isRecipe") {
                    put("type", "boolean")
                    put("description", "False when the content holds no recipe.")
                }
                for (key in listOf("title", "summary", "servings", "prepTime", "cookTime", "totalTime")) {
                    putJsonObject(key) { put("type", "string") }
                }
                putJsonObject("ingredients") {
                    put("type", "array")
                    put("items", item)
                }
                putJsonObject("steps") {
                    put("type", "array")
                    put("items", item)
                }
                putJsonObject("notes") { put("type", "string") }
                putJsonObject("tags") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("emoji") { put("type", "string") }
                putJsonObject("source") { put("type", "string") }
            }
        }

        // Prompts

        fun pagesPrompt(pageCount: Int, ocrText: String): String {
            var prompt = if (pageCount == 1) "Here's a photo of a recipe."
            else "Here are $pageCount photos of one recipe, in page order. The recipe continues from one page to the next."
            val hint = ocrText.trim()
            if (hint.isNotEmpty()) {
                prompt += "\n\nThe phone's text recognition read the following from the ${if (pageCount == 1) "photo" else "photos"}. " +
                    "It can have mistakes and its line order can be jumbled, so trust the photos where they disagree:\n---\n${hint.take(20_000)}\n---"
            }
            return prompt
        }

        fun morePagesPrompt(pageCount: Int, ocrText: String, soFar: Recipe): String {
            fun listed(items: List<RecipeItem>) = items.joinToString("\n") { if (it.isHeader) "## ${it.text}" else "- ${it.text}" }
            val summary = buildString {
                append("Title: ${soFar.title}\n")
                if (soFar.ingredients.isNotEmpty()) append("Ingredients so far:\n${listed(soFar.ingredients)}\n")
                if (soFar.steps.isNotEmpty()) append("Steps so far:\n${listed(soFar.steps)}\n")
            }
            val one = pageCount == 1
            var prompt = "${if (one) "This photo is another page" else "These $pageCount photos are more pages"} of a recipe that's already been read. Here's the recipe so far:\n---\n$summary---\n\n" +
                "Return only what's new on ${if (one) "this page" else "these pages"}: the ingredients, steps and notes it adds, continuing where the recipe left off. " +
                "Don't repeat anything above. Leave title, summary, servings and times empty unless they appear only on the new ${if (one) "page" else "pages"}."
            val hint = ocrText.trim()
            if (hint.isNotEmpty()) {
                prompt += "\n\nThe phone's text recognition read this from the new ${if (one) "page" else "pages"} (it can have mistakes):\n---\n${hint.take(20_000)}\n---"
            }
            return prompt
        }

        fun webPrompt(page: ScrapedPage): String = buildString {
            append("This comes from a web page.\nAddress: ${page.url}\nPage title: ${page.title}\nSite: ${page.siteName}")
            page.structured?.let {
                append("\n\nThe page's recipe markup (schema.org) says:\n---\n${it.promptText}\n---\n")
                append("Use the markup as the main source. Use the page text below to add group headings for ingredients and steps, the introduction, and notes, and to fix anything the markup garbled.")
            }
            append("\n\nText from the page:\n---\n${page.text}\n---")
        }

        fun textPrompt(text: String): String = "Here's a recipe someone pasted in:\n---\n${text.take(60_000)}\n---"

        // Decoding

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Turns the model's JSON into a parsed recipe, cleaning up anything it got slightly wrong. */
        fun decode(content: String): AIReading {
            val root = try {
                json.parseToJsonElement(OpenRouterClient.extractJson(content)).jsonObject
            } catch (e: Exception) {
                throw OpenRouterFailure.BadResponse("not JSON")
            }
            if ((root["isRecipe"] as? JsonPrimitive)?.booleanOrNull == false) throw NotARecipeException()

            fun string(key: String) = root[key].asText().trim()

            fun items(key: String, isStep: Boolean): MutableList<RecipeItem> {
                val list = root[key] as? JsonArray ?: return mutableListOf()
                return list.mapNotNull { element ->
                    var text: String
                    var isHeader = false
                    when (element) {
                        is JsonPrimitive -> text = element.contentOrNull.orEmpty()
                        is JsonObject -> {
                            text = (element["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            isHeader = (element["isHeader"] as? JsonPrimitive)?.booleanOrNull ?: false
                        }
                        else -> return@mapNotNull null
                    }
                    text = cleanedItemText(text, isStep)
                    if (text.endsWith(":")) {
                        text = text.dropLast(1).trim()
                        if (!isStep || text.length < 40) isHeader = true
                    }
                    if (text.isEmpty()) null else RecipeItem(text = text, isHeader = isHeader)
                }.toMutableList()
            }

            val parsed = ParsedRecipe(
                title = string("title"),
                summary = string("summary"),
                source = string("source"),
                servings = string("servings"),
                prepTime = DurationParser.normalized(string("prepTime")),
                cookTime = DurationParser.normalized(string("cookTime")),
                totalTime = DurationParser.normalized(string("totalTime")),
                ingredients = items("ingredients", isStep = false),
                steps = items("steps", isStep = true),
                notes = string("notes"),
            )
            val seen = HashSet<String>()
            val tags = (root["tags"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                .filter { it.isNotEmpty() && it.length <= 24 && seen.add(it.lowercase()) }
                .take(3)
            return AIReading(parsed, validEmoji(string("emoji")), tags)
        }

        private val bullets = Regex("^[•·▪▢☐□○◦‣*\\-–—]+\\s*")
        private val stepNumber = Regex("^(?i:step\\s*)?\\d{1,2}\\s*[.):]\\s+")
        private val stepWord = Regex("^(?i:step)\\s*\\d{1,2}\\s+")

        /** Drops bullets, checkboxes and step numbers the model sometimes keeps from the page. */
        fun cleanedItemText(raw: String, isStep: Boolean): String {
            var text = raw.replace(Regex("\\s+"), " ").trim()
            text = text.replace(bullets, "")
            if (isStep) {
                text = text.replace(stepNumber, "")
                text = text.replace(stepWord, "")
            }
            return text.trim()
        }

        /** One emoji, or null if the model sent words or several. */
        fun validEmoji(text: String): String? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val breaks = java.text.BreakIterator.getCharacterInstance()
            breaks.setText(trimmed)
            breaks.first()
            if (breaks.next() != trimmed.length) return null
            val first = trimmed.codePointAt(0)
            val emojiLike = first >= 0x2190 && Character.getType(first) == Character.OTHER_SYMBOL.toInt()
            return if (emojiLike) trimmed else null
        }
    }
}
