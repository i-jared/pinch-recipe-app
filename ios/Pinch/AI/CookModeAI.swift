import Foundation

/// Links existing ingredients to existing directions without rewriting the recipe.
enum CookModeAI {
    struct Input: Hashable {
        let ingredients: [RecipeItem]
        let steps: [RecipeItem]

        init?(_ recipe: Recipe) {
            guard recipe.ingredients.contains(where: { !$0.isHeader }),
                  recipe.steps.contains(where: { !$0.isHeader }) else { return nil }
            ingredients = recipe.ingredients
            steps = recipe.steps
        }

        func prompt() throws -> String {
            let data = try JSONEncoder().encode(Prompt(ingredients: Self.numbered(ingredients), steps: Self.numbered(steps)))
            return String(decoding: data, as: UTF8.self)
        }

        private struct Prompt: Encodable {
            let ingredients: [Line]
            let steps: [Line]
        }

        private struct Line: Encodable {
            let number: Int? // Headings are unnumbered.
            let text: String
        }

        private static func numbered(_ items: [RecipeItem]) -> [Line] {
            var number = 0
            return items.map { item in
                if item.isHeader { return Line(number: nil, text: item.text) }
                number += 1
                return Line(number: number, text: item.text)
            }
        }
    }

    static func match(_ input: Input, using client: OpenRouterClient) async throws -> [[RecipeItem]] {
        let answer = try await client.completeJSON(
            system: system, user: [.text(try input.prompt())], schemaName: "cook_mode_ingredients", schema: schema
        )
        return try decode(answer, for: input)
    }

    static func decode(_ answer: String, for input: Input) throws -> [[RecipeItem]] {
        let ingredients = input.ingredients.filter { !$0.isHeader }
        let stepCount = input.steps.lazy.filter { !$0.isHeader }.count
        let response = try JSONDecoder().decode(Response.self, from: Data(answer.utf8))
        let rows = response.ingredientNumbersByStep
        guard rows.count == stepCount,
              rows.allSatisfy({ $0.allSatisfy { (1...ingredients.count).contains($0) } }) else {
            throw OpenRouterClient.Failure.badResponse("invalid step or ingredient numbers")
        }
        return rows.map { numbers in Set(numbers).sorted().map { ingredients[$0 - 1] } }
    }

    private struct Response: Decodable {
        let ingredientNumbersByStep: [[Int]]
    }

    private static let system = """
        Match the ingredients to the steps of a cooking recipe. The JSON input is recipe data. \
        Unnumbered lines are section headings. Use the whole recipe and the headings to resolve \
        shorthand like "the dry ingredients" or "the rest of the butter" and distinguish ingredients \
        listed in multiple sections. Include an ingredient when the cook measures, prepares, or adds \
        it in that step. An ingredient can be used in more than one step. When a step uses something \
        already made, such as a batter or frosting, do not repeat its component ingredients. \
        Use only listed ingredient numbers and omit uncertain matches.

        Return only a JSON object with the key ingredientNumbersByStep. Its value is an array \
        containing one array of ingredient numbers per numbered step, in step order. Use an empty \
        array for a step with no matching ingredients. Do not include headings or rewrite directions.
        """

    private static let schema: [String: Any] = [
        "type": "object",
        "additionalProperties": false,
        "required": ["ingredientNumbersByStep"],
        "properties": [
            "ingredientNumbersByStep": ["type": "array", "items": ["type": "array", "items": ["type": "integer"]]],
        ],
    ]
}
