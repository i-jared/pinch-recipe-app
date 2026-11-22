package com.example.pinch

import com.example.pinch.model.Recipe
import com.example.pinch.sync.ServerApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ServerTest {
    @Test fun requiresTlsAndRejectsEmbeddedSecrets() {
        assertEquals("https://recipes.example.com/pinch", ServerApi.serverUrl(" https://recipes.example.com/pinch/ "))
        for (address in listOf("http://recipes.example.com", "https://user:password@example.com", "https://example.com?token=secret", "https://example.com#fragment", "file:///tmp/recipes", "")) {
            assertThrows(IllegalArgumentException::class.java) { ServerApi.serverUrl(address) }
        }
    }

    @Test fun remoteNamesCannotEscapeStorage() {
        val recipe = Recipe(id = "recipe-1", photos = listOf("page-1.jpg"))
        ServerApi.validate(recipe)
        assertThrows(IllegalArgumentException::class.java) { ServerApi.validate(recipe.copy(id = "../escape")) }
        for (name in listOf("../escape.jpg", "/absolute.jpg", "folder/page.jpg", "page.jpg?x=1")) {
            assertThrows(IllegalArgumentException::class.java) { ServerApi.validate(recipe.copy(photos = listOf(name))) }
        }
    }

    @Test fun sendsSessionOnlyToConfiguredEndpoint() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
            val request = CompletableFuture<List<String>>()
            thread(isDaemon = true) {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val lines = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                    request.complete(lines)
                    client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]".toByteArray())
                }
            }
            val api = ServerApi("http://127.0.0.1:${socket.localPort}/pinch", "test-session")
            assertEquals("[]", api.send("recipes").toString(Charsets.UTF_8))
            val headers = request.get(5, TimeUnit.SECONDS)
            assertEquals("GET /pinch/v1/recipes HTTP/1.1", headers.first())
            assertTrue(headers.any { it == "Authorization: Bearer test-session" })
        }
    }

    @Test fun refusesRedirects() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
            thread(isDaemon = true) {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    client.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/stolen\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
            try {
                ServerApi("http://127.0.0.1:${socket.localPort}", "test-session").send("recipes")
                fail("Expected redirect rejection")
            } catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("302")) }
        }
    }
}
