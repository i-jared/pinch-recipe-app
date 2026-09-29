package com.example.pinch

import com.example.pinch.data.PinchJson
import com.example.pinch.model.CoverPalette
import com.example.pinch.model.CoverPattern
import com.example.pinch.model.CoverSuggester
import com.example.pinch.model.Recipe
import com.example.pinch.model.RecipeItem
import com.example.pinch.model.SampleRecipes
import com.example.pinch.parsing.Box
import com.example.pinch.parsing.DurationParser
import com.example.pinch.parsing.IngredientParser
import com.example.pinch.parsing.LineLayout
import com.example.pinch.parsing.OcrFragment
import com.example.pinch.parsing.RecipeParser
import com.example.pinch.parsing.TextLine
import com.example.pinch.search.FuzzySearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Mirrors the iOS test suite case for case, so both parsers stay in lockstep. */
class RecipeParserTest {
    @Test
    fun pastedRecipeWithHeaders() {
        val text = """
            Grandma's Banana Bread
            The best way to use up spotty bananas.
            Serves 8 | Prep 15 min | Bake 1 hr

            Ingredients
            3 ripe bananas, mashed
            1/3 cup melted butter
            3/4 cup sugar
            1 large egg, beaten
            1 tsp vanilla
            1 1/2 cups all-purpose flour
            Pinch of salt

            Directions
            1. Preheat the oven to 350°F and butter a loaf pan.
            2. Mix the butter into the mashed bananas, then stir in the sugar, egg and vanilla.
            3. Mix in the flour and salt. Pour into the pan and bake for 55-60 minutes.

            Notes
            Freezes beautifully for up to 3 months.
        """.trimIndent()
        val recipe = RecipeParser.parse(text)
        assertEquals("Grandma's Banana Bread", recipe.title)
        assertEquals("The best way to use up spotty bananas.", recipe.summary)
        assertEquals("8", recipe.servings)
        assertEquals("15 min", recipe.prepTime)
        assertEquals("1 hr", recipe.cookTime)
        assertEquals(
            listOf(
                "3 ripe bananas, mashed", "1/3 cup melted butter", "3/4 cup sugar", "1 large egg, beaten",
                "1 tsp vanilla", "1 1/2 cups all-purpose flour", "Pinch of salt",
            ),
            recipe.ingredients.map { it.text },
        )
        assertEquals(
            listOf(
                "Preheat the oven to 350°F and butter a loaf pan.",
                "Mix the butter into the mashed bananas, then stir in the sugar, egg and vanilla.",
                "Mix in the flour and salt. Pour into the pan and bake for 55-60 minutes.",
            ),
            recipe.steps.map { it.text },
        )
        assertEquals("Freezes beautifully for up to 3 months.", recipe.notes)
    }

    @Test
    fun recipeCardWithoutHeaders() {
        val text = """
            Aunt May's Lemon Bars
            1 cup butter, softened
            1/2 cup powdered sugar
            2 cups flour
            4 eggs
            2 cups sugar
            1/3 cup lemon juice
            Cream the butter and powdered sugar, then mix in the flour and press into a 9x13 pan.
            Bake at 350 for 20 minutes.
            Whisk the eggs, sugar and lemon juice and pour over the hot crust. Bake 25 minutes more.
        """.trimIndent()
        val recipe = RecipeParser.parse(text)
        assertEquals("Aunt May's Lemon Bars", recipe.title)
        assertEquals(6, recipe.ingredients.size)
        assertEquals(3, recipe.steps.size)
        assertTrue(recipe.steps[0].text.startsWith("Cream the butter"))
    }

