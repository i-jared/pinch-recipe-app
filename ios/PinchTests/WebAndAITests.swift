import XCTest
@testable import Pinch

final class WebRecipeImporterTests: XCTestCase {
    func testNormalizesAddresses() {
        XCTAssertEqual(WebRecipeImporter.normalizedURL("example.com/recipes/pie")?.absoluteString, "https://example.com/recipes/pie")
        XCTAssertEqual(WebRecipeImporter.normalizedURL("  http://Food.example.org/a?b=1  ")?.absoluteString, "https://Food.example.org/a?b=1")
        XCTAssertEqual(
            WebRecipeImporter.normalizedURL("You have to try this! https://cooking.example.com/recipes/123-soup.")?.absoluteString,
            "https://cooking.example.com/recipes/123-soup"
        )
        XCTAssertNil(WebRecipeImporter.normalizedURL("banana bread"))
        XCTAssertNil(WebRecipeImporter.normalizedURL("localhost"))
        XCTAssertNil(WebRecipeImporter.normalizedURL("ftp://example.com/file"))
        XCTAssertNil(WebRecipeImporter.normalizedURL(""))
    }

    func testOnlyBareLinksCountAsLinks() {
        XCTAssertEqual(RecipeImporter.link(in: " https://example.com/pie \n")?.absoluteString, "https://example.com/pie")
        XCTAssertEqual(RecipeImporter.link(in: "www.example.com/pie")?.absoluteString, "https://www.example.com/pie")
        XCTAssertNil(RecipeImporter.link(in: "Pie\n2 cups flour\nhttps://example.com"))
    }

    func testISODurations() {
        XCTAssertEqual(WebRecipeImporter.isoDurationText("PT1H30M"), "1 hr 30 min")
        XCTAssertEqual(WebRecipeImporter.isoDurationText("PT45M"), "45 min")
        XCTAssertEqual(WebRecipeImporter.isoDurationText("P0DT0H20M"), "20 min")
        XCTAssertEqual(WebRecipeImporter.isoDurationText("PT90M"), "1 hr 30 min")
        XCTAssertEqual(WebRecipeImporter.isoDurationText("P1DT2H"), "26 hr")
        XCTAssertEqual(WebRecipeImporter.isoDurationText("PT0S"), "")
        XCTAssertEqual(WebRecipeImporter.isoDurationText("pt10m"), "10 min")
        XCTAssertEqual(WebRecipeImporter.isoDurationText("20 minutes"), "20 min")
    }

    func testDecodesEntities() {
        XCTAssertEqual(
            WebRecipeImporter.decodeEntities("Mac &amp; Cheese &#8211; Grandma&#x2019;s &frac12; cup"),
            "Mac & Cheese – Grandma’s ½ cup"
        )
        XCTAssertEqual(WebRecipeImporter.decodeEntities("Mom&amp;#8217;s"), "Mom’s")
        XCTAssertEqual(WebRecipeImporter.decodeEntities("It&#146;s 350&deg;F"), "It’s 350°F")
        XCTAssertEqual(WebRecipeImporter.decodeEntities("AT&T &unknown; stays"), "AT&T &unknown; stays")
    }

