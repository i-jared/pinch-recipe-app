import UIKit
import WebKit
import os

private let log = Logger(subsystem: "Pinch", category: "WebView")

/// Loads a page in an invisible web view. Some recipe sites turn away plain requests with a
/// bot check ("Just a moment…"), and some build the recipe with JavaScript; a real browser
/// engine gets through both the same way Safari would.
@MainActor
final class WebViewPageLoader: NSObject, WKNavigationDelegate {
    private var failure: Error?
    private var mainStatus = 0

    static func load(_ url: URL, timeout: TimeInterval = 25) async throws -> (html: String, url: URL) {
        let loader = WebViewPageLoader()
        defer { withExtendedLifetime(loader) {} }
        return try await loader.run(url, timeout: timeout)
    }

    private func run(_ url: URL, timeout: TimeInterval) async throws -> (html: String, url: URL) {
        let configuration = WKWebViewConfiguration()
        // Nothing from one site's visit (cookies, cached routes) is kept for the next.
        configuration.websiteDataStore = .nonPersistent()
        configuration.applicationNameForUserAgent = WebRecipeImporter.safariApplicationName
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        configuration.allowsInlineMediaPlayback = false
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 844), configuration: configuration)
        webView.navigationDelegate = self
        webView.isUserInteractionEnabled = false
        webView.alpha = 0.01
        // Kept in the window (under everything) so the page runs as if it were on screen.
        keyWindow?.insertSubview(webView, at: 0)
        defer {
            webView.stopLoading()
            webView.navigationDelegate = nil
            webView.removeFromSuperview()
        }

        webView.load(URLRequest(url: url, timeoutInterval: timeout))
        let started = Date()
        var lastHTML = ""
        var lastLength = -1
        var steadyPolls = 0
        while Date().timeIntervalSince(started) < timeout {
            try await Task.sleep(for: .milliseconds(700))
            if let failure { throw Self.mapped(failure) }
            if mainStatus == 404 || mainStatus == 410 { throw WebRecipeImporter.Failure.notFound }
            guard let html = try? await webView.evaluateJavaScript("document.documentElement.outerHTML") as? String,
                  !html.isEmpty else { continue }
            lastHTML = html
            if WebRecipeImporter.isChallengePage(html) { continue }
            // Recipe markup is usually in the page from the start.
            if html.contains("application/ld+json") {
                let found = await Task.detached(priority: .userInitiated) {
                    WebRecipeImporter.structuredRecipe(in: html) != nil
                }.value
                if found {
                    log.info("Recipe markup found after \(Date().timeIntervalSince(started), format: .fixed(precision: 1))s")
                    return (html, webView.url ?? url)
                }
            }
            // Otherwise wait for loading to settle before taking the page as it is.
            let length = html.utf16.count
            steadyPolls = (!webView.isLoading && length == lastLength) ? steadyPolls + 1 : 0
            lastLength = length
            if steadyPolls >= 2 { return (html, webView.url ?? url) }
        }
        if lastHTML.isEmpty || WebRecipeImporter.isChallengePage(lastHTML) { throw WebRecipeImporter.Failure.blocked }
        return (lastHTML, webView.url ?? url)
    }

    private var keyWindow: UIWindow? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
    }

    private static func mapped(_ error: Error) -> Error {
        let code = URLError.Code(rawValue: (error as NSError).code)
        switch code {
        case .notConnectedToInternet, .networkConnectionLost, .dataNotAllowed: return WebRecipeImporter.Failure.offline
        case .cannotFindHost, .dnsLookupFailed: return WebRecipeImporter.Failure.unreachable("no website at that address")
        case .timedOut: return WebRecipeImporter.Failure.unreachable("it took too long to load")
        default: return WebRecipeImporter.Failure.unreachable(error.localizedDescription)
        }
    }

    // MARK: WKNavigationDelegate

    func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse) async -> WKNavigationResponsePolicy {
        if navigationResponse.isForMainFrame, let http = navigationResponse.response as? HTTPURLResponse {
            mainStatus = http.statusCode
        }
        return .allow
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        // A redirect that gets cancelled isn't a real failure.
        if (error as NSError).code != NSURLErrorCancelled { failure = error }
    }
}
