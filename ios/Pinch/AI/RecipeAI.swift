import Foundation

/// What the AI read, ready to become a recipe.
struct AIReading: Sendable {
    var parsed: ParsedRecipe
    var emoji: String?
    var tags: [String]
}

/// Reads recipes from photos, web pages and pasted text with the model on OpenRouter,
/// and turns its answer into Pinch's recipe format. Mirrored by Android's `RecipeAI.kt`.
struct RecipeAI: Sendable {
    let client: OpenRouterClient

    enum Failure: LocalizedError, Equatable {
        case notARecipe

        var errorDescription: String? { "The AI couldn't find a recipe there." }
    }

    static let tagChoices = [
        "Breakfast", "Lunch", "Dinner", "Dessert", "Baking", "Snack", "Drinks", "Soup", "Salad",
        "Pasta", "Chicken", "Seafood", "Vegetarian", "Sides", "Holidays",
    ]

    static let systemPrompt = """
        You are the recipe reader in Pinch, a recipe box app. You turn photos of recipe cards and \
        cookbook pages, text from web pages, or pasted text into one clean, structured recipe.

        Rules:
        - Copy the recipe faithfully. Keep every ingredient, amount, unit, temperature, time and step \
        exactly as written. Never invent, estimate, convert or leave anything out.
        - Fix obvious reading mistakes (such as "f1our" for "flour" or "3S0°F" for "350°F") and rejoin \
        lines that were split across columns or pages. Read handwriting carefully.
        - ingredients: one ingredient per item, as written, such as "2 cups all-purpose flour, sifted". \
        A group heading such as "For the frosting" is its own item with isHeader true and no colon.
        - steps: one step per item, in order, without step numbers or bullets. Keep the recipe's wording. \
        A heading such as "Make the sauce" is its own item with isHeader true.
        - title: the recipe's name in title case.
        - summary: the recipe's own short introduction or headnote, at most three sentences. Empty if \
        there isn't one. Never write your own.
        - servings: the yield as written, such as "4", "6 to 8" or "24 cookies".
        - prepTime, cookTime, totalTime: short forms such as "15 min" or "1 hr 30 min". Empty when not \
        stated. Don't work out a total that isn't given.
        - notes: tips, variations, storage or make-ahead notes from the recipe, one per line. Empty if none.
        - tags: up to three that fit, chosen from: \(tagChoices.joined(separator: ", ")).
        - emoji: one food emoji that fits the dish.
        - source: the cookbook, website, magazine or person the recipe is credited to, if shown. Otherwise empty.
        - If there are several recipes, read the main one. Ignore ads, navigation, comments, reviews, \
        author bios, nutrition facts and "jump to recipe" links.
        - Keep the recipe's language.
        - If there's no recipe at all, set isRecipe to false and leave everything else empty.

        Answer with only a JSON object with these keys: isRecipe (boolean), title, summary, servings, \
        prepTime, cookTime, totalTime (strings), ingredients and steps (arrays of objects with text \
        (string) and isHeader (boolean)), notes (string), tags (array of strings), emoji, source (strings).
        """

    static let schema: [String: Any] = {
        let item: [String: Any] = [
            "type": "object",
            "additionalProperties": false,
            "required": ["text", "isHeader"],
            "properties": [
                "text": ["type": "string"],
                "isHeader": ["type": "boolean", "description": "True for a group heading such as 'For the sauce'."],
            ],
        ]
        let string: [String: Any] = ["type": "string"]
        return [
            "type": "object",
            "additionalProperties": false,
            "required": ["isRecipe", "title", "summary", "servings", "prepTime", "cookTime", "totalTime",
                         "ingredients", "steps", "notes", "tags", "emoji", "source"],
            "properties": [
                "isRecipe": ["type": "boolean", "description": "False when the content holds no recipe."],
                "title": string,
                "summary": string,
                "servings": string,
                "prepTime": string,
                "cookTime": string,
                "totalTime": string,
                "ingredients": ["type": "array", "items": item],
                "steps": ["type": "array", "items": item],
                "notes": string,
                "tags": ["type": "array", "items": string],
                "emoji": string,
                "source": string,
            ],
        ]
    }()

