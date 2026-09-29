package com.example.pinch.scan

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

/**
 * Loads a page in an invisible WebView. Some recipe sites turn away plain requests with a
 * bot check ("Just a moment…"), and some build the recipe with JavaScript; a real browser
 * engine gets through both the same way Chrome would.
 */
object WebViewPageLoader {
    private const val TAG = "PinchWebView"

    /** [activity], when there is one, hosts the WebView off screen so the page runs as if visible. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun load(context: Context, activity: Activity?, url: String, timeoutMs: Long = 25_000): Pair<String, String> =
        withContext(Dispatchers.Main) {
            var failure: Exception? = null
            var mainStatus = 0
            val webView = WebView(activity ?: context)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                blockNetworkImage = true
                mediaPlaybackRequiresUserGesture = true
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    failure = null
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    failure = when (error.errorCode) {
                        ERROR_HOST_LOOKUP -> WebImportException.Unreachable("couldn't find that website; check the address and your connection")
                        ERROR_CONNECT, ERROR_IO -> WebImportException.Offline
                        ERROR_TIMEOUT -> WebImportException.Unreachable("it took too long to load")
                        else -> WebImportException.Unreachable(error.description?.toString() ?: "the page didn't load")
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) mainStatus = response.statusCode
                }

                // Without this, a crashed or reclaimed page renderer takes Pinch down with it.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    failure = WebImportException.Unreachable("the page stopped loading")
                    return true
                }
            }

            val host = activity?.window?.decorView as? ViewGroup
            if (host != null && host.width > 0) {
                // Attached, full size, but parked off screen where it can't be seen or touched.
                webView.translationX = -(host.width + 200f)
                webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                host.addView(webView, ViewGroup.LayoutParams(host.width, host.height))
            } else {
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, 1080, 2000)
            }

            try {
                webView.loadUrl(url)
                val started = System.currentTimeMillis()
                var lastHtml = ""
                var lastLength = -1
                var steadyPolls = 0
                while (System.currentTimeMillis() - started < timeoutMs) {
                    delay(700)
                    failure?.let { throw it }
                    if (mainStatus == 404 || mainStatus == 410) throw WebImportException.NotFound
                    failure?.let { throw it }
                    // A page stuck in a script may never answer; the next poll tries again.
                    val html = withTimeoutOrNull(5_000) { webView.outerHtml() }.orEmpty()
                    if (html.isEmpty()) continue
                    lastHtml = html
                    if (WebRecipeImporter.isChallengePage(html)) continue
                    // Recipe markup is usually in the page from the start.
                    if (html.contains("application/ld+json") &&
                        withContext(Dispatchers.Default) { WebRecipeImporter.structuredRecipe(html) } != null
                    ) {
                        Log.i(TAG, "Recipe markup found after ${(System.currentTimeMillis() - started) / 1000.0}s")
                        return@withContext html to (webView.url ?: url)
                    }
                    // Otherwise wait for loading to settle before taking the page as it is.
                    steadyPolls = if (webView.progress == 100 && html.length == lastLength) steadyPolls + 1 else 0
                    lastLength = html.length
                    if (steadyPolls >= 2) return@withContext html to (webView.url ?: url)
                }
                if (lastHtml.isEmpty() || WebRecipeImporter.isChallengePage(lastHtml)) throw WebImportException.Blocked
                lastHtml to (webView.url ?: url)
            } finally {
                webView.stopLoading()
                host?.removeView(webView)
                webView.destroy()
            }
        }

    private val json = Json { isLenient = true }

    private suspend fun WebView.outerHtml(): String = suspendCancellableCoroutine { continuation ->
        evaluateJavascript("document.documentElement.outerHTML") { value ->
            // The result comes back as a JSON string literal.
            val html = runCatching { if (value == null || value == "null") "" else json.decodeFromString<String>(value) }.getOrDefault("")
            if (continuation.isActive) continuation.resume(html)
        }
    }
}
