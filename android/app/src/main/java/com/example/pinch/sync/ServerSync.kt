package com.example.pinch.sync

import android.net.Uri
import android.util.Base64
import com.example.pinch.ai.OpenRouterClient
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

import android.content.Context
import com.example.pinch.ai.SecretStore
import com.example.pinch.data.PinchJson
import com.example.pinch.data.RecipeRepository
import com.example.pinch.model.Recipe
import com.example.pinch.model.Cookbook
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.util.UUID

@Serializable
data class ServerGroup(val id: String, val name: String, val isOwner: Boolean,
    val memberCount: Int = 1, val memberNames: List<String> = emptyList(), val recipeCount: Int = 0,
    val latestActivity: Double = 0.0, val previews: List<Recipe> = emptyList(), val mySharedRecipeIds: List<String> = emptyList(),
    val role: String = "owner", val ownerName: String = "", val document: Cookbook? = null) {
    val canEdit: Boolean get() = isOwner || role == "editor"
}
@Serializable
data class SharedRecipe(val id: String, val sharedBy: String, val canRemove: Boolean, val recipe: Recipe,
    val sharedAt: Double = 0.0, val savedRecipeId: String? = null)
@Serializable data class GroupMember(val id: String, val username: String, val isOwner: Boolean, val isYou: Boolean, val role: String)
@Serializable data class InvitationDetails(val id: String, val name: String, val invitedBy: String, val role: String)
data class GroupInvitation(val server: String, val code: String) {
    companion object {
        fun parse(text: String): GroupInvitation {
            val uri = URI(text.trim())
            val address: String
            val code: String
            if (uri.scheme == "pinch" && uri.host == "join") {
                val fields = uri.rawQuery.orEmpty().split("&").mapNotNull {
                    val pair = it.split("=", limit = 2)
                    if (pair.size == 2) pair[0] to java.net.URLDecoder.decode(pair[1], "UTF-8") else null
                }.toMap()
                address = fields["server"].orEmpty(); code = fields["code"].orEmpty()
            } else {
                require(uri.scheme == "https" && uri.path.endsWith("/join")) { "Paste a complete Pinch invitation link." }
                address = text.trim().substringBefore('#').removeSuffix("/join"); code = uri.fragment.orEmpty()
            }
            require(code.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "This invitation is incomplete." }
            return GroupInvitation(ServerApi.serverUrl(address), code)
        }
    }
}
@Serializable
private data class ServerSession(val server: String, val username: String, val token: String, val managed: Boolean = false)

@Serializable
private data class ManagedAuthorization(val state: String, val verifier: String) {
    fun url(): String = Uri.parse("$MANAGED_SERVER/v1/managed/oauth/authorize").buildUpon()
        .appendQueryParameter("client_id", "pinch-mobile").appendQueryParameter("redirect_uri", OAUTH_REDIRECT)
        .appendQueryParameter("response_type", "code").appendQueryParameter("state", state)
        .appendQueryParameter("code_challenge", encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())))
        .appendQueryParameter("code_challenge_method", "S256").build().toString()

    fun exchange(callback: Uri): ByteArray {
        val code = callback.getQueryParameter("code")
        require(callback.scheme == OAUTH_SCHEME && callback.authority == "oauth" && callback.path.isNullOrEmpty() &&
            callback.getQueryParameter("state") == state && !code.isNullOrEmpty()) { "Sign-in expired. Start again from Pinch." }
        return mapOf("grant_type" to "authorization_code", "client_id" to "pinch-mobile", "redirect_uri" to OAUTH_REDIRECT,
            "code" to code, "code_verifier" to verifier).entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }.toByteArray()
    }

    companion object {
        fun create() = ManagedAuthorization(random(), random())
        private fun random() = encode(ByteArray(32).also(SecureRandom()::nextBytes))
        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

private const val MANAGED_SERVER = "https://recipes.lmbrt.net"
const val OAUTH_SCHEME = "net.lmbrt.recipes.pinch"
private const val OAUTH_REDIRECT = "$OAUTH_SCHEME://oauth"

data class ServerState(
    val server: String = "", val username: String = "", val connected: Boolean = false,
    val syncing: Boolean = false, val lastSynced: Instant? = null, val message: String? = null,
    val managed: Boolean = false,
)

class ServerApi(val base: String, private val token: String) {
    companion object {
        fun serverUrl(text: String): String {
            val uri = runCatching { URI(text.trim()) }.getOrNull()
            require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Enter an HTTPS server address, such as https://recipes.example.com."
            }
            return uri.toASCIIString().trimEnd('/')
        }
        fun validate(recipe: Recipe) {
            require(recipe.id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) &&
                recipe.photoFiles.all { it.matches(Regex("[a-zA-Z0-9_-]{1,80}\\.jpg")) } &&
                (recipe.bannerPhoto == null || recipe.bannerPhoto in recipe.recipePhotos)) {
                "The server returned an invalid recipe or photo name."
            }
        }
    }

    suspend fun send(path: String, method: String = "GET", data: ByteArray? = null, photo: Boolean = false, form: Boolean = false): ByteArray = withContext(Dispatchers.IO) {
        val connection = URI("$base/v1/$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Content-Type", when {
                form -> "application/x-www-form-urlencoded"
                photo -> "image/jpeg"
                else -> "application/json"
            })
            if (method == "PUT" && path.startsWith("recipes/")) connection.setRequestProperty("X-Pinch-Recipe-Photos", "1")
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (data != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(data.size)
                connection.outputStream.use { it.write(data) }
            }
            val status = connection.responseCode
            val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: byteArrayOf()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)).optString("detail") }.getOrNull()
                error(detail?.takeIf { it.isNotBlank() } ?: "Server request failed ($status).")
            }
            bytes
        } finally { connection.disconnect() }
    }

    suspend fun json(path: String, fields: Map<String, String> = emptyMap()): ByteArray =
        send(path, "POST", JSONObject(fields).toString().toByteArray())
}