    // MARK: Reading

    /// Reads photographed pages. `ocrText` is what the phone's own text recognition found,
    /// sent along to help with small print.
    func readPages(_ jpegs: [Data], ocrText: String) async throws -> AIReading {
        try await read(Self.pagesPrompt(pageCount: jpegs.count, ocrText: ocrText), images: jpegs)
    }

    /// Reads extra pages for a recipe that's already underway and returns only what they add.
    func readMorePages(_ jpegs: [Data], ocrText: String, soFar recipe: Recipe) async throws -> AIReading {
        try await read(Self.morePagesPrompt(pageCount: jpegs.count, ocrText: ocrText, soFar: recipe), images: jpegs)
    }

    func readWebPage(_ page: ScrapedPage) async throws -> AIReading {
        try await read(Self.webPrompt(page), images: [])
    }

    func readText(_ text: String) async throws -> AIReading {
        try await read(Self.textPrompt(text), images: [])
    }

    private func read(_ prompt: String, images: [Data]) async throws -> AIReading {
        let parts = [OpenRouterClient.Part.text(prompt)] + images.map { OpenRouterClient.Part.jpeg($0) }
        let content = try await client.completeJSON(system: Self.systemPrompt, user: parts, schemaName: "recipe", schema: Self.schema)
        return try Self.decode(content)
    }

    // MARK: Prompts

    static func pagesPrompt(pageCount: Int, ocrText: String) -> String {
        var prompt = pageCount == 1
            ? "Here's a photo of a recipe."
            : "Here are \(pageCount) photos of one recipe, in page order. The recipe continues from one page to the next."
        let hint = ocrText.trimmingCharacters(in: .whitespacesAndNewlines)
        if !hint.isEmpty {
            prompt += """


                The phone's text recognition read the following from the \(pageCount == 1 ? "photo" : "photos"). \
                It can have mistakes and its line order can be jumbled, so trust the photos where they disagree:
                ---
                \(String(hint.prefix(20_000)))
                ---
                """
        }
        return prompt
    }

    static func morePagesPrompt(pageCount: Int, ocrText: String, soFar recipe: Recipe) -> String {
        func listed(_ items: [RecipeItem]) -> String {
            let lines: [String] = items.map { item in item.isHeader ? "## \(item.text)" : "- \(item.text)" }
            return lines.joined(separator: "\n")
        }
        var summary = "Title: \(recipe.title)\n"
        if !recipe.ingredients.isEmpty {
            summary += "Ingredients so far:\n\(listed(recipe.ingredients))\n"
        }
        if !recipe.steps.isEmpty {
            summary += "Steps so far:\n\(listed(recipe.steps))\n"
        }
        var prompt = """
            \(pageCount == 1 ? "This photo is another page" : "These \(pageCount) photos are more pages") of a recipe \
            that's already been read. Here's the recipe so far:
            ---
            \(summary)---

            Return only what's new on \(pageCount == 1 ? "this page" : "these pages"): the ingredients, steps \
            and notes it adds, continuing where the recipe left off. Don't repeat anything above. Leave title, \
            summary, servings and times empty unless they appear only on the new \(pageCount == 1 ? "page" : "pages").
            """
        let hint = ocrText.trimmingCharacters(in: .whitespacesAndNewlines)
        if !hint.isEmpty {
            prompt += """


                The phone's text recognition read this from the new \(pageCount == 1 ? "page" : "pages") (it can have mistakes):
                ---
                \(String(hint.prefix(20_000)))
                ---
                """
        }
        return prompt
    }

