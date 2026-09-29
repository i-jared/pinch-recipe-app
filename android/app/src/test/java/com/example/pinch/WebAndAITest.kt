package com.example.pinch

import com.example.pinch.ai.AIAccount
import com.example.pinch.ai.AIConfig
import com.example.pinch.ai.AIReading
import com.example.pinch.ai.NotARecipeException
import com.example.pinch.ai.OpenRouterClient
import com.example.pinch.ai.OpenRouterFailure
import com.example.pinch.ai.RecipeAI
import com.example.pinch.data.PinchJson
import com.example.pinch.model.CoverPalette
import com.example.pinch.model.Recipe
import com.example.pinch.model.RecipeItem
import com.example.pinch.parsing.ParsedRecipe
import com.example.pinch.scan.RecipeImporter
import com.example.pinch.scan.ScrapedPage
import com.example.pinch.scan.StructuredRecipe
import com.example.pinch.scan.WebRecipeImporter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class WebRecipeImporterTest {
    @Test fun normalizesAddresses() {
        assertEquals("https://example.com/recipes/pie", WebRecipeImporter.normalizedUrl("example.com/recipes/pie"))
        assertEquals("https://Food.example.org/a?b=1", WebRecipeImporter.normalizedUrl("  http://Food.example.org/a?b=1  "))
        assertEquals(
            "https://cooking.example.com/recipes/123-soup",
            WebRecipeImporter.normalizedUrl("You have to try this! https://cooking.example.com/recipes/123-soup."),
        )
        assertNull(WebRecipeImporter.normalizedUrl("banana bread"))
        assertNull(WebRecipeImporter.normalizedUrl("localhost"))
        assertNull(WebRecipeImporter.normalizedUrl("ftp://example.com/file"))
        assertNull(WebRecipeImporter.normalizedUrl(""))
    }

    @Test fun onlyBareLinksCountAsLinks() {
        assertEquals("https://example.com/pie", WebRecipeImporter.link(" https://example.com/pie \n"))
        assertEquals("https://www.example.com/pie", WebRecipeImporter.link("www.example.com/pie"))
        assertNull(WebRecipeImporter.link("Pie\n2 cups flour\nhttps://example.com"))
        assertEquals("example.com", WebRecipeImporter.displayHost("https://www.example.com/pie"))
    }

    @Test fun browserUserAgentDropsWebViewMarkers() {
        val webView = "Mozilla/5.0 (Linux; Android 15; razr; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0.7339.51 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 15; razr) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.7339.51 Mobile Safari/537.36",
            WebRecipeImporter.browserUserAgent(webView),
        )
    }

    @Test fun isoDurations() {
        assertEquals("1 hr 30 min", WebRecipeImporter.isoDurationText("PT1H30M"))
        assertEquals("45 min", WebRecipeImporter.isoDurationText("PT45M"))
        assertEquals("20 min", WebRecipeImporter.isoDurationText("P0DT0H20M"))
        assertEquals("1 hr 30 min", WebRecipeImporter.isoDurationText("PT90M"))
        assertEquals("26 hr", WebRecipeImporter.isoDurationText("P1DT2H"))
        assertEquals("", WebRecipeImporter.isoDurationText("PT0S"))
        assertEquals("10 min", WebRecipeImporter.isoDurationText("pt10m"))
        assertEquals("20 min", WebRecipeImporter.isoDurationText("20 minutes"))
    }

    @Test fun decodesEntities() {
        assertEquals("Mac & Cheese – Grandma’s ½ cup", WebRecipeImporter.decodeEntities("Mac &amp; Cheese &#8211; Grandma&#x2019;s &frac12; cup"))
        assertEquals("Mom’s", WebRecipeImporter.decodeEntities("Mom&amp;#8217;s"))
        assertEquals("It’s 350°F", WebRecipeImporter.decodeEntities("It&#146;s 350&deg;F"))
        assertEquals("AT&T &unknown; stays", WebRecipeImporter.decodeEntities("AT&T &unknown; stays"))
    }

    @Test fun findsRecipeInGraphWithSections() {
        val html = """
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
        """.trimIndent()
        val page = WebRecipeImporter.parse(html, "https://www.example.com/lasagna")
        val recipe = page.structured!!
        assertEquals("Best & Easiest Lasagna", recipe.name)
        assertEquals("A weeknight classic.", recipe.description)
        assertEquals("8 servings", recipe.yield)
        assertEquals("30 min", recipe.prepTime)
        assertEquals("1 hr", recipe.cookTime)
        assertEquals("1 hr 30 min", recipe.totalTime)
        assertEquals("Sam Cook", recipe.author)
        assertEquals(listOf("Dinner", "Italian", "pasta", "cheesy"), recipe.categories)
        assertEquals(listOf("1 lb lasagna noodles", "2 cups ricotta", "1 jar marinara"), recipe.ingredients)
        assertEquals(
            listOf("Make the filling", "Mix the ricotta and egg.", "Assemble", "Layer noodles, filling and sauce.", "Bake at 375°F for 1 hour."),
            recipe.instructions.map { it.text },
        )
        assertEquals(listOf(true, false, true, false, false), recipe.instructions.map { it.isHeader })
        assertEquals("Example Kitchen", page.siteName)
        assertEquals("Best & Easiest Lasagna", page.title)

        val parsed = recipe.parsed
        assertEquals("Best & Easiest Lasagna", parsed.title)
        assertEquals(5, parsed.steps.size)
        assertTrue(parsed.rawText.contains("## Assemble"))
    }

    @Test fun recipeWithTypeListAndTextInstructions() {
        val html = """
            <script type='application/ld+json'>
            [{"@type":"Organization","name":"Pie Co"},
             {"@type":["Recipe","NewsArticle"],"name":"Apple Pie","recipeYield":6,
              "recipeIngredient":"6 apples",
              "recipeInstructions":"<p>Peel the apples.</p><p>Bake until golden.</p>"}]
            </script>
        """.trimIndent()
        val page = WebRecipeImporter.parse(html, "https://www.pies.example/apple")
        val recipe = page.structured!!
        assertEquals("6", recipe.yield)
        assertEquals(listOf("6 apples"), recipe.ingredients)
        assertEquals(listOf("Peel the apples.", "Bake until golden."), recipe.instructions.map { it.text })
        assertEquals("pies.example", page.siteName)
    }

    @Test fun toleratesRawLineBreaksInMarkup() {
        val html = "<script type=\"application/ld+json\">{\"@type\":\"Recipe\",\"name\":\"Soup\",\"recipeIngredient\":[\"1 onion\"],\"description\":\"Warm\nand cozy\"}</script>"
        assertEquals("Warm and cozy", WebRecipeImporter.structuredRecipe(html)!!.description)
    }

    @Test fun pageWithoutMarkupHasNoStructuredRecipe() {
        val page = WebRecipeImporter.parse("<html><head><title>Just a blog</title></head><body><p>Hello</p></body></html>", "https://blog.example.net/post")
        assertNull(page.structured)
        assertEquals("Just a blog", page.title)
        assertEquals("Hello", page.text)
    }

    @Test fun readableTextDropsScriptsAndMenus() {
        val html = """
            <head><title>T</title><style>.a{}</style></head>
            <body><nav><a href="/">Home</a> <a>Recipes</a></nav>
            <script>var x = "<p>nope</p>";</script>
            <h2>Ingredients</h2><ul><li>2 cups&nbsp;flour</li><li>1 tsp salt</li></ul>
            <p>Mix <b>well</b>.<br>Then bake.</p>
            <footer>© 2026</footer></body>
        """.trimIndent()
        assertEquals("Ingredients\n• 2 cups flour\n• 1 tsp salt\nMix well.\nThen bake.", WebRecipeImporter.readableText(html))
    }

    @Test fun focusedTextKeepsTheRecipeCard() {
        val story = (0 until 400).joinToString("\n") { "Story paragraph number $it about my summer." }
        val card = "Ingredients\n1 cup sugar\n2 eggs\n½ cup milk\nInstructions\nWhisk everything."
        val text = "Jump to recipe\nIngredients\n$story\n$card\nComments\n$story"
        val focused = WebRecipeImporter.focusedText(text, 4000)
        assertEquals(4000, focused.length)
        assertTrue(focused.contains("1 cup sugar\n2 eggs"))
        assertTrue(focused.contains("Whisk everything."))
        assertEquals("short", WebRecipeImporter.focusedText("short", 4000))
    }

    @Test fun metaTagsInAnyAttributeOrder() {
        val tags = WebRecipeImporter.metaTags("<head><meta content='Site &amp; Co' property=\"og:site_name\"><meta name=description content=hello></head>")
        assertEquals("og:site_name", tags.first()["property"])
        assertEquals("Site &amp; Co", tags.first()["content"])
        assertEquals("hello", tags.last()["content"])
    }

    @Test fun decodesDeclaredCharset() {
        val bytes = "Café".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Café", WebRecipeImporter.decode(bytes, "text/html; charset=ISO-8859-1"))
        assertEquals("Café", WebRecipeImporter.decode("<meta charset=\"utf-8\">Café".toByteArray(), null).substringAfter(">"))
    }

    @Test fun absurdNumbersAreIgnored() {
        assertEquals("", WebRecipeImporter.isoDurationText("PT99999999999999999999M"))
        assertTrue(com.example.pinch.parsing.DurationParser.matches("simmer 99999999999999999999 minutes").isEmpty())
        assertTrue(com.example.pinch.parsing.DurationParser.matches("bake 9999999999999999h").isEmpty())
        assertNull(com.example.pinch.parsing.IngredientParser.leadingNumber("99999999999999999999 cups flour"))
        assertEquals("48 hr", WebRecipeImporter.isoDurationText("PT48H"))
    }

    @Test fun spotsBotChecks() {
        assertTrue(WebRecipeImporter.isChallengePage("<html><head><title>Just a moment...</title>"))
        assertFalse(WebRecipeImporter.isChallengePage("<html><head><title>Banana Bread</title>"))
    }
}