/** Local-first sync. UI state and repository mutations run on the main dispatcher. */
class ServerSync(context: Context, private val repository: RecipeRepository, private val scope: CoroutineScope) {
    private val secrets = SecretStore(context)
    private var session = secrets.get("server.session")?.let { runCatching { PinchJson.json.decodeFromString<ServerSession>(it) }.getOrNull() }
        ?.takeIf { runCatching { ServerApi.serverUrl(it.server) }.isSuccess && (!it.managed || it.server == MANAGED_SERVER) }
    private val _state = MutableStateFlow(session?.let { ServerState(it.server, it.username, true, managed = it.managed) } ?: ServerState())
    val state = _state.asStateFlow()
    private val _groups = MutableStateFlow<List<ServerGroup>>(emptyList())
    val groups = _groups.asStateFlow()
    private var scheduled: Job? = null
    private var syncTask: Deferred<Unit>? = null
    private var generation = 0
    private var needsSync = false

    private fun api(): ServerApi = session?.let { ServerApi(it.server, it.token) } ?: error("Connect to a server first.")

    fun managedAIClient(): OpenRouterClient? = session?.takeIf { it.managed }?.let {
        OpenRouterClient(it.token, endpoint = "$MANAGED_SERVER/v1/managed/ai")
    }

    suspend fun connect(address: String, username: String, password: String, registrationKey: String, register: Boolean, invitationCode: String? = null) {
        val base = ServerApi.serverUrl(address)
        val data = ServerApi(base, "").json("auth/${if (register) (if (invitationCode == null) "register" else "register-cookbook-invite") else "login"}", mapOf(
            "username" to username.trim(), "password" to password, "registrationKey" to registrationKey, "code" to invitationCode.orEmpty(),
        ))
        saveSession(data, base, managed = false)
    }

    suspend fun connectManaged(username: String, password: String, register: Boolean) {
        val data = ServerApi(MANAGED_SERVER, "").json(if (register) "managed/register" else "managed/login",
            mapOf("username" to username.trim(), "password" to password))
        saveSession(data, MANAGED_SERVER, managed = true)
    }

    fun startManagedAuthorization(): String {
        val authorization = ManagedAuthorization.create()
        check(secrets.put("managed.authorization", PinchJson.json.encodeToString(authorization))) {
            "Couldn't securely start sign-in. Try again."
        }
        _state.value = _state.value.copy(message = null)
        return authorization.url()
    }

