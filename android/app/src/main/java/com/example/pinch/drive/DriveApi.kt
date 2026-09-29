package com.example.pinch.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

data class DriveFile(val id: String, val name: String, val appProperties: Map<String, String>)

class DriveHttpException(val code: Int, message: String) : IOException("Google Drive responded with an error ($code): $message")

/**
 * The handful of Google Drive v3 REST calls sync needs. Same layout as iOS: a "Pinch Recipes"
 * folder with one JSON file per recipe and a "Photos" subfolder.
 */
class DriveApi(
    private val token: suspend () -> String,
    private val onUnauthorized: suspend (String) -> Unit,
) {
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val BASE = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    }

    suspend fun userEmail(): String? {
        val body = send("https://www.googleapis.com/drive/v3/about", query = mapOf("fields" to "user(emailAddress)"))
        return JSONObject(String(body)).optJSONObject("user")?.optString("emailAddress")?.ifEmpty { null }
    }

    suspend fun findFolder(key: String, value: String, parent: String?): String? {
        var q = "mimeType = '$FOLDER_MIME' and trashed = false and appProperties has { key='$key' and value='$value' }"
        if (parent != null) q += " and '$parent' in parents"
        return list(q).firstOrNull()?.id
    }

    suspend fun createFolder(name: String, parent: String?, appProperties: Map<String, String>): String {
        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
            .put("appProperties", JSONObject(appProperties))
        if (parent != null) metadata.put("parents", JSONArray().put(parent))
        val body = send(BASE, "POST", mapOf("fields" to "id"), metadata.toString().toByteArray(), "application/json; charset=UTF-8")
        return JSONObject(String(body)).getString("id")
    }

    /** False when the folder was deleted or moved to the trash in Drive. */
    suspend fun isUsableFolder(id: String): Boolean = try {
        !JSONObject(String(send("$BASE/$id", query = mapOf("fields" to "trashed")))).optBoolean("trashed", false)
    } catch (e: DriveHttpException) {
        if (e.code == 404) false else throw e
    }

    suspend fun files(folder: String): List<DriveFile> = list("'$folder' in parents and trashed = false")

    private suspend fun list(query: String): List<DriveFile> {
        val all = ArrayList<DriveFile>()
        var pageToken: String? = null
        do {
            val params = mutableMapOf(
                "q" to query,
                "spaces" to "drive",
                "pageSize" to "1000",
                "orderBy" to "createdTime",
                "fields" to "nextPageToken, files(id, name, mimeType, appProperties)",
            )
            pageToken?.let { params["pageToken"] = it }
            val page = JSONObject(String(send(BASE, query = params)))
            val files = page.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val props = HashMap<String, String>()
                f.optJSONObject("appProperties")?.let { o -> o.keys().forEach { k -> props[k] = o.getString(k) } }
                all.add(DriveFile(f.getString("id"), f.optString("name"), props))
            }
            pageToken = page.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        return all
    }

    /** Creates a file, or replaces the content of [existingId]. Returns the file ID. */
    suspend fun upload(
        name: String,
        mimeType: String,
        data: ByteArray,
        parent: String,
        appProperties: Map<String, String>,
        existingId: String?,
    ): String {
        val metadata = JSONObject().put("name", name).put("appProperties", JSONObject(appProperties))
        if (existingId == null) {
            metadata.put("parents", JSONArray().put(parent))
            metadata.put("mimeType", mimeType)
        }
        val boundary = "pinch-" + UUID.randomUUID()
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            write(metadata.toString().toByteArray())
            write("\r\n--$boundary\r\nContent-Type: $mimeType\r\n\r\n".toByteArray())
            write(data)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val url = if (existingId == null) UPLOAD else "$UPLOAD/$existingId"
        val response = send(
            url, if (existingId == null) "POST" else "PATCH",
            mapOf("uploadType" to "multipart", "fields" to "id"), body, "multipart/related; boundary=$boundary",
        )
        return JSONObject(String(response)).getString("id")
    }

    suspend fun download(id: String): ByteArray = send("$BASE/$id", query = mapOf("alt" to "media"))

    suspend fun delete(id: String) {
        send("$BASE/$id", "DELETE")
    }

    private suspend fun send(
        path: String,
        method: String = "GET",
        query: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        retrying: Boolean = false,
    ): ByteArray {
        val accessToken = token()
        val result = withContext(Dispatchers.IO) {
            val queryString = query.entries.sortedBy { it.key }.joinToString("&") {
                "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8").replace("+", "%20")}"
            }
            val url = URL(if (queryString.isEmpty()) path else "$path?$queryString")
            val connection = url.openConnection() as HttpURLConnection
            try {
                // HttpURLConnection has no PATCH; Google accepts the override header.
                if (method == "PATCH") {
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                } else {
                    connection.requestMethod = method
                }
                connection.connectTimeout = 20_000
                connection.readTimeout = 45_000
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
                if (body != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                code to bytes
            } finally {
                connection.disconnect()
            }
        }
        val (code, bytes) = result
        if (code == 401 && !retrying) {
            onUnauthorized(accessToken)
            return send(path, method, query, body, contentType, retrying = true)
        }
        if (code !in 200..299) throw DriveHttpException(code, String(bytes).take(300))
        return bytes
    }
}