class RecipeAITest {
    @Test fun decodesFencedAnswer() {
        val content = """
            ```json
            {"isRecipe": true, "title": "Lemon Bars", "summary": "", "servings": "16 bars",
             "prepTime": "20 minutes", "cookTime": "45 min", "totalTime": "",
             "ingredients": [{"text": "For the crust:", "isHeader": false}, {"text": "• 1 cup flour", "isHeader": false},
                             {"text": "For the filling", "isHeader": true}, {"text": "4 eggs", "isHeader": false}],
             "steps": [{"text": "1. Heat the oven to 350°F.", "isHeader": false}, {"text": "Step 2: Press in the crust.", "isHeader": false}, {"text": "  ", "isHeader": false}],
             "notes": "Keeps 3 days.", "tags": ["Dessert", "Baking", "dessert", "Snack", "Holidays"], "emoji": "🍋", "source": "Aunt Jo"}
            ```
        """.trimIndent()
        val reading = RecipeAI.decode(content)
        val parsed = reading.parsed
        assertEquals("Lemon Bars", parsed.title)
        assertEquals("16 bars", parsed.servings)
        assertEquals("20 min", parsed.prepTime)
        assertEquals(listOf("For the crust", "1 cup flour", "For the filling", "4 eggs"), parsed.ingredients.map { it.text })
        assertEquals(listOf(true, false, true, false), parsed.ingredients.map { it.isHeader })
        assertEquals(listOf("Heat the oven to 350°F.", "Press in the crust."), parsed.steps.map { it.text })
        assertEquals("Aunt Jo", parsed.source)
        assertEquals("🍋", reading.emoji)
        assertEquals(listOf("Dessert", "Baking", "Snack"), reading.tags)
    }

