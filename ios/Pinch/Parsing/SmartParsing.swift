import Foundation
import os
#if canImport(FoundationModels)
import FoundationModels
#endif

private let log = Logger(subsystem: "Pinch", category: "SmartParsing")

/// Optional clean-up pass with Apple's on-device language model (iOS 26+, Apple
/// Intelligence devices). It runs entirely on the phone, so it works offline. When the
/// model isn't available, or it fails, the rule-based parser's result is used as is.
enum SmartParsing {
    static var isAvailable: Bool {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            return SystemLanguageModel.default.isAvailable
        }
        #endif
        return false
    }

    /// Returns an improved parse, or nil if the model couldn't help.
    static func refine(_ parsed: ParsedRecipe) async -> ParsedRecipe? {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *), SystemLanguageModel.default.isAvailable {
            return await ModelRefiner.refine(parsed)
        }
        #endif
        return nil
    }
}

#if canImport(FoundationModels)
@available(iOS 26.0, *)
@Generable
struct GeneratedRecipe {
    @Guide(description: "The recipe's name as printed, in title case. Empty if the text has no name.")
    var title: String

    @Guide(description: "The short introduction or headnote, if there is one. Otherwise empty.")
    var summary: String

    @Guide(description: "Servings or yield as written, such as '4' or '24 cookies'. Empty if not stated.")
    var servings: String

    @Guide(description: "Prep time as written, such as '15 min'. Empty if not stated.")
    var prepTime: String

    @Guide(description: "Cook or bake time as written. Empty if not stated.")
    var cookTime: String

    @Guide(description: "Total time as written. Empty if not stated.")
    var totalTime: String

    @Guide(description: "Every ingredient line in order, with its quantity and unit exactly as written. Group headings such as 'For the frosting:' are their own line ending in a colon.")
    var ingredients: [String]

    @Guide(description: "Every instruction step in order, without step numbers. Keep the recipe's wording.")
    var steps: [String]

    @Guide(description: "Tips, variations or storage notes. Empty if none.")
    var notes: String
}

@available(iOS 26.0, *)
private enum ModelRefiner {
    static let instructions = """
        You turn text scanned from recipe cards and cookbook pages into a structured recipe. \
        The text comes from OCR, so lines may break in odd places and a few characters may be \
        misread. Fix obvious recognition mistakes and rejoin broken lines. Never invent \
        ingredients, quantities, times or steps that are not in the text, and never drop any.
        """

    static func refine(_ parsed: ParsedRecipe) async -> ParsedRecipe? {
        let text = parsed.rawText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count >= 40, text.count <= 5000 else {
            log.info("Skipping model: \(text.count) characters")
            return nil
        }
        let session = LanguageModelSession(instructions: instructions)
        let started = Date()
        do {
            let response = try await session.respond(
                to: "Scanned recipe text:\n\n\(text)",
                generating: GeneratedRecipe.self,
                options: GenerationOptions(sampling: .greedy)
            )
            let merged = merge(response.content, into: parsed)
            log.info("Model finished in \(Date().timeIntervalSince(started), format: .fixed(precision: 1))s: \(response.content.ingredients.count) ingredients, \(response.content.steps.count) steps, kept=\(merged != nil)")
            return merged
        } catch {
            log.error("Model failed after \(Date().timeIntervalSince(started), format: .fixed(precision: 1))s: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    /// Uses the model's structure but keeps the rule-based values where the model left gaps.
    static func merge(_ generated: GeneratedRecipe, into parsed: ParsedRecipe) -> ParsedRecipe? {
        let ingredients = generated.ingredients.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        let steps = generated.steps.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        // A model result that lost most of the recipe is worse than the heuristic one.
        let parsedCount = parsed.ingredients.count + parsed.steps.count
        guard ingredients.count + steps.count >= max(1, parsedCount / 2) else { return nil }

        func pick(_ model: String, _ fallback: String) -> String {
            let trimmed = model.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? fallback : trimmed
        }

        var result = parsed
        result.title = pick(generated.title, parsed.title)
        result.summary = pick(generated.summary, parsed.summary)
        result.servings = pick(generated.servings, parsed.servings)
        result.prepTime = DurationParser.normalized(pick(generated.prepTime, parsed.prepTime))
        result.cookTime = DurationParser.normalized(pick(generated.cookTime, parsed.cookTime))
        result.totalTime = DurationParser.normalized(pick(generated.totalTime, parsed.totalTime))
        result.notes = pick(generated.notes, parsed.notes)
        result.ingredients = ingredients.map { line in
            line.hasSuffix(":") ? RecipeItem(text: String(line.dropLast()), isHeader: true) : RecipeItem(text: line)
        }
        result.steps = steps.map { RecipeItem(text: $0) }
        return result
    }
}
#endif
