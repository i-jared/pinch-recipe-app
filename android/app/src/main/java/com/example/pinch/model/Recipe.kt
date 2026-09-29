package com.example.pinch.model

import com.example.pinch.parsing.DurationParser
import com.example.pinch.parsing.IngredientParser
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** One line in a recipe's ingredient list or method. `isHeader` marks a group heading. */
@Serializable
data class RecipeItem(
    val id: String = newItemId(),
    val text: String = "",
    val isHeader: Boolean = false,
) {
    companion object {
        fun newItemId(): String = UUID.randomUUID().toString().replace("-", "").take(10)
    }
}

/**
 * A saved recipe. Its JSON encoding is the on-disk and Google Drive format, shared with the
 * iOS app (see docs/recipe-format.md), so a Drive backup restores on either platform.
 */
@Serializable
data class Recipe(
    val schema: Int = SCHEMA_VERSION,
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val summary: String = "",
    val source: String = "",
    /** The web page a recipe was imported from. Empty for scanned or typed recipes. */
    val sourceUrl: String = "",
    val servings: String = "",
    val prepTime: String = "",
    val cookTime: String = "",
    val totalTime: String = "",
    val ingredients: List<RecipeItem> = emptyList(),
    val steps: List<RecipeItem> = emptyList(),
    val notes: String = "",
    val tags: List<String> = emptyList(),
    val emoji: String = "🍽️",
    val palette: CoverPalette = CoverPalette.JADE,
    val pattern: CoverPattern = CoverPattern.GINGHAM,
    /** File names of scanned pages, in page order. */
    val photos: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant = createdAt,
    @Serializable(with = InstantSerializer::class) val lastCookedAt: Instant? = null,
    /** The text exactly as it was read from the scans, kept for search and re-parsing. */
    val rawText: String = "",
    /** Set when deleted, so the deletion can sync to other devices. */
    @Serializable(with = InstantSerializer::class) val deletedAt: Instant? = null,
) {
    val isDeleted: Boolean get() = deletedAt != null

    val displayTitle: String get() = title.trim().ifEmpty { "Untitled recipe" }

    /** The best single "how long does this take" figure for cards and pills. */
    val displayTime: String?
        get() {
            DurationParser.minutes(totalTime)?.let { return DurationParser.formatMinutes(it) }
            val prep = DurationParser.minutes(prepTime)
            val cook = DurationParser.minutes(cookTime)
            return when {
                prep != null && cook != null -> DurationParser.formatMinutes(prep + cook)
                prep != null -> DurationParser.formatMinutes(prep)
                cook != null -> DurationParser.formatMinutes(cook)
                else -> listOf(totalTime, cookTime, prepTime).firstOrNull { it.isNotBlank() }?.trim()
            }
        }

    val totalMinutes: Int?
        get() {
            DurationParser.minutes(totalTime)?.let { return it }
            val parts = listOfNotNull(DurationParser.minutes(prepTime), DurationParser.minutes(cookTime))
            return if (parts.isEmpty()) null else parts.sum()
        }

    val servingsCount: Double? get() = IngredientParser.leadingNumber(servings)

    /** What the servings count counts: "biscuits" for "12 biscuits", null for a bare "4". */
    val servingsNoun: String?
        get() {
            val words = servings.split(" ").filter { it.isNotEmpty() }
            val index = words.indexOfFirst { IngredientParser.leadingNumber(it) != null }
            if (index < 0) return null
            var rest = words.drop(index + 1)
            if (rest.firstOrNull()?.lowercase() in setOf("-", "–", "to", "or")) rest = rest.drop(2)
            if (rest.firstOrNull()?.all { it.isDigit() || it == '-' || it == '–' } == true) rest = rest.drop(1)
            val noun = rest.joinToString(" ").trim(' ', '.', ',', '(', ')')
            if (noun.isEmpty() || noun.length > 24) return null
            return if (noun.lowercase() == "servings" || noun.lowercase() == "serving") null else noun
        }

    /** "4 servings", or the recipe's own words for yields like "24 cookies". */
    val servingsLabel: String?
        get() {
            val s = servings.trim()
            if (s.isEmpty()) return null
            if (s.all { it.isDigit() || it == '-' || it == '–' || it == ' ' }) return if (s == "1") "1 serving" else "$s servings"
            return s
        }

    val ingredientLines: List<RecipeItem> get() = ingredients.filter { !it.isHeader }
    val stepLines: List<RecipeItem> get() = steps.filter { !it.isHeader }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** Cover colors. Names are shared with iOS. */
@Serializable
enum class CoverPalette {
    @SerialName("tomato") TOMATO,
    @SerialName("jade") JADE,
    @SerialName("butter") BUTTER,
    @SerialName("blueberry") BLUEBERRY,
    @SerialName("plum") PLUM,
    @SerialName("saffron") SAFFRON,
    @SerialName("beet") BEET,
    @SerialName("sky") SKY,
    @SerialName("sage") SAGE,
    @SerialName("cocoa") COCOA;

    val displayName: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
}

/** Tablecloth and tin patterns. Names are shared with iOS. */
@Serializable
enum class CoverPattern {
    @SerialName("gingham") GINGHAM,
    @SerialName("stripes") STRIPES,
    @SerialName("dots") DOTS,
    @SerialName("waves") WAVES,
    @SerialName("tiles") TILES;

    val displayName: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
}

/** ISO-8601 UTC with milliseconds, e.g. 2026-09-27T18:25:43.511Z, matching iOS. */
object InstantSerializer : KSerializer<Instant> {
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(format(value))

    override fun deserialize(decoder: Decoder): Instant = parse(decoder.decodeString())

    fun format(value: Instant): String = formatter.format(value)

    fun parse(text: String): Instant =
        runCatching { Instant.parse(text) }.getOrElse { Instant.ofEpochMilli(text.toDouble().toLong()) }
}