    @Test fun notARecipe() {
        try {
            RecipeAI.decode("""{"isRecipe": false, "title": "", "ingredients": [], "steps": []}""")
            fail("Expected NotARecipeException")
        } catch (e: NotARecipeException) {
            // Expected.
        }
    }

    @Test fun acceptsLooseShapes() {
        val reading = RecipeAI.decode("""Sure! {"title": "Toast", "servings": 2, "ingredients": ["2 slices bread"], "steps": ["Toast it."], "emoji": "bread"}""")
        assertEquals("2", reading.parsed.servings)
        assertEquals(listOf("2 slices bread"), reading.parsed.ingredients.map { it.text })
        assertEquals(listOf("Toast it."), reading.parsed.steps.map { it.text })
        assertNull(reading.emoji)
    }

    @Test fun garbageIsABadResponse() {
        try {
            RecipeAI.decode("I can't help with that.")
            fail("Expected BadResponse")
        } catch (e: OpenRouterFailure.BadResponse) {
            assertEquals("not JSON", e.detail)
        }
    }

    @Test fun emojiValidation() {
        assertEquals("🍽️", RecipeAI.validEmoji("🍽️"))
        assertEquals("☕️", RecipeAI.validEmoji(" ☕️ "))
        assertNull(RecipeAI.validEmoji("1"))
        assertNull(RecipeAI.validEmoji("🍕🍝"))
        assertNull(RecipeAI.validEmoji("pizza"))
    }