    @Test
    fun subheadersAndShoutingCard() {
        val text = """
            CARROT CAKE
            FOR THE CAKE:
            2 CUPS FLOUR
            2 TSP CINNAMON
            3 CUPS GRATED CARROTS
            FOR THE FROSTING:
            8 OZ CREAM CHEESE
            1/2 CUP BUTTER
            DIRECTIONS
            MIX EVERYTHING FOR THE CAKE AND BAKE 30 MINUTES.
            BEAT THE FROSTING UNTIL FLUFFY AND SPREAD OVER THE COOLED CAKE.
        """.trimIndent()
        val recipe = RecipeParser.parse(text)
        assertEquals("Carrot Cake", recipe.title)
        assertEquals(listOf("For the cake", "For the frosting"), recipe.ingredients.filter { it.isHeader }.map { it.text })
        assertEquals("2 cups flour", recipe.ingredients.first { !it.isHeader }.text)
        assertEquals(2, recipe.steps.size)
        assertEquals("Mix everything for the cake and bake 30 minutes.", recipe.steps.first().text)
    }

    @Test
    fun inlineHeaderAndBullets() {
        val text = """
            Quick Guacamole
            Ingredients:
            • 2 ripe avocados
            • 1 lime, juiced
            • ½ tsp salt
            Method:
            - Mash the avocados with a fork.
            - Stir in the lime juice and salt.
        """.trimIndent()
        val recipe = RecipeParser.parse(text)
        assertEquals(listOf("2 ripe avocados", "1 lime, juiced", "½ tsp salt"), recipe.ingredients.map { it.text })
        assertEquals(listOf("Mash the avocados with a fork.", "Stir in the lime juice and salt."), recipe.steps.map { it.text })
    }

    @Test
    fun metadataNotConfusedWithSteps() {
        val text = """
            Roast Chicken
            Ingredients
            1 whole chicken
            2 tbsp olive oil
            Instructions
            Cook for 10 minutes until the skin is golden.
            Bake 45 minutes.
        """.trimIndent()
        val recipe = RecipeParser.parse(text)
        assertEquals("", recipe.cookTime)
        assertEquals(2, recipe.steps.size)
    }

    @Test
    fun wrappedStepLinesFromPlainText() {
        val text = """
            Simple Rice
            Directions
            Rinse the rice until the water runs clear, then add it to a pot with
            the water and salt.
            Simmer covered for 18 minutes.
        """.trimIndent()
        assertEquals(
            listOf(
                "Rinse the rice until the water runs clear, then add it to a pot with the water and salt.",
                "Simmer covered for 18 minutes.",
            ),
            RecipeParser.parse(text).steps.map { it.text },
        )
    }

    @Test
    fun continuationPageAppendsToSteps() {
        val lines = RecipeParser.linesFromPlainText("4. Fold in the berries.\n5. Bake for 40 minutes.")
        val recipe = RecipeParser.parse(lines, continuing = RecipeParser.Section.STEPS)
        assertEquals("", recipe.title)
        assertEquals(listOf("Fold in the berries.", "Bake for 40 minutes."), recipe.steps.map { it.text })
    }

    @Test
    fun titleFromLargestTextWithGeometry() {
        fun line(text: String, y: Double, h: Double) = TextLine(text, Box(0.1, y, 0.6, h))
        val recipe = RecipeParser.parse(
            listOf(
                line("DESSERTS", 0.03, 0.015),
                line("Peach Cobbler", 0.08, 0.05),
                line("Serves 6", 0.15, 0.02),
                line("4 cups sliced peaches", 0.2, 0.02),
                line("1 cup sugar", 0.23, 0.02),
                line("1 cup flour", 0.26, 0.02),
            )
        )
        assertEquals("Peach Cobbler", recipe.title)
        assertEquals("6", recipe.servings)
        assertEquals(3, recipe.ingredients.size)
        assertFalse(recipe.summary.contains("DESSERTS"))
    }

    @Test
    fun pipesReadAsLettersStillSplitMetadata() {
        val meta = RecipeParser.extractMetadata("Makes 12 biscuits I Prep 15 min l Bake 14 min", allowBareCookTime = true)
        assertEquals("12 biscuits", meta.servings)
        assertEquals("15 min", meta.prep)
        assertEquals("14 min", meta.cook)
        assertEquals("", meta.remainder)
    }

