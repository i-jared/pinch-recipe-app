import CoreGraphics
import XCTest
@testable import Pinch

final class RecipeParserTests: XCTestCase {
    func testPastedRecipeWithHeaders() {
        let text = """
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
        """
        let recipe = RecipeParser.parse(text: text)
        XCTAssertEqual(recipe.title, "Grandma's Banana Bread")
        XCTAssertEqual(recipe.summary, "The best way to use up spotty bananas.")
        XCTAssertEqual(recipe.servings, "8")
        XCTAssertEqual(recipe.prepTime, "15 min")
        XCTAssertEqual(recipe.cookTime, "1 hr")
        XCTAssertEqual(recipe.ingredients.map(\.text), [
            "3 ripe bananas, mashed", "1/3 cup melted butter", "3/4 cup sugar", "1 large egg, beaten",
            "1 tsp vanilla", "1 1/2 cups all-purpose flour", "Pinch of salt",
        ])
        XCTAssertEqual(recipe.steps.map(\.text), [
            "Preheat the oven to 350°F and butter a loaf pan.",
            "Mix the butter into the mashed bananas, then stir in the sugar, egg and vanilla.",
            "Mix in the flour and salt. Pour into the pan and bake for 55-60 minutes.",
        ])
        XCTAssertEqual(recipe.notes, "Freezes beautifully for up to 3 months.")
    }

    func testRecipeCardWithoutHeaders() {
        // Many handwritten cards just list ingredients and then write the method.
        let text = """
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
        """
        let recipe = RecipeParser.parse(text: text)
        XCTAssertEqual(recipe.title, "Aunt May's Lemon Bars")
        XCTAssertEqual(recipe.ingredients.count, 6)
        XCTAssertEqual(recipe.steps.count, 3)
        XCTAssertTrue(recipe.steps[0].text.hasPrefix("Cream the butter"))
    }

    func testSubheadersAndShoutingCard() {
        let text = """
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
        """
        let recipe = RecipeParser.parse(text: text)
        XCTAssertEqual(recipe.title, "Carrot Cake")
        let headers = recipe.ingredients.filter(\.isHeader).map(\.text)
        XCTAssertEqual(headers, ["For the cake", "For the frosting"])
        XCTAssertEqual(recipe.ingredients.filter { !$0.isHeader }.first?.text, "2 cups flour")
        XCTAssertEqual(recipe.steps.count, 2)
        XCTAssertEqual(recipe.steps.first?.text, "Mix everything for the cake and bake 30 minutes.")
    }

    func testInlineHeaderAndBullets() {
        let text = """
        Quick Guacamole
        Ingredients:
        • 2 ripe avocados
        • 1 lime, juiced
        • ½ tsp salt
        Method:
        - Mash the avocados with a fork.
        - Stir in the lime juice and salt.
        """
        let recipe = RecipeParser.parse(text: text)
        XCTAssertEqual(recipe.ingredients.map(\.text), ["2 ripe avocados", "1 lime, juiced", "½ tsp salt"])
        XCTAssertEqual(recipe.steps.map(\.text), ["Mash the avocados with a fork.", "Stir in the lime juice and salt."])
    }

    func testMetadataNotConfusedWithSteps() {
        let text = """
        Roast Chicken
        Ingredients
        1 whole chicken
        2 tbsp olive oil
        Instructions
        Cook for 10 minutes until the skin is golden.
        Bake 45 minutes.
        """
        let recipe = RecipeParser.parse(text: text)
        XCTAssertEqual(recipe.cookTime, "")
        XCTAssertEqual(recipe.steps.count, 2)
    }

    func testWrappedStepLinesFromPlainText() {
        let text = """
        Simple Rice
        Directions
        Rinse the rice until the water runs clear, then add it to a pot with
        the water and salt.
        Simmer covered for 18 minutes.
        """
        let recipe = RecipeParser.parse(text: text)
        XCTAssertEqual(recipe.steps.map(\.text), [
            "Rinse the rice until the water runs clear, then add it to a pot with the water and salt.",
            "Simmer covered for 18 minutes.",
        ])
    }

