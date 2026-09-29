import Foundation
import Observation
import os

private let log = Logger(subsystem: "Pinch", category: "AI")

/// The model that reads recipes, and where to get a key for it.
enum AIConfig {
    static let model = "deepseek/deepseek-v4.1-flash"
    static let modelDisplayName = "DeepSeek V4.1 Flash"
    static let keysPage = URL(string: "https://openrouter.ai/keys")!
    static let creditsPage = URL(string: "https://openrouter.ai/settings/credits")!
}

/// The person's own OpenRouter key. It is kept in the Keychain on this device only: it isn't
/// part of the app, isn't synced, and never goes anywhere except OpenRouter.
@Observable
@MainActor
final class AIAccount {
    static let shared = AIAccount()

    private static let service = "Pinch.OpenRouter"
    private static let account = "openrouter.apiKey"

    private(set) var apiKey: String?

    init() {
        apiKey = Keychain.read(Self.account, service: Self.service)
    }

    var isConnected: Bool { apiKey != nil }

    /// A client for reading recipes, or nil when no key is saved.
    var client: OpenRouterClient? { apiKey.map { OpenRouterClient(apiKey: $0) } }

    /// "sk-or-v1-…9f3a": enough to recognize the key without showing it.
    var maskedKey: String {
        guard let key = apiKey else { return "" }
        let tail = key.suffix(4)
        let head = key.hasPrefix("sk-or-v1-") ? "sk-or-v1-" : String(key.prefix(3))
        return "\(head)…\(tail)"
    }

    func save(_ key: String) {
        let trimmed = Self.cleaned(key)
        guard !trimmed.isEmpty else { return }
        Keychain.save(trimmed, for: Self.account, service: Self.service)
        apiKey = trimmed
    }

    func remove() {
        Keychain.delete(Self.account, service: Self.service)
        apiKey = nil
    }

    /// Keys pasted from a web page often pick up spaces, line breaks or quotes.
    nonisolated static func cleaned(_ key: String) -> String {
        key.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "\"'`")))
    }
}

/// A small client for OpenRouter's OpenAI-compatible chat completions API.
struct OpenRouterClient: Sendable {
    static let chatURL = URL(string: "https://openrouter.ai/api/v1/chat/completions")!
    static let keyURL = URL(string: "https://openrouter.ai/api/v1/key")!

    let apiKey: String
    var model: String = AIConfig.model

    enum Part: Sendable {
        case text(String)
        case jpeg(Data)
    }

    enum Failure: LocalizedError, Equatable {
        case invalidKey
        case noCredits
        case rateLimited
        case offline
        case timedOut
        /// OpenRouter turned the request down (no provider could take it, or it was malformed).
        case rejected(String)
        case unavailable(String)
        case badResponse(String)

        var errorDescription: String? {
            switch self {
            case .invalidKey: "OpenRouter didn't accept your key. Check it in Settings."
            case .noCredits: "Your OpenRouter account is out of credit. Add more at openrouter.ai."
            case .rateLimited: "OpenRouter is busy right now. Try again in a minute."
            case .offline: "You're offline."
            case .timedOut: "The AI took too long to answer."
            case .rejected(let message), .unavailable(let message): "OpenRouter couldn't run the model (\(message))."
            case .badResponse(let detail): "The AI's answer didn't make sense (\(detail))."
            }
        }
    }

    struct KeyInfo: Equatable, Sendable {
        var label: String
        /// Dollars spent with this key so far.
        var usage: Double
        /// Dollars left under this key's own spending limit, if it has one.
        var limitRemaining: Double?
        var isFreeTier: Bool
    }

    // MARK: Chat completions

    /// Sends one request that must be answered with JSON matching `schema`, and returns the JSON text.
    func completeJSON(
        system: String,
        user parts: [Part],
        schemaName: String,
        schema: [String: Any],
        maxTokens: Int = 8192
    ) async throws -> String {
        let strict = Self.requestBody(model: model, system: system, parts: parts, schemaName: schemaName, schema: schema, maxTokens: maxTokens, strict: true)
        do {
            return try await send(strict)
        } catch Failure.rejected(let message) {
            // No provider could take the strict request. The prompt describes the JSON too,
            // so one looser attempt usually still works.
            log.info("Strict request rejected (\(message, privacy: .public)); retrying loosely")
            let loose = Self.requestBody(model: model, system: system, parts: parts, schemaName: schemaName, schema: schema, maxTokens: maxTokens, strict: false)
            return try await send(loose)
        }
    }

    private func send(_ body: [String: Any], attempt: Int = 1) async throws -> String {
        var request = URLRequest(url: Self.chatURL, timeoutInterval: 150)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Pinch", forHTTPHeaderField: "X-Title")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let started = Date()
        let session = WebRecipeImporter.freshSession(timeout: 150)
        defer { session.finishTasksAndInvalidate() }
        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            if Task.isCancelled || (error as? URLError)?.code == .cancelled { throw CancellationError() }
            throw Self.failure(from: error)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        log.info("OpenRouter answered \(status) in \(Date().timeIntervalSince(started), format: .fixed(precision: 1))s")

        if (200..<300).contains(status) {
            return try Self.content(from: data)
        }
        let failure = Self.failure(status: status, body: data)
        // Provider hiccups are usually gone a moment later.
        if attempt == 1, [500, 502, 503, 504].contains(status) {
            try? await Task.sleep(for: .seconds(1.5))
            return try await send(body, attempt: 2)
        }
        throw failure
    }

