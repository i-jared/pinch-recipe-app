package com.example.pinch.data

import com.example.pinch.model.Recipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

/**
 * The recipe library. Each recipe is one JSON file, which keeps saves small and makes
 * Google Drive sync a file-by-file affair. Deleted recipes stay as tombstones so the
 * deletion reaches other devices. Mirrors the iOS `RecipeStore`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeRepository(
    private val directory: File,
    val photos: PhotoStore,
    private val scope: CoroutineScope,
) {
    private val byId = MutableStateFlow<Map<String, Recipe>>(emptyMap())
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Visible recipes, newest first. */
    val recipes: StateFlow<List<Recipe>> = byId
        .map { map -> map.values.filter { !it.isDeleted }.sortedWith(compareByDescending<Recipe> { it.createdAt }.thenBy { it.title }) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Called after any local edit; the sync engine uses it to schedule an upload. */
    var onLocalChange: (() -> Unit)? = null

    // One writer at a time so saves land on disk in order.
    private val io = Dispatchers.IO.limitedParallelism(1)

    init {
        directory.mkdirs()
        scope.launch(io) {
            val loaded = HashMap<String, Recipe>()
            directory.listFiles { f -> f.extension == "json" }?.forEach { file ->
                runCatching { PinchJson.decode(file.readText()) }.getOrNull()?.let { loaded[it.id] = it }
            }
            // Anything saved while the files were loading wins over what was on disk.
            byId.update { saved -> loaded + saved }
            _loaded.value = true
        }
    }

    fun recipe(id: String): Recipe? = byId.value[id]?.takeIf { !it.isDeleted }

    fun observe(id: String) = byId.map { it[id]?.takeIf { r -> !r.isDeleted } }

    val allIncludingDeleted: List<Recipe> get() = byId.value.values.toList()

    /** Tags in use, most common first. */
    fun allTags(recipes: List<Recipe>): List<String> =
        recipes.flatMap { it.tags }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    // Local edits

    fun save(recipe: Recipe) {
        commit(recipe.copy(updatedAt = Instant.now()))
        onLocalChange?.invoke()
    }

    fun saveAll(recipes: List<Recipe>) {
        recipes.forEach { commit(it.copy(updatedAt = Instant.now())) }
        onLocalChange?.invoke()
    }

    fun delete(id: String) {
        val recipe = byId.value[id] ?: return
        val pages = recipe.photos
        val now = Instant.now()
        commit(
            recipe.copy(
                deletedAt = now, updatedAt = now, photos = emptyList(), ingredients = emptyList(),
                steps = emptyList(), rawText = "", notes = "",
            )
        )
        scope.launch(io) { pages.forEach(photos::delete) }
        onLocalChange?.invoke()
    }

    fun toggleFavorite(id: String) {
        val recipe = recipe(id) ?: return
        save(recipe.copy(isFavorite = !recipe.isFavorite))
    }

    fun markCooked(id: String) {
        val recipe = recipe(id) ?: return
        save(recipe.copy(lastCookedAt = Instant.now()))
    }

    // Sync

    /** Stores a version that came from Google Drive, keeping its timestamps. */
    fun applySynced(recipe: Recipe) {
        val existing = byId.value[recipe.id]
        if (existing != null) {
            // Pages removed on another device (or the whole recipe) go from this one too.
            val gone = existing.photos.toSet() - (if (recipe.isDeleted) emptySet() else recipe.photos.toSet())
            if (gone.isNotEmpty()) scope.launch(io) { gone.forEach(photos::delete) }
        }
        commit(recipe)
    }

    private fun commit(recipe: Recipe) {
        byId.update { it + (recipe.id to recipe) }
        val text = PinchJson.encode(recipe)
        scope.launch(io) {
            val target = File(directory, "${recipe.id}.json")
            val temp = File(directory, "${recipe.id}.json.tmp")
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.writeText(text)
                temp.delete()
            }
        }
    }
}
