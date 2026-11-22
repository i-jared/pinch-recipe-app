package com.example.pinch.ui.library

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.pinch.AppContainer
import com.example.pinch.model.CoverPalette
import com.example.pinch.model.CoverPattern
import com.example.pinch.model.CoverSuggester
import com.example.pinch.model.Recipe
import com.example.pinch.model.SampleRecipes
import com.example.pinch.search.FuzzySearch
import com.example.pinch.ui.LocalTransitionScopes
import com.example.pinch.ui.capture.PasteRecipeDialog
import com.example.pinch.ui.capture.WebRecipeDialog
import com.example.pinch.ui.components.CoverArt
import com.example.pinch.data.PhotoStore
import com.example.pinch.ui.components.EmojiSticker
import com.example.pinch.ui.components.PrimaryButton
import com.example.pinch.ui.components.SoftButton
import com.example.pinch.ui.components.TabChip
import com.example.pinch.ui.components.TimerDock
import com.example.pinch.ui.components.bottomFade
import com.example.pinch.ui.groups.GroupsScreen
import com.example.pinch.sync.ServerGroup
import com.example.pinch.ui.theme.Pinch
import com.example.pinch.ui.theme.base
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File

private sealed interface Filter {
    data object All : Filter
    data object Favorites : Filter
    data object Quick : Filter
    data class Tag(val tag: String) : Filter
}

private enum class Sort(val label: String) { NEWEST("Newest"), ALPHABETICAL("A to Z"), QUICKEST("Quickest"), COOKED("Recently cooked") }

@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenRecipe: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCapture: () -> Unit,
    onOpenCookbook: (ServerGroup) -> Unit,
    onConnect: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE) }
    var presentation by rememberSaveable { mutableStateOf(preferences.getString("presentation", "recipes") ?: "recipes") }
    val recipes by container.repository.recipes.collectAsState()
    val loaded by container.repository.loaded.collectAsState()
    val timers by container.timers.timers.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by remember { mutableStateOf<Filter>(Filter.All) }
    var sort by rememberSaveable { mutableStateOf(Sort.NEWEST) }
    var showPaste by remember { mutableStateOf(false) }
    var showWeb by remember { mutableStateOf(false) }
    val aiKey by container.ai.apiKey.collectAsState()
    val hosting by container.server.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<Recipe?>(null) }
    val focus = LocalFocusManager.current

    val launchers = rememberCaptureLaunchers(
        onPages = { uris ->
            container.capture.startPages(uris)
            onOpenCapture()
        },
    )

    Box(Modifier.fillMaxSize().background(Pinch.colors.canvas)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(158.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 140.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Header(count = recipes.size, presentation = presentation, onChangePresentation = {
                    focus.clearFocus()
                    presentation = it
                    preferences.edit().putString("presentation", it).apply()
                }, onOpenSettings = onOpenSettings)
            }
            if (presentation == "cookbooks") {
                item(span = { GridItemSpan(maxLineSpan) }) { GroupsScreen(container, onOpenCookbook, onConnect) }
            } else if (loaded && recipes.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyLibrary(onSample = { container.repository.saveAll(SampleRecipes.all()) })
                }
            } else if (recipes.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchField(query, onChange = { query = it })
                }
                if (query.isBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        FilterBar(recipes, container, filter, { filter = it }, sort, { sort = it })
                    }
                    val shown = filtered(recipes, filter, sort)
                    if (shown.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "Nothing here yet. Recipes you mark with a heart or tag show up in these filters.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Pinch.colors.inkSoft,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 40.dp).fillMaxWidth(),
                            )
                        }
                    }
                    items(shown, key = { it.id }) { recipe ->
                        RecipeTin(
                            recipe = recipe,
                            photos = container.photos,
                            onClick = { focus.clearFocus(); onOpenRecipe(recipe.id) },
                            onToggleFavorite = { container.repository.toggleFavorite(recipe.id) },
                            onDelete = { pendingDelete = recipe },
                        )
                    }
                } else {
                    val results = container.search.search(query, recipes)
                    if (results.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { NoResults(query) }
                    }
                    items(results, key = { it.recipe.id }, span = { GridItemSpan(maxLineSpan) }) { result ->
                        SearchRow(result, container.photos) { focus.clearFocus(); onOpenRecipe(result.recipe.id) }
                    }
                }
            }
        }

        // Keeps scrolled tins from running under the clock and battery icons.
        Box(
            Modifier
                .fillMaxWidth()
                .background(Pinch.colors.canvas.copy(alpha = 0.94f))
                .windowInsetsTopHeight(WindowInsets.statusBars),
        )

        // Scan and add, pinned to the bottom.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .bottomFade(Pinch.colors.canvas)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TimerDock(timers, container.timers)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    "Scan a recipe",
                    onClick = { focus.clearFocus(); launchers.scan() },
                    icon = Icons.Rounded.CameraAlt,
                    modifier = Modifier.weight(1f).shadow(14.dp, CircleShape, ambientColor = Pinch.colors.jadeDeep, spotColor = Pinch.colors.jadeDeep),
                )
                Spacer(Modifier.width(10.dp))
                AddMenu(
                    onPhotos = launchers.pickPhotos,
                    onWeb = { showWeb = true },
                    onPaste = { showPaste = true },
                    onBlank = { container.capture.startBlank(); onOpenCapture() },
                )
            }
        }
    }

    if (showWeb) {
        WebRecipeDialog(
            aiConnected = hosting.managed || aiKey != null,
            onDismiss = { showWeb = false },
            onSubmit = { url ->
                showWeb = false
                container.capture.startUrl(url)
                onOpenCapture()
            },
        )
    }

    if (showPaste) {
        PasteRecipeDialog(
            onDismiss = { showPaste = false },
            onSubmit = { text ->
                showPaste = false
                container.capture.startText(text)
                onOpenCapture()
            },
        )
    }

    pendingDelete?.let { recipe ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${recipe.displayTitle}?") },
            text = { Text(if (container.server.state.value.connected) "This removes it from your devices and your server backup. This also removes it from your cookbooks." else "This removes it from this device.") },
            confirmButton = {
                TextButton(onClick = { container.repository.delete(recipe.id); pendingDelete = null }) {
                    Text("Delete recipe", color = Pinch.colors.cherry)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep it") } },
            containerColor = Pinch.colors.surface,
        )
    }
}

