package com.example.pinch.data

import com.example.pinch.model.Recipe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/** JSON shared with iOS: same keys, ISO-8601 dates with milliseconds, unknown keys ignored. */
@OptIn(ExperimentalSerializationApi::class)
object PinchJson {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encode(recipe: Recipe): String = json.encodeToString(Recipe.serializer(), recipe)

    fun decode(text: String): Recipe {
        val recipe = json.decodeFromString(Recipe.serializer(), text)
        return recipe.copy(id = recipe.id.lowercase())
    }
}
