package com.example.pinch.ui.detail

import androidx.compose.material.icons.rounded.Language
import com.example.pinch.scan.WebRecipeImporter
import androidx.compose.ui.platform.LocalUriHandler
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.pinch.AppContainer
import com.example.pinch.CookingSession
import com.example.pinch.data.PhotoStore
import com.example.pinch.model.CoverPalette
import com.example.pinch.model.Recipe
import com.example.pinch.parsing.DurationParser
import com.example.pinch.parsing.IngredientParser
import com.example.pinch.parsing.TextKit
import com.example.pinch.timers.TimerManager
import com.example.pinch.ui.LocalTransitionScopes
import com.example.pinch.ui.components.CoverArt
import com.example.pinch.ui.components.EmojiSticker
import com.example.pinch.ui.components.MetaPill
import com.example.pinch.ui.components.PageViewerDialog
import com.example.pinch.ui.components.PrimaryButton
import com.example.pinch.ui.components.RichText
import com.example.pinch.ui.components.SectionHeading
import com.example.pinch.ui.components.StepText
import com.example.pinch.ui.components.TimerDock
import com.example.pinch.ui.components.bottomFade
import com.example.pinch.ui.components.rememberNotificationPermissionRequest
import com.example.pinch.ui.share.ShareSheet
import com.example.pinch.ui.groups.AddToCookbooksSheet
import com.example.pinch.ui.groups.rememberGroupWork
import com.example.pinch.ui.groups.Feedback
import com.example.pinch.ui.components.SoftButton
import com.example.pinch.sync.SharedRecipe
import com.example.pinch.sync.ServerGroup
import com.example.pinch.ui.theme.Pinch
import com.example.pinch.ui.theme.base
import com.example.pinch.ui.theme.deep
import com.example.pinch.ui.theme.onBase
import com.example.pinch.ui.theme.tint
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(container: AppContainer, recipeId: String, onBack: () -> Unit, onCook: () -> Unit, onEdit: () -> Unit,
    previewRecipe: Recipe? = null, sharedItem: SharedRecipe? = null, sharedGroup: ServerGroup? = null,
    onOpenCopy: (String) -> Unit = {}, onOpenGroup: (String) -> Unit = {}) {
    val recipe by container.repository.observe(recipeId).collectAsState(initial = container.repository.recipe(recipeId))
    val timers by container.timers.timers.collectAsState()
    val current = previewRecipe ?: recipe
    if (current == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val session = container.cooking
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val heroHeight = 300.dp
    val scrolledPastHero by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > with(density) { (heroHeight - 90.dp).toPx() } }
    }
    val groups = com.example.pinch.ui.groups.cookbookList(container)
    val library by container.repository.recipes.collectAsState()
    val work = rememberGroupWork()
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(3000); notice = null } }
    var showCookbooks by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sharedItem) { if (sharedItem == null && container.server.state.value.connected) { work.run { container.server.loadGroups() } } }
    var menu by remember { mutableStateOf(false) }
    var showShare by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var keepAwake by rememberSaveable { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<ViewedPhoto?>(null) }
    val haptics = LocalHapticFeedback.current
    val askForNotifications = rememberNotificationPermissionRequest()
    val view = LocalView.current
    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize().background(Pinch.colors.canvas)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 150.dp)) {
            item {
                val scopes = LocalTransitionScopes.current
                Box(Modifier.fillMaxWidth().height(heroHeight)) {
                    val cover = Modifier.fillMaxSize()
                    val banner = current.bannerPhoto?.let(container.photos::file)
                    if (scopes != null) {
                        with(scopes.shared) {
                            CoverArt(current.palette, current.pattern, cover.sharedBounds(rememberSharedContentState("cover-${current.id}"), scopes.animated), cell = 28.dp, photo = banner)
                        }
                    } else {
                        CoverArt(current.palette, current.pattern, cover, cell = 28.dp, photo = banner)
                    }
                    EmojiSticker(current.emoji, 96.dp, Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 52.dp), tilt = -7f)
                }
            }
            item {
                Column(
                    Modifier
                        .offset(y = (-32).dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                        .background(Pinch.colors.canvas)
                        .padding(start = 20.dp, end = 20.dp, top = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(30.dp),
                ) {
                    TitleBlock(current)
                    if (sharedItem != null && sharedGroup != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Shared by ${sharedItem.sharedBy} · ${sharedGroup.name}", style = MaterialTheme.typography.titleSmall, color = Pinch.colors.jadeDeep)
                            val copyId = library.firstOrNull { it.origin?.shareId == sharedItem.id }?.id
                            if (copyId != null) SoftButton("Saved · Open my copy", { onOpenCopy(copyId) }, icon = Icons.Rounded.Check)
                            else SoftButton("Save to my recipes", { work.run { container.server.saveCopy(sharedItem, sharedGroup) } }, icon = Icons.Rounded.Add, enabled = !work.busy)
                            Feedback(work)
                        }
                    } else {
                        val sharedWith = groups.filter { current.id in it.mySharedRecipeIds }
                        if (sharedWith.isNotEmpty()) {
                            Column {
                                sharedWith.forEach { group -> TextButton(onClick = { onOpenGroup(group.id) }) { Icon(Icons.Rounded.CollectionsBookmark, null); Spacer(Modifier.width(8.dp)); Text("In ${group.name}") } }
                                TextButton(onClick = { showCookbooks = true }) { Text("Add to another cookbook") }
                            }
                        }
                        current.origin?.let { Text("From ${it.sharedBy} · ${it.groupName}", color = Pinch.colors.inkSoft) }
                        notice?.let { Text(it, color = Pinch.colors.jadeDeep) }
                    }
                    IngredientsCard(current, session)
                    StepsList(current, session) { seconds, label ->
                        askForNotifications()
                        container.timers.start(seconds, label, current)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    if (current.notes.isNotBlank()) NotesCard(current.notes)
                    PhotoStrip(container.photos, current.recipePhotos, "Recipe photos", isRecipePhoto = true) {
                        viewer = ViewedPhoto(it, isRecipePhoto = true)
                    }
                    PhotoStrip(container.photos, current.photos, if (current.photos.size == 1) "Original page" else "Original pages") {
                        viewer = ViewedPhoto(it)
                    }
                    Footer(current)
                }
            }
        }

        // Our own bar background once the cover has scrolled away.
        AnimatedVisibility(scrolledPastHero, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxWidth().background(Pinch.colors.canvas)) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Spacer(Modifier.height(64.dp))
                HorizontalDivider(color = Pinch.colors.line)
            }
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            AnimatedVisibility(scrolledPastHero, Modifier.weight(1f), enter = fadeIn(), exit = fadeOut()) {
                Text(
                    current.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = Pinch.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            if (!scrolledPastHero) Spacer(Modifier.weight(1f))
            if (sharedItem == null) { BarButton(
                if (current.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (current.isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (current.isFavorite) Pinch.colors.cherry else Pinch.colors.ink,
            ) {
                container.repository.toggleFavorite(current.id)
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            Spacer(Modifier.width(8.dp))
            BarButton(Icons.Rounded.CollectionsBookmark, "Add to cookbook") { showCookbooks = true }
            Spacer(Modifier.width(8.dp))
            BarButton(Icons.Rounded.Share, "Share recipe") { showShare = true }
            }
            Spacer(Modifier.width(8.dp))
            Box {
                BarButton(Icons.Rounded.MoreVert, "More") { menu = true }
                DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Pinch.colors.surface) {
                    if (sharedItem == null) DropdownMenuItem(text = { Text("Edit recipe") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(
                        text = { Text("Keep screen on") },
                        leadingIcon = { Icon(Icons.Rounded.WbSunny, null) },
                        trailingIcon = { if (keepAwake) Icon(Icons.Rounded.Check, null, tint = Pinch.colors.jadeDeep) },
                        onClick = { keepAwake = !keepAwake; menu = false },
                    )
                    if (sharedItem == null) DropdownMenuItem(text = { Text("Mark as cooked today") }, leadingIcon = { Icon(Icons.Rounded.Verified, null) }, onClick = {
                        menu = false
                        container.repository.markCooked(current.id)
                    })
                    if (session.hasProgress(current.id)) {
                        DropdownMenuItem(text = { Text("Clear checkmarks") }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) }, onClick = {
                            menu = false
                            session.reset(current.id)
                        })
                    }
                    HorizontalDivider(color = Pinch.colors.line)
                    if (sharedItem == null) DropdownMenuItem(
                        text = { Text("Delete recipe", color = Pinch.colors.cherry) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Pinch.colors.cherry) },
                        onClick = { menu = false; confirmDelete = true },
                    )
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .bottomFade(Pinch.colors.canvas)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TimerDock(timers, container.timers, recipeId = current.id)
            if (current.stepLines.isNotEmpty()) {
                PrimaryButton(
                    if (session.doneCount(current.id) > 0) "Keep cooking" else "Start cooking",
                    onClick = onCook,
                    icon = Icons.Rounded.PlayArrow,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showShare) {
        ShareSheet(current, session.scale(current.id), container.photos) { showShare = false }
    }
    if (showCookbooks) {
        AddToCookbooksSheet(container, current, onDismiss = { showCookbooks = false }, onAdded = { notice = it })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Pinch.colors.surface,
            title = { Text("Delete ${current.displayTitle}?") },
            text = { Text(if (container.server.state.value.connected) "This removes it from your devices and your server backup. This also removes it from your cookbooks." else "This removes it from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onBack()
                    container.repository.delete(current.id)
                }) { Text("Delete recipe", color = Pinch.colors.cherry) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
    }
    viewer?.let { photo ->
        val names = if (photo.isRecipePhoto) current.recipePhotos else current.photos
        PageViewerDialog(names.map(container.photos::file), photo.index, photo.isRecipePhoto) { viewer = null }
    }
}

private data class ViewedPhoto(val index: Int, val isRecipePhoto: Boolean = false)

@Composable
private fun PhotoStrip(photos: PhotoStore, names: List<String>, title: String, isRecipePhoto: Boolean = false, onOpen: (Int) -> Unit) {
    if (names.isEmpty()) return
    val kind = if (isRecipePhoto) "photo" else "page"
    val shape = RoundedCornerShape(14.dp)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading(title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(names) { index, name ->
                AsyncImage(
                    model = photos.file(name),
                    contentDescription = "Open $kind ${index + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(if (isRecipePhoto) 140.dp else 110.dp, if (isRecipePhoto) 104.dp else 146.dp)
                        .clip(shape)
                        .border(1.dp, Pinch.colors.line, shape)
                        .clickable(role = Role.Button) { onOpen(index) },
                )
            }
        }
    }
}

@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    BarButton(icon, description, Pinch.colors.ink, onClick)
}

@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Pinch.colors.surface.copy(alpha = 0.92f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TitleBlock(recipe: Recipe) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(recipe.displayTitle, style = MaterialTheme.typography.headlineLarge, color = Pinch.colors.ink, modifier = Modifier.semantics { heading() })
        if (recipe.summary.isNotBlank()) {
            Text(recipe.summary, style = MaterialTheme.typography.bodyLarge, color = Pinch.colors.inkSoft)
        }
        val pills = buildList {
            if (recipe.prepTime.isNotBlank()) add(Icons.Rounded.Schedule to "Prep ${recipe.prepTime}")
            if (recipe.cookTime.isNotBlank()) add(Icons.Rounded.LocalFireDepartment to "Cook ${recipe.cookTime}")
            if (recipe.totalTime.isNotBlank()) add(Icons.Rounded.HourglassEmpty to "Total ${recipe.totalTime}")
            recipe.servingsLabel?.let { add(Icons.Rounded.Group to it) }
        }
        val link = recipe.sourceUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (pills.isNotEmpty() || recipe.source.isNotBlank() || link != null) {
            val uriHandler = LocalUriHandler.current
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pills.forEach { (icon, text) -> MetaPill(icon, text) }
                if (link != null) {
                    MetaPill(
                        Icons.Rounded.Language,
                        recipe.source.ifBlank { WebRecipeImporter.displayHost(link) },
                        tint = Pinch.colors.jadeDeep,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = "Open the recipe's web page") { runCatching { uriHandler.openUri(link) } },
                    )
                } else if (recipe.source.isNotBlank()) {
                    MetaPill(Icons.AutoMirrored.Rounded.MenuBook, recipe.source, tint = Pinch.colors.inkSoft)
                }
            }
        }
        if (recipe.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                recipe.tags.forEach { tag ->
                    Text(
                        tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = recipe.palette.deep,
                        modifier = Modifier.clip(CircleShape).background(recipe.palette.tint).padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun IngredientsCard(recipe: Recipe, session: CookingSession) {
    val scale = session.scale(recipe.id)
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeading("Ingredients") {
            ServingsScaler(recipe.servingsCount, recipe.servingsNoun, scale) { session.setScale(recipe.id, it) }
        }
        if (recipe.ingredients.isEmpty()) {
            Text("No ingredients yet. Tap Edit to add some.", style = MaterialTheme.typography.bodyLarge, color = Pinch.colors.inkSoft)
        } else {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Pinch.colors.surface).padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                recipe.ingredients.forEachIndexed { index, item ->
                    if (item.isHeader) {
                        Text(
                            item.text,
                            style = MaterialTheme.typography.titleMedium,
                            color = recipe.palette.deep,
                            modifier = Modifier.padding(top = if (index == 0) 4.dp else 18.dp, bottom = 4.dp).semantics { heading() },
                        )
                    } else {
                        IngredientRow(
                            text = IngredientParser.scale(item.text, scale),
                            accent = recipe.palette.deep,
                            checked = session.isChecked(item.id, recipe.id),
                        ) {
                            session.toggleChecked(item.id, recipe.id)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        if (index < recipe.ingredients.size - 1 && !recipe.ingredients[index + 1].isHeader) {
                            HorizontalDivider(color = Pinch.colors.line.copy(alpha = 0.7f), modifier = Modifier.padding(start = 38.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IngredientRow(text: String, accent: Color, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics { stateDescription = if (checked) "Checked" else "Not checked" }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            null,
            tint = if (checked) Pinch.colors.jadeDeep else Pinch.colors.line,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            RichText.ingredient(text, accent, dimmed = checked),
            style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (checked) TextDecoration.LineThrough else null),
            color = if (checked) Pinch.colors.inkSoft else Pinch.colors.ink,
            modifier = Modifier.weight(1f).padding(top = 1.dp),
        )
    }
}

/** "4 servings" with − and +, or "Double" when the recipe doesn't say how many it serves. */
@Composable
fun ServingsScaler(baseServings: Double?, noun: String?, scale: Double, onChange: (Double) -> Unit) {
    val multipliers = listOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0)
    val usesServings = baseServings != null && baseServings in 1.0..16.0
    val servings = Math.round((baseServings ?: 1.0) * scale).toInt()
    val label = if (usesServings) {
        if (noun != null) (if (servings == 1) "1 ${TextKit.singular(noun)}" else "$servings $noun")
        else if (servings == 1) "1 serving" else "$servings servings"
    } else {
        when (scale) {
            0.5 -> "Half batch"
            1.0 -> "Full batch"
            1.5 -> "1½ batches"
            2.0 -> "Double"
            3.0 -> "Triple"
            else -> "${IngredientParser.format(scale, false)}× batch"
        }
    }
    val canDecrease = if (usesServings) servings > 1 else scale > multipliers.first()
    val canIncrease = if (usesServings) servings < 99 else scale < multipliers.last()
    fun change(direction: Int) {
        if (usesServings) {
            onChange((servings + direction).coerceAtLeast(1) / baseServings!!)
        } else {
            val index = multipliers.indexOfFirst { it >= scale - 0.001 }.coerceAtLeast(1)
            onChange(multipliers[(index + direction).coerceIn(0, multipliers.size - 1)])
        }
    }
    val labelColor by animateColorAsState(if (scale == 1.0) Pinch.colors.ink else Pinch.colors.jadeDeep, label = "scale")
    Row(Modifier.clip(CircleShape).background(Pinch.colors.sunken), verticalAlignment = Alignment.CenterVertically) {
        ScalerButton(Icons.Rounded.Remove, if (usesServings) "Fewer servings" else "Smaller batch", canDecrease) { change(-1) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor, maxLines = 1, modifier = Modifier.widthIn(min = 84.dp).padding(horizontal = 2.dp))
        ScalerButton(Icons.Rounded.Add, if (usesServings) "More servings" else "Bigger batch", canIncrease) { change(1) }
    }
}

@Composable
private fun ScalerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).alpha(if (enabled) 1f else 0.35f)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Pinch.colors.ink, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun StepsList(recipe: Recipe, session: CookingSession, onTimer: (Int, String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val done = session.doneCount(recipe.id)
        SectionHeading("Steps") {
            if (done > 0) Text("$done of ${recipe.stepLines.size} done", style = MaterialTheme.typography.labelMedium, color = Pinch.colors.jadeDeep)
        }
        if (recipe.steps.isEmpty()) {
            Text("No steps yet. Tap Edit to add some.", style = MaterialTheme.typography.bodyLarge, color = Pinch.colors.inkSoft)
        }
        var number = 0
        recipe.steps.forEach { item ->
            if (item.isHeader) {
                Text(item.text, style = MaterialTheme.typography.titleMedium, color = recipe.palette.deep, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
            } else {
                number++
                val n = number
                StepCard(
                    number = n,
                    text = item.text,
                    palette = recipe.palette,
                    isDone = session.isDone(item.id, recipe.id),
                    onToggle = {
                        session.toggleDone(item.id, recipe.id)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onTimer = { seconds -> onTimer(seconds, StepText.timerLabel(item.text, n)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StepCard(number: Int, text: String, palette: CoverPalette, isDone: Boolean, onToggle: () -> Unit, onTimer: (Int) -> Unit) {
    val durations = remember(text) { DurationParser.matches(text) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Pinch.colors.surface)
            .clickable(onClick = onToggle)
            .semantics { stateDescription = if (isDone) "Done" else "Not done" }
            .padding(16.dp)
            .alpha(if (isDone) 0.75f else 1f),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(if (isDone) Pinch.colors.sunken else palette.base), contentAlignment = Alignment.Center) {
            if (isDone) Icon(Icons.Rounded.Check, "Step $number, done", tint = Pinch.colors.jadeDeep, modifier = Modifier.size(20.dp))
            else Text("$number", style = MaterialTheme.typography.titleMedium, color = palette.onBase)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(RichText.step(text), style = MaterialTheme.typography.bodyLarge, color = if (isDone) Pinch.colors.inkSoft else Pinch.colors.ink)
            if (durations.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    durations.forEach { match ->
                        Row(
                            Modifier
                                .heightIn(min = 38.dp)
                                .clip(CircleShape)
                                .background(Pinch.colors.butter)
                                .clickable(role = Role.Button) { onTimer(match.seconds) }
                                .semantics { contentDescription = "Start a ${match.label} timer" }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Timer, null, tint = Color(0xFF17332C), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("${match.label} timer", style = MaterialTheme.typography.labelMedium, color = Color(0xFF17332C))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesCard(notes: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Pinch.colors.note).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.EditNote, null, tint = Pinch.colors.noteInk)
            Spacer(Modifier.width(8.dp))
            Text("Notes", style = MaterialTheme.typography.titleMedium, color = Pinch.colors.noteInk)
        }
        Text(notes, style = MaterialTheme.typography.bodyLarge, color = Pinch.colors.noteInk)
    }
}

@Composable
private fun Footer(recipe: Recipe) {
    val added = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).format(recipe.createdAt.atZone(ZoneId.systemDefault()))
    Column {
        Text("Added $added", style = MaterialTheme.typography.bodySmall, color = Pinch.colors.inkSoft)
        recipe.lastCookedAt?.let {
            Text(
                "Last cooked ${DateUtils.getRelativeTimeSpanString(it.toEpochMilli(), System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)}",
                style = MaterialTheme.typography.bodySmall,
                color = Pinch.colors.inkSoft,
            )
        }
    }
}