    func testFindsRecipeInGraphWithSections() throws {
        let html = """
        <html><head>
        <title>Best Lasagna | Example Kitchen</title>
        <meta property="og:site_name" content="Example Kitchen">
        <script type="application/ld+json">
        {"@context":"https://schema.org","@graph":[
          {"@type":"WebSite","name":"Example Kitchen"},
          {"@type":"Recipe","name":"Best &amp; Easiest Lasagna",
           "description":"<p>A weeknight classic.</p>",
           "recipeYield":["8","8 servings"],
           "prepTime":"PT30M","cookTime":"PT1H","totalTime":"PT1H30M",
           "author":{"@type":"Person","name":"Sam Cook"},
           "recipeCategory":"Dinner","recipeCuisine":["Italian"],"keywords":"pasta, cheesy",
           "recipeIngredient":["1 lb lasagna noodles","2 cups ricotta","▢ 1 jar marinara"],
           "recipeInstructions":[
             {"@type":"HowToSection","name":"Make the filling","itemListElement":[
               {"@type":"HowToStep","text":"Mix the ricotta and egg."}]},
             {"@type":"HowToSection","name":"Assemble","itemListElement":[
               {"@type":"HowToStep","text":"1. Layer noodles, filling and sauce."},
               {"@type":"HowToStep","name":"Bake","text":"Bake at 375&deg;F for 1 hour."}]}
           ]}
        ]}
        </script></head><body><h1>Best Lasagna</h1></body></html>
        """
        let page = WebRecipeImporter.parse(html: html, url: URL(string: "https://www.example.com/lasagna")!)
        let recipe = try XCTUnwrap(page.structured)
        XCTAssertEqual(recipe.name, "Best & Easiest Lasagna")
        XCTAssertEqual(recipe.description, "A weeknight classic.")
        XCTAssertEqual(recipe.yield, "8 servings")
        XCTAssertEqual(recipe.prepTime, "30 min")
        XCTAssertEqual(recipe.cookTime, "1 hr")
        XCTAssertEqual(recipe.totalTime, "1 hr 30 min")
        XCTAssertEqual(recipe.author, "Sam Cook")
        XCTAssertEqual(recipe.categories, ["Dinner", "Italian", "pasta", "cheesy"])
        XCTAssertEqual(recipe.ingredients, ["1 lb lasagna noodles", "2 cups ricotta", "1 jar marinara"])
        XCTAssertEqual(recipe.instructions.map(\.text), [
            "Make the filling", "Mix the ricotta and egg.", "Assemble", "Layer noodles, filling and sauce.", "Bake at 375°F for 1 hour.",
        ])
        XCTAssertEqual(recipe.instructions.map(\.isHeader), [true, false, true, false, false])
        XCTAssertEqual(page.siteName, "Example Kitchen")
        XCTAssertEqual(page.title, "Best & Easiest Lasagna")

        let parsed = recipe.parsed
        XCTAssertEqual(parsed.title, "Best & Easiest Lasagna")
        XCTAssertEqual(parsed.steps.count, 5)
        XCTAssertTrue(parsed.rawText.contains("## Assemble"))
    }

    func testRecipeWithTypeListAndTextInstructions() throws {
        let html = """
        <script type='application/ld+json'>
        [{"@type":"Organization","name":"Pie Co"},
         {"@type":["Recipe","NewsArticle"],"name":"Apple Pie","recipeYield":6,
          "recipeIngredient":"6 apples",
          "recipeInstructions":"<p>Peel the apples.</p><p>Bake until golden.</p>"}]
        </script>
        """
        let page = WebRecipeImporter.parse(html: html, url: URL(string: "https://www.pies.example/apple")!)
        let recipe = try XCTUnwrap(page.structured)
        XCTAssertEqual(recipe.yield, "6")
        XCTAssertEqual(recipe.ingredients, ["6 apples"])
        XCTAssertEqual(recipe.instructions.map(\.text), ["Peel the apples.", "Bake until golden."])
        XCTAssertEqual(page.siteName, "pies.example")
    }

    func testToleratesRawLineBreaksInMarkup() throws {
        let html = "<script type=\"application/ld+json\">{\"@type\":\"Recipe\",\"name\":\"Soup\",\"recipeIngredient\":[\"1 onion\"],\"description\":\"Warm\nand cozy\"}</script>"
        let recipe = try XCTUnwrap(WebRecipeImporter.structuredRecipe(in: html))
        XCTAssertEqual(recipe.description, "Warm and cozy")
    }

    func testPageWithoutMarkupHasNoStructuredRecipe() {
        let html = "<html><head><title>Just a blog</title></head><body><p>Hello</p></body></html>"
        let page = WebRecipeImporter.parse(html: html, url: URL(string: "https://blog.example.net/post")!)
        XCTAssertNil(page.structured)
        XCTAssertEqual(page.title, "Just a blog")
        XCTAssertEqual(page.text, "Hello")
    }

    func testReadableTextDropsScriptsAndMenus() {
        let html = """
        <head><title>T</title><style>.a{}</style></head>
        <body><nav><a href="/">Home</a> <a>Recipes</a></nav>
        <script>var x = "<p>nope</p>";</script>
        <h2>Ingredients</h2><ul><li>2 cups&nbsp;flour</li><li>1 tsp salt</li></ul>
        <p>Mix <b>well</b>.<br>Then bake.</p>
        <footer>© 2026</footer></body>
        """
        XCTAssertEqual(WebRecipeImporter.readableText(fromHTML: html), "Ingredients\n• 2 cups flour\n• 1 tsp salt\nMix well.\nThen bake.")
    }

