import Foundation

/// A fetched recipe page, boiled down to what's needed to read it.
struct ScrapedPage: Sendable {
    var url: URL
    var title: String
    var siteName: String
    /// The page's schema.org Recipe markup, if it has any. Most recipe sites do.
    var structured: StructuredRecipe?
    /// The page's readable text, trimmed around the recipe when the page is very long.
    var text: String
}

/// A recipe from a page's schema.org markup (JSON-LD).
struct StructuredRecipe: Sendable, Equatable {
    var name = ""
    var description = ""
    var yield = ""
    var prepTime = ""
    var cookTime = ""
    var totalTime = ""
    var ingredients: [String] = []
    /// Steps, with section names (HowToSection) as header items.
    var instructions: [RecipeItem] = []
    var author = ""
    var publisher = ""
    var categories: [String] = []

    var isUseful: Bool { !ingredients.isEmpty || !instructions.isEmpty }

    /// A plain-text rendering for the AI prompt.
    var promptText: String {
        var lines: [String] = []
        func add(_ label: String, _ value: String) { if !value.isEmpty { lines.append("\(label): \(value)") } }
        add("Name", name)
        add("Description", description)
        add("Yield", yield)
        add("Prep time", prepTime)
        add("Cook time", cookTime)
        add("Total time", totalTime)
        add("Author", author)
        add("Categories", categories.joined(separator: ", "))
        if !ingredients.isEmpty {
            lines.append("Ingredients:")
            lines += ingredients.map { "- \($0)" }
        }
        if !instructions.isEmpty {
            lines.append("Instructions:")
            lines += instructions.map { $0.isHeader ? "## \($0.text)" : "- \($0.text)" }
        }
        return lines.joined(separator: "\n")
    }

    /// The markup mapped straight into a recipe, for when AI reading isn't set up.
    var parsed: ParsedRecipe {
        var result = ParsedRecipe()
        result.title = name
        result.summary = description
        result.servings = yield
        result.prepTime = prepTime
        result.cookTime = cookTime
        result.totalTime = totalTime
        result.ingredients = ingredients.map { RecipeItem(text: $0) }
        result.steps = instructions
        result.source = publisher.isEmpty ? author : publisher
        result.rawText = promptText
        return result
    }
}

/// Fetches a recipe web page and finds the recipe in it. Mirrored by Android's `WebRecipeImporter.kt`.
enum WebRecipeImporter {
    enum Failure: LocalizedError, Equatable {
        case badAddress
        case offline
        case blocked
        case notFound
        case notAWebPage
        case unreachable(String)
        case noRecipe
        case needsAI

        var errorDescription: String? {
            switch self {
            case .badAddress: "That doesn't look like a web address. Check it and try again."
            case .offline: "You're offline. Connect to the internet to add a recipe from a website."
            case .blocked: "That website didn't let Pinch in. Open it in your browser, copy the recipe, and paste it in instead."
            case .notFound: "That page doesn't exist anymore. Check the address and try again."
            case .notAWebPage: "That link isn't a web page Pinch can read."
            case .unreachable(let detail): "Couldn't open that page (\(detail))."
            case .noRecipe: "Couldn't find a recipe on that page. If you can see it in your browser, copy it and paste it in instead."
            case .needsAI: "That page doesn't label its recipe, so Pinch needs AI reading to pick it out. Add an OpenRouter key in Settings, or copy the recipe and paste it in instead."
            }
        }
    }