    fun finishManagedAuthorization(callback: Uri) {
        val saved = secrets.get("managed.authorization") ?: return
        if (session != null) return
        val authorization = runCatching { PinchJson.json.decodeFromString<ManagedAuthorization>(saved) }.getOrNull() ?: return
        val data = runCatching { authorization.exchange(callback) }.getOrNull() ?: return
        secrets.remove("managed.authorization")
        val expected = generation
        scope.launch {
            try {
                val response = ServerApi(MANAGED_SERVER, "").send("managed/oauth/token", "POST", data, form = true)
                if (generation == expected) saveSession(response, MANAGED_SERVER, managed = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == expected) _state.value = _state.value.copy(message = error.message ?: "Couldn't complete sign-in.")
            }
        }
    }

    private fun saveSession(data: ByteArray, address: String, managed: Boolean) {
        val response = JSONObject(data.toString(Charsets.UTF_8))
        val token = response.optString("token").ifBlank { response.getString("access_token") }
        check(token.isNotBlank()) { "The sign-in response was incomplete. Try again." }
        val value = ServerSession(address, response.getString("username"), token, managed)
        check(secrets.put("server.session", PinchJson.json.encodeToString(value))) { "Couldn't securely save the connection. Try again." }
        generation++
        secrets.remove("managed.authorization")
        session = value
        _state.value = ServerState(value.server, value.username, true, managed = value.managed)
        _groups.value = emptyList()
        syncIfConnected()
    }

    fun disconnect() {
        val old = session?.let { ServerApi(it.server, it.token) }
        generation++
        scheduled?.cancel(); syncTask?.cancel(); syncTask = null
        session = null; secrets.remove("server.session"); secrets.remove("managed.authorization")
        _state.value = _state.value.copy(connected = false, syncing = false, lastSynced = null, message = null, managed = false)
        _groups.value = emptyList()
        if (old != null) scope.launch { runCatching { old.json("auth/logout") } }
    }

    fun scheduleSync() {
        needsSync = true
        scheduled?.cancel()
        if (session == null) return
        scheduled = scope.launch { delay(2000); syncIfConnected() }
    }

    fun syncIfConnected() {
        if (session == null) return
        scope.launch {
            try { sync() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Visible in state; retry on foreground or edit. */ }
        }
    }

    suspend fun sync() {
        syncTask?.let { return it.await() }
        val api = api()
        val expected = generation
        _state.value = _state.value.copy(syncing = true, message = null)
        val task = scope.async(start = CoroutineStart.LAZY) {
            try {
                repository.loaded.first { it }
                do {
                    needsSync = false
                    performSync(api, expected)
                } while (needsSync && expected == generation)
                if (expected == generation) _state.value = _state.value.copy(lastSynced = Instant.now())
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (expected == generation) _state.value = _state.value.copy(message = e.message ?: "Couldn't reach your server. Try again when online.")
                throw e
            } finally {
                if (expected == generation) { syncTask = null; _state.value = _state.value.copy(syncing = false) }
            }
        }
        syncTask = task
        task.start()
        task.await()
    }

    suspend fun deleteAccount(password: String) {
        api().send("auth/account", "DELETE", JSONObject(mapOf("password" to password)).toString().toByteArray())
        disconnect()
    }

    private suspend fun checkSession(expected: Int) {
        currentCoroutineContext().ensureActive()
        if (expected != generation || session == null) throw CancellationException()
    }

    private suspend fun performSync(api: ServerApi, expected: Int) {
        val remote = PinchJson.json.decodeFromString<List<Recipe>>(api.send("recipes").toString(Charsets.UTF_8))
        checkSession(expected)
        remote.forEach(ServerApi::validate)
        val remoteById = remote.associateBy { it.id }
        val local = repository.allIncludingDeleted
        val ids = local.map { it.id }.toSet()
        for (recipe in local) {
            checkSession(expected)
            val other = remoteById[recipe.id]
            if (other != null && other.updatedAt.toEpochMilli() >= recipe.updatedAt.toEpochMilli()) {
                receive(other, api, expected)
            } else {
                ServerApi.validate(recipe)
                for (name in recipe.photoFiles) {
                    val data = withContext(Dispatchers.IO) { repository.photos.bytes(name) }
                        ?: error("A photo is missing from ${recipe.displayTitle}.")
                    api.send("photos/$name", "PUT", data, photo = true)
                    checkSession(expected)
                }
                val data = api.send("recipes/${recipe.id}", "PUT", PinchJson.encode(recipe).toByteArray())
                receive(PinchJson.decode(data.toString(Charsets.UTF_8)), api, expected)
            }
        }
        for (recipe in remote) if (recipe.id !in ids) receive(recipe, api, expected)
        val books = PinchJson.json.decodeFromString<List<ServerGroup>>(api.send("cookbooks").toString(Charsets.UTF_8))
        checkSession(expected)
        books.mapNotNull { it.document }.forEach(repository::applyCookbook)
        for (book in repository.cookbooks.value) {
            val result = api.send("cookbooks/${book.id}", "PUT", PinchJson.json.encodeToString(book).toByteArray())
            checkSession(expected)
            PinchJson.json.decodeFromString<ServerGroup>(result.toString(Charsets.UTF_8)).document?.let(repository::applyCookbook)
        }
        val updated = PinchJson.json.decodeFromString<List<ServerGroup>>(api.send("cookbooks").toString(Charsets.UTF_8))
        checkSession(expected)
        _groups.value = updated
    }