private fun filtered(recipes: List<Recipe>, filter: Filter, sort: Sort): List<Recipe> {
    val list = when (filter) {
        Filter.All -> recipes
        Filter.Favorites -> recipes.filter { it.isFavorite }
        Filter.Quick -> recipes.filter { (it.totalMinutes ?: Int.MAX_VALUE) <= 30 }
        is Filter.Tag -> recipes.filter { filter.tag in it.tags }
    }
    return when (sort) {
        Sort.NEWEST -> list.sortedByDescending { it.createdAt }
        Sort.ALPHABETICAL -> list.sortedBy { it.displayTitle.lowercase() }
        Sort.QUICKEST -> list.sortedBy { it.totalMinutes ?: Int.MAX_VALUE }
        Sort.COOKED -> list.sortedByDescending { it.lastCookedAt?.toEpochMilli() ?: 0L }
    }
}

@Composable
private fun Header(count: Int, presentation: String, onChangePresentation: (String) -> Unit, onOpenSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Pinch", style = MaterialTheme.typography.displaySmall, color = Pinch.colors.ink)
            Text(
                when (count) {
                    0 -> "Your recipe box"
                    1 -> "1 recipe in your box"
                    else -> "$count recipes in your box"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Pinch.colors.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LibraryViewButton(Icons.Rounded.CollectionsBookmark, "Cookbooks", presentation == "cookbooks") { onChangePresentation("cookbooks") }
            LibraryViewButton(Icons.Rounded.GridView, "All recipes", presentation == "recipes") { onChangePresentation("recipes") }
        }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Pinch.colors.sunken)
                .clickable(role = Role.Button, onClick = onOpenSettings)
                .semantics { contentDescription = "Settings" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Settings, null, tint = Pinch.colors.ink)
        }
    }
}

@Composable
private fun LibraryViewButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .background(if (selected) Pinch.colors.jade else Pinch.colors.sunken)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (selected) Pinch.colors.onJade else Pinch.colors.ink) }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(Pinch.colors.sunken)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Pinch.colors.inkSoft)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Search recipes and ingredients", style = MaterialTheme.typography.bodyLarge, color = Pinch.colors.inkSoft, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Pinch.colors.ink),
                cursorBrush = SolidColor(Pinch.colors.jadeDeep),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search recipes" },
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Clear search",
                tint = Pinch.colors.inkSoft,
                modifier = Modifier.clip(CircleShape).clickable { onChange(""); focus.clearFocus() }.padding(6.dp),
            )
        }
    }
}