    func testContinuationPageAppendsToSteps() {
        let lines = RecipeParser.lines(fromPlainText: """
        4. Fold in the berries.
        5. Bake for 40 minutes.
        """)
        let recipe = RecipeParser.parse(lines: lines, continuing: .steps)
        XCTAssertEqual(recipe.title, "")
        XCTAssertEqual(recipe.steps.map(\.text), ["Fold in the berries.", "Bake for 40 minutes."])
    }

    func testTitleFromLargestTextWithGeometry() {
        func line(_ text: String, y: CGFloat, height: CGFloat) -> TextLine {
            TextLine(text: text, box: CGRect(x: 0.1, y: y, width: 0.6, height: height))
        }
        let lines = [
            line("DESSERTS", y: 0.03, height: 0.015),
            line("Peach Cobbler", y: 0.08, height: 0.05),
            line("Serves 6", y: 0.15, height: 0.02),
            line("4 cups sliced peaches", y: 0.2, height: 0.02),
            line("1 cup sugar", y: 0.23, height: 0.02),
            line("1 cup flour", y: 0.26, height: 0.02),
        ]
        let recipe = RecipeParser.parse(lines: lines)
        XCTAssertEqual(recipe.title, "Peach Cobbler")
        XCTAssertEqual(recipe.servings, "6")
        XCTAssertEqual(recipe.ingredients.count, 3)
        XCTAssertFalse(recipe.summary.contains("DESSERTS"))
    }

    func testPipesReadAsLettersStillSplitMetadata() {
        let meta = RecipeParser.extractMetadata(from: "Makes 12 biscuits I Prep 15 min l Bake 14 min", allowBareCookTime: true)
        XCTAssertEqual(meta.servings, "12 biscuits")
        XCTAssertEqual(meta.prep, "15 min")
        XCTAssertEqual(meta.cook, "14 min")
        XCTAssertEqual(meta.remainder, "")
    }

    func testPageTurnMarkersAreDropped() {
        let text = """
        Soup
        Directions
        1. Chop the onions.
        — continued on next page —
        """
        XCTAssertEqual(RecipeParser.parse(text: text).steps.map(\.text), ["Chop the onions."])
    }

    func testHyphenatedLineJoin() {
        XCTAssertEqual(RecipeParser.join("Stir until com-", "bined."), "Stir until combined.")
        XCTAssertEqual(RecipeParser.join("2 cups all-", "purpose flour"), "2 cups all-purpose flour")
        XCTAssertEqual(RecipeParser.join("Add the", "onions."), "Add the onions.")
    }

    func testSplitsVeryLongParagraphIntoSteps() {
        let long = String(repeating: "Stir the pot gently and keep the heat low so nothing catches. ", count: 8)
        let parts = RecipeParser.splitLongStep(long.trimmingCharacters(in: .whitespaces))
        XCTAssertGreaterThan(parts.count, 1)
        XCTAssertTrue(parts.allSatisfy { $0.count <= 240 })
    }
}

final class LineLayoutTests: XCTestCase {
    private func frag(_ text: String, x: CGFloat, y: CGFloat, w: CGFloat, h: CGFloat = 0.02) -> OCRFragment {
        OCRFragment(text: text, box: CGRect(x: x, y: y, width: w, height: h))
    }

