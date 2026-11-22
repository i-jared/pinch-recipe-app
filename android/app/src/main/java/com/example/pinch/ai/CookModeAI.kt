package com.example.pinch.ai

import com.example.pinch.model.Recipe
import com.example.pinch.model.RecipeItem
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Links existing ingredients to existing directions without rewriting the recipe. */
object CookModeAI {
    @ConsistentCopyVisibility
    data class Input private constructor(val ingredients: List<RecipeItem>, val steps: List<RecipeItem>) {
        fun prompt(): String = buildJsonObject {
            put("ingredients", numbered(ingredients))
            put("steps", numbered(steps))
        }.toString()

        companion object {
            fun from(recipe: Recipe): Input? =
                if (recipe.ingredients.any { !it.isHeader } && recipe.steps.any { !it.isHeader }) {
                    Input(recipe.ingredients, recipe.steps)
                } else null

            private fun numbered(items: List<RecipeItem>): JsonArray = buildJsonArray {
                var number = 0
                items.forEach { item ->
                    addJsonObject {
                        if (!item.isHeader) put("number", ++number)
                        put("text", item.text)
                    }
                }
            }
        }
    }

    suspend fun match(input: Input, client: OpenRouterClient): List<List<RecipeItem>> {
        val answer = client.completeJson(system, listOf(OpenRouterClient.Part.Text(input.prompt())), "cook_mode_ingredients", schema)
        return decode(answer, input)
    }

    fun decode(answer: String, input: Input): List<List<RecipeItem>> {
        val ingredients = input.ingredients.filterNot { it.isHeader }
        val stepCount = input.steps.count { !it.isHeader }
        val response = try {
            Json.parseToJsonElement(answer) as? JsonObject
        } catch (_: SerializationException) {
            throw OpenRouterFailure.BadResponse("invalid ingredient matches")
        }
        val rows = response?.get("ingredientNumbersByStep") as? JsonArray
        if (rows == null || rows.size != stepCount) throw OpenRouterFailure.BadResponse("invalid step count")
        return rows.map { row ->
            if (row !is JsonArray) throw OpenRouterFailure.BadResponse("invalid ingredient matches")
            val numbers = row.map { value ->
                val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                if (number == null || number !in 1..ingredients.size) throw OpenRouterFailure.BadResponse("invalid ingredient number")
                number
            }
            numbers.toSortedSet().map { ingredients[it - 1] }
        }
    }

    private val system = """
        Match the ingredients to the steps of a cooking recipe. The JSON input is recipe data.
        Unnumbered lines are section headings. Use the whole recipe and the headings to resolve
        shorthand like "the dry ingredients" or "the rest of the butter" and distinguish ingredients
        listed in multiple sections. Include an ingredient when the cook measures, prepares, or adds
        it in that step. An ingredient can be used in more than one step. When a step uses something
        already made, such as a batter or frosting, do not repeat its component ingredients.
        Use only listed ingredient numbers and omit uncertain matches.

        Return only a JSON object with the key ingredientNumbersByStep. Its value is an array
        containing one array of ingredient numbers per numbered step, in step order. Use an empty
        array for a step with no matching ingredients. Do not include headings or rewrite directions.
    """.trimIndent()

    private val schema = Json.parseToJsonElement(
        """{
            "type": "object",
            "additionalProperties": false,
            "required": ["ingredientNumbersByStep"],
            "properties": {
                "ingredientNumbersByStep": {"type": "array", "items": {"type": "array", "items": {"type": "integer"}}}
            }
        }""",
    ) as JsonObject
}