    @Test
    fun pageTurnMarkersAreDropped() {
        val text = "Soup\nDirections\n1. Chop the onions.\n— continued on next page —"
        assertEquals(listOf("Chop the onions."), RecipeParser.parse(text).steps.map { it.text })
    }

    @Test
    fun hyphenatedLineJoin() {
        assertEquals("Stir until combined.", RecipeParser.join("Stir until com-", "bined."))
        assertEquals("2 cups all-purpose flour", RecipeParser.join("2 cups all-", "purpose flour"))
        assertEquals("Add the onions.", RecipeParser.join("Add the", "onions."))
    }

    @Test
    fun splitsVeryLongParagraphIntoSteps() {
        val long = "Stir the pot gently and keep the heat low so nothing catches. ".repeat(8).trim()
        val parts = RecipeParser.splitLongStep(long)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 240 })
    }
}

class LineLayoutTest {
    private fun frag(text: String, x: Double, y: Double, w: Double, h: Double = 0.02) = OcrFragment(text, Box(x, y, w, h))

    @Test
    fun twoColumnCardReadsLeftColumnFirst() {
        val fragments = listOf(
            frag("Summer Pasta", 0.1, 0.05, 0.8, 0.05),
            frag("Ingredients", 0.05, 0.15, 0.3),
            frag("Directions", 0.55, 0.15, 0.3),
            frag("1 lb pasta", 0.05, 0.19, 0.3),
            frag("Boil the pasta until", 0.55, 0.19, 0.4),
            frag("2 cups tomatoes", 0.05, 0.23, 0.3),
            frag("tender, then drain.", 0.55, 0.23, 0.3),
            frag("1 cup basil", 0.05, 0.27, 0.3),
            frag("Toss with the rest.", 0.55, 0.27, 0.35),
        )
        val lines = LineLayout.lines(fragments, 0)
        assertEquals(
            listOf(
                "Summer Pasta", "Ingredients", "1 lb pasta", "2 cups tomatoes", "1 cup basil",
                "Directions", "Boil the pasta until", "tender, then drain.", "Toss with the rest.",
            ),
            lines.map { it.text },
        )
        val recipe = RecipeParser.parse(lines)
        assertEquals(3, recipe.ingredients.size)
        assertEquals(listOf("Boil the pasta until tender, then drain.", "Toss with the rest."), recipe.steps.map { it.text })
    }

    @Test
    fun quantityColumnIsReadAsTable() {
        val fragments = listOf(
            frag("Flour", 0.1, 0.1, 0.2), frag("2 cups", 0.7, 0.1, 0.15),
            frag("Sugar", 0.1, 0.14, 0.2), frag("1 cup", 0.7, 0.14, 0.15),
            frag("Butter", 0.1, 0.18, 0.2), frag("8 tbsp", 0.7, 0.18, 0.15),
        )
        assertEquals(listOf("2 cups Flour", "1 cup Sugar", "8 tbsp Butter"), LineLayout.lines(fragments, 0).map { it.text })
    }

    @Test
    fun sameRowFragmentsMerge() {
        val fragments = listOf(frag("Preheat the oven", 0.1, 0.1, 0.4), frag("to 400°F.", 0.51, 0.1, 0.2))
        assertEquals(listOf("Preheat the oven to 400°F."), LineLayout.lines(fragments, 0).map { it.text })
    }
}

