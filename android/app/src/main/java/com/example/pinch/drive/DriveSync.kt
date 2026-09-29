package com.example.pinch.drive

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.example.pinch.data.PinchJson
import com.example.pinch.data.RecipeRepository
import com.example.pinch.model.Recipe
import com.example.pinch.scan.await
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Two-way sync between the local recipe files and a "Pinch Recipes" folder in Google Drive,
 * with the same rules as iOS: one JSON file per recipe tagged with its ID and last-edit
 * time, newer side wins, deletions travel as tombstones. Offline, sync simply waits.
 *
 * Authorization uses Google Identity Services: the first connect shows Google's consent
 * screen; after that Play services hands out fresh access tokens silently.
 */
class DriveSync(
    private val context: Context,
    private val repository: RecipeRepository,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Disconnected : State
        data object Connecting : State
        data object Idle : State
        data object Syncing : State
        data object Offline : State
        data class Failed(val message: String) : State
    }

    private val prefs = context.getSharedPreferences("drive", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<State>(if (prefs.getString(KEY_EMAIL, null) != null) State.Idle else State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _email = MutableStateFlow(prefs.getString(KEY_EMAIL, null))
    val accountEmail: StateFlow<String?> = _email.asStateFlow()
    private val _lastSynced = MutableStateFlow(prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 })
    val lastSynced: StateFlow<Long?> = _lastSynced.asStateFlow()

    private var pending: Job? = null
    private var running = false
    private val client get() = Identity.getAuthorizationClient(context)

    val isConnected: Boolean get() = _email.value != null

    private fun request(): AuthorizationRequest {
        val builder = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveApi.SCOPE)))
        _email.value?.let { builder.setAccount(Account(it, "com.google")) }
        return builder.build()
    }

    // Connecting

    /** Starts connecting. Returns a consent screen for the UI to show, or null if no UI is needed. */
    suspend fun beginConnect(): PendingIntent? {
        _state.value = State.Connecting
        return try {
            val result = client.authorize(request()).await()
            if (result.hasResolution()) {
                result.pendingIntent
            } else {
                finishConnect(result.accessToken)
                null
            }
        } catch (e: Exception) {
            _state.value = State.Failed(describe(e))
            null
        }
    }

    /** Called with the consent screen's result. */
    fun completeConnect(data: Intent?) {
        scope.launch {
            try {
                val result = client.getAuthorizationResultFromIntent(data)
                finishConnect(result.accessToken)
            } catch (e: ApiException) {
                _state.value = if (e.statusCode == CommonStatusCodes.CANCELED) State.Disconnected else State.Failed(describe(e))
            } catch (e: Exception) {
                _state.value = State.Failed(describe(e))
            }
        }
    }

    fun cancelConnect() {
        if (_state.value == State.Connecting) _state.value = State.Disconnected
    }

    private suspend fun finishConnect(token: String?) {
        if (token == null) {
            _state.value = State.Failed("Google didn't grant access to Drive.")
            return
        }
        val email = try {
            DriveApi({ token }, { }).userEmail() ?: "Google account"
        } catch (e: Exception) {
            _state.value = if (isOffline(e)) State.Offline else State.Failed(describe(e))
            return
        }
        prefs.edit { putString(KEY_EMAIL, email) }
        _email.value = email
        _state.value = State.Idle
        sync()
    }

    fun disconnect() {
        pending?.cancel()
        val email = _email.value
        scope.launch {
            if (email != null) {
                runCatching {
                    client.revokeAccess(
                        RevokeAccessRequest.builder().setAccount(Account(email, "com.google")).setScopes(listOf(Scope(DriveApi.SCOPE))).build()
                    ).await()
                }
            }
        }
        prefs.edit { clear() }
        _email.value = null
        _lastSynced.value = null
        _state.value = State.Disconnected
    }

    // Syncing

    /** Debounced sync after local edits. */
    fun scheduleSync() {
        if (!isConnected) return
        pending?.cancel()
        pending = scope.launch {
            delay(3000)
            sync()
        }
    }

    fun syncIfConnected() {
        if (isConnected) scope.launch { sync() }
    }

    suspend fun sync() {
        if (!isConnected || running) return
        running = true
        _state.value = State.Syncing
        try {
            performSync(api())
            val now = System.currentTimeMillis()
            prefs.edit { putLong(KEY_LAST_SYNC, now) }
            _lastSynced.value = now
            _state.value = State.Idle
        } catch (e: NeedsConsentException) {
            _state.value = State.Failed("Google needs you to sign in again. Tap Connect Google Drive.")
            prefs.edit { remove(KEY_EMAIL) }
            _email.value = null
        } catch (e: Exception) {
            if (isOffline(e)) {
                _state.value = State.Offline
            } else {
                prefs.edit { remove(KEY_ROOT); remove(KEY_PHOTOS) }
                _state.value = State.Failed("Couldn't sync with Google Drive. Pinch will try again later.")
            }
        } finally {
            running = false
        }
    }

    private class NeedsConsentException : Exception()

    private suspend fun silentToken(): String {
        val result = client.authorize(request()).await()
        if (result.hasResolution()) throw NeedsConsentException()
        return result.accessToken ?: throw NeedsConsentException()
    }

    private fun api() = DriveApi(
        token = { silentToken() },
        onUnauthorized = { stale -> runCatching { client.clearToken(ClearTokenRequest.builder().setToken(stale).build()).await() } },
    )

    private suspend fun performSync(api: DriveApi) {
        val root = folder(api, KEY_ROOT, ROOT_FOLDER, "pinchRoot", null)
        val photosFolder = folder(api, KEY_PHOTOS, PHOTOS_FOLDER, "pinchPhotos", root)

        val remoteRecipes = HashMap<String, Pair<DriveFile, Long>>()
        for (file in api.files(root)) {
            val id = file.appProperties["recipeId"] ?: continue
            val updated = file.appProperties["updatedAt"]?.toLongOrNull() ?: 0L
            val existing = remoteRecipes[id]
            if (existing != null && existing.second >= updated) continue
            remoteRecipes[id] = file to updated
        }
        val remotePhotos = api.files(photosFolder)
        val photoIds = HashMap<String, String>()
        remotePhotos.forEach { photoIds[it.name] = it.id }
        val photos = repository.photos

        suspend fun uploadPhotos(recipe: Recipe) {
            for (name in recipe.photos) {
                if (photoIds.containsKey(name)) continue
                val bytes = photos.bytes(name) ?: continue
                photoIds[name] = api.upload(name, "image/jpeg", bytes, photosFolder, mapOf("recipeId" to recipe.id, "kind" to "photo"), null)
            }
            // Pages removed from the recipe are removed from Drive too.
            for (photo in remotePhotos) {
                if (photo.appProperties["recipeId"] == recipe.id && photo.name !in recipe.photos) {
                    runCatching { api.delete(photo.id) }
                    photoIds.remove(photo.name)
                }
            }
        }

        suspend fun downloadPhotos(recipe: Recipe) {
            for (name in recipe.photos) {
                if (photos.exists(name)) continue
                val id = photoIds[name] ?: continue
                photos.save(api.download(id), name)
            }
        }

        suspend fun upload(recipe: Recipe, existingId: String?) {
            if (recipe.isDeleted) {
                remotePhotos.filter { it.appProperties["recipeId"] == recipe.id }.forEach { runCatching { api.delete(it.id) } }
            } else {
                uploadPhotos(recipe)
            }
            api.upload(
                fileName(recipe), "application/json", PinchJson.encode(recipe).toByteArray(), root,
                mapOf("recipeId" to recipe.id, "updatedAt" to recipe.updatedAt.toEpochMilli().toString(), "kind" to "recipe"),
                existingId,
            )
        }

        suspend fun downloadRecipe(file: DriveFile) {
            val recipe = PinchJson.decode(String(api.download(file.id)))
            if (!recipe.isDeleted) downloadPhotos(recipe)
            repository.applySynced(recipe)
        }

        val local = repository.allIncludingDeleted
        val localIds = local.map { it.id }.toSet()
        for (recipe in local) {
            val localUpdated = recipe.updatedAt.toEpochMilli()
            val remote = remoteRecipes[recipe.id]
            when {
                remote == null -> if (!recipe.isDeleted) upload(recipe, null)
                localUpdated > remote.second -> upload(recipe, remote.first.id)
                localUpdated < remote.second -> downloadRecipe(remote.first)
                !recipe.isDeleted -> downloadPhotos(recipe)
            }
        }
        for ((id, remote) in remoteRecipes) {
            if (id !in localIds) downloadRecipe(remote.first)
        }
    }

    private suspend fun folder(api: DriveApi, key: String, name: String, marker: String, parent: String?): String {
        // A folder trashed in Drive would swallow new uploads, so look it up again.
        prefs.getString(key, null)?.let { if (api.isUsableFolder(it)) return it }
        val id = api.findFolder(marker, "1", parent) ?: run {
            val created = api.createFolder(name, parent, mapOf(marker to "1"))
            // Another device may have created one at the same moment. Everyone settles on the
            // oldest (search results are ordered by creation time) and the newer, empty one goes.
            val oldest = api.findFolder(marker, "1", parent) ?: created
            if (oldest != created) runCatching { api.delete(created) }
            oldest
        }
        prefs.edit { putString(key, id) }
        return id
    }

    private fun fileName(recipe: Recipe) = recipe.displayTitle.replace("/", "-").take(80) + ".json"

    private fun isOffline(e: Throwable): Boolean =
        e is UnknownHostException || e is SocketTimeoutException || (e is IOException && e !is DriveHttpException) ||
            (e is ApiException && e.statusCode == CommonStatusCodes.NETWORK_ERROR)

    private fun describe(e: Exception): String = when {
        e is ApiException && e.statusCode == CommonStatusCodes.DEVELOPER_ERROR ->
            "Google Drive isn't set up for this build of Pinch yet. (Developers: see docs/google-drive-setup.md.)"
        e is ApiException && e.statusCode == CommonStatusCodes.NETWORK_ERROR ->
            "You're offline. Connect to the internet to link Google Drive."
        isOffline(e) -> "You're offline. Connect to the internet to link Google Drive."
        else -> "Couldn't connect to Google Drive. Try again in a moment."
    }

    companion object {
        const val ROOT_FOLDER = "Pinch Recipes"
        const val PHOTOS_FOLDER = "Photos"
        private const val KEY_EMAIL = "email"
        private const val KEY_LAST_SYNC = "lastSynced"
        private const val KEY_ROOT = "rootFolder"
        private const val KEY_PHOTOS = "photosFolder"
    }
}