@Composable
private fun FilterBar(
    recipes: List<Recipe>,
    container: AppContainer,
    filter: Filter,
    onFilter: (Filter) -> Unit,
    sort: Sort,
    onSort: (Sort) -> Unit,
) {
    var sortOpen by remember { mutableStateOf(false) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        item { TabChip("All", filter == Filter.All, { onFilter(Filter.All) }) }
        if (recipes.any { it.isFavorite }) {
            item { TabChip("Favorites", filter == Filter.Favorites, { onFilter(Filter.Favorites) }, Icons.Rounded.Favorite) }
        }
        if (recipes.any { (it.totalMinutes ?: Int.MAX_VALUE) <= 30 }) {
            item { TabChip("30 min or less", filter == Filter.Quick, { onFilter(Filter.Quick) }, Icons.Rounded.Bolt) }
        }
        items(container.repository.allTags(recipes)) { tag ->
            TabChip(tag, filter == Filter.Tag(tag), { onFilter(Filter.Tag(tag)) })
        }
        item {
            Box {
                Row(
                    Modifier.clip(CircleShape).clickable { sortOpen = true }.padding(horizontal = 10.dp, vertical = 9.dp)
                        .semantics { contentDescription = "Sort recipes, currently ${sort.label}" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.SwapVert, null, tint = Pinch.colors.inkSoft, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(sort.label, style = MaterialTheme.typography.labelMedium, color = Pinch.colors.inkSoft)
                }
                DropdownMenu(sortOpen, onDismissRequest = { sortOpen = false }, containerColor = Pinch.colors.surface) {
                    Sort.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { onSort(option); sortOpen = false })
                    }
                }
            }
        }
    }
}