class IngredientParserTest {
    @Test
    fun quantities() {
        assertEquals(2.0, IngredientParser.parse("2 cups flour").quantity!!.low, 1e-9)
        assertEquals(1.5, IngredientParser.parse("1 1/2 cups milk").quantity!!.low, 1e-9)
        assertEquals(1.5, IngredientParser.parse("1½ tsp salt").quantity!!.low, 1e-9)
        assertEquals(0.75, IngredientParser.parse("¾ cup sugar").quantity!!.low, 1e-9)
        assertEquals(1.5, IngredientParser.parse("1-1/2 lb beef").quantity!!.low, 1e-9)
        assertEquals(0.5, IngredientParser.parse("0.5 kg potatoes").quantity!!.low, 1e-9)
        val range = IngredientParser.parse("2-3 cloves garlic").quantity!!
        assertEquals(2.0, range.low, 1e-9)
        assertEquals(3.0, range.high!!, 1e-9)
        assertNull(IngredientParser.parse("Salt and pepper to taste").quantity)
        assertNull(IngredientParser.parse("One of my favorites").quantity)
        assertEquals(1.0, IngredientParser.parse("a pinch of nutmeg").quantity!!.low, 1e-9)
    }

    @Test
    fun units() {
        assertEquals("tbsp.", IngredientParser.parse("2 tbsp. butter").unit)
        assertEquals("can", IngredientParser.parse("1 (14 oz) can tomatoes").unit)
        assertNull(IngredientParser.parse("3 large eggs").unit)
        assertEquals("g", IngredientParser.parse("200g dark chocolate").unit)
    }

    @Test
    fun scaling() {
        assertEquals("3 cups flour", IngredientParser.scale("1 1/2 cups flour", 2.0))
        assertEquals("2 cups sugar", IngredientParser.scale("1 cup sugar", 2.0))
        assertEquals("1 cup sugar", IngredientParser.scale("2 cups sugar", 0.5))
        assertEquals("3 eggs", IngredientParser.scale("1 egg", 3.0))
        assertEquals("2 large eggs, beaten", IngredientParser.scale("1 large egg, beaten", 2.0))
        assertEquals("1½ cups milk", IngredientParser.scale("¾ cup milk", 2.0))
        assertEquals("4-6 cloves garlic", IngredientParser.scale("2-3 cloves garlic", 2.0))
        assertEquals("375 g butter", IngredientParser.scale("250 g butter", 1.5))
        assertEquals("1/3 cup oil", IngredientParser.scale("1/3 cup oil", 1.0))
        assertEquals("Salt to taste", IngredientParser.scale("Salt to taste", 2.0))
        assertEquals("2 onions, diced", IngredientParser.scale("1 onion, diced", 2.0))
    }

    @Test
    fun scalingRoundsLikeACook() {
        val factor = 7.0 / 6.0
        assertEquals("1 large egg", IngredientParser.scale("1 large egg", factor))
        assertEquals("2½ slices bread", IngredientParser.scale("2 slices bread", factor))
        assertEquals("1¼ lbs ground beef", IngredientParser.scale("1 lb ground beef", factor))
        assertEquals("⅓ cup milk", IngredientParser.scale("1/3 cup milk", factor))
        assertEquals("1⅛ tsp salt", IngredientParser.scale("1 tsp salt", factor))
        assertEquals("½ egg", IngredientParser.scale("1 egg", 0.25))
        assertEquals("5 eggs", IngredientParser.scale("3 eggs", 1.5))
    }

    @Test
    fun format() {
        assertEquals("½", IngredientParser.format(0.5, false))
        assertEquals("1⅓", IngredientParser.format(1.333, false))
        assertEquals("2", IngredientParser.format(2.0, false))
        assertEquals("1", IngredientParser.format(0.99, false))
        assertEquals("2.5", IngredientParser.format(2.5, true))
        assertEquals("235", IngredientParser.format(237.0, true))
    }

    @Test
    fun highlightSpan() {
        assertEquals(0 until 6, IngredientParser.highlightSpan("2 cups flour"))
    }

    @Test
    fun keyWords() {
        assertEquals(listOf("garlic"), IngredientParser.keyWords("2 cloves garlic, minced"))
        assertEquals(listOf("tomato"), IngredientParser.keyWords("1 (28 oz) can crushed tomatoes"))
        assertEquals(listOf("salt", "pepper"), IngredientParser.keyWords("Salt and pepper to taste"))
    }