    /// The JSON body for a chat completion. With `strict`, OpenRouter only routes to providers
    /// that enforce the schema and can turn reasoning off.
    static func requestBody(
        model: String,
        system: String,
        parts: [Part],
        schemaName: String,
        schema: [String: Any],
        maxTokens: Int,
        strict: Bool
    ) -> [String: Any] {
        let content: [[String: Any]] = parts.map { part in
            switch part {
            case .text(let text):
                return ["type": "text", "text": text]
            case .jpeg(let data):
                return ["type": "image_url", "image_url": ["url": "data:image/jpeg;base64," + data.base64EncodedString()]]
            }
        }
        var body: [String: Any] = [
            "model": model,
            "messages": [
                ["role": "system", "content": system],
                ["role": "user", "content": content],
            ],
            "temperature": 0,
            "max_tokens": maxTokens,
        ]
        if strict {
            body["response_format"] = [
                "type": "json_schema",
                "json_schema": ["name": schemaName, "strict": true, "schema": schema],
            ]
            body["provider"] = ["require_parameters": true]
            body["reasoning"] = ["effort": "none"]
        } else {
            body["response_format"] = ["type": "json_object"]
        }
        return body
    }

    /// Pulls the model's JSON out of a chat completion response.
    static func content(from data: Data) throws -> String {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw Failure.badResponse("not JSON")
        }
        if let error = json["error"] as? [String: Any] {
            throw failure(status: (error["code"] as? Int) ?? 0, message: error["message"] as? String)
        }
        guard let choice = (json["choices"] as? [[String: Any]])?.first else {
            throw Failure.badResponse("no answer")
        }
        if let error = choice["error"] as? [String: Any] {
            throw failure(status: (error["code"] as? Int) ?? 0, message: error["message"] as? String)
        }
        let finish = choice["finish_reason"] as? String
        let message = choice["message"] as? [String: Any]
        let text = message?["content"] as? String ?? ""
        if finish == "length" { throw Failure.badResponse("the answer was cut off") }
        let cleaned = extractJSON(text)
        guard !cleaned.isEmpty else {
            throw Failure.badResponse(finish == "content_filter" ? "the provider blocked it" : "empty answer")
        }
        return cleaned
    }

    /// Strips code fences, thinking tags or chatter around a JSON object.
    static func extractJSON(_ text: String) -> String {
        var t = text
        if let end = t.range(of: "</think>") { t = String(t[end.upperBound...]) }
        t = t.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix("```") {
            if let newline = t.firstIndex(of: "\n") { t = String(t[t.index(after: newline)...]) } else { t = "" }
            if let fence = t.range(of: "```", options: .backwards) { t = String(t[..<fence.lowerBound]) }
        }
        if let start = t.firstIndex(of: "{"), let end = t.lastIndex(of: "}"), start < end {
            t = String(t[start...end])
        }
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func failure(status: Int, body: Data) -> Failure {
        let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any]
        let message = (json?["error"] as? [String: Any])?["message"] as? String
        return failure(status: status, message: message)
    }

    static func failure(status: Int, message: String?) -> Failure {
        let detail = String((message ?? "HTTP \(status)").prefix(160))
        switch status {
        case 400, 404, 422: return .rejected(detail)
        case 401: return .invalidKey
        case 402: return .noCredits
        case 408: return .timedOut
        case 429: return .rateLimited
        default: return .unavailable(detail)
        }
    }

    static func failure(from error: Error) -> Failure {
        guard let urlError = error as? URLError else { return .badResponse(error.localizedDescription) }
        switch urlError.code {
        case .timedOut: return .timedOut
        case .notConnectedToInternet, .networkConnectionLost, .cannotFindHost, .cannotConnectToHost,
             .dnsLookupFailed, .dataNotAllowed, .internationalRoamingOff:
            return .offline
        default: return .unavailable(urlError.localizedDescription)
        }
    }

    // MARK: Key check

    /// Checks a key with OpenRouter and returns what it knows about it.
    static func checkKey(_ key: String) async throws -> KeyInfo {
        var request = URLRequest(url: keyURL, timeoutInterval: 20)
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        let session = WebRecipeImporter.freshSession(timeout: 20)
        defer { session.finishTasksAndInvalidate() }
        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            if Task.isCancelled || (error as? URLError)?.code == .cancelled { throw CancellationError() }
            throw failure(from: error)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw status == 403 ? Failure.invalidKey : failure(status: status, body: data)
        }
        return try keyInfo(from: data)
    }

    static func keyInfo(from data: Data) throws -> KeyInfo {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let info = json["data"] as? [String: Any] else {
            throw Failure.badResponse("unexpected key details")
        }
        return KeyInfo(
            label: info["label"] as? String ?? "",
            usage: (info["usage"] as? NSNumber)?.doubleValue ?? 0,
            limitRemaining: (info["limit_remaining"] as? NSNumber)?.doubleValue,
            isFreeTier: info["is_free_tier"] as? Bool ?? false
        )
    }
}
