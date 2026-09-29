package com.example.pinch.model

import java.time.Instant

/** A few recipes to explore the app with before scanning your own. Same as iOS. */
object SampleRecipes {
    fun all(): List<Recipe> = listOf(sundaySauce(), cookies(), curry())

    private fun items(lines: List<String>) = lines.map { line ->
        if (line.startsWith("# ")) RecipeItem(text = line.removePrefix("# "), isHeader = true) else RecipeItem(text = line)
    }

    fun sundaySauce(now: Instant = Instant.now()) = Recipe(
        title = "Sunday Tomato Sauce with Meatballs",
        summary = "A big pot for a slow afternoon. It tastes even better the next day.",
        source = "Grandma's recipe card",
        servings = "6",
        prepTime = "30 min",
        cookTime = "2 hr",
        ingredients = items(
            listOf(
                "# For the meatballs",
                "1 lb ground beef",
                "½ lb ground pork",
                "2 slices white bread, crusts removed",
                "⅓ cup milk",
                "1 large egg",
                "½ cup grated Parmesan",
                "2 cloves garlic, minced",
                "1 tsp kosher salt",
                "# For the sauce",
                "3 tbsp olive oil",
                "1 onion, finely chopped",
                "4 cloves garlic, sliced",
                "2 (28 oz) cans crushed tomatoes",
                "1 cup water",
                "1 bay leaf",
                "Handful of fresh basil",
                "Salt and pepper to taste",
            )
        ),
        steps = items(
            listOf(
                "Soak the bread in the milk for 5 minutes, then mash it with a fork.",
                "Mix the beef, pork, soaked bread, egg, Parmesan, minced garlic and salt with your hands until just combined. Roll into 18 meatballs.",
                "Heat the olive oil in a large pot over medium-high heat. Brown the meatballs in batches, about 8 minutes per batch, and set aside.",
                "Lower the heat to medium. Cook the onion in the same pot until soft, about 6 minutes, then add the sliced garlic for 1 minute.",
                "Stir in the tomatoes, water and bay leaf, scraping up the browned bits. Return the meatballs to the pot.",
                "Simmer gently, partly covered, for 1 1/2 hours, stirring now and then.",
                "Tear in the basil, season with salt and pepper, and serve over pasta.",
            )
        ),
        notes = "Freezes well for up to 3 months. Grandma always added a Parmesan rind while it simmered.",
        tags = listOf("Dinner", "Pasta"),
        emoji = "🍝",
        palette = CoverPalette.TOMATO,
        pattern = CoverPattern.GINGHAM,
        createdAt = now,
        updatedAt = now,
    )

    fun cookies(now: Instant = Instant.now().minusMillis(1)) = Recipe(
        title = "Brown Butter Chocolate Chip Cookies",
        summary = "Crisp edges, soft middles, and a toasty flavor from browning the butter first.",
        source = "Recipe box",
        servings = "24 cookies",
        prepTime = "20 min",
        cookTime = "12 min",
        ingredients = items(
            listOf(
                "1 cup unsalted butter",
                "1 cup packed brown sugar",
                "½ cup granulated sugar",
                "2 large eggs",
                "2 tsp vanilla extract",
                "2 ¼ cups all-purpose flour",
                "1 tsp baking soda",
                "1 tsp salt",
                "2 cups chocolate chips",
                "Flaky salt, for sprinkling",
            )
        ),
        steps = items(
            listOf(
                "Melt the butter in a light-colored pan over medium heat, swirling, until it smells nutty and turns golden brown, 5 to 7 minutes. Pour into a large bowl and let cool for 10 minutes.",
                "Whisk both sugars into the butter, then the eggs and vanilla, until glossy.",
                "Stir in the flour, baking soda and salt until no dry streaks remain. Fold in the chocolate chips.",
                "Chill the dough for 30 minutes. Meanwhile, heat the oven to 350°F and line two baking sheets with parchment.",
                "Scoop 2-tablespoon balls onto the sheets, 2 inches apart. Bake 10-12 minutes, until the edges are golden.",
                "Sprinkle with flaky salt and cool on the sheet for 5 minutes before moving to a rack.",
            )
        ),
        notes = "For thicker cookies, chill the dough overnight.",
        tags = listOf("Dessert", "Baking"),
        emoji = "🍪",
        palette = CoverPalette.COCOA,
        pattern = CoverPattern.DOTS,
        createdAt = now,
        updatedAt = now,
    )

    fun curry(now: Instant = Instant.now().minusMillis(2)) = Recipe(
        title = "Weeknight Chickpea Coconut Curry",
        summary = "Pantry staples, one pot, and dinner in about half an hour.",
        servings = "4",
        totalTime = "35 min",
        ingredients = items(
            listOf(
                "2 tbsp coconut oil",
                "1 onion, diced",
                "1 tbsp grated fresh ginger",
                "3 cloves garlic, minced",
                "2 tbsp curry powder",
                "1 tsp ground cumin",
                "2 (15 oz) cans chickpeas, drained and rinsed",
                "1 (14 oz) can coconut milk",
                "1 (14 oz) can diced tomatoes",
                "2 big handfuls spinach",
                "1 lime, juiced",
                "Cooked rice, for serving",
            )
        ),
        steps = items(
            listOf(
                "Warm the coconut oil in a wide pot over medium heat. Cook the onion until soft and golden, about 8 minutes.",
                "Add the ginger, garlic, curry powder and cumin and stir for 1 minute, until fragrant.",
                "Add the chickpeas, coconut milk and tomatoes. Bring to a simmer and cook for 15 minutes, until slightly thickened.",
                "Stir in the spinach until wilted, then the lime juice. Taste for salt.",
                "Serve over rice.",
            )
        ),
        notes = "Swap the spinach for kale, or add a diced sweet potato with the tomatoes and simmer 10 minutes longer.",
        tags = listOf("Dinner", "Vegetarian"),
        emoji = "🍛",
        palette = CoverPalette.SAFFRON,
        pattern = CoverPattern.STRIPES,
        createdAt = now,
        updatedAt = now,
    )
}