    func testFocusedTextKeepsTheRecipeCard() {
        let story = (0..<400).map { "Story paragraph number \($0) about my summer." }.joined(separator: "\n")
        let card = "Ingredients\n1 cup sugar\n2 eggs\n½ cup milk\nInstructions\nWhisk everything."
        let text = "Jump to recipe\nIngredients\n" + story + "\n" + card + "\nComments\n" + story
        let focused = WebRecipeImporter.focusedText(text, limit: 4000)
        XCTAssertEqual(focused.count, 4000)
        XCTAssertTrue(focused.contains("1 cup sugar\n2 eggs"))
        XCTAssertTrue(focused.contains("Whisk everything."))
        XCTAssertEqual(WebRecipeImporter.focusedText("short", limit: 4000), "short")
    }

    func testAbsurdNumbersAreIgnored() {
        XCTAssertEqual(WebRecipeImporter.isoDurationText("PT99999999999999999999M"), "")
        XCTAssertTrue(DurationParser.matches(in: "simmer 99999999999999999999 minutes").isEmpty)
        XCTAssertTrue(DurationParser.matches(in: "bake 9999999999999999h").isEmpty)
        XCTAssertNil(IngredientParser.leadingNumber(in: "99999999999999999999 cups flour"))
        XCTAssertEqual(WebRecipeImporter.isoDurationText("PT48H"), "48 hr")
    }

    func testMetaTagsInAnyAttributeOrder() {
        let html = "<head><meta content='Site &amp; Co' property=\"og:site_name\"><meta name=description content=hello></head>"
        let tags = WebRecipeImporter.metaTags(in: html)
        XCTAssertEqual(tags.first?["property"], "og:site_name")
        XCTAssertEqual(tags.first?["content"], "Site &amp; Co")
        XCTAssertEqual(tags.last?["content"], "hello")
    }
}

final class RecipeAITests: XCTestCase {
    func testDecodesFencedAnswer() throws {
        let content = """
        ```json
        {"isRecipe": true, "title": "Lemon Bars", "summary": "", "servings": "16 bars",
         "prepTime": "20 minutes", "cookTime": "45 min", "totalTime": "",
         "ingredients": [{"text": "For the crust:", "isHeader": false}, {"text": "• 1 cup flour", "isHeader": false},
                         {"text": "For the filling", "isHeader": true}, {"text": "4 eggs", "isHeader": false}],
         "steps": [{"text": "1. Heat the oven to 350°F.", "isHeader": false}, {"text": "Step 2: Press in the crust.", "isHeader": false}, {"text": "  ", "isHeader": false}],
         "notes": "Keeps 3 days.", "tags": ["Dessert", "Baking", "dessert", "Snack", "Holidays"], "emoji": "🍋", "source": "Aunt Jo"}
        ```
        """
        let reading = try RecipeAI.decode(content)
        let parsed = reading.parsed
        XCTAssertEqual(parsed.title, "Lemon Bars")
        XCTAssertEqual(parsed.servings, "16 bars")
        XCTAssertEqual(parsed.prepTime, "20 min")
        XCTAssertEqual(parsed.ingredients.map(\.text), ["For the crust", "1 cup flour", "For the filling", "4 eggs"])
        XCTAssertEqual(parsed.ingredients.map(\.isHeader), [true, false, true, false])
        XCTAssertEqual(parsed.steps.map(\.text), ["Heat the oven to 350°F.", "Press in the crust."])
        XCTAssertEqual(parsed.source, "Aunt Jo")
        XCTAssertEqual(reading.emoji, "🍋")
        XCTAssertEqual(reading.tags, ["Dessert", "Baking", "Snack"])
    }