    @Test
    fun servingsNumber() {
        assertEquals(4.0, IngredientParser.leadingNumber("4")!!, 1e-9)
        assertEquals(24.0, IngredientParser.leadingNumber("about 24 cookies")!!, 1e-9)
        assertEquals(4.0, IngredientParser.leadingNumber("4-6")!!, 1e-9)
        assertNull(IngredientParser.leadingNumber("a crowd"))
    }
}

class DurationParserTest {
    @Test
    fun findsDurations() {
        assertEquals(listOf(2700), DurationParser.matches("Simmer for 45 minutes.").map { it.seconds })
        assertEquals(listOf(4500), DurationParser.matches("Bake 1 hour 15 minutes").map { it.seconds })
        assertEquals(listOf(5400), DurationParser.matches("Roast 1 1/2 hours").map { it.seconds })
        assertEquals(listOf(7200, 1200), DurationParser.matches("Chill 2 hrs, then bake 20 min").map { it.seconds })
        assertEquals(listOf(1800), DurationParser.matches("Cook half an hour").map { it.seconds })
        assertEquals(0, DurationParser.matches("Rest for a few minutes").size)
        assertEquals(listOf(30), DurationParser.matches("Microwave 30 seconds").map { it.seconds })
        assertEquals(listOf(1200), DurationParser.matches("a 20-minute rest").map { it.seconds })
    }

    @Test
    fun ranges() {
        val match = DurationParser.matches("Bake 10-12 minutes").first()
        assertEquals(600, match.seconds)
        assertEquals(720, match.upperSeconds)
        assertEquals("10–12 min", match.label)
    }

    @Test
    fun notFooledByTemperaturesOrQuantities() {
        assertTrue(DurationParser.matches("Heat oven to 350°F").isEmpty())
        assertTrue(DurationParser.matches("2 cups flour").isEmpty())
    }

    @Test
    fun minutesAndFormatting() {
        assertEquals(90, DurationParser.minutes("1 hr 30 min"))
        assertEquals(45, DurationParser.minutes("45"))
        assertEquals("1 hr 15 min", DurationParser.formatMinutes(75))
        assertEquals("1 hr 30 min", DurationParser.normalized("1 hour 30 minutes"))
        assertEquals("1:02:05", DurationParser.clock(3725))
    }
}

class FuzzySearchTest {
    private val recipes = listOf(
        Recipe(title = "Chicken Tikka Masala", ingredients = listOf(RecipeItem(text = "2 lb chicken thighs"), RecipeItem(text = "1 cup yogurt")), tags = listOf("Dinner")),
        Recipe(title = "Chocolate Chip Cookies", ingredients = listOf(RecipeItem(text = "2 cups chocolate chips")), tags = listOf("Dessert")),
        Recipe(title = "Crème Brûlée", ingredients = listOf(RecipeItem(text = "2 cups heavy cream"))),
        Recipe(title = "Garden Salad", ingredients = listOf(RecipeItem(text = "1 cucumber"), RecipeItem(text = "Cherry tomatoes")), steps = listOf(RecipeItem(text = "Toss with the vinaigrette."))),
    )

    private fun titles(query: String) = FuzzySearch().search(query, recipes).map { it.recipe.title }

    @Test
    fun exactAndPrefix() {
        assertEquals("Chicken Tikka Masala", titles("chicken").first())
        assertEquals("Chocolate Chip Cookies", titles("choc").first())
    }

    @Test
    fun typos() {
        assertEquals("Chicken Tikka Masala", titles("chiken").first())
        assertEquals("Chocolate Chip Cookies", titles("choclate").first())
        assertEquals("Chicken Tikka Masala", titles("masla").first())
    }

    @Test
    fun abbreviationAndAccents() {
        assertEquals("Chicken Tikka Masala", titles("chkn").first())
        assertEquals("Crème Brûlée", titles("creme brulee").first())
    }