/** A recipe on the shelf: a patterned tin with a white label for the name. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun RecipeTin(recipe: Recipe, onClick: () -> Unit, photos: PhotoStore? = null, onToggleFavorite: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    val scopes = LocalTransitionScopes.current
    val shape = RoundedCornerShape(22.dp)
    Box {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.8f)
                .clip(shape)
                .combinedClickable(onClick = onClick, onLongClick = { if (onToggleFavorite != null || onDelete != null) menu = true }, role = Role.Button)
                .semantics(mergeDescendants = true) { contentDescription = recipe.displayTitle + (recipe.displayTime?.let { ", $it" } ?: "") },
        ) {
            val cover = Modifier.fillMaxSize()
            val banner = recipe.bannerPhoto?.let { photos?.file(it) }
            if (scopes != null) {
                with(scopes.shared) {
                    CoverArt(
                        recipe.palette, recipe.pattern,
                        cover.sharedBounds(rememberSharedContentState("cover-${recipe.id}"), scopes.animated),
                        cell = 20.dp, photo = banner,
                    )
                }
            } else {
                CoverArt(recipe.palette, recipe.pattern, cover, cell = 20.dp, photo = banner)
            }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                    EmojiSticker(recipe.emoji, 54.dp, tilt = if (CoverSuggester.stableIndex(recipe.id, 2) == 0) -8f else 7f)
                    Spacer(Modifier.weight(1f))
                    if (recipe.isFavorite) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Favorite, "Favorite", tint = Pinch.colors.cherry, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(
                    Modifier
                        .padding(8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        recipe.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF17332C),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    recipe.displayTime?.let { time ->
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Schedule, null, tint = Color(0xFF587068), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(time, style = MaterialTheme.typography.labelSmall, color = Color(0xFF587068))
                        }
                    }
                }
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Pinch.colors.surface) {
            if (onToggleFavorite != null) DropdownMenuItem(
                text = { Text(if (recipe.isFavorite) "Remove from favorites" else "Add to favorites") },
                leadingIcon = { Icon(if (recipe.isFavorite) Icons.Rounded.FavoriteBorder else Icons.Rounded.Favorite, null) },
                onClick = { onToggleFavorite(); menu = false },
            )
            if (onDelete != null) DropdownMenuItem(
                text = { Text("Delete", color = Pinch.colors.cherry) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Pinch.colors.cherry) },
                onClick = { onDelete(); menu = false },
            )
        }
    }
}

@Composable
private fun SearchRow(result: FuzzySearch.Result, photos: PhotoStore, onClick: () -> Unit) {
    val recipe = result.recipe
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Pinch.colors.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            CoverArt(recipe.palette, recipe.pattern, Modifier.fillMaxSize(), cell = 14.dp, photo = recipe.bannerPhoto?.let(photos::file))
            Text(recipe.emoji, style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(recipe.displayTitle, style = MaterialTheme.typography.titleMedium, color = Pinch.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = result.snippet ?: recipe.displayTime
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = Pinch.colors.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Pinch.colors.inkSoft.copy(alpha = 0.6f))
    }
}

@Composable
private fun NoResults(query: String) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No recipes match “$query”", style = MaterialTheme.typography.titleMedium, color = Pinch.colors.ink)
        Spacer(Modifier.height(6.dp))
        Text("Try an ingredient, like “lemon”, or part of a name.", style = MaterialTheme.typography.bodyMedium, color = Pinch.colors.inkSoft)
    }
}

@Composable
private fun EmptyLibrary(onSample: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(200.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            MiniTin(CoverPalette.SAFFRON, CoverPattern.STRIPES, "🥧", -14f, -78, 16)
            MiniTin(CoverPalette.JADE, CoverPattern.DOTS, "🥗", 12f, 78, 18)
            MiniTin(CoverPalette.TOMATO, CoverPattern.GINGHAM, "🍝", -3f, 0, -6)
        }
        Spacer(Modifier.height(22.dp))
        Text("Your recipe box is empty", style = MaterialTheme.typography.headlineSmall, color = Pinch.colors.ink)
        Spacer(Modifier.height(10.dp))
        Text(
            "Scan a recipe card, a cookbook page or a magazine clipping, or bring one in from a website. Pinch reads it and files the recipe for you.",
            style = MaterialTheme.typography.bodyLarge,
            color = Pinch.colors.inkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(22.dp))
        SoftButton("Add three sample recipes", onClick = onSample)
    }
}

@Composable
private fun MiniTin(palette: CoverPalette, pattern: CoverPattern, emoji: String, angle: Float, x: Int, y: Int) {
    Box(
        Modifier
            .offset(x.dp, y.dp)
            .rotate(angle)
            .size(118.dp, 146.dp)
            .shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = palette.base, spotColor = palette.base)
            .clip(RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        CoverArt(palette, pattern, Modifier.fillMaxSize(), cell = 16.dp)
        EmojiSticker(emoji, 58.dp, tilt = 0f)
    }
}

@Composable
private fun AddMenu(onPhotos: () -> Unit, onWeb: () -> Unit, onPaste: () -> Unit, onBlank: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(56.dp)
                .shadow(14.dp, CircleShape, ambientColor = Pinch.colors.jadeDeep, spotColor = Pinch.colors.jadeDeep)
                .clip(CircleShape)
                .background(Pinch.colors.jade)
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = "More ways to add a recipe" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, null, tint = Pinch.colors.onJade, modifier = Modifier.size(26.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Pinch.colors.surface) {
            DropdownMenuItem(text = { Text("Choose photos") }, leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) }, onClick = { open = false; onPhotos() })
            DropdownMenuItem(text = { Text("From a website") }, leadingIcon = { Icon(Icons.Rounded.Language, null) }, onClick = { open = false; onWeb() })
            DropdownMenuItem(text = { Text("Paste text") }, leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) }, onClick = { open = false; onPaste() })
            DropdownMenuItem(text = { Text("Type it in") }, leadingIcon = { Icon(Icons.Rounded.EditNote, null) }, onClick = { open = false; onBlank() })
        }
    }
}

// Capture launchers, shared with the editor's "Add page".

class CaptureLaunchers(val scan: () -> Unit, val pickPhotos: () -> Unit)

@Composable
fun rememberCaptureLaunchers(onPages: (List<Uri>) -> Unit): CaptureLaunchers {
    val context = LocalContext.current
    // Saveable: Android may stop Pinch while the camera app is open.
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) onPages(uris)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        if (saved && uri != null) onPages(listOf(uri))
    }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages.orEmpty().map { it.imageUri }
            if (pages.isNotEmpty()) onPages(pages)
        }
    }

    fun openCamera() {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "page-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraUri = uri
        try {
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            // No camera app (some tablets and emulators): choose photos instead.
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    return remember(context) {
        CaptureLaunchers(
            scan = {
                val activity = context.findActivity()
                val client = DocumentScanner.client()
                val modules = ModuleInstall.getClient(context)
                // ML Kit's scanner finds page edges, straightens them and takes as many pages as
                // you need, but Play services downloads it on first use. Until it's on the phone,
                // the camera app stands in, so scanning always works offline.
                modules.areModulesAvailable(client)
                    .addOnSuccessListener { response ->
                        if (response.areModulesAvailable() && activity != null) {
                            client.getStartScanIntent(activity)
                                .addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
                                .addOnFailureListener { openCamera() }
                        } else {
                            modules.deferredInstall(client)
                            openCamera()
                        }
                    }
                    .addOnFailureListener { openCamera() }
            },
            pickPhotos = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }
}

object DocumentScanner {
    fun client() = GmsDocumentScanning.getClient(
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(10)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    )
}

fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
