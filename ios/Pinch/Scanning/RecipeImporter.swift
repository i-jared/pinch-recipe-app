import UIKit
import os

private let log = Logger(subsystem: "Pinch", category: "Import")

/// Turns scanned pages, web pages or pasted text into a recipe draft ready for review.
/// With an OpenRouter key saved, the AI reads everything; without one, or when it can't be
/// reached, the phone's own reader takes over.
@MainActor
enum RecipeImporter {
    enum Stage: Equatable {
        /// Finding the words on each page with the phone's text recognition.
        case reading(page: Int, of: Int)
        /// Tidying with Apple Intelligence.
        case tidying
        case fetching(host: String)
        case aiReading(AISource)
    }

    enum AISource: Equatable {
        case pages(Int)
        case website
        case text
    }

    /// Who turned the source into a recipe, shown on the review screen.
    enum ReadBy: Equatable {
        case phone
        case appleIntelligence
        case ai
        /// The website's own recipe markup, used as is.
        case website
        /// Nothing to read: the recipe is being typed in.
        case typing
        /// AI reading was tried but didn't work, so the phone's reader filled in.
        case fallback(reason: String)
    }

    enum ImportError: LocalizedError {
        case noText
        case aiFailedNoMarkup(String)

        var errorDescription: String? {
            switch self {
            case .noText:
                "Couldn't find any words in that photo. Try again in brighter light, or type the recipe in."
            case .aiFailedNoMarkup(let reason):
                "AI reading didn't work (\(reason)), and that page doesn't label its recipe for Pinch to copy. Try again in a moment, or copy the recipe and paste it in."
            }
        }
    }

    // MARK: Photos

    static func importPages(
        _ images: [UIImage],
        photos: PhotoStore,
        ai: OpenRouterClient?,
        smartParsing: Bool,
        onStage: (Stage) -> Void
    ) async throws -> (recipe: Recipe, readBy: ReadBy) {
        var names: [String] = []
        var lines: [TextLine] = []
        do {
            for (index, image) in images.enumerated() {
                onStage(.reading(page: index + 1, of: images.count))
                let name = try photos.save(image)
                names.append(name)
                let upright = photos.image(named: name) ?? image
                lines += try await TextRecognizer.recognize(upright, page: index)
            }
        } catch {
            names.forEach(photos.delete)
            throw error
        }

        var parsed = RecipeParser.parse(lines: lines)
        var fallbackReason: String?
        if let ai {
            onStage(.aiReading(.pages(names.count)))
            do {
                let jpegs = await aiJPEGs(names.compactMap(photos.image(named:)))
                var reading = try await RecipeAI(client: ai).readPages(jpegs, ocrText: parsed.rawText)
                reading.parsed.rawText = parsed.rawText
                var recipe = makeRecipe(from: reading)
                recipe.photos = names
                return (recipe, .ai)
            } catch {
                if Task.isCancelled {
                    names.forEach(photos.delete)
                    throw CancellationError()
                }
                fallbackReason = (error as? RecipeAI.Failure) == .notARecipe
                    ? "the AI didn't see a recipe in \(names.count == 1 ? "that photo" : "those photos")"
                    : reason(for: error)
                log.error("AI reading failed: \(String(describing: error), privacy: .public)")
            }
        }

        // Handwriting the phone can't read may still be readable by the AI, so this check
        // only comes once the AI has had its turn.
        guard !parsed.rawText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            names.forEach(photos.delete)
            throw ImportError.noText
        }

        var readBy: ReadBy = fallbackReason.map { .fallback(reason: $0) } ?? .phone
        if smartParsing && SmartParsing.isAvailable {
            onStage(.tidying)
            if let refined = await SmartParsing.refine(parsed) {
                parsed = refined
                if fallbackReason == nil { readBy = .appleIntelligence }
            }
        }

