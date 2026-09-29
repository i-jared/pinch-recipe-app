package com.example.pinch.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import android.security.keystore.KeyPermanentlyInvalidatedException
import javax.crypto.AEADBadTagException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The model that reads recipes, and where to get a key for it. Same as iOS. */
object AIConfig {
    const val MODEL = "deepseek/deepseek-v4.1-flash"
    const val MODEL_DISPLAY_NAME = "DeepSeek V4.1 Flash"
    const val KEYS_PAGE = "https://openrouter.ai/keys"
}

/**
 * Small secrets encrypted with a key that never leaves the Android Keystore. The file is
 * left out of backups (see res/xml/backup_rules.xml), since the key can't follow it anyway.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_SIZE))
            String(cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("Pinch", "Couldn't read a saved secret", e)
            // Only a secret that can never be read again is thrown away (the Keystore key was
            // replaced, say, after a restore to a new phone). Other errors may pass.
            if (e is AEADBadTagException || e is KeyPermanentlyInvalidatedException || e is UnrecoverableKeyException ||
                e is IllegalArgumentException
            ) {
                prefs.edit { remove(name) }
            }
            null
        }
    }

    /** Returns false if the Keystore couldn't encrypt the secret. */
    fun put(name: String, value: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit { putString(name, Base64.getEncoder().encodeToString(cipher.iv + encrypted)) }
        true
    } catch (e: Exception) {
        Log.w("Pinch", "Couldn't save a secret", e)
        false
    }

    fun remove(name: String) {
        prefs.edit { remove(name) }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "pinch.secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

/**
 * The person's own OpenRouter key. It stays on this phone: it isn't part of the app,
 * isn't synced, and never goes anywhere except OpenRouter.
 */
class AIAccount(context: Context) {
    private val store = SecretStore(context)
    private val _apiKey = MutableStateFlow(store.get(KEY))
    val apiKey: StateFlow<String?> = _apiKey.asStateFlow()

    val isConnected: Boolean get() = _apiKey.value != null

    /** A client for reading recipes, or null when no key is saved. */
    fun client(): OpenRouterClient? = _apiKey.value?.let { OpenRouterClient(it) }

    /** Returns false if the key couldn't be stored securely on this phone. */
    fun save(key: String): Boolean {
        val cleaned = cleaned(key)
        if (cleaned.isEmpty() || !store.put(KEY, cleaned)) return false
        _apiKey.value = cleaned
        return true
    }

    fun remove() {
        store.remove(KEY)
        _apiKey.value = null
    }

    companion object {
        private const val KEY = "openrouter.apiKey"

        /** Keys pasted from a web page often pick up spaces, line breaks or quotes. */
        fun cleaned(key: String): String = key.trim { it.isWhitespace() || it == '"' || it == '\'' || it == '`' }

        /** "sk-or-v1-…9f3a": enough to recognize the key without showing it. */
        fun masked(key: String): String {
            val head = if (key.startsWith("sk-or-v1-")) "sk-or-v1-" else key.take(3)
            return "$head…${key.takeLast(4)}"
        }
    }
}

/** Why a request to OpenRouter didn't work. Messages are shown to the cook. */
sealed class OpenRouterFailure(message: String) : Exception(message) {
    data object InvalidKey : OpenRouterFailure("OpenRouter didn't accept your key. Check it in Settings.")
    data object NoCredits : OpenRouterFailure("Your OpenRouter account is out of credit. Add more at openrouter.ai.")
    data object RateLimited : OpenRouterFailure("OpenRouter is busy right now. Try again in a minute.")
    data object Offline : OpenRouterFailure("You're offline.")
    data object TimedOut : OpenRouterFailure("The AI took too long to answer.")
    /** OpenRouter turned the request down (no provider could take it, or it was malformed). */
    data class Rejected(val detail: String) : OpenRouterFailure("OpenRouter couldn't run the model ($detail).")
    data class Unavailable(val detail: String) : OpenRouterFailure("OpenRouter couldn't run the model ($detail).")
    data class BadResponse(val detail: String) : OpenRouterFailure("The AI's answer didn't make sense ($detail).")
}

/** A small client for OpenRouter's OpenAI-compatible chat completions API. Mirrors iOS. */
class OpenRouterClient(private val apiKey: String, private val model: String = AIConfig.MODEL) {
    sealed interface Part {
        data class Text(val text: String) : Part
        class Jpeg(val bytes: ByteArray) : Part
    }

    data class KeyInfo(val label: String, val usage: Double, val limitRemaining: Double?, val isFreeTier: Boolean)

    /** Sends one request that must be answered with JSON matching [schema], and returns the JSON text. */
    suspend fun completeJson(system: String, parts: List<Part>, schemaName: String, schema: JsonObject, maxTokens: Int = 8192): String {
        val strict = requestBody(model, system, parts, schemaName, schema, maxTokens, strict = true)
        return try {
            send(strict.toString())
        } catch (e: OpenRouterFailure.Rejected) {
            // No provider could take the strict request. The prompt describes the JSON too,
            // so one looser attempt usually still works.
            Log.i(TAG, "Strict request rejected (${e.detail}); retrying loosely")
            send(requestBody(model, system, parts, schemaName, schema, maxTokens, strict = false).toString())
        }
    }

    private suspend fun send(body: String, attempt: Int = 1): String {
        val started = System.currentTimeMillis()
        val (code, text) = try {
            http(CHAT_URL, "POST", body, apiKey, readTimeoutMs = 150_000)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw failure(e)
        }
        Log.i(TAG, "OpenRouter answered $code in ${(System.currentTimeMillis() - started) / 1000.0}s")
        if (code in 200..299) return content(text)
        // Provider hiccups are usually gone a moment later.
        if (attempt == 1 && code in listOf(500, 502, 503, 504)) {
            delay(1500)
            return send(body, attempt = 2)
        }
        throw failureFromBody(code, text)
    }

    companion object {
        private const val TAG = "PinchAI"
        const val CHAT_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val KEY_URL = "https://openrouter.ai/api/v1/key"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * The JSON body for a chat completion. With [strict], OpenRouter only routes to providers
         * that enforce the schema and can turn reasoning off.
         */
        fun requestBody(
            model: String,
            system: String,
            parts: List<Part>,
            schemaName: String,
            schema: JsonObject,
            maxTokens: Int,
            strict: Boolean,
        ): JsonObject = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        for (part in parts) {
                            when (part) {
                                is Part.Text -> addJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                }
                                is Part.Jpeg -> addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(part.bytes))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            put("temperature", 0)
            put("max_tokens", maxTokens)
            if (strict) {
                putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", schemaName)
                        put("strict", true)
                        put("schema", schema)
                    }
                }
                putJsonObject("provider") { put("require_parameters", true) }
                putJsonObject("reasoning") { put("effort", "none") }
            } else {
                putJsonObject("response_format") { put("type", "json_object") }
            }
        }

        /** Pulls the model's JSON out of a chat completion response. */
        fun content(body: String): String {
            val root = try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw OpenRouterFailure.BadResponse("not JSON")
            }
            (root["error"] as? JsonObject)?.let { throw failure(it) }
            val choice = (root["choices"]?.let { runCatching { it.jsonArray }.getOrNull() })?.firstOrNull() as? JsonObject
                ?: throw OpenRouterFailure.BadResponse("no answer")
            (choice["error"] as? JsonObject)?.let { throw failure(it) }
            val finish = choice.string("finish_reason")
            val text = (choice["message"] as? JsonObject)?.string("content").orEmpty()
            if (finish == "length") throw OpenRouterFailure.BadResponse("the answer was cut off")
            val cleaned = extractJson(text)
            if (cleaned.isEmpty()) {
                throw OpenRouterFailure.BadResponse(if (finish == "content_filter") "the provider blocked it" else "empty answer")
            }
            return cleaned
        }

        /** Strips code fences, thinking tags or chatter around a JSON object. */
        fun extractJson(text: String): String {
            var t = text
            t.indexOf("</think>").takeIf { it >= 0 }?.let { t = t.substring(it + "</think>".length) }
            t = t.trim()
            if (t.startsWith("```")) {
                val newline = t.indexOf('\n')
                t = if (newline >= 0) t.substring(newline + 1) else ""
                val fence = t.lastIndexOf("```")
                if (fence >= 0) t = t.substring(0, fence)
            }
            val start = t.indexOf('{')
            val end = t.lastIndexOf('}')
            if (start in 0 until end) t = t.substring(start, end + 1)
            return t.trim()
        }

        fun failureFromBody(status: Int, body: String): OpenRouterFailure {
            val message = runCatching { (json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.string("message") }.getOrNull()
            return failure(status, message)
        }

        private fun failure(error: JsonObject): OpenRouterFailure =
            failure((error["code"] as? JsonPrimitive)?.intOrNull ?: 0, error.string("message"))

        fun failure(status: Int, message: String?): OpenRouterFailure = when (status) {
            400, 404, 422 -> OpenRouterFailure.Rejected((message ?: "HTTP $status").take(160))
            401 -> OpenRouterFailure.InvalidKey
            402 -> OpenRouterFailure.NoCredits
            408 -> OpenRouterFailure.TimedOut
            429 -> OpenRouterFailure.RateLimited
            else -> OpenRouterFailure.Unavailable((message ?: "HTTP $status").take(160))
        }

        fun failure(e: IOException): OpenRouterFailure = when (e) {
            is SocketTimeoutException -> OpenRouterFailure.TimedOut
            is UnknownHostException, is ConnectException -> OpenRouterFailure.Offline
            else -> OpenRouterFailure.Unavailable(e.message ?: "network error")
        }

        /** Checks a key with OpenRouter and returns what it knows about it. */
        suspend fun checkKey(key: String): KeyInfo {
            val (code, text) = try {
                http(KEY_URL, "GET", null, key, readTimeoutMs = 20_000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw failure(e)
            }
            if (code !in 200..299) throw if (code == 403) OpenRouterFailure.InvalidKey else failureFromBody(code, text)
            return keyInfo(text)
        }

        fun keyInfo(body: String): KeyInfo {
            val data = runCatching { json.parseToJsonElement(body).jsonObject["data"] as? JsonObject }.getOrNull()
                ?: throw OpenRouterFailure.BadResponse("unexpected key details")
            return KeyInfo(
                label = data.string("label").orEmpty(),
                usage = (data["usage"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                limitRemaining = (data["limit_remaining"] as? JsonPrimitive)?.doubleOrNull,
                isFreeTier = (data["is_free_tier"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        /**
         * One HTTP exchange on a background thread. Cancelling the coroutine closes the
         * connection, so a request that's no longer wanted stops right away.
         */
        suspend fun http(url: String, method: String, body: String?, bearer: String, readTimeoutMs: Int): Pair<Int, String> = coroutineScope {
            val connection = URL(url).openConnection() as HttpURLConnection
            val exchange = async(Dispatchers.IO) {
                try {
                    connection.requestMethod = method
                    connection.connectTimeout = 20_000
                    connection.readTimeout = readTimeoutMs
                    connection.setRequestProperty("Authorization", "Bearer $bearer")
                    connection.setRequestProperty("X-Title", "Pinch")
                    if (body != null) {
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { it.write(bytes) }
                    }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    code to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
                } finally {
                    connection.disconnect()
                }
            }
            try {
                exchange.await()
            } catch (e: CancellationException) {
                connection.disconnect()
                throw e
            }
        }
    }
}

/** Reads a JSON value as text whatever its shape, for lenient decoding. */
internal fun JsonElement?.asText(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    is kotlinx.serialization.json.JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
    else -> ""
}