    @Test fun promptsCarryTheirSources() {
        val pages = RecipeAI.pagesPrompt(2, "2 cups flour")
        assertTrue(pages.contains("2 photos"))
        assertTrue(pages.contains("2 cups flour"))
        assertFalse(RecipeAI.pagesPrompt(1, " ").contains("text recognition"))

        val structured = StructuredRecipe(name = "Soup", ingredients = listOf("1 onion"))
        val web = RecipeAI.webPrompt(ScrapedPage("https://example.com/soup", "Soup", "Example", structured, "Soup\n1 onion"))
        assertTrue(web.contains("https://example.com/soup"))
        assertTrue(web.contains("- 1 onion"))
        assertTrue(web.contains("Text from the page"))

        val recipe = Recipe(title = "Pie", ingredients = listOf(RecipeItem(text = "Crust", isHeader = true), RecipeItem(text = "1 cup flour")))
        val more = RecipeAI.morePagesPrompt(1, "", recipe)
        assertTrue(more.contains("## Crust\n- 1 cup flour"))
        assertTrue(more.contains("this page"))
    }

    @Test fun aiEmojiPicksMatchingPalette() {
        val recipe = RecipeImporter.makeRecipe(AIReading(ParsedRecipe(title = "Morning Mix"), "☕", listOf("Breakfast")))
        assertEquals("☕", recipe.emoji)
        assertEquals(CoverPalette.COCOA, recipe.palette)
        assertEquals(listOf("Breakfast"), recipe.tags)
    }

    @Test fun promptsMatchIOS() {
        // The two apps send the same instructions, so they read recipes the same way.
        assertTrue(RecipeAI.SYSTEM_PROMPT.startsWith("You are the recipe reader in Pinch"))
        assertTrue(RecipeAI.SYSTEM_PROMPT.contains("chosen from: Breakfast, Lunch, Dinner, Dessert, Baking, Snack, Drinks, Soup, Salad, Pasta, Chicken, Seafood, Vegetarian, Sides, Holidays."))
    }
}

