package com.example.pinch

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.example.pinch.model.SampleRecipes
import com.example.pinch.sync.GroupInvitation
import com.example.pinch.sync.OAUTH_SCHEME
import com.example.pinch.ui.PinchNavHost
import com.example.pinch.ui.theme.PinchTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    private val container get() = (application as PinchApplication).container

    /** Set when another app shares a recipe in; the nav host opens the capture flow. */
    private val openCapture = MutableStateFlow(false)
    private val invitation = MutableStateFlow<GroupInvitation?>(null)
    private val invitationError = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        // Development aid: `--es importPages /path/a.jpg,/path/b.jpg` runs the scan flow on files.
        if (BuildConfig.DEBUG && savedInstanceState == null) {
            intent.getStringExtra("importPages")?.let { paths ->
                val uris = paths.split(",").map { Uri.fromFile(java.io.File(it)) }
                container.capture.startPages(uris)
                openCapture.value = true
            }
            // `--es importUrl https://…` runs the website flow; add `--ez forceWebView true` to
            // load it in the hidden browser.
            container.capture.forceWebView = intent.getBooleanExtra("forceWebView", false)
            intent.getStringExtra("importUrl")?.let { url ->
                container.capture.startUrl(url)
                openCapture.value = true
            }
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("seedSamples", false)) {
            lifecycleScope.launch {
                container.repository.loaded.first { it }
                if (container.repository.allIncludingDeleted.none { !it.isDeleted }) container.repository.saveAll(SampleRecipes.all())
            }
        }
        setContent {
            PinchTheme {
                PinchNavHost(container = container, openCapture = openCapture, invitation = invitation, invitationError = invitationError)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        container.capture.activity = WeakReference(this)
    }

    override fun onPause() {
        super.onPause()
        if (container.capture.activity?.get() === this) container.capture.activity = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == OAUTH_SCHEME) {
            intent.data?.let(container.server::finishManagedAuthorization)
            return
        }
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "pinch") {
            runCatching { GroupInvitation.parse(intent.data.toString()) }.onSuccess { invitation.value = it }.onFailure { invitationError.value = it.message ?: "This invitation is incomplete." }
            return
        }
        if (intent.action == Intent.ACTION_SEND) {
            val link = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (link != null) runCatching { GroupInvitation.parse(link) }.getOrNull()?.let { invitation.value = it; return }
        }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.parcelable<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    uri != null && intent.type?.startsWith("image/") == true -> container.capture.startPages(listOf(uri))
                    !text.isNullOrBlank() -> container.capture.startText(text)
                    else -> return
                }
                openCapture.value = true
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.parcelableList<Uri>(Intent.EXTRA_STREAM)
                if (uris.isEmpty()) return
                container.capture.startPages(uris)
                openCapture.value = true
            }
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T> Intent.parcelable(key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(key, T::class.java) else getParcelableExtra(key) as? T

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.parcelableList(key: String): List<T> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableArrayListExtra(key, T::class.java) else getParcelableArrayListExtra(key))
            ?: emptyList()
}