    func testTwoColumnCardReadsLeftColumnFirst() {
        let fragments = [
            frag("Summer Pasta", x: 0.1, y: 0.05, w: 0.8, h: 0.05),
            frag("Ingredients", x: 0.05, y: 0.15, w: 0.3),
            frag("Directions", x: 0.55, y: 0.15, w: 0.3),
            frag("1 lb pasta", x: 0.05, y: 0.19, w: 0.3),
            frag("Boil the pasta until", x: 0.55, y: 0.19, w: 0.4),
            frag("2 cups tomatoes", x: 0.05, y: 0.23, w: 0.3),
            frag("tender, then drain.", x: 0.55, y: 0.23, w: 0.3),
            frag("1 cup basil", x: 0.05, y: 0.27, w: 0.3),
            frag("Toss with the rest.", x: 0.55, y: 0.27, w: 0.35),
        ]
        let lines = LineLayout.lines(from: fragments, page: 0)
        XCTAssertEqual(lines.map(\.text), [
            "Summer Pasta", "Ingredients", "1 lb pasta", "2 cups tomatoes", "1 cup basil",
            "Directions", "Boil the pasta until", "tender, then drain.", "Toss with the rest.",
        ])
        let recipe = RecipeParser.parse(lines: lines)
        XCTAssertEqual(recipe.ingredients.count, 3)
        XCTAssertEqual(recipe.steps.map(\.text), ["Boil the pasta until tender, then drain.", "Toss with the rest."])
    }

    func testQuantityColumnIsReadAsTable() {
        let fragments = [
            frag("Flour", x: 0.1, y: 0.1, w: 0.2),
            frag("2 cups", x: 0.7, y: 0.1, w: 0.15),
            frag("Sugar", x: 0.1, y: 0.14, w: 0.2),
            frag("1 cup", x: 0.7, y: 0.14, w: 0.15),
            frag("Butter", x: 0.1, y: 0.18, w: 0.2),
            frag("8 tbsp", x: 0.7, y: 0.18, w: 0.15),
        ]
        let lines = LineLayout.lines(from: fragments, page: 0)
        XCTAssertEqual(lines.map(\.text), ["2 cups Flour", "1 cup Sugar", "8 tbsp Butter"])
    }

    func testSameRowFragmentsMerge() {
        let fragments = [
            frag("Preheat the oven", x: 0.1, y: 0.1, w: 0.4),
            frag("to 400°F.", x: 0.51, y: 0.1, w: 0.2),
        ]
        XCTAssertEqual(LineLayout.lines(from: fragments, page: 0).map(\.text), ["Preheat the oven to 400°F."])
    }
}

final class IngredientParserTests: XCTestCase {
    func testQuantities() {
        XCTAssertEqual(IngredientParser.parse("2 cups flour").quantity?.low, 2)
        XCTAssertEqual(IngredientParser.parse("1 1/2 cups milk").quantity?.low, 1.5)
        XCTAssertEqual(IngredientParser.parse("1½ tsp salt").quantity?.low, 1.5)
        XCTAssertEqual(IngredientParser.parse("¾ cup sugar").quantity?.low, 0.75)
        XCTAssertEqual(IngredientParser.parse("1-1/2 lb beef").quantity?.low, 1.5)
        XCTAssertEqual(IngredientParser.parse("0.5 kg potatoes").quantity?.low, 0.5)
        let range = IngredientParser.parse("2-3 cloves garlic").quantity
        XCTAssertEqual(range?.low, 2)
        XCTAssertEqual(range?.high, 3)
        XCTAssertNil(IngredientParser.parse("Salt and pepper to taste").quantity)
        XCTAssertNil(IngredientParser.parse("One of my favorites").quantity)
        XCTAssertEqual(IngredientParser.parse("a pinch of nutmeg").quantity?.low, 1)
    }

    func testUnits() {
        XCTAssertEqual(IngredientParser.parse("2 tbsp. butter").unit, "tbsp.")
        XCTAssertEqual(IngredientParser.parse("1 (14 oz) can tomatoes").unit, "can")
        XCTAssertEqual(IngredientParser.parse("3 large eggs").unit, nil)
        XCTAssertEqual(IngredientParser.parse("200g dark chocolate").unit, "g")
    }

