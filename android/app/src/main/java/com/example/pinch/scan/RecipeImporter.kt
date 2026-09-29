package com.example.pinch.scan

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.pinch.ai.AIReading
import com.example.pinch.ai.NotARecipeException
import com.example.pinch.ai.OpenRouterClient
import com.example.pinch.ai.OpenRouterFailure
import com.example.pinch.ai.RecipeAI
import com.example.pinch.data.ImageTools
import com.example.pinch.data.PhotoStore
import com.example.pinch.model.CoverSuggester
import com.example.pinch.model.Recipe
import com.example.pinch.model.RecipeItem
import com.example.pinch.parsing.ParsedRecipe
import com.example.pinch.parsing.RecipeParser
import com.example.pinch.parsing.TextLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Turns scanned pages, web pages or pasted text into a recipe draft ready for review. With an
 * OpenRouter key saved, the AI reads everything; without one, or when it can't be reached, the
 * phone's own reader takes over. Mirrors iOS.
 */
object RecipeImporter {
    private const val TAG = "PinchImport"

    sealed interface Stage {
        /** Finding the words on each page with the phone's text recognition. */
        data class Reading(val page: Int, val total: Int) : Stage
        data class Fetching(val host: String) : Stage
        data class AiReading(val source: AiSource) : Stage
    }

    sealed interface AiSource {
        data class Pages(val count: Int) : AiSource
        data object Website : AiSource
        data object Text : AiSource
    }

    /** Who turned the source into a recipe, shown on the review screen. */
    sealed interface ReadBy {
        data object Phone : ReadBy
        data object Ai : ReadBy
        /** The website's own recipe markup, used as is. */
        data object Website : ReadBy
        /** Nothing to read: the recipe is being typed in. */
        data object Typing : ReadBy
        /** AI reading was tried but didn't work, so the phone's reader filled in. */
        data class Fallback(val reason: String) : ReadBy
    }

    data class Result(val recipe: Recipe, val readBy: ReadBy)

    class NoTextException : Exception(
        "Couldn't find any words in that photo. Try again in brighter light, or type the recipe in."
    )

    class AiFailedNoMarkupException(reason: String) : Exception(
        "AI reading didn't work ($reason), and that page doesn't label its recipe for Pinch to copy. Try again in a moment, or copy the recipe and paste it in."
    )

    // Photos

    suspend fun importPages(
        context: Context,
        uris: List<Uri>,
        photos: PhotoStore,
        ai: OpenRouterClient?,
        onStage: (Stage) -> Unit,
    ): Result = withContext(Dispatchers.Default) {
        val (names, lines) = savePages(context, uris, photos, firstPage = 0, onStage)
        val parsed = RecipeParser.parse(lines)
        var fallbackReason: String? = null
        if (ai != null) {
            withContext(Dispatchers.Main) { onStage(Stage.AiReading(AiSource.Pages(names.size))) }
            try {
                val reading = RecipeAI(ai).readPages(aiJpegs(photos, names), parsed.rawText)
                reading.parsed.rawText = parsed.rawText
                return@withContext Result(makeRecipe(reading).copy(photos = names), ReadBy.Ai)
            } catch (e: CancellationException) {
                names.forEach(photos::delete)
                throw e
            } catch (e: Exception) {
                fallbackReason = if (e is NotARecipeException) {
                    "the AI didn't see a recipe in ${if (names.size == 1) "that photo" else "those photos"}"
                } else {
                    reason(e)
                }
                Log.e(TAG, "AI reading failed", e)
            }
        }
        // Handwriting the phone can't read may still be readable by the AI, so this check
        // only comes once the AI has had its turn.
        if (parsed.rawText.isBlank()) {
            names.forEach(photos::delete)
            throw NoTextException()
        }
        Result(makeRecipe(parsed).copy(photos = names), fallbackReason?.let { ReadBy.Fallback(it) } ?: ReadBy.Phone)
    }

    /** Saves each page and reads its words, deleting the saved pages if anything goes wrong. */
    private suspend fun savePages(
        context: Context,
        uris: List<Uri>,
        photos: PhotoStore,
        firstPage: Int,
        onStage: (Stage) -> Unit,
    ): Pair<List<String>, List<TextLine>> {
        val names = ArrayList<String>()
        val lines = ArrayList<TextLine>()
        try {
            for ((index, uri) in uris.withIndex()) {
                withContext(Dispatchers.Main) { onStage(Stage.Reading(index + 1, uris.size)) }
                // Recorded inside the save, so a cancel right after it still cleans the file up.
                val name = withContext(Dispatchers.IO) { photos.save(context, uri).also { names.add(it) } }
                val bitmap = withContext(Dispatchers.IO) { photos.loadBitmap(name) } ?: continue
                lines += TextRecognizer.recognize(bitmap, firstPage + index)
                bitmap.recycle()
            }
        } catch (e: Exception) {
            names.forEach(photos::delete)
            throw e
        }
        return names to lines
    }