    /// What Safari on this iPhone calls itself, so sites send the same page they'd show there.
    /// (Safari keeps the OS version in its user agent frozen at 18.6.)
    static var userAgent: String {
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) \(safariApplicationName)"
    }

    static var safariApplicationName: String {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        return "Version/\(version.majorVersion).\(version.minorVersion) Mobile/15E148 Safari/604.1"
    }
    static let textLimit = 60_000
    /// Pages bigger than this are cut off; recipe pages are far smaller.
    static let maxBytes = 6_000_000

    // MARK: Addresses

    /// Finds a web address in typed or shared text ("Look at this! https://…") and makes it https.
    static func normalizedURL(_ input: String) -> URL? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        var candidate: String?
        if let range = trimmed.range(of: "https?://[^\\s<>\"']+", options: [.regularExpression, .caseInsensitive]) {
            candidate = String(trimmed[range])
        } else if !trimmed.contains(" ") {
            candidate = "https://" + trimmed
        }
        guard var text = candidate else { return nil }
        // Trailing punctuation from a sentence isn't part of the link.
        while let last = text.last, ".,;:!?)]}".contains(last) { text.removeLast() }
        guard var components = URLComponents(string: text),
              let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = components.host, host.contains("."), !host.hasPrefix("."), !host.hasSuffix("."),
              !host.contains("..") else { return nil }
        components.scheme = "https"
        return components.url
    }

    // MARK: Fetching

    /// A fresh session per page: nothing a site sets (cookies, cached HTTP/3 routes that can go
    /// stale on some networks) carries over to the next import.
    static func freshSession(timeout: TimeInterval) -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = timeout
        configuration.urlCache = nil
        return URLSession(configuration: configuration)
    }

    static func fetch(_ url: URL) async throws -> (html: String, url: URL) {
        let session = freshSession(timeout: 30)
        defer { session.finishTasksAndInvalidate() }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", forHTTPHeaderField: "Accept")
        request.setValue(acceptLanguage, forHTTPHeaderField: "Accept-Language")
        do {
            // Streamed, so a link to a video or PDF is turned away before it downloads.
            let (stream, response) = try await session.bytes(for: request)
            guard let http = response as? HTTPURLResponse else { throw Failure.notAWebPage }
            switch http.statusCode {
            case 200..<300: break
            case 401, 403, 429, 503: throw Failure.blocked
            case 404, 410: throw Failure.notFound
            default: throw Failure.unreachable("error \(http.statusCode)")
            }
            let mime = http.mimeType?.lowercased() ?? ""
            guard mime.isEmpty || mime.contains("html") || mime.contains("xml") || mime.hasPrefix("text/") else {
                throw Failure.notAWebPage
            }
            var data = Data()
            data.reserveCapacity(min(max(Int(http.expectedContentLength), 256_000), maxBytes))
            for try await byte in stream {
                data.append(byte)
                if data.count >= maxBytes { break }
            }
            let html = decode(data, encodingName: http.textEncodingName)
            if isChallengePage(html) { throw Failure.blocked }
            return (html, http.url ?? url)
        } catch let error as URLError {
            switch error.code {
            case .cancelled:
                throw CancellationError()
            case .notConnectedToInternet, .networkConnectionLost, .dataNotAllowed, .internationalRoamingOff:
                throw Failure.offline
            case .cannotFindHost, .dnsLookupFailed:
                throw Failure.unreachable("no website at that address")
            case .timedOut:
                throw Failure.unreachable("it took too long to load")
            default:
                throw Failure.unreachable(error.localizedDescription)
            }
        }
    }

    private static var acceptLanguage: String {
        let languages = Locale.preferredLanguages.prefix(3)
        guard !languages.isEmpty else { return "en-US,en;q=0.9" }
        return languages.enumerated().map { index, language in
            index == 0 ? language : "\(language);q=\(String(format: "%.1f", 1 - Double(index) * 0.2))"
        }.joined(separator: ",")
    }

    static func decode(_ data: Data, encodingName: String?) -> String {
        if let name = encodingName {
            let cf = CFStringConvertIANACharSetNameToEncoding(name as CFString)
            if cf != kCFStringEncodingInvalidId {
                let encoding = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(cf))
                if let text = String(data: data, encoding: encoding) { return text }
            }
        }
        return String(data: data, encoding: .utf8) ?? String(data: data, encoding: .windowsCP1252) ?? String(decoding: data, as: UTF8.self)
    }

    /// Bot checks ("Just a moment…") come back as ordinary pages with no recipe in them.
    static func isChallengePage(_ html: String) -> Bool {
        let head = html.prefix(6000).lowercased()
        return head.contains("<title>just a moment") || head.contains("cf-browser-verification")
            || head.contains("challenge-platform") && !html.contains("application/ld+json")
    }

    // MARK: Parsing

    static func parse(html: String, url: URL) -> ScrapedPage {
        let metas = metaTags(in: html)
        func meta(_ key: String) -> String? {
            for tag in metas where (tag["property"] ?? tag["name"])?.lowercased() == key {
                let value = clean(tag["content"] ?? "")
                if !value.isEmpty { return value }
            }
            return nil
        }
        let structured = structuredRecipe(in: html)
        let htmlTitle = firstGroup("<title\\b[^>]*>([\\s\\S]*?)</title\\s*>", in: html).map(clean)
        let host = (url.host ?? "").replacingOccurrences(of: "^www\\.", with: "", options: .regularExpression)
        func firstFilled(_ candidates: [String?]) -> String? {
            candidates.compactMap { $0 }.first { !$0.isEmpty }
        }
        let title = firstFilled([structured?.name, meta("og:title"), htmlTitle]) ?? ""
        let siteName = firstFilled([meta("og:site_name"), structured?.publisher, meta("application-name")]) ?? host
        let text = focusedText(readableText(fromHTML: html), limit: textLimit)
        return ScrapedPage(url: url, title: title, siteName: siteName, structured: structured, text: text)
    }

    // MARK: Structured data

    static func structuredRecipe(in html: String) -> StructuredRecipe? {
        let pattern = "<script\\b[^>]*application/ld\\+json[^>]*>([\\s\\S]*?)</script\\s*>"
        for block in allGroups(pattern, in: html) {
            var body = block.trimmingCharacters(in: .whitespacesAndNewlines)
            body = body.replacingOccurrences(of: "^(<!--|<!\\[CDATA\\[)", with: "", options: .regularExpression)
            body = body.replacingOccurrences(of: "(-->|\\]\\]>)$", with: "", options: .regularExpression)
            guard let json = parseJSON(body), let node = findRecipe(in: json, depth: 0) else { continue }
            let recipe = map(node)
            if recipe.isUseful { return recipe }
        }
        return nil
    }

    private static func parseJSON(_ text: String) -> Any? {
        if let data = text.data(using: .utf8), let value = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) {
            return value
        }
        // Hand-written markup often has raw line breaks or tabs inside strings.
        let relaxed = text.replacingOccurrences(of: "[\\n\\r\\t]", with: " ", options: .regularExpression)
        guard let data = relaxed.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }

    private static func findRecipe(in value: Any, depth: Int) -> [String: Any]? {
        guard depth < 10 else { return nil }
        if let dict = value as? [String: Any] {
            if isType(dict["@type"], "recipe") { return dict }
            let preferred = ["@graph", "mainEntity", "mainEntityOfPage", "itemListElement", "item", "hasPart"]
            let others = dict.keys.filter { !preferred.contains($0) }.sorted()
            for key in preferred + others {
                if let child = dict[key], child is [Any] || child is [String: Any],
                   let found = findRecipe(in: child, depth: depth + 1) {
                    return found
                }
            }
        } else if let array = value as? [Any] {
            for element in array {
                if let found = findRecipe(in: element, depth: depth + 1) { return found }
            }
        }
        return nil
    }

    /// Matches "Recipe", "schema:Recipe", "https://schema.org/Recipe" or an array containing one.
    private static func isType(_ value: Any?, _ type: String) -> Bool {
        if let list = value as? [Any] { return list.contains { isType($0, type) } }
        guard let s = value as? String else { return false }
        let last = s.split(whereSeparator: { $0 == "/" || $0 == ":" || $0 == "#" }).last.map(String.init) ?? s
        return last.lowercased() == type
    }

    private static func typeName(_ value: Any?) -> String {
        if let list = value as? [Any] { return list.compactMap { $0 as? String }.first.map { typeName($0) } ?? "" }
        guard let s = value as? String else { return "" }
        return (s.split(whereSeparator: { $0 == "/" || $0 == ":" || $0 == "#" }).last.map(String.init) ?? s).lowercased()
    }

    private static func map(_ node: [String: Any]) -> StructuredRecipe {
        var recipe = StructuredRecipe()
        recipe.name = clean(text(node["name"]))
        recipe.description = clean(text(node["description"]))
        recipe.yield = yieldText(node["recipeYield"] ?? node["yield"])
        recipe.prepTime = isoDurationText(text(node["prepTime"]))
        recipe.cookTime = isoDurationText(text(node["cookTime"]))
        recipe.totalTime = isoDurationText(text(node["totalTime"]))
        recipe.ingredients = strings(node["recipeIngredient"] ?? node["ingredients"])
            .map { clean($0).replacingOccurrences(of: "^[•·▪▢☐□*\\-–]+\\s*", with: "", options: .regularExpression) }
            .filter { !$0.isEmpty }
        recipe.instructions = instructionItems(node["recipeInstructions"], depth: 0)
        recipe.author = clean(personName(node["author"]))
        recipe.publisher = clean(personName(node["publisher"]))
        var categories: [String] = []
        for key in ["recipeCategory", "recipeCuisine", "keywords"] {
            categories += strings(node[key]).flatMap { $0.split(whereSeparator: { $0 == "," || $0 == ";" }).map(String.init) }
        }
        var seen = Set<String>()
        recipe.categories = categories.map(clean).filter { !$0.isEmpty && seen.insert($0.lowercased()).inserted }.prefix(8).map { $0 }
        return recipe
    }

    /// A readable value from whatever shape JSON-LD used: text, number, list or object.
    private static func text(_ value: Any?) -> String {
        switch value {
        case let s as String: return s
        case let n as NSNumber: return n.stringValue
        case let list as [Any]: return list.first.map { text($0) } ?? ""
        case let dict as [String: Any]: return text(dict["@value"] ?? dict["text"] ?? dict["name"])
        default: return ""
        }
    }

    private static func strings(_ value: Any?) -> [String] {
        switch value {
        case let s as String: return [s]
        case let list as [Any]: return list.flatMap { strings($0) }
        case let dict as [String: Any]: return [text(dict)]
        case let n as NSNumber: return [n.stringValue]
        default: return []
        }
    }

    private static func personName(_ value: Any?) -> String {
        switch value {
        case let s as String: return s
        case let list as [Any]: return list.first.map { personName($0) } ?? ""
        case let dict as [String: Any]: return text(dict["name"])
        default: return ""
        }
    }

    /// Picks the most useful yield from values like ["4", "4 servings"] or "Makes 24 cookies".
    private static func yieldText(_ value: Any?) -> String {
        let options = strings(value).map(clean).filter { !$0.isEmpty }
        let startsWithNumber = options.filter { $0.first?.isNumber == true }
        if let descriptive = startsWithNumber.first(where: { $0.contains(where: \.isLetter) }) { return descriptive }
        return startsWithNumber.first ?? options.first ?? ""
    }

    private static func instructionItems(_ value: Any?, depth: Int) -> [RecipeItem] {
        guard let value, depth < 8 else { return [] }
        if let s = value as? String {
            // A single block of text, sometimes with HTML paragraphs or numbered lines.
            return readableText(fromHTML: s)
                .components(separatedBy: "\n")
                .map { RecipeAI.cleanedItemText($0, isStep: true) }
                .filter { !$0.isEmpty }
                .map { RecipeItem(text: $0) }
        }
        if let list = value as? [Any] {
            return list.flatMap { instructionItems($0, depth: depth + 1) }
        }
        guard let dict = value as? [String: Any] else { return [] }
        let type = typeName(dict["@type"])
        if type == "howtosection" || type == "itemlist" || (dict["text"] == nil && dict["itemListElement"] != nil) {
            var items: [RecipeItem] = []
            let name = clean(text(dict["name"]))
            if !name.isEmpty { items.append(RecipeItem(text: name, isHeader: true)) }
            items += instructionItems(dict["itemListElement"], depth: depth + 1)
            return items
        }
        let step = RecipeAI.cleanedItemText(clean(text(dict["text"] ?? dict["description"] ?? dict["name"])), isStep: true)
        return step.isEmpty ? [] : [RecipeItem(text: step)]
    }

    /// "PT1H30M" → "1 hr 30 min". Text that isn't an ISO 8601 duration is tidied instead.
    static func isoDurationText(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let minutes = isoMinutes(trimmed) else { return DurationParser.normalized(trimmed) }
        return minutes > 0 ? DurationParser.format(minutes: minutes) : ""
    }

    static func isoMinutes(_ text: String) -> Int? {
        let pattern = "^P(?:(\\d+(?:[.,]\\d+)?)Y)?(?:(\\d+(?:[.,]\\d+)?)M)?(?:(\\d+(?:[.,]\\d+)?)W)?(?:(\\d+(?:[.,]\\d+)?)D)?(?:T(?:(\\d+(?:[.,]\\d+)?)H)?(?:(\\d+(?:[.,]\\d+)?)M)?(?:(\\d+(?:[.,]\\d+)?)S)?)?$"
        guard text.count > 1, let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) else { return nil }
        let ns = text as NSString
        guard let match = regex.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) else { return nil }
        func value(_ group: Int) -> Double {
            let range = match.range(at: group)
            guard range.location != NSNotFound else { return 0 }
            return Double(ns.substring(with: range).replacingOccurrences(of: ",", with: ".")) ?? 0
        }
        let minutes = value(3) * 10_080 + value(4) * 1440 + value(5) * 60 + value(6) + value(7) / 60
        // Anything past 1000 hours is garbage in the markup.
        guard minutes.isFinite, minutes <= 60_000 else { return 0 }
        return Int(minutes.rounded())
    }

    // MARK: Page text

    /// The page as plain text, one block per line, without scripts, menus or markup.
    static func readableText(fromHTML html: String) -> String {
        var s = html
        s = replacing("<!--[\\s\\S]*?-->", in: s, with: " ")
        for tag in ["script", "style", "noscript", "svg", "template", "iframe", "head", "nav", "footer", "form", "select", "button"] {
            s = replacing("<\(tag)\\b[^>]*>[\\s\\S]*?</\(tag)\\s*>", in: s, with: " ")
        }
        s = replacing("<br\\s*/?>", in: s, with: "\n")
        s = replacing("<li\\b[^>]*>", in: s, with: "\n• ")
        s = replacing("</?(?:p|div|section|article|header|main|aside|h[1-6]|ul|ol|li|tr|table|tbody|thead|tfoot|figure|figcaption|blockquote|dd|dt|dl|pre|hr)\\b[^>]*>", in: s, with: "\n")
        s = replacing("<t[dh]\\b[^>]*>", in: s, with: " ")
        s = replacing("<[^>]+>", in: s, with: "")
        s = decodeEntities(s)

        var lines: [String] = []
        for raw in s.components(separatedBy: .newlines) {
            let line = raw.replacingOccurrences(of: "[\\s\\u00A0\\u200B]+", with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty, line != "•", line != lines.last else { continue }
            lines.append(line)
        }
        return lines.joined(separator: "\n")
    }

    /// Long pages are cut down to a window around the recipe card: the "Ingredients" heading
    /// followed by the most lines that start with an amount.
    static func focusedText(_ text: String, limit: Int) -> String {
        guard text.count > limit else { return text }
        let lines = text.components(separatedBy: "\n")
        var offsets: [Int] = []
        var running = 0
        for line in lines {
            offsets.append(running)
            running += line.count + 1
        }
        var bestLine: Int?
        var bestScore = 0
        for (index, line) in lines.enumerated() where line.count <= 40 && line.lowercased().contains("ingredient") {
            let window = lines[(index + 1)..<min(lines.count, index + 30)]
            let score = window.filter(startsWithAmount).count
            if score > bestScore {
                bestScore = score
                bestLine = index
            }
        }
        guard let anchor = bestLine else { return String(text.prefix(limit)) }
        let start = max(0, min(offsets[anchor] - limit / 4, text.count - limit))
        let from = text.index(text.startIndex, offsetBy: start)
        let to = text.index(from, offsetBy: limit, limitedBy: text.endIndex) ?? text.endIndex
        return String(text[from..<to])
    }

    private static func startsWithAmount(_ line: String) -> Bool {
        var trimmed = Substring(line)
        if trimmed.hasPrefix("• ") { trimmed = trimmed.dropFirst(2) }
        guard let first = trimmed.first else { return false }
        return first.isNumber || "½⅓⅔¼¾⅛⅜⅝⅞".contains(first)
    }

    // MARK: HTML helpers

    static func metaTags(in html: String) -> [[String: String]] {
        let head: Substring
        if let end = html.range(of: "</head>", options: .caseInsensitive) {
            head = html[..<end.lowerBound]
        } else {
            head = html.prefix(200_000)
        }
        let attributePattern = "([a-zA-Z_:.-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))"
        guard let attributes = try? NSRegularExpression(pattern: attributePattern) else { return [] }
        return allMatches("<meta\\b[^>]*>", in: String(head)).map { tag in
            var result: [String: String] = [:]
            let ns = tag as NSString
            for match in attributes.matches(in: tag, range: NSRange(location: 0, length: ns.length)) {
                let key = ns.substring(with: match.range(at: 1)).lowercased()
                for group in 2...4 where match.range(at: group).location != NSNotFound {
                    result[key] = ns.substring(with: match.range(at: group))
                    break
                }
            }
            return result
        }
    }

    /// Decodes tags and entities out of a markup value and collapses its whitespace.
    static func clean(_ text: String) -> String {
        var s = text
        if s.contains("<") { s = replacing("<[^>]+>", in: s, with: " ") }
        s = decodeEntities(s)
        return s.replacingOccurrences(of: "[\\s\\u00A0\\u200B]+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static let namedEntities: [String: String] = [
        "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ", "ensp": " ", "emsp": " ",
        "thinsp": " ", "shy": "", "zwj": "", "zwnj": "", "ndash": "–", "mdash": "—", "minus": "−", "hellip": "…",
        "lsquo": "‘", "rsquo": "’", "sbquo": "‚", "ldquo": "“", "rdquo": "”", "bdquo": "„", "laquo": "«",
        "raquo": "»", "lsaquo": "‹", "rsaquo": "›", "prime": "′", "Prime": "″", "bull": "•", "middot": "·",
        "deg": "°", "frac12": "½", "frac14": "¼", "frac34": "¾", "frac13": "⅓", "frac23": "⅔", "frac15": "⅕",
        "frac16": "⅙", "frac18": "⅛", "frac38": "⅜", "frac58": "⅝", "frac78": "⅞", "frasl": "⁄", "times": "×",
        "divide": "÷", "plusmn": "±", "copy": "©", "reg": "®", "trade": "™", "cent": "¢", "pound": "£",
        "euro": "€", "yen": "¥", "sect": "§", "para": "¶", "micro": "µ", "ordm": "º", "ordf": "ª",
        "iexcl": "¡", "iquest": "¿", "aacute": "á", "Aacute": "Á", "agrave": "à", "Agrave": "À",
        "acirc": "â", "Acirc": "Â", "auml": "ä", "Auml": "Ä", "atilde": "ã", "Atilde": "Ã", "aring": "å",
        "Aring": "Å", "aelig": "æ", "AElig": "Æ", "ccedil": "ç", "Ccedil": "Ç", "eacute": "é", "Eacute": "É",
        "egrave": "è", "Egrave": "È", "ecirc": "ê", "Ecirc": "Ê", "euml": "ë", "Euml": "Ë", "iacute": "í",
        "Iacute": "Í", "igrave": "ì", "icirc": "î", "iuml": "ï", "ntilde": "ñ", "Ntilde": "Ñ", "oacute": "ó",
        "Oacute": "Ó", "ograve": "ò", "ocirc": "ô", "Ocirc": "Ô", "ouml": "ö", "Ouml": "Ö", "otilde": "õ",
        "oslash": "ø", "Oslash": "Ø", "oelig": "œ", "OElig": "Œ", "szlig": "ß", "uacute": "ú", "Uacute": "Ú",
        "ugrave": "ù", "ucirc": "û", "uuml": "ü", "Uuml": "Ü", "yacute": "ý", "yuml": "ÿ",
    ]

    /// Windows-1252 characters that old pages write as numeric entities in the 128–159 range.
    private static let cp1252: [UInt32: String] = [
        0x80: "€", 0x82: "‚", 0x84: "„", 0x85: "…", 0x86: "†", 0x87: "‡", 0x89: "‰", 0x8B: "‹", 0x8C: "Œ",
        0x91: "‘", 0x92: "’", 0x93: "“", 0x94: "”", 0x95: "•", 0x96: "–", 0x97: "—", 0x98: "˜", 0x99: "™",
        0x9B: "›", 0x9C: "œ",
    ]

    static func decodeEntities(_ text: String) -> String {
        guard text.contains("&") else { return text }
        var result = decodeEntitiesOnce(text)
        // Markup is sometimes encoded twice ("&amp;#8217;").
        if result.contains("&"), result.range(of: "&(#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);", options: .regularExpression) != nil {
            result = decodeEntitiesOnce(result)
        }
        return result
    }

    private static let entityRegex = try! NSRegularExpression(pattern: "&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});")

    private static func decodeEntitiesOnce(_ text: String) -> String {
        let ns = text as NSString
        var output = ""
        var cursor = 0
        for match in entityRegex.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            output += ns.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
            let name = ns.substring(with: match.range(at: 1))
            output += replacement(forEntity: name) ?? ns.substring(with: match.range)
            cursor = match.range.location + match.range.length
        }
        output += ns.substring(from: cursor)
        return output
    }

    private static func replacement(forEntity name: String) -> String? {
        if name.hasPrefix("#") {
            let digits = name.dropFirst()
            let value: UInt32?
            if digits.first == "x" || digits.first == "X" {
                value = UInt32(digits.dropFirst(), radix: 16)
            } else {
                value = UInt32(digits)
            }
            guard let value else { return nil }
            if let mapped = cp1252[value] { return mapped }
            guard value != 0, let scalar = Unicode.Scalar(value) else { return "" }
            return String(Character(scalar))
        }
        return namedEntities[name]
    }

    // MARK: Regex helpers

    nonisolated(unsafe) private static var regexCache: [String: NSRegularExpression] = [:]
    private static let cacheLock = NSLock()

    private static func regex(_ pattern: String) -> NSRegularExpression? {
        cacheLock.lock()
        defer { cacheLock.unlock() }
        if let cached = regexCache[pattern] { return cached }
        let compiled = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
        regexCache[pattern] = compiled
        return compiled
    }

    private static func replacing(_ pattern: String, in text: String, with template: String) -> String {
        guard let regex = regex(pattern) else { return text }
        return regex.stringByReplacingMatches(in: text, range: NSRange(location: 0, length: (text as NSString).length), withTemplate: template)
    }

    private static func allMatches(_ pattern: String, in text: String) -> [String] {
        guard let regex = regex(pattern) else { return [] }
        let ns = text as NSString
        return regex.matches(in: text, range: NSRange(location: 0, length: ns.length)).map { ns.substring(with: $0.range) }
    }

    private static func allGroups(_ pattern: String, in text: String) -> [String] {
        guard let regex = regex(pattern) else { return [] }
        let ns = text as NSString
        return regex.matches(in: text, range: NSRange(location: 0, length: ns.length)).compactMap {
            $0.range(at: 1).location == NSNotFound ? nil : ns.substring(with: $0.range(at: 1))
        }
    }

    private static func firstGroup(_ pattern: String, in text: String) -> String? {
        guard let regex = regex(pattern) else { return nil }
        let ns = text as NSString
        guard let match = regex.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)),
              match.range(at: 1).location != NSNotFound else { return nil }
        return ns.substring(with: match.range(at: 1))
    }
}