    func testScaling() {
        XCTAssertEqual(IngredientParser.scale("1 1/2 cups flour", by: 2), "3 cups flour")
        XCTAssertEqual(IngredientParser.scale("1 cup sugar", by: 2), "2 cups sugar")
        XCTAssertEqual(IngredientParser.scale("2 cups sugar", by: 0.5), "1 cup sugar")
        XCTAssertEqual(IngredientParser.scale("1 egg", by: 3), "3 eggs")
        XCTAssertEqual(IngredientParser.scale("1 large egg, beaten", by: 2), "2 large eggs, beaten")
        XCTAssertEqual(IngredientParser.scale("¾ cup milk", by: 2), "1½ cups milk")
        XCTAssertEqual(IngredientParser.scale("2-3 cloves garlic", by: 2), "4-6 cloves garlic")
        XCTAssertEqual(IngredientParser.scale("250 g butter", by: 1.5), "375 g butter")
        XCTAssertEqual(IngredientParser.scale("1/3 cup oil", by: 1), "1/3 cup oil")
        XCTAssertEqual(IngredientParser.scale("Salt to taste", by: 2), "Salt to taste")
        XCTAssertEqual(IngredientParser.scale("1 onion, diced", by: 2), "2 onions, diced")
    }

    func testScalingRoundsLikeACook() {
        // 7 servings from a recipe for 6.
        let factor = 7.0 / 6.0
        XCTAssertEqual(IngredientParser.scale("1 large egg", by: factor), "1 large egg")
        XCTAssertEqual(IngredientParser.scale("2 slices bread", by: factor), "2½ slices bread")
        XCTAssertEqual(IngredientParser.scale("1 lb ground beef", by: factor), "1¼ lbs ground beef")
        XCTAssertEqual(IngredientParser.scale("1/3 cup milk", by: factor), "⅓ cup milk")
        XCTAssertEqual(IngredientParser.scale("1 tsp salt", by: factor), "1⅛ tsp salt")
        XCTAssertEqual(IngredientParser.scale("1 egg", by: 0.25), "½ egg")
        XCTAssertEqual(IngredientParser.scale("3 eggs", by: 1.5), "5 eggs")
    }

    func testFormat() {
        XCTAssertEqual(IngredientParser.format(0.5, decimal: false), "½")
        XCTAssertEqual(IngredientParser.format(1.333, decimal: false), "1⅓")
        XCTAssertEqual(IngredientParser.format(2.0, decimal: false), "2")
        XCTAssertEqual(IngredientParser.format(0.99, decimal: false), "1")
        XCTAssertEqual(IngredientParser.format(2.5, decimal: true), "2.5")
        XCTAssertEqual(IngredientParser.format(237, decimal: true), "235")
    }

    func testHighlightSpan() {
        let span = IngredientParser.highlightSpan("2 cups flour")
        XCTAssertEqual(span?.start, 0)
        XCTAssertEqual(span?.end, 6)
    }

    func testKeyWords() {
        XCTAssertEqual(IngredientParser.keyWords("2 cloves garlic, minced"), ["garlic"])
        XCTAssertEqual(IngredientParser.keyWords("1 (28 oz) can crushed tomatoes"), ["tomato"])
        XCTAssertEqual(IngredientParser.keyWords("Salt and pepper to taste"), ["salt", "pepper"])
    }

    func testServingsNumber() {
        XCTAssertEqual(IngredientParser.leadingNumber(in: "4"), 4)
        XCTAssertEqual(IngredientParser.leadingNumber(in: "about 24 cookies"), 24)
        XCTAssertEqual(IngredientParser.leadingNumber(in: "4-6"), 4)
        XCTAssertNil(IngredientParser.leadingNumber(in: "a crowd"))
    }
}

