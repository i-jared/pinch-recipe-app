package com.example.pinch.data

import com.example.pinch.model.Recipe
import com.example.pinch.model.Cookbook
import com.example.pinch.model.CookbookEntry
import kotlinx.serialization.encodeToString
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
 * server sync a file-by-file affair. Deleted recipes stay as tombstones so the
 * deletion reaches other devices. Mirrors the iOS `RecipeStore`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeRepository(
    private val directory: File,
    val photos: PhotoStore,
    private val scope: CoroutineScope,
) {
    private val byId = MutableStateFlow<Map<String, Recipe>>(emptyMap())
    private val _cookbooks = MutableStateFlow<List<Cookbook>>(emptyList())
    val cookbooks = _cookbooks.asStateFlow()
    private val cookbookDirectory = File(directory, "_cookbooks")
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
        cookbookDirectory.mkdirs()
        scope.launch(io) {
            val loaded = HashMap<String, Recipe>()
            directory.listFiles { f -> f.extension == "json" }?.forEach { file ->
                runCatching { PinchJson.decode(file.readText()) }.getOrNull()?.let { loaded[it.id] = it }
            }
            // Anything saved while the files were loading wins over what was on disk.
            byId.update { saved -> loaded + saved }
            val books = cookbookDirectory.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { runCatching { PinchJson.json.decodeFromString<Cookbook>(it.readText()) }.getOrNull() }
            _cookbooks.update { current -> (books + current).groupBy { it.id }.values.map { versions -> versions.reduce { a, b -> a.merging(b) } } }
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
        val files = recipe.photoFiles
        val now = Instant.now()
        _cookbooks.value.filter { id in it.recipeIds }.forEach { setMembership(id, it.id, false) }
        commit(
            recipe.copy(
                deletedAt = now, updatedAt = now, photos = emptyList(), ingredients = emptyList(),
                recipePhotos = emptyList(), bannerPhoto = null,
                steps = emptyList(), rawText = "", notes = "",
            )
        )
        scope.launch(io) { files.forEach(photos::delete) }
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

    fun createCookbook(name: String): Cookbook = Cookbook(name = name.trim()).also { applyCookbook(it); onLocalChange?.invoke() }

    fun renameCookbook(id: String, name: String) {
        _cookbooks.value.firstOrNull { it.id == id }?.let { applyCookbook(it.copy(name = name.trim(), updatedAt = nextCookbookTime(it.updatedAt))); onLocalChange?.invoke() }
    }

    fun deleteCookbook(id: String) {
        _cookbooks.value.firstOrNull { it.id == id }?.let {
            val changed = nextCookbookTime(it.updatedAt)
            applyCookbook(it.copy(updatedAt = changed, deletedAt = changed)); onLocalChange?.invoke()
        }
    }

    fun setMembership(recipeId: String, bookId: String, included: Boolean) {
        _cookbooks.value.firstOrNull { it.id == bookId && it.deletedAt == null }?.let {
            val changed = nextCookbookTime(it.entries.firstOrNull { entry -> entry.recipeId == recipeId }?.updatedAt ?: Instant.EPOCH)
            applyCookbook(it.copy(entries = it.entries.filter { entry -> entry.recipeId != recipeId } + CookbookEntry(recipeId, changed, !included)))
            onLocalChange?.invoke()
        }
    }

    fun applyCookbook(incoming: Cookbook) {
        require(incoming.id.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val book = _cookbooks.value.firstOrNull { it.id == incoming.id }?.merging(incoming) ?: incoming
        _cookbooks.update { books -> books.filter { it.id != book.id } + book }
        val text = PinchJson.json.encodeToString(book)
        scope.launch(io) {
            val target = File(cookbookDirectory, "${book.id}.json")
            val temp = File(cookbookDirectory, "${book.id}.json.tmp")
            temp.writeText(text)
            if (!temp.renameTo(target)) { target.writeText(text); temp.delete() }
        }
    }

    private fun nextCookbookTime(previous: Instant): Instant = maxOf(Instant.now(), Instant.ofEpochMilli(previous.toEpochMilli() + 1))

    /** Stores a version that came from server, keeping its timestamps. */
    fun applySynced(recipe: Recipe) {
        val existing = byId.value[recipe.id]
        if (existing != null) {
            val gone = existing.photoFiles.toSet() - (if (recipe.isDeleted) emptySet() else recipe.photoFiles.toSet())
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