class OpenRouterClientTest {
    @Test fun strictRequestBody() {
        val body = OpenRouterClient.requestBody(
            AIConfig.MODEL, "sys", listOf(OpenRouterClient.Part.Text("hello"), OpenRouterClient.Part.Jpeg(byteArrayOf(1, 2, 3))),
            "recipe", RecipeAI.SCHEMA, maxTokens = 100, strict = true,
        )
        assertEquals("deepseek/deepseek-v4.1-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals(100, body["max_tokens"]!!.jsonPrimitive.int)
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("sys", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        val parts = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AQID", parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        val format = body["response_format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        val schema = format["json_schema"]!!.jsonObject
        assertTrue(schema["strict"]!!.jsonPrimitive.boolean)
        assertEquals("recipe", schema["name"]!!.jsonPrimitive.content)
        assertTrue(body["provider"]!!.jsonObject["require_parameters"]!!.jsonPrimitive.boolean)
        assertEquals("none", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test fun looseRequestBody() {
        val body = OpenRouterClient.requestBody("m", "s", listOf(OpenRouterClient.Part.Text("t")), "recipe", JsonObject(emptyMap()), 10, strict = false)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(body["provider"])
        assertNull(body["reasoning"])
    }

    @Test fun schemaRequiresEveryProperty() {
        val properties = RecipeAI.SCHEMA["properties"]!!.jsonObject
        val required = (RecipeAI.SCHEMA["required"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(properties.keys, required.toSet())
        assertFalse(RecipeAI.SCHEMA["additionalProperties"]!!.jsonPrimitive.boolean)
    }

    @Test fun readsContent() {
        val response = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"```json\n{\"title\":\"A\"}\n```"}}]}"""
        assertEquals("""{"title":"A"}""", OpenRouterClient.content(response))
    }

    @Test fun truncatedAnswerFails() {
        try {
            OpenRouterClient.content("""{"choices":[{"finish_reason":"length","message":{"content":"{\"title\":\"A"}}]}""")
            fail("Expected BadResponse")
        } catch (e: OpenRouterFailure.BadResponse) {
            assertEquals("the answer was cut off", e.detail)
        }
    }

    @Test fun errorsMapToFailures() {
        assertEquals(OpenRouterFailure.InvalidKey, OpenRouterClient.failureFromBody(401, ""))
        assertEquals(OpenRouterFailure.NoCredits, OpenRouterClient.failureFromBody(402, ""))
        assertEquals(OpenRouterFailure.RateLimited, OpenRouterClient.failureFromBody(429, ""))
        assertEquals(
            OpenRouterFailure.Rejected("No endpoints found that can handle the requested parameters."),
            OpenRouterClient.failureFromBody(404, """{"error":{"code":404,"message":"No endpoints found that can handle the requested parameters."}}"""),
        )
        assertEquals(OpenRouterFailure.Unavailable("HTTP 502"), OpenRouterClient.failureFromBody(502, ""))
        assertEquals(OpenRouterFailure.Offline, OpenRouterClient.failure(UnknownHostException("openrouter.ai")))
        assertEquals(OpenRouterFailure.TimedOut, OpenRouterClient.failure(SocketTimeoutException()))
        try {
            OpenRouterClient.content("""{"error":{"code":402,"message":"Insufficient credits"}}""")
            fail("Expected NoCredits")
        } catch (e: OpenRouterFailure) {
            assertEquals(OpenRouterFailure.NoCredits, e)
        }
    }

    @Test fun extractsJsonFromChatter() {
        assertEquals("""{"a":1}""", OpenRouterClient.extractJson("<think>hmm {no}</think>\nHere you go: {\"a\":1} Enjoy!"))
        assertEquals("""{"a":1}""", OpenRouterClient.extractJson("```\n{\"a\":1}\n```"))
    }

    @Test fun keyInfo() {
        val info = OpenRouterClient.keyInfo("""{"data":{"label":"sk-or-v1-abc...xyz","usage":0.0123,"limit":10,"limit_remaining":9.9877,"is_free_tier":false}}""")
        assertEquals(0.0123, info.usage, 0.00001)
        assertEquals(9.9877, info.limitRemaining!!, 0.00001)
        assertFalse(info.isFreeTier)
        assertNull(OpenRouterClient.keyInfo("""{"data":{"label":"x","usage":0,"limit":null,"limit_remaining":null,"is_free_tier":true}}""").limitRemaining)
    }

    @Test fun cleansAndMasksKeys() {
        assertEquals("sk-or-v1-abc123", AIAccount.cleaned("  \"sk-or-v1-abc123\"\n"))
        assertEquals("sk-or-v1-…f3a9", AIAccount.masked("sk-or-v1-0123456789abcdef3a9"))
    }
}

class SourceUrlCodingTest {
    @Test fun sourceUrlRoundTrips() {
        val recipe = Recipe(title = "Soup", source = "Example", sourceUrl = "https://example.com/soup")
        val text = PinchJson.encode(recipe)
        assertTrue(text.contains("\"sourceUrl\": \"https://example.com/soup\""))
        assertEquals("https://example.com/soup", PinchJson.decode(text).sourceUrl)
    }

    @Test fun missingSourceUrlIsEmpty() {
        val decoded = PinchJson.decode("""{"id":"abc","title":"Old recipe"}""")
        assertEquals("", decoded.sourceUrl)
        assertNotNull(decoded)
    }
}