final class DurationParserTests: XCTestCase {
    func testFindsDurations() {
        XCTAssertEqual(DurationParser.matches(in: "Simmer for 45 minutes.").map(\.seconds), [2700])
        XCTAssertEqual(DurationParser.matches(in: "Bake 1 hour 15 minutes").map(\.seconds), [4500])
        XCTAssertEqual(DurationParser.matches(in: "Roast 1 1/2 hours").map(\.seconds), [5400])
        XCTAssertEqual(DurationParser.matches(in: "Chill 2 hrs, then bake 20 min").map(\.seconds), [7200, 1200])
        XCTAssertEqual(DurationParser.matches(in: "Cook half an hour").map(\.seconds), [1800])
        XCTAssertEqual(DurationParser.matches(in: "Rest for a few minutes").count, 0)
        XCTAssertEqual(DurationParser.matches(in: "Microwave 30 seconds").map(\.seconds), [30])
        XCTAssertEqual(DurationParser.matches(in: "a 20-minute rest").map(\.seconds), [1200])
    }

    func testRanges() {
        let match = DurationParser.matches(in: "Bake 10-12 minutes").first
        XCTAssertEqual(match?.seconds, 600)
        XCTAssertEqual(match?.upperSeconds, 720)
        XCTAssertEqual(match?.label, "10–12 min")
    }

    func testNotFooledByTemperaturesOrQuantities() {
        XCTAssertTrue(DurationParser.matches(in: "Heat oven to 350°F").isEmpty)
        XCTAssertTrue(DurationParser.matches(in: "2 cups flour").isEmpty)
    }

    func testMinutesAndFormatting() {
        XCTAssertEqual(DurationParser.minutes(in: "1 hr 30 min"), 90)
        XCTAssertEqual(DurationParser.minutes(in: "45"), 45)
        XCTAssertEqual(DurationParser.format(minutes: 75), "1 hr 15 min")
        XCTAssertEqual(DurationParser.normalized("1 hour 30 minutes"), "1 hr 30 min")
        XCTAssertEqual(DurationParser.clock(3725), "1:02:05")
    }
}

final class FuzzySearchTests: XCTestCase {
    private var recipes: [Recipe] = []

    override func setUp() {
        recipes = [
            Recipe(title: "Chicken Tikka Masala", ingredients: [RecipeItem(text: "2 lb chicken thighs"), RecipeItem(text: "1 cup yogurt")], tags: ["Dinner"]),
            Recipe(title: "Chocolate Chip Cookies", ingredients: [RecipeItem(text: "2 cups chocolate chips")], tags: ["Dessert"]),
            Recipe(title: "Crème Brûlée", ingredients: [RecipeItem(text: "2 cups heavy cream")]),
            Recipe(title: "Garden Salad", ingredients: [RecipeItem(text: "1 cucumber"), RecipeItem(text: "Cherry tomatoes")], steps: [RecipeItem(text: "Toss with the vinaigrette.")]),
        ]
    }

    private func titles(_ query: String) -> [String] {
        var search = FuzzySearch()
        return search.search(query, in: recipes).map(\.recipe.title)
    }

    func testExactAndPrefix() {
        XCTAssertEqual(titles("chicken").first, "Chicken Tikka Masala")
        XCTAssertEqual(titles("choc").first, "Chocolate Chip Cookies")
    }

    func testTypos() {
        XCTAssertEqual(titles("chiken").first, "Chicken Tikka Masala")
        XCTAssertEqual(titles("choclate").first, "Chocolate Chip Cookies")
        XCTAssertEqual(titles("masla").first, "Chicken Tikka Masala")
    }

    func testAbbreviationAndAccents() {
        XCTAssertEqual(titles("chkn").first, "Chicken Tikka Masala")
        XCTAssertEqual(titles("creme brulee").first, "Crème Brûlée")
    }

    func testIngredientsAndSteps() {
        XCTAssertEqual(titles("tomatoes"), ["Garden Salad"])
        XCTAssertEqual(titles("vinaigrette"), ["Garden Salad"])
        XCTAssertEqual(titles("yogurt"), ["Chicken Tikka Masala"])
    }