    static func webPrompt(_ page: ScrapedPage) -> String {
        var prompt = """
            This comes from a web page.
            Address: \(page.url.absoluteString)
            Page title: \(page.title)
            Site: \(page.siteName)
            """
        if let structured = page.structured {
            prompt += """


                The page's recipe markup (schema.org) says:
                ---
                \(structured.promptText)
                ---
                Use the markup as the main source. Use the page text below to add group headings for \
                ingredients and steps, the introduction, and notes, and to fix anything the markup garbled.
                """
        }
        prompt += """


            Text from the page:
            ---
            \(page.text)
            ---
            """
        return prompt
    }

    static func textPrompt(_ text: String) -> String {
        """
        Here's a recipe someone pasted in:
        ---
        \(String(text.prefix(60_000)))
        ---
        """
    }

    // MARK: Decoding

    /// Turns the model's JSON into a parsed recipe, cleaning up anything it got slightly wrong.
    static func decode(_ content: String) throws -> AIReading {
        let json = OpenRouterClient.extractJSON(content)
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw OpenRouterClient.Failure.badResponse("not JSON")
        }
        if let isRecipe = object["isRecipe"] as? Bool, !isRecipe { throw Failure.notARecipe }

        func string(_ key: String) -> String {
            switch object[key] {
            case let s as String: s.trimmingCharacters(in: .whitespacesAndNewlines)
            case let n as NSNumber: n.stringValue
            case let list as [Any]: list.compactMap { $0 as? String }.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
            default: ""
            }
        }

        func items(_ key: String, isStep: Bool) -> [RecipeItem] {
            guard let list = object[key] as? [Any] else { return [] }
            return list.compactMap { element -> RecipeItem? in
                var text: String
                var isHeader = false
                if let s = element as? String {
                    text = s
                } else if let dict = element as? [String: Any] {
                    text = dict["text"] as? String ?? ""
                    isHeader = dict["isHeader"] as? Bool ?? false
                } else {
                    return nil
                }
                text = cleanedItemText(text, isStep: isStep)
                if text.hasSuffix(":") {
                    text = String(text.dropLast()).trimmingCharacters(in: .whitespaces)
                    if !isStep || text.count < 40 { isHeader = true }
                }
                return text.isEmpty ? nil : RecipeItem(text: text, isHeader: isHeader)
            }
        }

        var parsed = ParsedRecipe()
        parsed.title = string("title")
        parsed.summary = string("summary")
        parsed.servings = string("servings")
        parsed.prepTime = DurationParser.normalized(string("prepTime"))
        parsed.cookTime = DurationParser.normalized(string("cookTime"))
        parsed.totalTime = DurationParser.normalized(string("totalTime"))
        parsed.ingredients = items("ingredients", isStep: false)
        parsed.steps = items("steps", isStep: true)
        parsed.notes = string("notes")
        parsed.source = string("source")

        let tags = (object["tags"] as? [Any] ?? [])
            .compactMap { ($0 as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && $0.count <= 24 }
        var seen = Set<String>()
        let uniqueTags = tags.filter { seen.insert($0.lowercased()).inserted }

        return AIReading(parsed: parsed, emoji: validEmoji(string("emoji")), tags: Array(uniqueTags.prefix(3)))
    }

    /// Drops bullets, checkboxes and step numbers the model sometimes keeps from the page.
    static func cleanedItemText(_ raw: String, isStep: Bool) -> String {
        var text = raw.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        text = text.replacingOccurrences(of: "^[•·▪▢☐□○◦‣*\\-–—]+\\s*", with: "", options: .regularExpression)
        if isStep {
            text = text.replacingOccurrences(of: "^(?i:step\\s*)?\\d{1,2}\\s*[.):]\\s+", with: "", options: .regularExpression)
            text = text.replacingOccurrences(of: "^(?i:step)\\s*\\d{1,2}\\s+", with: "", options: .regularExpression)
        }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// One emoji, or nil if the model sent words or several.
    static func validEmoji(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count == 1, let first = trimmed.unicodeScalars.first,
              first.properties.isEmojiPresentation || (first.properties.isEmoji && first.value > 0xFF) else { return nil }
        return trimmed
    }
}