    /** Reads more pages and appends what they contain to an existing draft. */
    suspend fun appendPages(
        context: Context,
        uris: List<Uri>,
        recipe: Recipe,
        photos: PhotoStore,
        ai: OpenRouterClient?,
        onStage: (Stage) -> Unit,
    ): Recipe = withContext(Dispatchers.Default) {
        val section = when {
            recipe.steps.isNotEmpty() -> RecipeParser.Section.STEPS
            recipe.ingredients.isNotEmpty() -> RecipeParser.Section.INGREDIENTS
            else -> RecipeParser.Section.PREAMBLE
        }
        val (names, lines) = savePages(context, uris, photos, firstPage = recipe.photos.size, onStage)
        val parsed = RecipeParser.parse(lines, continuing = section)
        val rawText = listOf(recipe.rawText, parsed.rawText).filter { it.isNotEmpty() }.joinToString("\n\n")

        if (ai != null) {
            withContext(Dispatchers.Main) { onStage(Stage.AiReading(AiSource.Pages(names.size))) }
            try {
                val more = RecipeAI(ai).readMorePages(aiJpegs(photos, names), parsed.rawText, recipe).parsed
                return@withContext fillGaps(
                    recipe.copy(
                        photos = recipe.photos + names,
                        ingredients = recipe.ingredients + more.ingredients,
                        steps = recipe.steps + more.steps,
                        title = recipe.title.ifEmpty { more.title },
                        summary = recipe.summary.ifEmpty { more.summary },
                        rawText = rawText,
                    ),
                    more,
                )
            } catch (e: CancellationException) {
                names.forEach(photos::delete)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AI reading of extra pages failed", e)
            }
        }

        // Unlabeled text on a later page is most likely more method.
        val extraSteps = parsed.summary.split("\n").filter { it.isNotBlank() }.map { RecipeItem(text = it) }
        fillGaps(
            recipe.copy(
                photos = recipe.photos + names,
                ingredients = recipe.ingredients + parsed.ingredients,
                steps = recipe.steps + parsed.steps + extraSteps,
                rawText = rawText,
            ),
            parsed,
        )
    }

    private fun fillGaps(recipe: Recipe, parsed: ParsedRecipe) = recipe.copy(
        notes = listOf(recipe.notes, parsed.notes).filter { it.isNotEmpty() }.joinToString("\n"),
        servings = recipe.servings.ifEmpty { parsed.servings },
        prepTime = recipe.prepTime.ifEmpty { parsed.prepTime },
        cookTime = recipe.cookTime.ifEmpty { parsed.cookTime },
        totalTime = recipe.totalTime.ifEmpty { parsed.totalTime },
        source = recipe.source.ifEmpty { parsed.source },
    )

    // Text

    suspend fun importText(text: String, ai: OpenRouterClient?, onStage: (Stage) -> Unit): Result {
        val parsed = RecipeParser.parse(text)
        var fallbackReason: String? = null
        if (ai != null) {
            onStage(Stage.AiReading(AiSource.Text))
            try {
                val reading = RecipeAI(ai).readText(text)
                reading.parsed.rawText = parsed.rawText
                return Result(makeRecipe(reading), ReadBy.Ai)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fallbackReason = if (e is NotARecipeException) "the AI didn't see a recipe in that text" else reason(e)
                Log.e(TAG, "AI reading of text failed", e)
            }
        }
        return Result(makeRecipe(parsed), fallbackReason?.let { ReadBy.Fallback(it) } ?: ReadBy.Phone)
    }

    // Websites