    func testAllWordsMustMatch() {
        XCTAssertEqual(titles("chocolate chicken"), [])
        XCTAssertEqual(titles("dessert cookies"), ["Chocolate Chip Cookies"])
    }

    func testSnippetExplainsMatch() {
        var search = FuzzySearch()
        let result = search.search("yogurt", in: recipes).first
        XCTAssertEqual(result?.snippet, "1 cup yogurt")
    }

    func testEditDistance() {
        XCTAssertEqual(FuzzySearch.boundedDistance("chiken", "chicken", max: 2), 1)
        XCTAssertEqual(FuzzySearch.boundedDistance("teh", "the", max: 1), 1)
        XCTAssertNil(FuzzySearch.boundedDistance("apple", "banana", max: 2))
    }
}

final class RecipeCodingTests: XCTestCase {
    func testRecipePhotosAndBannerRoundTrip() throws {
        let recipe = Recipe(photos: ["page.jpg"], recipePhotos: ["first.jpg", "second.jpg"], bannerPhoto: "second.jpg")
        let decoded = try PinchJSON.decoder.decode(Recipe.self, from: PinchJSON.encoder.encode(recipe))
        XCTAssertEqual(decoded.photos, ["page.jpg"])
        XCTAssertEqual(decoded.recipePhotos, ["first.jpg", "second.jpg"])
        XCTAssertEqual(decoded.bannerPhoto, "second.jpg")
    }

    func testRoundTripKeepsEverything() throws {
        var recipe = SampleRecipes.sundaySauce
        recipe.lastCookedAt = Date(timeIntervalSince1970: 1_790_000_000.123)
        let data = try PinchJSON.encoder.encode(recipe)
        let decoded = try PinchJSON.decoder.decode(Recipe.self, from: data)
        XCTAssertEqual(decoded.title, recipe.title)
        XCTAssertEqual(decoded.ingredients, recipe.ingredients)
        XCTAssertEqual(decoded.palette, .tomato)
        XCTAssertEqual(PinchJSON.millis(decoded.updatedAt), PinchJSON.millis(recipe.updatedAt))
        XCTAssertEqual(PinchJSON.millis(decoded.lastCookedAt!), 1_790_000_000_123)
    }

    func testDecodesAndroidStyleJSON() throws {
        // Android writes the same keys; unknown keys and missing optional keys are fine.
        let json = """
        {"schema":1,"id":"ABC-123","title":"Toast","ingredients":[{"id":"x1","text":"1 slice bread","isHeader":false}],
         "steps":[],"palette":"butter","pattern":"dots","createdAt":"2026-09-27T18:25:43Z",
         "updatedAt":"2026-09-27T18:25:43.511Z","futureField":true}
        """
        let recipe = try PinchJSON.decoder.decode(Recipe.self, from: Data(json.utf8))
        XCTAssertEqual(recipe.id, "abc-123")
        XCTAssertEqual(recipe.palette, .butter)
        XCTAssertEqual(recipe.pattern, .dots)
        XCTAssertEqual(recipe.emoji, "🍽️")
        XCTAssertTrue(recipe.recipePhotos.isEmpty)
        XCTAssertNil(recipe.bannerPhoto)
        XCTAssertEqual(PinchJSON.millis(recipe.updatedAt) % 1000, 511)
    }

    func testCoverSuggestions() {
        XCTAssertEqual(CoverSuggester.suggest(title: "Mom's Chocolate Chip Cookies").emoji, "🍪")
        XCTAssertEqual(CoverSuggester.suggest(title: "Tomato Soup").emoji, "🍲")
        XCTAssertEqual(CoverSuggester.suggest(title: "Something", ingredients: ["2 lb chicken thighs"]).emoji, "🍗")
        XCTAssertEqual(CoverSuggester.stableIndex("hello", count: 5), CoverSuggester.stableIndex("hello", count: 5))
        XCTAssertEqual(CoverSuggester.suggestTags(title: "Chocolate Chip Cookies", ingredients: []), ["Dessert"])
    }
}