    @Test
    fun ingredientsAndSteps() {
        assertEquals(listOf("Garden Salad"), titles("tomatoes"))
        assertEquals(listOf("Garden Salad"), titles("vinaigrette"))
        assertEquals(listOf("Chicken Tikka Masala"), titles("yogurt"))
    }

    @Test
    fun allWordsMustMatch() {
        assertEquals(emptyList<String>(), titles("chocolate chicken"))
        assertEquals(listOf("Chocolate Chip Cookies"), titles("dessert cookies"))
    }

    @Test
    fun snippetExplainsMatch() {
        assertEquals("1 cup yogurt", FuzzySearch().search("yogurt", recipes).first().snippet)
    }

    @Test
    fun editDistance() {
        assertEquals(1, FuzzySearch.boundedDistance("chiken", "chicken", 2))
        assertEquals(1, FuzzySearch.boundedDistance("teh", "the", 1))
        assertNull(FuzzySearch.boundedDistance("apple", "banana", 2))
    }
}

class RecipeCodingTest {
    @Test
    fun roundTripKeepsEverything() {
        val recipe = SampleRecipes.sundaySauce().copy(lastCookedAt = Instant.ofEpochMilli(1_790_000_000_123))
        val decoded = PinchJson.decode(PinchJson.encode(recipe))
        assertEquals(recipe.title, decoded.title)
        assertEquals(recipe.ingredients, decoded.ingredients)
        assertEquals(CoverPalette.TOMATO, decoded.palette)
        assertEquals(recipe.updatedAt.toEpochMilli(), decoded.updatedAt.toEpochMilli())
        assertEquals(1_790_000_000_123, decoded.lastCookedAt!!.toEpochMilli())
    }

    @Test
    fun decodesIosStyleJson() {
        // iOS writes the same keys; unknown keys, unknown enum values and missing keys are fine.
        val json = """
            {"schema":1,"id":"ABC-123","title":"Toast","ingredients":[{"id":"x1","text":"1 slice bread","isHeader":false}],
             "steps":[],"palette":"butter","pattern":"plaid","createdAt":"2026-09-27T18:25:43Z",
             "updatedAt":"2026-09-27T18:25:43.511Z","futureField":true}
        """.trimIndent()
        val recipe = PinchJson.decode(json)
        assertEquals("abc-123", recipe.id)
        assertEquals(CoverPalette.BUTTER, recipe.palette)
        assertEquals(CoverPattern.GINGHAM, recipe.pattern)
        assertEquals("🍽️", recipe.emoji)
        assertEquals(511, recipe.updatedAt.toEpochMilli() % 1000)
    }

    @Test
    fun writesMillisecondDates() {
        val recipe = Recipe(createdAt = Instant.ofEpochMilli(1_790_000_000_000), updatedAt = Instant.ofEpochMilli(1_790_000_000_000))
        val text = PinchJson.encode(recipe)
        assertTrue(text.contains("\"createdAt\": \"2026-09-21T14:13:20.000Z\""))
        assertFalse(text.contains("deletedAt"))
    }

    @Test
    fun coverSuggestions() {
        assertEquals("🍪", CoverSuggester.suggest("Mom's Chocolate Chip Cookies").emoji)
        assertEquals("🍲", CoverSuggester.suggest("Tomato Soup").emoji)
        assertEquals("🍗", CoverSuggester.suggest("Something", listOf("2 lb chicken thighs")).emoji)
        assertEquals(listOf("Dessert"), CoverSuggester.suggestTags("Chocolate Chip Cookies", emptyList()))
        assertNotNull(CoverSuggester.pattern("abc"))
    }

    @Test
    fun stableIndexMatchesSwift() {
        // FNV-1a("hello") = 0x4f9f2cab, the same value iOS computes.
        assertEquals((0x4f9f2cabL % 5).toInt(), CoverSuggester.stableIndex("hello", 5))
    }
}
