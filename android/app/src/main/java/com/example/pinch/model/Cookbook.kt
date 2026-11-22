package com.example.pinch.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class CookbookEntry(val recipeId: String, @Serializable(with = InstantSerializer::class) val updatedAt: Instant = Instant.now(), val removed: Boolean = false)

@Serializable
data class Cookbook(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class) val deletedAt: Instant? = null,
    val entries: List<CookbookEntry> = emptyList(),
) {
    val recipeIds: Set<String> get() = entries.filter { !it.removed }.map { it.recipeId }.toSet()
    fun merging(other: Cookbook): Cookbook {
        val items = entries.associateBy { it.recipeId }.toMutableMap()
        for (entry in other.entries) {
            val current = items[entry.recipeId]
            if (current != null && (current.updatedAt.toEpochMilli() > entry.updatedAt.toEpochMilli() || (current.updatedAt.toEpochMilli() == entry.updatedAt.toEpochMilli() && current.removed))) continue
            items[entry.recipeId] = entry
        }
        return (if (other.updatedAt.toEpochMilli() > updatedAt.toEpochMilli()) other else this).copy(deletedAt = deletedAt ?: other.deletedAt, entries = items.values.sortedBy { it.recipeId })
    }
}
