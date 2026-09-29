import AuthenticationServices
import CryptoKit
import Foundation
import Security
import UIKit

/// OAuth client settings from the build (see Config/Pinch.xcconfig).
struct GoogleConfig: Sendable {
    let clientID: String

    /// Google's iOS clients redirect to the reversed client ID.
    var redirectScheme: String {
        "com.googleusercontent.apps." + clientID.replacingOccurrences(of: ".apps.googleusercontent.com", with: "")
    }

    var redirectURI: String { redirectScheme + ":/oauth2redirect" }

    static var current: GoogleConfig? {
        guard let id = Bundle.main.object(forInfoDictionaryKey: "PinchGoogleClientID") as? String,
              id.hasSuffix(".apps.googleusercontent.com") else { return nil }
        return GoogleConfig(clientID: id)
    }
}

enum GoogleAuthError: LocalizedError {
    case cancelled
    case notSignedIn
    case revoked
    case badResponse(String)

    var errorDescription: String? {
        switch self {
        case .cancelled: "Sign-in was cancelled."
        case .notSignedIn: "Connect Google Drive first."
        case .revoked: "Pinch no longer has access to your Google Drive. Connect it again to keep backing up."
        case .badResponse(let detail): "Google sign-in didn't finish (\(detail))."
        }
    }
}

/// Signs in with Google using the system browser sheet (OAuth 2.0 with PKCE) and keeps
/// the refresh token in the Keychain. Only the `drive.file` scope is requested, so
/// Pinch can see the files it creates and nothing else in your Drive.
@MainActor
final class GoogleAuth: NSObject {
    static let scope = "https://www.googleapis.com/auth/drive.file"
    private static let refreshTokenKey = "google.refreshToken"

    let config: GoogleConfig
    private var accessToken: String?
    private var expiry = Date.distantPast
    private var webSession: ASWebAuthenticationSession?

    init(config: GoogleConfig) {
        self.config = config
    }

    var isSignedIn: Bool { Keychain.read(Self.refreshTokenKey) != nil }

    func signIn() async throws {
        let verifier = Self.randomURLSafeString(byteCount: 48)
        let challenge = Self.base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
        let state = Self.randomURLSafeString(byteCount: 16)

        var components = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        components.queryItems = [
            URLQueryItem(name: "client_id", value: config.clientID),
            URLQueryItem(name: "redirect_uri", value: config.redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: Self.scope),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "prompt", value: "consent"),
        ]
        guard let url = components.url else { throw GoogleAuthError.badResponse("bad URL") }

        let callback: URL = try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: config.redirectScheme) { url, error in
                if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                    continuation.resume(throwing: GoogleAuthError.cancelled)
                } else if let error {
                    continuation.resume(throwing: error)
                } else if let url {
                    continuation.resume(returning: url)
                } else {
                    continuation.resume(throwing: GoogleAuthError.cancelled)
                }
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            webSession = session
            if !session.start() {
                continuation.resume(throwing: GoogleAuthError.badResponse("couldn't open sign-in"))
            }
        }
        webSession = nil

        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        if let error = items.first(where: { $0.name == "error" })?.value {
            throw error == "access_denied" ? GoogleAuthError.cancelled : GoogleAuthError.badResponse(error)
        }
        guard items.first(where: { $0.name == "state" })?.value == state,
              let code = items.first(where: { $0.name == "code" })?.value else {
            throw GoogleAuthError.badResponse("missing code")
        }

        let tokens = try await tokenRequest([
            "code": code,
            "client_id": config.clientID,
            "redirect_uri": config.redirectURI,
            "grant_type": "authorization_code",
            "code_verifier": verifier,
        ])
        guard let refresh = tokens.refreshToken else { throw GoogleAuthError.badResponse("no refresh token") }
        Keychain.save(refresh, for: Self.refreshTokenKey)
        accessToken = tokens.accessToken
        expiry = Date().addingTimeInterval(TimeInterval(tokens.expiresIn))
    }

    func validAccessToken() async throws -> String {
        if let accessToken, expiry.timeIntervalSinceNow > 60 { return accessToken }
        guard let refresh = Keychain.read(Self.refreshTokenKey) else { throw GoogleAuthError.notSignedIn }
        let tokens = try await tokenRequest([
            "refresh_token": refresh,
            "client_id": config.clientID,
            "grant_type": "refresh_token",
        ])
        accessToken = tokens.accessToken
        expiry = Date().addingTimeInterval(TimeInterval(tokens.expiresIn))
        return tokens.accessToken
    }

    func invalidateAccessToken() {
        accessToken = nil
        expiry = .distantPast
    }

    func signOut() async {
        if let refresh = Keychain.read(Self.refreshTokenKey) {
            var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/revoke")!)
            request.httpMethod = "POST"
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            request.httpBody = Self.formBody(["token": refresh])
            _ = try? await URLSession.shared.data(for: request)
        }
        Keychain.delete(Self.refreshTokenKey)
        invalidateAccessToken()
    }

    // MARK: Token endpoint

    private struct TokenResponse: Decodable {
        let accessToken: String
        let expiresIn: Int
        let refreshToken: String?

        enum CodingKeys: String, CodingKey {
            case accessToken = "access_token"
            case expiresIn = "expires_in"
            case refreshToken = "refresh_token"
        }
    }

    private func tokenRequest(_ fields: [String: String]) async throws -> TokenResponse {
        var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Self.formBody(fields)
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 400 || status == 401,
           let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           body["error"] as? String == "invalid_grant" {
            Keychain.delete(Self.refreshTokenKey)
            throw GoogleAuthError.revoked
        }
        guard (200..<300).contains(status) else { throw GoogleAuthError.badResponse("HTTP \(status)") }
        return try JSONDecoder().decode(TokenResponse.self, from: data)
    }

    private static func formBody(_ fields: [String: String]) -> Data {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return fields.map { key, value in
            "\(key)=\(value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value)"
        }
        .joined(separator: "&")
        .data(using: .utf8) ?? Data()
    }

    private static func randomURLSafeString(byteCount: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        _ = SecRandomCopyBytes(kSecRandomDefault, byteCount, &bytes)
        return base64URL(Data(bytes))
    }

    private static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

extension GoogleAuth: ASWebAuthenticationPresentationContextProviding {
    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .first(where: \.isKeyWindow) ?? ASPresentationAnchor()
        }
    }
}