    suspend fun importUrl(
        context: Context,
        activity: () -> Activity?,
        url: String,
        userAgent: String,
        ai: OpenRouterClient?,
        forceWebView: Boolean = false,
        onStage: (Stage) -> Unit,
    ): Result {
        onStage(Stage.Fetching(WebRecipeImporter.displayHost(url)))
        val page = try {
            if (forceWebView) throw WebImportException.Blocked
            val (html, finalUrl) = WebRecipeImporter.fetch(url, userAgent)
            var fetched = parse(html, finalUrl)
            if (fetched.structured == null && fetched.text.length < 500) {
                // Probably built with JavaScript; a browser engine sees the finished page.
                try {
                    val (rendered, renderedUrl) = WebViewPageLoader.load(context, activity(), url)
                    fetched = parse(rendered, renderedUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "Web view didn't help", e)
                }
            }
            fetched
        } catch (e: WebImportException.Blocked) {
            // A bot check turned the plain request away. A real browser engine usually gets in.
            Log.i(TAG, "Plain fetch was blocked; trying a WebView")
            val (rendered, renderedUrl) = WebViewPageLoader.load(context, activity(), url)
            parse(rendered, renderedUrl)
        }
        Log.i(TAG, "Page has markup: ${page.structured != null}, text: ${page.text.length} characters")

        var fallbackReason: String? = null
        if (ai != null) {
            onStage(Stage.AiReading(AiSource.Website))
            try {
                val reading = RecipeAI(ai).readWebPage(page)
                reading.parsed.rawText = page.structured?.promptText ?: page.text.take(8000)
                return Result(finishWebRecipe(makeRecipe(reading), page), ReadBy.Ai)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotARecipeException) {
                if (page.structured == null) throw WebImportException.NoRecipe
                fallbackReason = "the AI didn't find a recipe on the page"
            } catch (e: Exception) {
                fallbackReason = reason(e)
                Log.e(TAG, "AI reading of web page failed", e)
            }
        }
        val structured = page.structured
            ?: throw fallbackReason?.let { AiFailedNoMarkupException(it) } ?: WebImportException.NeedsAI
        return Result(finishWebRecipe(makeRecipe(structured.parsed), page), fallbackReason?.let { ReadBy.Fallback(it) } ?: ReadBy.Website)
    }

    private suspend fun parse(html: String, url: String) = withContext(Dispatchers.Default) { WebRecipeImporter.parse(html, url) }

    private fun finishWebRecipe(recipe: Recipe, page: ScrapedPage) = recipe.copy(
        sourceUrl = page.url,
        source = page.siteName.ifEmpty { recipe.source },
        title = recipe.title.ifEmpty { page.title },
    )

    // Building recipes

    fun makeRecipe(parsed: ParsedRecipe): Recipe {
        val base = Recipe(
            title = parsed.title,
            summary = parsed.summary,
            source = parsed.source,
            servings = parsed.servings,
            prepTime = parsed.prepTime,
            cookTime = parsed.cookTime,
            totalTime = parsed.totalTime,
            ingredients = parsed.ingredients.toList(),
            steps = parsed.steps.toList(),
            notes = parsed.notes,
            rawText = parsed.rawText,
        )
        val ingredientTexts = parsed.ingredients.map { it.text }
        val suggestion = CoverSuggester.suggest(parsed.title, ingredientTexts)
        return base.copy(
            emoji = suggestion.emoji,
            palette = suggestion.palette,
            pattern = CoverSuggester.pattern(base.id),
            tags = CoverSuggester.suggestTags(parsed.title, ingredientTexts),
        )
    }

    /** Like [makeRecipe], with the AI's emoji and tags where it chose them. */
    fun makeRecipe(reading: AIReading): Recipe {
        var recipe = makeRecipe(reading.parsed)
        reading.emoji?.let { emoji ->
            recipe = recipe.copy(emoji = emoji, palette = CoverSuggester.palette(emoji) ?: recipe.palette)
        }
        if (reading.tags.isNotEmpty()) recipe = recipe.copy(tags = reading.tags)
        return recipe
    }

    /** Page photos sized for the AI: big enough for small print, small enough to send quickly. */
    private suspend fun aiJpegs(photos: PhotoStore, names: List<String>): List<ByteArray> = withContext(Dispatchers.IO) {
        names.mapNotNull { name ->
            val bitmap = photos.loadBitmap(name)?.let { ImageTools.fitWithin(it, 2000) } ?: return@mapNotNull null
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }
    }

    /** A short, lowercase reason for the review banner. */
    private fun reason(e: Exception): String = when (e) {
        OpenRouterFailure.Offline -> "you're offline"
        OpenRouterFailure.InvalidKey -> "OpenRouter didn't accept your key"
        OpenRouterFailure.NoCredits -> "your OpenRouter account is out of credit"
        OpenRouterFailure.RateLimited -> "OpenRouter was busy"
        OpenRouterFailure.TimedOut -> "the AI took too long"
        is OpenRouterFailure.Rejected, is OpenRouterFailure.Unavailable -> "OpenRouter couldn't run the model"
        is OpenRouterFailure.BadResponse -> "the AI's answer didn't make sense"
        else -> (e.message ?: "something went wrong").replaceFirstChar { it.lowercase() }.trimEnd('.')
    }
}
