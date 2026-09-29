package com.example.pinch.search

import com.example.pinch.model.Recipe
import com.example.pinch.parsing.TextKit
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Typo-tolerant recipe search. Every query word must match something (a word, a prefix,
 * a near-miss spelling, or an abbreviation like "chkn"), and title matches count more than
 * matches in the method. Mirrors the iOS `FuzzySearch`.
 */
class FuzzySearch {
    data class Result(val recipe: Recipe, val score: Double, val snippet: String?)

    enum class Field(val weight: Double) {
        TITLE(3.0), TAGS(2.2), INGREDIENTS(1.6), SUMMARY(1.0), SOURCE(1.0), STEPS(0.7), NOTES(0.7), RAW_TEXT(0.4)
    }

    private class IndexedLine(val text: String, val words: List<String>, val singulars: List<String>)
    private class IndexedRecipe(val updatedAt: Instant, val fields: List<Pair<Field, List<IndexedLine>>>, val foldedTitle: String)

    private val cache = HashMap<String, IndexedRecipe>()

    @Synchronized
    fun search(query: String, recipes: List<Recipe>): List<Result> {
        val rawTokens = TextKit.words(query)
        val tokens = rawTokens.filter { it !in stopWords }.ifEmpty { rawTokens }
        if (tokens.isEmpty()) return emptyList()
        val foldedQuery = TextKit.words(query).joinToString(" ")

        val results = ArrayList<Result>()
        for (recipe in recipes) {
            val indexed = index(recipe)
            var total = 0.0
            var snippet: String? = null
            var bestSnippetScore = 0.0
            var matchedAll = true

            for (token in tokens) {
                var best = 0.0
                var bestField: Field? = null
                var bestLine: String? = null
                val singular = TextKit.singular(token)
                for ((field, lines) in indexed.fields) {
                    for (line in lines) {
                        val s = bestWordScore(token, singular, line) * field.weight
                        if (s > best) {
                            best = s
                            bestField = field
                            bestLine = line.text
                        }
                    }
                }
                if (best == 0.0) {
                    matchedAll = false
                    break
                }
                total += best
                if (bestField != null && bestField != Field.TITLE && best > bestSnippetScore) {
                    bestSnippetScore = best
                    snippet = snippetFor(bestField, bestLine ?: "", token)
                }
            }
            if (!matchedAll) continue

            if (indexed.foldedTitle.contains(foldedQuery)) total += 2.5
            if (indexed.foldedTitle.startsWith(foldedQuery)) total += 1.0
            // Titles that fully match shouldn't show a snippet from elsewhere.
            val titleLines = indexed.fields.first { it.first == Field.TITLE }.second
            val titleOnly = tokens.all { token -> titleLines.any { bestWordScore(token, TextKit.singular(token), it) >= 0.7 } }
            results.add(Result(recipe, total, if (titleOnly) null else snippet))
        }

        return results.sortedWith { a, b ->
            if (abs(a.score - b.score) > 0.0001) b.score.compareTo(a.score) else b.recipe.updatedAt.compareTo(a.recipe.updatedAt)
        }
    }

    private fun index(recipe: Recipe): IndexedRecipe {
        cache[recipe.id]?.let { if (it.updatedAt == recipe.updatedAt) return it }
        fun lines(texts: List<String>) = texts.filter { it.isNotEmpty() }.map { text ->
            val words = TextKit.words(text)
            IndexedLine(text, words, words.map(TextKit::singular))
        }
        val indexed = IndexedRecipe(
            recipe.updatedAt,
            listOf(
                Field.TITLE to lines(listOf(recipe.title)),
                Field.TAGS to lines(recipe.tags),
                Field.INGREDIENTS to lines(recipe.ingredients.map { it.text }),
                Field.SUMMARY to lines(listOf(recipe.summary)),
                Field.SOURCE to lines(listOf(recipe.source)),
                Field.STEPS to lines(recipe.steps.map { it.text }),
                Field.NOTES to lines(listOf(recipe.notes)),
                Field.RAW_TEXT to lines(listOf(recipe.rawText)),
            ),
            TextKit.words(recipe.title).joinToString(" "),
        )
        cache[recipe.id] = indexed
        return indexed
    }

    companion object {
        private val stopWords = setOf("a", "an", "and", "the", "of", "with", "in", "on", "for", "to", "or", "recipe", "recipes")

        private fun bestWordScore(token: String, singular: String, line: IndexedLine): Double {
            var best = 0.0
            for (i in line.words.indices) {
                val s = wordScore(token, singular, line.words[i], line.singulars[i])
                if (s > best) {
                    best = s
                    if (best >= 1) break
                }
            }
            return best
        }

        fun wordScore(token: String, singular: String, word: String, wordSingular: String): Double {
            if (word == token || wordSingular == singular) return 1.0
            val n = token.length
            if (word.startsWith(token)) return if (n >= 2) 0.9 else 0.6
            if (n >= 3 && word.contains(token)) return 0.72
            if (n >= 4) {
                val allowed = if (n <= 5) 1 else 2
                val d = boundedDistance(token, word, allowed)
                if (d != null) return if (d == 1) 0.66 else 0.52
                // Typo while still typing: "chocl" vs "choco…"
                if (word.length > n) {
                    val prefixDistance = boundedDistance(token, word.take(n), 1)
                    if (prefixDistance != null && prefixDistance <= 1) return 0.58
                }
            }
            // Abbreviations like "chkn" → "chicken".
            if (n >= 4 && token.first() == word.firstOrNull() && word.length <= n * 3 && isSubsequence(token, word)) return 0.42
            return 0.0
        }

        /** Damerau–Levenshtein (optimal string alignment) distance, or null if above [limit]. */
        fun boundedDistance(a: String, b: String, limit: Int): Int? {
            if (abs(a.length - b.length) > limit) return null
            if (a.isEmpty()) return if (b.length <= limit) b.length else null
            if (b.isEmpty()) return if (a.length <= limit) a.length else null
            var previousPrevious = IntArray(b.length + 1)
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                var rowMin = current[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var value = min(min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        value = min(value, previousPrevious[j - 2] + 1)
                    }
                    current[j] = value
                    rowMin = min(rowMin, value)
                }
                if (rowMin > limit) return null
                val recycled = previousPrevious
                previousPrevious = previous
                previous = current
                current = recycled
            }
            val distance = previous[b.length]
            return if (distance <= limit) distance else null
        }

        fun isSubsequence(needle: String, haystack: String): Boolean {
            var j = 0
            for (ch in needle) {
                while (j < haystack.length && haystack[j] != ch) j++
                if (j == haystack.length) return false
                j++
            }
            return true
        }

        private fun snippetFor(field: Field, line: String, token: String): String? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            return when (field) {
                Field.INGREDIENTS, Field.TAGS, Field.SOURCE -> trimmed.take(80)
                Field.TITLE -> null
                else -> {
                    // Show the words around the match, not the whole paragraph.
                    val words = trimmed.split(" ").filter { it.isNotEmpty() }
                    val folded = words.map { TextKit.words(it).joinToString("") }
                    val hit = folded.indexOfFirst { it.startsWith(token) || it.contains(token) }.coerceAtLeast(0)
                    val start = max(0, hit - 4)
                    val end = min(words.size, hit + 8)
                    var text = words.subList(start, end).joinToString(" ")
                    if (start > 0) text = "…$text"
                    if (end < words.size) text += "…"
                    text
                }
            }
        }
    }
}
