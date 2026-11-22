package com.example.pinch

import com.example.pinch.model.Cookbook
import com.example.pinch.model.CookbookEntry
import com.example.pinch.data.PinchJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CookbookTest {
    @Test fun offlineChangesKeepContributionsAndRemovals() {
        val old = Instant.ofEpochSecond(1000)
        val newer = old.plusSeconds(2)
        val device = Cookbook("book", "Dinners", old, entries = listOf(CookbookEntry("mine", newer, true)))
        val server = Cookbook("book", "Dinners", old, entries = listOf(CookbookEntry("mine", old), CookbookEntry("contributed", newer)))
        val merged = device.merging(server)
        assertEquals(setOf("contributed"), merged.recipeIds)
        assertEquals(merged, server.merging(device))
        assertEquals(merged, PinchJson.json.decodeFromString<Cookbook>(PinchJson.json.encodeToString(merged)))
    }
    @Test fun tombstonesSurviveOfflineEditsAndSerializedPrecision() {
        val deleted = Cookbook("book", "Family", Instant.ofEpochSecond(1000), deletedAt = Instant.ofEpochSecond(1001), entries = listOf(CookbookEntry("bread", Instant.ofEpochMilli(2000), true)))
        val stale = deleted.copy(deletedAt = null, entries = listOf(CookbookEntry("bread", Instant.ofEpochMilli(2000).plusNanos(100))))
        assertNotNull(deleted.merging(stale).deletedAt)
        assertTrue(deleted.merging(stale).recipeIds.isEmpty())
    }
}
