package com.example.pinch

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.webkit.WebSettings
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.pinch.ai.AIAccount
import com.example.pinch.data.PhotoStore
import com.example.pinch.data.RecipeRepository
import com.example.pinch.drive.DriveSync
import com.example.pinch.model.Recipe
import com.example.pinch.parsing.ParsedRecipe
import com.example.pinch.scan.RecipeImporter
import com.example.pinch.scan.WebRecipeImporter
import com.example.pinch.search.FuzzySearch
import com.example.pinch.timers.TimerManager
import com.example.pinch.ui.library.DocumentScanner
import com.google.android.gms.common.moduleinstall.ModuleInstall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference

class PinchApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Fetch the document scanner in the background while online, so it's ready offline.
        runCatching { ModuleInstall.getClient(this).deferredInstall(DocumentScanner.client()) }
        // Sync whenever the app comes to the foreground, if Drive is connected.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.drive.syncIfConnected()
        })
    }
}

/** Everything the screens share, created once per process. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val photos = PhotoStore(File(context.filesDir, "photos"))
    val repository = RecipeRepository(File(context.filesDir, "recipes"), photos, scope)
    val timers = TimerManager(context, scope)
    val drive = DriveSync(context, repository, scope)
    val cooking = CookingSession()
    val ai = AIAccount(context)
    val capture = CaptureSession(context, repository, ai, scope)
    val search = FuzzySearch()

    init {
        repository.onLocalChange = { drive.scheduleSync() }
    }
}

/**
 * What the cook has ticked off while making a recipe. Kept in memory for the session, so
 * moving between the recipe and cook mode keeps your place.
 */
class CookingSession {
    private val checked = mutableStateMapOf<String, Set<String>>()
    private val done = mutableStateMapOf<String, Set<String>>()
    private val scales = mutableStateMapOf<String, Double>()
    private val stepIndex = mutableStateMapOf<String, Int>()

    fun isChecked(itemId: String, recipeId: String) = checked[recipeId]?.contains(itemId) == true
    fun toggleChecked(itemId: String, recipeId: String) {
        val set = checked[recipeId] ?: emptySet()
        checked[recipeId] = if (itemId in set) set - itemId else set + itemId
    }

    fun isDone(stepId: String, recipeId: String) = done[recipeId]?.contains(stepId) == true
    fun toggleDone(stepId: String, recipeId: String) {
        val set = done[recipeId] ?: emptySet()
        done[recipeId] = if (stepId in set) set - stepId else set + stepId
    }

    fun setDone(stepId: String, recipeId: String, value: Boolean) {
        val set = done[recipeId] ?: emptySet()
        done[recipeId] = if (value) set + stepId else set - stepId
    }

    fun doneCount(recipeId: String) = done[recipeId]?.size ?: 0
    fun hasProgress(recipeId: String) = !checked[recipeId].isNullOrEmpty() || !done[recipeId].isNullOrEmpty()

    fun scale(recipeId: String) = scales[recipeId] ?: 1.0
    fun setScale(recipeId: String, scale: Double) {
        scales[recipeId] = scale
    }

    fun currentStep(recipeId: String) = stepIndex[recipeId] ?: 0
    fun setCurrentStep(recipeId: String, index: Int) {
        stepIndex[recipeId] = index
    }

    fun reset(recipeId: String) {
        checked.remove(recipeId)
        done.remove(recipeId)
        stepIndex.remove(recipeId)
    }
}

/** Scan → read → review. Survives navigation so the reading screen and the editor share it. */
class CaptureSession(
    private val context: Context,
    private val repository: RecipeRepository,
    private val ai: AIAccount,
    private val scope: CoroutineScope,
) {
    sealed interface Phase {
        data object Idle : Phase
        data class Reading(val stage: RecipeImporter.Stage, val pages: List<Uri> = emptyList()) : Phase
        data class Reviewing(val draft: Recipe, val readBy: RecipeImporter.ReadBy) : Phase
        data class Failed(val message: String) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private var job: Job? = null

    /** The activity on screen, which hosts the hidden browser used for some websites. */
    var activity: WeakReference<Activity>? = null

    /** Development aid: always load websites in the hidden browser. */
    var forceWebView = false

    /** Sites get the same page Chrome on this phone would get. Looked up once, on the main thread. */
    private val userAgent by lazy {
        WebRecipeImporter.browserUserAgent(runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault(FALLBACK_USER_AGENT))
    }

    private fun run(start: Phase, work: suspend () -> Phase) {
        job?.cancel()
        discardUnsavedDraft()
        _phase.value = start
        job = scope.launch {
            _phase.value = try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Phase.Failed(e.message ?: "Couldn't read that recipe.")
            }
        }
    }

    fun startPages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val client = ai.client()
        run(Phase.Reading(RecipeImporter.Stage.Reading(1, uris.size), uris)) {
            val result = RecipeImporter.importPages(context, uris, repository.photos, client) { stage ->
                _phase.value = Phase.Reading(stage, uris)
            }
            Phase.Reviewing(result.recipe, result.readBy)
        }
    }

    /**
     * Text typed, pasted or shared in. A link, or a line or two with a link in it (what browsers
     * and apps share), opens the link instead.
     */
    fun startText(text: String) {
        val shortNote = text.length < 300 && text.lines().count { it.isNotBlank() } <= 3
        val link = WebRecipeImporter.link(text)
            ?: text.takeIf { shortNote && it.contains("http", ignoreCase = true) }?.let { WebRecipeImporter.normalizedUrl(it) }
        if (link != null) return startUrl(link)
        val client = ai.client()
        run(Phase.Reading(RecipeImporter.Stage.AiReading(RecipeImporter.AiSource.Text))) {
            val result = RecipeImporter.importText(text, client) { stage -> _phase.value = Phase.Reading(stage) }
            Phase.Reviewing(result.recipe, result.readBy)
        }
    }

    fun startUrl(url: String) {
        val client = ai.client()
        val agent = userAgent
        run(Phase.Reading(RecipeImporter.Stage.Fetching(WebRecipeImporter.displayHost(url)))) {
            val result = RecipeImporter.importUrl(context, { activity?.get() }, url, agent, client, forceWebView) { stage ->
                _phase.value = Phase.Reading(stage)
            }
            Phase.Reviewing(result.recipe, result.readBy)
        }
    }

    fun startBlank() {
        job?.cancel()
        discardUnsavedDraft()
        _phase.value = Phase.Reviewing(RecipeImporter.makeRecipe(ParsedRecipe()), RecipeImporter.ReadBy.Typing)
    }

    /** Something new was shared in while a scan was still being reviewed: its pages go. */
    private fun discardUnsavedDraft() {
        (_phase.value as? Phase.Reviewing)?.draft?.photos?.forEach(repository.photos::delete)
    }

    /** Ends the flow, calling off any reading still going on. */
    fun finish() {
        job?.cancel()
        job = null
        _phase.value = Phase.Idle
    }

    private companion object {
        const val FALLBACK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