    func testNotARecipe() {
        XCTAssertThrowsError(try RecipeAI.decode(#"{"isRecipe": false, "title": "", "ingredients": [], "steps": []}"#)) { error in
            XCTAssertEqual(error as? RecipeAI.Failure, .notARecipe)
        }
    }

    func testAcceptsLooseShapes() throws {
        let reading = try RecipeAI.decode(#"Sure! {"title": "Toast", "servings": 2, "ingredients": ["2 slices bread"], "steps": ["Toast it."], "emoji": "bread"}"#)
        XCTAssertEqual(reading.parsed.servings, "2")
        XCTAssertEqual(reading.parsed.ingredients.map(\.text), ["2 slices bread"])
        XCTAssertEqual(reading.parsed.steps.map(\.text), ["Toast it."])
        XCTAssertNil(reading.emoji)
    }

    func testGarbageIsABadResponse() {
        XCTAssertThrowsError(try RecipeAI.decode("I can't help with that.")) { error in
            XCTAssertEqual(error as? OpenRouterClient.Failure, .badResponse("not JSON"))
        }
    }

    func testEmojiValidation() {
        XCTAssertEqual(RecipeAI.validEmoji("🍽️"), "🍽️")
        XCTAssertEqual(RecipeAI.validEmoji(" ☕️ "), "☕️")
        XCTAssertNil(RecipeAI.validEmoji("1"))
        XCTAssertNil(RecipeAI.validEmoji("🍕🍝"))
        XCTAssertNil(RecipeAI.validEmoji("pizza"))
    }

    func testPromptsCarryTheirSources() {
        let pages = RecipeAI.pagesPrompt(pageCount: 2, ocrText: "2 cups flour")
        XCTAssertTrue(pages.contains("2 photos"))
        XCTAssertTrue(pages.contains("2 cups flour"))
        XCTAssertFalse(RecipeAI.pagesPrompt(pageCount: 1, ocrText: " ").contains("text recognition"))

        var structured = StructuredRecipe()
        structured.name = "Soup"
        structured.ingredients = ["1 onion"]
        let page = ScrapedPage(url: URL(string: "https://example.com/soup")!, title: "Soup", siteName: "Example", structured: structured, text: "Soup\n1 onion")
        let web = RecipeAI.webPrompt(page)
        XCTAssertTrue(web.contains("https://example.com/soup"))
        XCTAssertTrue(web.contains("- 1 onion"))
        XCTAssertTrue(web.contains("Text from the page"))

        let recipe = Recipe(title: "Pie", ingredients: [RecipeItem(text: "Crust", isHeader: true), RecipeItem(text: "1 cup flour")])
        let more = RecipeAI.morePagesPrompt(pageCount: 1, ocrText: "", soFar: recipe)
        XCTAssertTrue(more.contains("## Crust\n- 1 cup flour"))
        XCTAssertTrue(more.contains("this page"))
    }

    @MainActor
    func testAIEmojiPicksMatchingPalette() {
        var parsed = ParsedRecipe()
        parsed.title = "Morning Mix"
        let recipe = RecipeImporter.makeRecipe(from: AIReading(parsed: parsed, emoji: "☕", tags: ["Breakfast"]))
        XCTAssertEqual(recipe.emoji, "☕")
        XCTAssertEqual(recipe.palette, .cocoa)
        XCTAssertEqual(recipe.tags, ["Breakfast"])
    }
}

final class OpenRouterClientTests: XCTestCase {
    func testStrictRequestBody() throws {
        let body = OpenRouterClient.requestBody(
            model: AIConfig.model, system: "sys", parts: [.text("hello"), .jpeg(Data([1, 2, 3]))],
            schemaName: "recipe", schema: RecipeAI.schema, maxTokens: 100, strict: true
        )
        XCTAssertEqual(body["model"] as? String, "deepseek/deepseek-v4.1-flash")
        XCTAssertEqual(body["max_tokens"] as? Int, 100)
        let messages = try XCTUnwrap(body["messages"] as? [[String: Any]])
        XCTAssertEqual(messages[0]["role"] as? String, "system")
        XCTAssertEqual(messages[0]["content"] as? String, "sys")
        let parts = try XCTUnwrap(messages[1]["content"] as? [[String: Any]])
        XCTAssertEqual(parts[0]["type"] as? String, "text")
        XCTAssertEqual(parts[1]["type"] as? String, "image_url")
        XCTAssertEqual((parts[1]["image_url"] as? [String: Any])?["url"] as? String, "data:image/jpeg;base64,AQID")
        let format = try XCTUnwrap(body["response_format"] as? [String: Any])
        XCTAssertEqual(format["type"] as? String, "json_schema")
        let schema = try XCTUnwrap(format["json_schema"] as? [String: Any])
        XCTAssertEqual(schema["strict"] as? Bool, true)
        XCTAssertEqual(schema["name"] as? String, "recipe")
        XCTAssertEqual((body["provider"] as? [String: Any])?["require_parameters"] as? Bool, true)
        XCTAssertEqual((body["reasoning"] as? [String: Any])?["effort"] as? String, "none")
        XCTAssertNoThrow(try JSONSerialization.data(withJSONObject: body))
    }

    func testLooseRequestBody() {
        let body = OpenRouterClient.requestBody(model: "m", system: "s", parts: [.text("t")], schemaName: "recipe", schema: [:], maxTokens: 10, strict: false)
        XCTAssertEqual((body["response_format"] as? [String: Any])?["type"] as? String, "json_object")
        XCTAssertNil(body["provider"])
        XCTAssertNil(body["reasoning"])
    }

    func testSchemaRequiresEveryProperty() throws {
        let properties = try XCTUnwrap(RecipeAI.schema["properties"] as? [String: Any])
        let required = try XCTUnwrap(RecipeAI.schema["required"] as? [String])
        XCTAssertEqual(Set(required), Set(properties.keys))
        XCTAssertEqual(RecipeAI.schema["additionalProperties"] as? Bool, false)
    }

    func testReadsContent() throws {
        let response = #"{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"```json\n{\"title\":\"A\"}\n```"}}]}"#
        XCTAssertEqual(try OpenRouterClient.content(from: Data(response.utf8)), #"{"title":"A"}"#)
    }

    func testTruncatedAnswerFails() {
        let response = #"{"choices":[{"finish_reason":"length","message":{"content":"{\"title\":\"A"}}]}"#
        XCTAssertThrowsError(try OpenRouterClient.content(from: Data(response.utf8))) { error in
            XCTAssertEqual(error as? OpenRouterClient.Failure, .badResponse("the answer was cut off"))
        }
    }

    func testErrorsMapToFailures() {
        XCTAssertEqual(OpenRouterClient.failure(status: 401, body: Data()), .invalidKey)
        XCTAssertEqual(OpenRouterClient.failure(status: 402, body: Data()), .noCredits)
        XCTAssertEqual(OpenRouterClient.failure(status: 429, body: Data()), .rateLimited)
        let body = Data(#"{"error":{"code":404,"message":"No endpoints found that can handle the requested parameters."}}"#.utf8)
        XCTAssertEqual(OpenRouterClient.failure(status: 404, body: body), .rejected("No endpoints found that can handle the requested parameters."))
        XCTAssertEqual(OpenRouterClient.failure(status: 502, body: Data()), .unavailable("HTTP 502"))
        XCTAssertEqual(OpenRouterClient.failure(from: URLError(.notConnectedToInternet)), .offline)
        XCTAssertEqual(OpenRouterClient.failure(from: URLError(.timedOut)), .timedOut)
        let inBody = #"{"error":{"code":402,"message":"Insufficient credits"}}"#
        XCTAssertThrowsError(try OpenRouterClient.content(from: Data(inBody.utf8))) { error in
            XCTAssertEqual(error as? OpenRouterClient.Failure, .noCredits)
        }
    }

    func testExtractsJSONFromChatter() {
        XCTAssertEqual(OpenRouterClient.extractJSON("<think>hmm {no}</think>\nHere you go: {\"a\":1} Enjoy!"), #"{"a":1}"#)
        XCTAssertEqual(OpenRouterClient.extractJSON("```\n{\"a\":1}\n```"), #"{"a":1}"#)
    }

    func testKeyInfo() throws {
        let data = Data(#"{"data":{"label":"sk-or-v1-abc...xyz","usage":0.0123,"limit":10,"limit_remaining":9.9877,"is_free_tier":false}}"#.utf8)
        let info = try OpenRouterClient.keyInfo(from: data)
        XCTAssertEqual(info.usage, 0.0123, accuracy: 0.00001)
        XCTAssertEqual(info.limitRemaining ?? 0, 9.9877, accuracy: 0.00001)
        XCTAssertFalse(info.isFreeTier)
        let unlimited = try OpenRouterClient.keyInfo(from: Data(#"{"data":{"label":"x","usage":0,"limit":null,"limit_remaining":null,"is_free_tier":true}}"#.utf8))
        XCTAssertNil(unlimited.limitRemaining)
    }

    func testCleansPastedKeys() {
        XCTAssertEqual(AIAccount.cleaned("  \"sk-or-v1-abc123\"\n"), "sk-or-v1-abc123")
    }
}

final class SourceURLCodingTests: XCTestCase {
    func testSourceURLRoundTrips() throws {
        let recipe = Recipe(title: "Soup", source: "Example", sourceURL: "https://example.com/soup")
        let data = try PinchJSON.encoder.encode(recipe)
        XCTAssertTrue(String(decoding: data, as: UTF8.self).contains(#""sourceUrl" : "https://example.com/soup""#))
        let decoded = try PinchJSON.decoder.decode(Recipe.self, from: data)
        XCTAssertEqual(decoded.sourceURL, "https://example.com/soup")
    }

    func testMissingSourceURLIsEmpty() throws {
        let json = #"{"id":"abc","title":"Old recipe"}"#
        let decoded = try PinchJSON.decoder.decode(Recipe.self, from: Data(json.utf8))
        XCTAssertEqual(decoded.sourceURL, "")
        let data = try PinchJSON.encoder.encode(decoded)
        XCTAssertFalse(String(decoding: data, as: UTF8.self).contains("sourceUrl"))
    }
}