        var recipe = makeRecipe(from: parsed)
        recipe.photos = names
        return (recipe, readBy)
    }

    /// Reads more pages and appends what they contain to an existing draft.
    static func appendPages(
        _ images: [UIImage],
        to recipe: inout Recipe,
        photos: PhotoStore,
        ai: OpenRouterClient?,
        onStage: (Stage) -> Void
    ) async throws {
        let section: RecipeParser.Section = !recipe.steps.isEmpty ? .steps : (!recipe.ingredients.isEmpty ? .ingredients : .preamble)
        var lines: [TextLine] = []
        var names: [String] = []
        let firstPage = recipe.photos.count
        do {
            for (index, image) in images.enumerated() {
                onStage(.reading(page: index + 1, of: images.count))
                let name = try photos.save(image)
                names.append(name)
                lines += try await TextRecognizer.recognize(photos.image(named: name) ?? image, page: firstPage + index)
            }
        } catch {
            names.forEach(photos.delete)
            throw error
        }
        let parsed = RecipeParser.parse(lines: lines, continuing: section)

        if let ai {
            onStage(.aiReading(.pages(names.count)))
            do {
                let jpegs = await aiJPEGs(names.compactMap(photos.image(named:)))
                let reading = try await RecipeAI(client: ai).readMorePages(jpegs, ocrText: parsed.rawText, soFar: recipe)
                let more = reading.parsed
                recipe.photos += names
                recipe.ingredients += more.ingredients
                recipe.steps += more.steps
                if recipe.title.isEmpty { recipe.title = more.title }
                if recipe.summary.isEmpty { recipe.summary = more.summary }
                fillGaps(in: &recipe, from: more)
                recipe.rawText = [recipe.rawText, parsed.rawText].filter { !$0.isEmpty }.joined(separator: "\n\n")
                return
            } catch {
                if Task.isCancelled {
                    names.forEach(photos.delete)
                    throw CancellationError()
                }
                log.error("AI reading of extra pages failed: \(String(describing: error), privacy: .public)")
            }
        }

        recipe.photos += names
        recipe.ingredients += parsed.ingredients
        recipe.steps += parsed.steps
        if !parsed.summary.isEmpty {
            // Unlabeled text on a later page is most likely more method.
            recipe.steps += parsed.summary.split(separator: "\n").map { RecipeItem(text: String($0)) }
        }
        fillGaps(in: &recipe, from: parsed)
        recipe.rawText = [recipe.rawText, parsed.rawText].filter { !$0.isEmpty }.joined(separator: "\n\n")
    }

    private static func fillGaps(in recipe: inout Recipe, from parsed: ParsedRecipe) {
        if !parsed.notes.isEmpty { recipe.notes = [recipe.notes, parsed.notes].filter { !$0.isEmpty }.joined(separator: "\n") }
        if recipe.servings.isEmpty { recipe.servings = parsed.servings }
        if recipe.prepTime.isEmpty { recipe.prepTime = parsed.prepTime }
        if recipe.cookTime.isEmpty { recipe.cookTime = parsed.cookTime }
        if recipe.totalTime.isEmpty { recipe.totalTime = parsed.totalTime }
        if recipe.source.isEmpty { recipe.source = parsed.source }
    }

    // MARK: Text

    static func importText(
        _ text: String,
        ai: OpenRouterClient?,
        smartParsing: Bool,
        onStage: (Stage) -> Void
    ) async throws -> (recipe: Recipe, readBy: ReadBy) {
        var parsed = RecipeParser.parse(text: text)
        var fallbackReason: String?
        if let ai {
            onStage(.aiReading(.text))
            do {
                var reading = try await RecipeAI(client: ai).readText(text)
                reading.parsed.rawText = parsed.rawText
                return (makeRecipe(from: reading), .ai)
            } catch {
                if Task.isCancelled { throw CancellationError() }
                fallbackReason = (error as? RecipeAI.Failure) == .notARecipe ? "the AI didn't see a recipe in that text" : reason(for: error)
                log.error("AI reading of text failed: \(String(describing: error), privacy: .public)")
            }
        }
        var readBy: ReadBy = fallbackReason.map { .fallback(reason: $0) } ?? .phone
        if smartParsing && SmartParsing.isAvailable {
            onStage(.tidying)
            if let refined = await SmartParsing.refine(parsed) {
                parsed = refined
                if fallbackReason == nil { readBy = .appleIntelligence }
            }
        }
        return (makeRecipe(from: parsed), readBy)
    }

    /// Pasted or shared text that is nothing but a link should be opened, not parsed.
    nonisolated static func link(in text: String) -> URL? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.range(of: "^(https?://|www\\.)\\S+$", options: [.regularExpression, .caseInsensitive]) != nil else { return nil }
        return WebRecipeImporter.normalizedURL(trimmed)
    }

    // MARK: Websites

    static func importURL(_ url: URL, ai: OpenRouterClient?, onStage: (Stage) -> Void) async throws -> (recipe: Recipe, readBy: ReadBy) {
        onStage(.fetching(host: displayHost(url)))
        var page: ScrapedPage
        do {
            #if DEBUG
            // `-PinchForceWebView YES` exercises the web view path on any site.
            if UserDefaults.standard.bool(forKey: "PinchForceWebView") { throw WebRecipeImporter.Failure.blocked }
            #endif
            let fetched = try await WebRecipeImporter.fetch(url)
            page = await parse(fetched.html, url: fetched.url)
            if page.structured == nil && page.text.count < 500 {
                // Probably built with JavaScript; a browser engine sees the finished page.
                if let rendered = try? await WebViewPageLoader.load(url) {
                    page = await parse(rendered.html, url: rendered.url)
                }
            }
        } catch WebRecipeImporter.Failure.blocked {
            // A bot check turned the plain request away. A real browser engine usually gets in.
            log.info("Plain fetch was blocked; trying a web view")
            let rendered = try await WebViewPageLoader.load(url)
            page = await parse(rendered.html, url: rendered.url)
        }
        log.info("Page has markup: \(page.structured != nil), text: \(page.text.count) characters")

        var fallbackReason: String?
        if let ai {
            onStage(.aiReading(.website))
            do {
                var reading = try await RecipeAI(client: ai).readWebPage(page)
                reading.parsed.rawText = page.structured?.promptText ?? String(page.text.prefix(8000))
                var recipe = makeRecipe(from: reading)
                finishWebRecipe(&recipe, page: page)
                return (recipe, .ai)
            } catch {
                if Task.isCancelled { throw CancellationError() }
                if (error as? RecipeAI.Failure) == .notARecipe {
                    guard page.structured != nil else { throw WebRecipeImporter.Failure.noRecipe }
                    fallbackReason = "the AI didn't find a recipe on the page"
                } else {
                    fallbackReason = reason(for: error)
                }
                log.error("AI reading of web page failed: \(String(describing: error), privacy: .public)")
            }
        }

        guard let structured = page.structured else {
            if let fallbackReason { throw ImportError.aiFailedNoMarkup(fallbackReason) }
            throw WebRecipeImporter.Failure.needsAI
        }
        var recipe = makeRecipe(from: structured.parsed)
        finishWebRecipe(&recipe, page: page)
        return (recipe, fallbackReason.map { .fallback(reason: $0) } ?? .website)
    }

    private static func parse(_ html: String, url: URL) async -> ScrapedPage {
        await Task.detached(priority: .userInitiated) {
            WebRecipeImporter.parse(html: html, url: url)
        }.value
    }

    private static func finishWebRecipe(_ recipe: inout Recipe, page: ScrapedPage) {
        recipe.sourceURL = page.url.absoluteString
        if !page.siteName.isEmpty { recipe.source = page.siteName }
        if recipe.title.isEmpty { recipe.title = page.title }
    }

    nonisolated static func displayHost(_ url: URL) -> String {
        (url.host ?? url.absoluteString).replacingOccurrences(of: "^www\\.", with: "", options: .regularExpression)
    }

    // MARK: Building recipes

    static func makeRecipe(from parsed: ParsedRecipe) -> Recipe {
        var recipe = Recipe(
            title: parsed.title,
            summary: parsed.summary,
            source: parsed.source,
            servings: parsed.servings,
            prepTime: parsed.prepTime,
            cookTime: parsed.cookTime,
            totalTime: parsed.totalTime,
            ingredients: parsed.ingredients,
            steps: parsed.steps,
            notes: parsed.notes,
            rawText: parsed.rawText
        )
        let ingredientTexts = parsed.ingredients.map(\.text)
        let suggestion = CoverSuggester.suggest(title: parsed.title, ingredients: ingredientTexts)
        recipe.emoji = suggestion.emoji
        recipe.palette = suggestion.palette
        recipe.pattern = CoverSuggester.pattern(for: recipe.id)
        recipe.tags = CoverSuggester.suggestTags(title: parsed.title, ingredients: ingredientTexts)
        return recipe
    }

    /// Like `makeRecipe(from:)`, with the AI's emoji and tags where it chose them.
    static func makeRecipe(from reading: AIReading) -> Recipe {
        var recipe = makeRecipe(from: reading.parsed)
        if let emoji = reading.emoji {
            recipe.emoji = emoji
            let bare = emoji.replacingOccurrences(of: "\u{FE0F}", with: "")
            if let entry = CoverSuggester.table.first(where: { $0.emoji.replacingOccurrences(of: "\u{FE0F}", with: "") == bare }) {
                recipe.palette = entry.palette
            }
        }
        if !reading.tags.isEmpty { recipe.tags = reading.tags }
        return recipe
    }

    /// Page photos sized for the AI: big enough for small print, small enough to send quickly.
    nonisolated static func aiJPEGs(_ images: [UIImage]) async -> [Data] {
        await Task.detached(priority: .userInitiated) {
            images.compactMap { ImageTools.normalized($0, maxDimension: 2000).jpegData(compressionQuality: 0.75) }
        }.value
    }

    /// A short, lowercase reason for the review banner.
    private static func reason(for error: Error) -> String {
        if let failure = error as? OpenRouterClient.Failure {
            switch failure {
            case .offline: return "you're offline"
            case .invalidKey: return "OpenRouter didn't accept your key"
            case .sessionExpired: return "your Pinch sign-in expired"
            case .noCredits: return "your OpenRouter account is out of credit"
            case .rateLimited: return "OpenRouter was busy"
            case .timedOut: return "the AI took too long"
            case .rejected, .unavailable: return "OpenRouter couldn't run the model"
            case .badResponse: return "the AI's answer didn't make sense"
            }
        }
        let text = error.localizedDescription
        return text.prefix(1).lowercased() + text.dropFirst().trimmingCharacters(in: CharacterSet(charactersIn: "."))
    }
}