    private suspend fun receive(recipe: Recipe, api: ServerApi, expected: Int) {
        ServerApi.validate(recipe)
        checkSession(expected)
        for (name in recipe.photoFiles) if (!repository.photos.exists(name)) {
            val data = api.send("photos/$name")
            checkSession(expected)
            withContext(Dispatchers.IO) { repository.photos.save(data, name) }
        }
        checkSession(expected)
        val current = repository.allIncludingDeleted.firstOrNull { it.id == recipe.id }
        if (current != null && current.updatedAt.toEpochMilli() > recipe.updatedAt.toEpochMilli()) return
        repository.applySynced(recipe)
    }

    fun allCookbooks(): List<ServerGroup> {
        val books = repository.cookbooks.value
        val ids = books.map { it.id }.toSet()
        val personal = books.filter { it.deletedAt == null }.map { book ->
            val recipes = repository.allIncludingDeleted.filter { !it.isDeleted && it.id in book.recipeIds }
            val remote = groups.value.firstOrNull { it.id == book.id }
            ServerGroup(book.id, book.name, true, memberCount = remote?.memberCount ?: 1, memberNames = remote?.memberNames.orEmpty(), recipeCount = recipes.size,
                latestActivity = remote?.latestActivity ?: 0.0, previews = recipes.take(3), mySharedRecipeIds = book.recipeIds.toList(), role = "owner", ownerName = state.value.username, document = book)
        }
        return (personal + groups.value.filter { it.id !in ids && it.document?.deletedAt == null }).sortedBy { it.name.lowercase() }
    }
    suspend fun loadGroups() { if (state.value.connected) sync() }
    suspend fun createGroup(name: String): ServerGroup {
        val book = repository.createCookbook(name)
        return ServerGroup(book.id, book.name, true, document = book)
    }
    suspend fun joinGroup(code: String) {
        api().json("cookbooks/join", mapOf("code" to code.trim())); loadGroups()
    }
    suspend fun invite(group: ServerGroup): String {
        api().send("cookbooks/${group.id}/invitations", "DELETE"); return ""
    }
    suspend fun leave(group: ServerGroup) {
        if (group.isOwner) repository.deleteCookbook(group.id)
        else { api().send("cookbooks/${group.id}/membership", "DELETE"); loadGroups() }
    }
    suspend fun sharedRecipes(group: ServerGroup): List<SharedRecipe> {
        val book = repository.cookbooks.value.firstOrNull { it.id == group.id }
        if (group.isOwner && book != null) return repository.allIncludingDeleted.filter { !it.isDeleted && it.id in book.recipeIds }.map {
            SharedRecipe(it.id, it.origin?.sharedBy ?: "You", true, it, sharedAt = (book.entries.firstOrNull { entry -> entry.recipeId == it.id }?.updatedAt?.toEpochMilli() ?: 0) / 1000.0, savedRecipeId = it.id)
        }
        val recipes = PinchJson.json.decodeFromString<List<SharedRecipe>>(api().send("cookbooks/${group.id}/recipes").toString(Charsets.UTF_8))
        recipes.forEach { ServerApi.validate(it.recipe) }; return recipes
    }
    suspend fun share(recipe: Recipe, group: ServerGroup) {
        if (group.isOwner) { repository.setMembership(recipe.id, group.id, true); return }
        val api = api(); val expected = generation
        val latest = repository.recipe(recipe.id) ?: recipe
        ServerApi.validate(latest)
        for (name in latest.photoFiles) {
            val data = withContext(Dispatchers.IO) { repository.photos.bytes(name) } ?: error("A photo is missing from this recipe.")
            api.send("photos/$name", "PUT", data, photo = true); checkSession(expected)
        }
        val response = api.send("recipes/${latest.id}", "PUT", PinchJson.encode(latest).toByteArray())
        receive(PinchJson.decode(response.toString(Charsets.UTF_8)), api, expected)
        checkSession(expected)
        api.json("cookbooks/${group.id}/recipes", mapOf("recipeId" to latest.id))
    }
    suspend fun remove(item: SharedRecipe, group: ServerGroup) {
        if (group.isOwner) repository.setMembership(item.id, group.id, false)
        else api().send("cookbooks/${group.id}/recipes/${item.id}", "DELETE")
    }
    suspend fun saveCopy(item: SharedRecipe, group: ServerGroup): String {
        if (group.isOwner) return item.recipe.id
        val api = api(); val expected = generation
        for (deleted in repository.allIncludingDeleted.filter { it.isDeleted && it.origin?.shareId == item.id }) {
            api.send("recipes/${deleted.id}", "PUT", PinchJson.encode(deleted).toByteArray()); checkSession(expected)
        }
        val recipe = PinchJson.decode(api.json("cookbooks/${group.id}/recipes/${item.id}/save").toString(Charsets.UTF_8))
        receive(recipe, api, expected)
        return recipe.id
    }
    suspend fun preview(shareId: String, group: ServerGroup): Pair<SharedRecipe, Recipe> {
        val api = api(); val expected = generation
        val fresh = PinchJson.json.decodeFromString<SharedRecipe>(api.send("cookbooks/${group.id}/recipes/$shareId").toString(Charsets.UTF_8))
        ServerApi.validate(fresh.recipe)
        val copied = mutableMapOf<String, String>()
        try {
            for (name in fresh.recipe.photoFiles.distinct()) {
                val data = api.send("cookbooks/${group.id}/recipes/$shareId/photos/$name")
                checkSession(expected)
                val local = "${UUID.randomUUID()}.jpg"
                copied[name] = local
                withContext(Dispatchers.IO) { repository.photos.save(data, local) }
            }
            checkSession(expected)
            return fresh to fresh.recipe.copy(
                id = "shared-$shareId",
                photos = fresh.recipe.photos.map(copied::getValue),
                recipePhotos = fresh.recipe.recipePhotos.map(copied::getValue),
                bannerPhoto = fresh.recipe.bannerPhoto?.let(copied::getValue),
            )
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { copied.values.forEach(repository.photos::delete) }; throw e
        }
    }
    suspend fun members(group: ServerGroup): List<GroupMember> = PinchJson.json.decodeFromString(api().send("cookbooks/${group.id}/members").toString(Charsets.UTF_8))
    suspend fun rename(group: ServerGroup, name: String) { repository.renameCookbook(group.id, name) }
    suspend fun removeMember(group: ServerGroup, member: GroupMember) {
        api().send("cookbooks/${group.id}/members/${member.id}", "DELETE"); loadGroups()
    }
    suspend fun changeRole(group: ServerGroup, member: GroupMember, role: String) {
        api().send("cookbooks/${group.id}/members/${member.id}", "PATCH", JSONObject(mapOf("role" to role)).toString().toByteArray()); loadGroups()
    }
    suspend fun invitationLink(group: ServerGroup, role: String = "viewer"): String {
        sync()
        val code = JSONObject(api().json("cookbooks/${group.id}/invitations", mapOf("role" to role)).toString(Charsets.UTF_8)).getString("inviteCode")
        return state.value.server.trimEnd('/') + "/join#" + code
    }
    suspend fun invitationDetails(invitation: GroupInvitation): InvitationDetails =
        PinchJson.json.decodeFromString(ServerApi(invitation.server, "").send("cookbook-invitations/${invitation.code}").toString(Charsets.UTF_8))
    fun savedCopy(item: SharedRecipe): Recipe? = repository.allIncludingDeleted.firstOrNull { !it.isDeleted && it.origin?.shareId == item.id }
}
