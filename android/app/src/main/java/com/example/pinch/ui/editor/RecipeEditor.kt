package com.example.pinch.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.pinch.AppContainer
import com.example.pinch.data.PinchJson
import com.example.pinch.model.CoverPalette
import com.example.pinch.model.CoverPattern
import com.example.pinch.model.Recipe
import com.example.pinch.model.RecipeItem
import com.example.pinch.parsing.DurationParser
import com.example.pinch.parsing.RecipeParser
import com.example.pinch.parsing.TextKit
import com.example.pinch.scan.RecipeImporter
import com.example.pinch.ui.components.CoverArt
import com.example.pinch.ui.components.EmojiSticker
import com.example.pinch.ui.components.PageViewerDialog
import com.example.pinch.ui.library.rememberCaptureLaunchers
import com.example.pinch.ui.theme.Pinch
import com.example.pinch.ui.theme.base
import com.example.pinch.ui.theme.deep
import com.example.pinch.ui.theme.tint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

val RecipeSaver = Saver<Recipe, String>(save = { PinchJson.encode(it) }, restore = { PinchJson.decode(it) })

/** Edit a saved recipe. */
@Composable
fun EditScreen(container: AppContainer, recipeId: String, onDone: () -> Unit) {
    val recipe = remember(recipeId) { container.repository.recipe(recipeId) }
    if (recipe == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    RecipeEditor(
        container = container,
        initial = recipe,
        isNew = false,
        onSave = {
            container.repository.save(it)
            onDone()
        },
        onCancel = { onDone() },
    )
}

private enum class Kind { INGREDIENT, STEP }

/** Review a freshly scanned recipe, or edit a saved one. Mirrors the iOS editor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipeEditor(
    container: AppContainer,
    initial: Recipe,
    isNew: Boolean,
    onSave: (Recipe) -> Unit,
    onCancel: (Recipe) -> Unit,
    readBy: RecipeImporter.ReadBy = RecipeImporter.ReadBy.Typing,
) {
    var draft by rememberSaveable(stateSaver = RecipeSaver) { mutableStateOf(initial) }
    var addedPhotos by rememberSaveable { mutableStateOf(listOf<String>()) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<RecipeImporter.Stage?>(null) }
    var addJob by remember { mutableStateOf<Job?>(null) }
    var addError by remember { mutableStateOf<String?>(null) }
    var focusId by remember { mutableStateOf<String?>(null) }
    var viewer by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val recipes by container.repository.recipes.collectAsState()

    val launchers = rememberCaptureLaunchers { uris ->
        addJob = scope.launch {
            val before = draft.photos.toSet()
            adding = RecipeImporter.Stage.Reading(1, uris.size)
            try {
                val updated = RecipeImporter.appendPages(context, uris, draft, container.photos, container.ai.client()) { stage -> adding = stage }
                addedPhotos = addedPhotos + updated.photos.filter { it !in before }
                draft = updated
            } catch (e: CancellationException) {
                // Called off from the progress dialog; the new pages were already cleaned up.
                throw e
            } catch (e: Exception) {
                addError = e.message ?: "Couldn't read that page."
            } finally {
                adding = null
                addJob = null
            }
        }
    }

    fun cancel() {
        addedPhotos.forEach(container.photos::delete)
        onCancel(if (isNew) draft else draft.copy(photos = emptyList()))
    }

    fun attemptCancel() {
        val pristine = isNew && draft.title.isBlank() && draft.ingredients.isEmpty() && draft.steps.isEmpty() && draft.photos.isEmpty()
        if (pristine || (!isNew && draft == initial)) cancel() else confirmDiscard = true
    }

    fun save() {
        fun clean(items: List<RecipeItem>) = items.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
        val cleaned = draft.copy(
            title = draft.title.trim(),
            summary = draft.summary.trim(),
            notes = draft.notes.trim(),
            servings = draft.servings.trim(),
            sourceUrl = draft.sourceUrl.trim(),
            prepTime = DurationParser.normalized(draft.prepTime),
            cookTime = DurationParser.normalized(draft.cookTime),
            totalTime = DurationParser.normalized(draft.totalTime),
            ingredients = clean(draft.ingredients),
            steps = clean(draft.steps),
            emoji = draft.emoji.ifBlank { "🍽️" },
        )
        // Pages removed while editing (including ones added this session) are deleted
        // from disk once the edit is saved.
        ((initial.photos + addedPhotos).toSet() - cleaned.photos.toSet()).forEach(container.photos::delete)
        onSave(cleaned)
    }

    fun updateItems(kind: Kind, transform: (List<RecipeItem>) -> List<RecipeItem>) {
        draft = if (kind == Kind.INGREDIENT) draft.copy(ingredients = transform(draft.ingredients)) else draft.copy(steps = transform(draft.steps))
    }

    BackHandler { attemptCancel() }

    Column(Modifier.fillMaxSize().background(Pinch.colors.canvas)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = ::attemptCancel) { Text("Cancel", style = MaterialTheme.typography.labelLarge, color = Pinch.colors.jadeDeep) }
            Text(
                if (isNew) "Check it over" else "Edit recipe",
                style = MaterialTheme.typography.titleMedium,
                color = Pinch.colors.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = ::save) { Text(if (isNew) "Save recipe" else "Save", style = MaterialTheme.typography.labelLarge, color = Pinch.colors.jadeDeep) }
        }

        LazyColumn(
            Modifier.weight(1f).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (isNew) item { IntroBanner(draft, readBy) }

            item {
                SectionLabel(if (draft.photos.isEmpty()) "Pages" else "Pages (${draft.photos.size})")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(draft.photos, key = { _, name -> name }) { index, name ->
                        PageThumb(
                            container = container,
                            name = name,
                            number = index + 1,
                            canMoveEarlier = index > 0,
                            canMoveLater = index < draft.photos.size - 1,
                            onOpen = { viewer = index },
                            onMove = { delta ->
                                val list = draft.photos.toMutableList()
                                val target = index + delta
                                list[index] = list[target].also { list[target] = list[index] }
                                draft = draft.copy(photos = list)
                            },
                            onRemove = { draft = draft.copy(photos = draft.photos - name) },
                        )
                    }
                    item { AddPageTile(empty = draft.photos.isEmpty(), onScan = launchers.scan, onPhotos = launchers.pickPhotos) }
                }
                Text(
                    if (draft.photos.isEmpty()) "Recipe split across pages? Add each page and Pinch joins them up."
                    else "Touch and hold a page to reorder or remove it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Pinch.colors.inkSoft,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            }

            item {
                SectionLabel("Cover")
                EditorCard {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(84.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable(role = Role.Button) { showEmoji = true }
                                .semantics { contentDescription = "Change emoji, currently ${draft.emoji}" },
                            contentAlignment = Alignment.Center,
                        ) {
                            CoverArt(draft.palette, draft.pattern, Modifier.fillMaxSize(), cell = 14.dp)
                            EmojiSticker(draft.emoji, 50.dp, tilt = -6f)
                            Box(
                                Modifier.align(Alignment.BottomEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Pinch.colors.jadeDeep),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Edit, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(CoverPalette.entries.size) { i ->
                                    val palette = CoverPalette.entries[i]
                                    val selected = draft.palette == palette
                                    Box(
                                        Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(palette.base)
                                            .border(if (selected) 3.dp else 0.dp, Pinch.colors.ink, CircleShape)
                                            .clickable(role = Role.RadioButton) { draft = draft.copy(palette = palette) }
                                            .semantics { contentDescription = palette.displayName + if (selected) ", selected" else "" },
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CoverPattern.entries.forEach { pattern ->
                                    val selected = draft.pattern == pattern
                                    Box(
                                        Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .border(if (selected) 3.dp else 0.dp, Pinch.colors.ink, RoundedCornerShape(9.dp))
                                            .clickable(role = Role.RadioButton) { draft = draft.copy(pattern = pattern) }
                                            .semantics { contentDescription = pattern.displayName + if (selected) ", selected" else "" },
                                    ) { CoverArt(draft.palette, pattern, Modifier.fillMaxSize(), cell = 9.dp) }
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionLabel("Name")
                EditorCard {
                    PlainField(
                        value = draft.title,
                        onValueChange = { draft = draft.copy(title = it.replace("\n", " ")) },
                        placeholder = "Recipe name",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(16.dp),
                    )
                    HorizontalDivider(color = Pinch.colors.line, modifier = Modifier.padding(start = 16.dp))
                    PlainField(
                        value = draft.summary,
                        onValueChange = { draft = draft.copy(summary = it) },
                        placeholder = "A line or two about it (optional)",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            item {
                SectionLabel("Details")
                EditorCard {
                    DetailRow("Servings", Icons.Rounded.Group, draft.servings, "e.g. 4") { draft = draft.copy(servings = it) }
                    DetailRow("Prep", Icons.Rounded.Schedule, draft.prepTime, "e.g. 15 min") { draft = draft.copy(prepTime = it) }
                    DetailRow("Cook", Icons.Rounded.LocalFireDepartment, draft.cookTime, "e.g. 1 hr") { draft = draft.copy(cookTime = it) }
                    DetailRow("Total", Icons.Rounded.HourglassEmpty, draft.totalTime, "e.g. 1 hr 15 min") { draft = draft.copy(totalTime = it) }
                    val showLink = draft.sourceUrl.isNotEmpty() || initial.sourceUrl.isNotEmpty()
                    DetailRow("From", Icons.AutoMirrored.Rounded.MenuBook, draft.source, "e.g. Grandma's card", last = !showLink) { draft = draft.copy(source = it) }
                    if (showLink) {
                        DetailRow("Web page", Icons.Rounded.Language, draft.sourceUrl, "https://…", last = true) { draft = draft.copy(sourceUrl = it) }
                    }
                }
            }

            item {
                SectionLabel("Tags")
                TagsEditor(
                    draft = draft,
                    allTags = container.repository.allTags(recipes),
                    onChange = { draft = draft.copy(tags = it) },
                )
            }

            item {
                ItemsEditor(
                    title = "Ingredients",
                    kind = Kind.INGREDIENT,
                    items = draft.ingredients,
                    focusId = focusId,
                    onFocused = { focusId = null },
                    update = { transform -> updateItems(Kind.INGREDIENT, transform) },
                    moveToOther = { item ->
                        updateItems(Kind.INGREDIENT) { list -> list.filter { it.id != item.id } }
                        updateItems(Kind.STEP) { it + item.copy(isHeader = false) }
                    },
                    requestFocus = { focusId = it },
                )
            }

            item {
                ItemsEditor(
                    title = "Steps",
                    kind = Kind.STEP,
                    items = draft.steps,
                    focusId = focusId,
                    onFocused = { focusId = null },
                    update = { transform -> updateItems(Kind.STEP, transform) },
                    moveToOther = { item ->
                        updateItems(Kind.STEP) { list -> list.filter { it.id != item.id } }
                        updateItems(Kind.INGREDIENT) { it + item.copy(isHeader = false) }
                    },
                    requestFocus = { focusId = it },
                )
            }

            item {
                SectionLabel("Notes")
                EditorCard {
                    PlainField(
                        value = draft.notes,
                        onValueChange = { draft = draft.copy(notes = it) },
                        placeholder = "Tips, swaps, who loved it",
                        modifier = Modifier.padding(16.dp).heightIn(min = 80.dp),
                    )
                }
            }

            if (draft.rawText.isNotEmpty()) {
                item { ScannedText(draft.rawText, fromWebsite = draft.photos.isEmpty() && draft.sourceUrl.isNotEmpty()) }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = Pinch.colors.surface,
            title = { Text(if (isNew) "Discard this recipe?" else "Discard your changes?") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; cancel() }) {
                    Text(if (isNew) "Discard recipe" else "Discard changes", color = Pinch.colors.cherry)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    if (showEmoji) {
        EmojiPickerDialog(current = draft.emoji, onDismiss = { showEmoji = false }, onPick = {
            draft = draft.copy(emoji = it)
            showEmoji = false
        })
    }
    adding?.let { stage ->
        Dialog(onDismissRequest = {}) {
            Column(
                Modifier.clip(RoundedCornerShape(24.dp)).background(Pinch.colors.surface).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Pinch.colors.jadeDeep)
                Spacer(Modifier.height(14.dp))
                Text(
                    when (stage) {
                        is RecipeImporter.Stage.Reading -> if (stage.total > 1) "Reading page ${stage.page} of ${stage.total}" else "Reading the new page"
                        is RecipeImporter.Stage.AiReading -> "AI is reading the new ${if ((stage.source as? RecipeImporter.AiSource.Pages)?.count == 1) "page" else "pages"}"
                        else -> "Reading the new page"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Pinch.colors.ink,
                )
                if (stage is RecipeImporter.Stage.AiReading) {
                    TextButton(onClick = { addJob?.cancel() }) { Text("Cancel", color = Pinch.colors.jadeDeep) }
                }
            }
        }
    }
    addError?.let { message ->
        AlertDialog(
            onDismissRequest = { addError = null },
            containerColor = Pinch.colors.surface,
            title = { Text("Couldn't add that page") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { addError = null }) { Text("OK") } },
        )
    }
    viewer?.let { start ->
        PageViewerDialog(draft.photos.map { container.photos.file(it) }, start, onDismiss = { viewer = null })
    }
}

@Composable
private fun IntroBanner(draft: Recipe, readBy: RecipeImporter.ReadBy) {
    val i = draft.ingredientLines.size
    val s = draft.stepLines.size
    val detail = when (readBy) {
        RecipeImporter.ReadBy.Ai -> "Read with AI. Give the amounts a quick check, and tap anything to fix it."
        RecipeImporter.ReadBy.Website -> "Copied from the website's recipe card. Tap anything to fix it."
        RecipeImporter.ReadBy.Phone -> "Read on this phone. Tap anything to fix it. For much better reading, add an OpenRouter key in Settings."
        is RecipeImporter.ReadBy.Fallback -> "Pinch read this on the phone instead. Tap anything to fix it."
        RecipeImporter.ReadBy.Typing -> "Tap anything to fix it. Use a line's menu to move it or turn it into a heading."
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(draft.palette.tint).padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(draft.emoji, fontSize = 30.sp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                if (i == 0 && s == 0) "Start with the recipe's name"
                else "Found $i ingredient${if (i == 1) "" else "s"} and $s step${if (s == 1) "" else "s"}",
                style = MaterialTheme.typography.titleMedium,
                color = Pinch.colors.ink,
            )
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Pinch.colors.inkSoft)
            if (readBy is RecipeImporter.ReadBy.Fallback) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Warning, null, tint = Pinch.colors.cherry, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "AI reading didn't work: ${readBy.reason}.",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Pinch.colors.cherry,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Pinch.colors.inkSoft, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun EditorCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Pinch.colors.surface)) { content() }
}

@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    singleLine: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
    fieldModifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: () -> Unit = {},
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = style.copy(color = Pinch.colors.ink, textAlign = textAlign),
        cursorBrush = SolidColor(Pinch.colors.jadeDeep),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        modifier = modifier.fillMaxWidth().then(fieldModifier),
        decorationBox = { inner ->
            Box(contentAlignment = if (textAlign == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(textAlign = textAlign), color = Pinch.colors.inkSoft.copy(alpha = 0.7f))
                inner()
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, icon: ImageVector, value: String, placeholder: String, last: Boolean = false, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Pinch.colors.ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = Pinch.colors.ink, modifier = Modifier.width(88.dp))
        PlainField(value, onChange, placeholder, singleLine = true, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
    if (!last) HorizontalDivider(color = Pinch.colors.line, modifier = Modifier.padding(start = 48.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsEditor(draft: Recipe, allTags: List<String>, onChange: (List<String>) -> Unit) {
    var newTag by remember { mutableStateOf("") }
    val common = listOf("Breakfast", "Lunch", "Dinner", "Dessert", "Baking", "Snack", "Drinks", "Vegetarian", "Holidays", "Family")
    val suggestions = (allTags + common).distinct().filter { it !in draft.tags }.take(5)
    fun add() {
        val tag = TextKit.capitalizeFirstLetter(newTag.trim())
        if (tag.isNotEmpty() && draft.tags.none { it.equals(tag, ignoreCase = true) }) onChange(draft.tags + tag)
        newTag = ""
    }
    EditorCard {
        FlowRow(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            draft.tags.forEach { tag ->
                Row(
                    Modifier.clip(CircleShape).background(draft.palette.tint).clickable { onChange(draft.tags - tag) }
                        .semantics { contentDescription = "Remove tag $tag" }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tag, style = MaterialTheme.typography.labelMedium, color = draft.palette.deep)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.Close, null, tint = draft.palette.deep, modifier = Modifier.size(14.dp))
                }
            }
            suggestions.forEach { tag ->
                Row(
                    Modifier.clip(CircleShape).border(1.5.dp, Pinch.colors.line, CircleShape).clickable { onChange(draft.tags + tag) }
                        .semantics { contentDescription = "Add tag $tag" }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, null, tint = Pinch.colors.inkSoft, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tag, style = MaterialTheme.typography.labelMedium, color = Pinch.colors.inkSoft)
                }
            }
        }
        HorizontalDivider(color = Pinch.colors.line, modifier = Modifier.padding(start = 16.dp))
        PlainField(
            value = newTag,
            onValueChange = { newTag = it.replace("\n", "") },
            placeholder = "Add a tag, like Dinner or Holidays",
            singleLine = true,
            imeAction = ImeAction.Done,
            onImeAction = ::add,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ItemsEditor(
    title: String,
    kind: Kind,
    items: List<RecipeItem>,
    focusId: String?,
    onFocused: () -> Unit,
    update: ((List<RecipeItem>) -> List<RecipeItem>) -> Unit,
    moveToOther: (RecipeItem) -> Unit,
    requestFocus: (String) -> Unit,
) {
    SectionLabel(title)
    EditorCard {
        var number = 0
        items.forEachIndexed { index, item ->
            if (!item.isHeader) number++
            ItemRow(
                item = item,
                number = if (kind == Kind.STEP && !item.isHeader) number else null,
                placeholder = if (kind == Kind.INGREDIENT) "e.g. 2 cups flour" else "Describe this step",
                focus = focusId == item.id,
                onFocused = onFocused,
                onChange = { text -> update { list -> list.map { if (it.id == item.id) it.copy(text = text) else it } } },
                onSplit = { head, tail ->
                    val added = RecipeItem(text = tail)
                    update { list ->
                        val i = list.indexOfFirst { it.id == item.id }
                        list.toMutableList().apply {
                            set(i, item.copy(text = head))
                            add(i + 1, added)
                        }
                    }
                    requestFocus(added.id)
                },
                menu = { dismiss ->
                    DropdownMenuItem(
                        text = { Text(if (item.isHeader) "Make it a regular line" else "Make it a heading") },
                        leadingIcon = { Icon(Icons.Rounded.TextFields, null) },
                        onClick = { dismiss(); update { list -> list.map { if (it.id == item.id) it.copy(isHeader = !it.isHeader) else it } } },
                    )
                    if (index > 0) {
                        DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) }, onClick = {
                            dismiss(); update { list -> list.toMutableList().apply { add(index - 1, removeAt(index)) } }
                        })
                    }
                    if (index < items.size - 1) {
                        DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) }, onClick = {
                            dismiss(); update { list -> list.toMutableList().apply { add(index + 1, removeAt(index)) } }
                        })
                    }
                    DropdownMenuItem(
                        text = { Text(if (kind == Kind.INGREDIENT) "Move to steps" else "Move to ingredients") },
                        leadingIcon = { Icon(Icons.Rounded.SwapVert, null) },
                        onClick = { dismiss(); moveToOther(item) },
                    )
                    if (kind == Kind.STEP && index > 0 && !items[index - 1].isHeader) {
                        DropdownMenuItem(text = { Text("Join with step above") }, leadingIcon = { Icon(Icons.Rounded.VerticalAlignTop, null) }, onClick = {
                            dismiss()
                            update { list ->
                                list.toMutableList().apply {
                                    set(index - 1, list[index - 1].copy(text = RecipeParser.join(list[index - 1].text, item.text)))
                                    removeAt(index)
                                }
                            }
                        })
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = Pinch.colors.cherry) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Pinch.colors.cherry) },
                        onClick = { dismiss(); update { list -> list.filter { it.id != item.id } } },
                    )
                },
            )
            HorizontalDivider(color = Pinch.colors.line, modifier = Modifier.padding(start = 48.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val added = RecipeItem(text = "")
                update { it + added }
                requestFocus(added.id)
            }) {
                Icon(Icons.Rounded.AddCircle, null, tint = Pinch.colors.jadeDeep)
                Spacer(Modifier.width(8.dp))
                Text(if (kind == Kind.INGREDIENT) "Add ingredient" else "Add step", style = MaterialTheme.typography.labelLarge, color = Pinch.colors.jadeDeep)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val added = RecipeItem(text = "", isHeader = true)
                update { it + added }
                requestFocus(added.id)
            }) {
                Icon(Icons.Rounded.TextFields, null, tint = Pinch.colors.inkSoft, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add heading", style = MaterialTheme.typography.labelMedium, color = Pinch.colors.inkSoft)
            }
        }
    }
}

/** One editable ingredient or step. Pressing Enter starts a new line below. */
@Composable
private fun ItemRow(
    item: RecipeItem,
    number: Int?,
    placeholder: String,
    focus: Boolean,
    onFocused: () -> Unit,
    onChange: (String) -> Unit,
    onSplit: (String, String) -> Unit,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    val requester = remember { FocusRequester() }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(focus) {
        if (focus) {
            requester.requestFocus()
            onFocused()
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            when {
                item.isHeader -> Icon(Icons.Rounded.TextFields, null, tint = Pinch.colors.jadeDeep, modifier = Modifier.size(16.dp))
                number != null -> Text("$number", style = MaterialTheme.typography.labelMedium, color = Pinch.colors.jadeDeep)
                else -> Box(Modifier.size(6.dp).clip(CircleShape).background(Pinch.colors.jadeDeep))
            }
        }
        Spacer(Modifier.width(10.dp))
        PlainField(
            value = item.text,
            onValueChange = { value ->
                val newline = value.indexOf('\n')
                if (newline >= 0) onSplit(value.substring(0, newline), value.substring(newline + 1).trim('\n')) else onChange(value)
            },
            placeholder = if (item.isHeader) "Heading, like For the sauce" else placeholder,
            style = if (item.isHeader) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            fieldModifier = Modifier.focusRequester(requester).padding(vertical = 6.dp),
        )
        Box {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable { menuOpen = true }.semantics { contentDescription = "More for this line" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.MoreVert, null, tint = Pinch.colors.inkSoft) }
            DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Pinch.colors.surface) {
                menu { menuOpen = false }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageThumb(
    container: AppContainer,
    name: String,
    number: Int,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(84.dp, 112.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Pinch.colors.sunken)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .semantics { contentDescription = "Page $number" },
        ) {
            AsyncImage(model = container.photos.file(name), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.BottomStart).padding(6.dp).size(24.dp).clip(CircleShape).background(Pinch.colors.jade),
                contentAlignment = Alignment.Center,
            ) { Text("$number", style = MaterialTheme.typography.labelSmall, color = Pinch.colors.onJade) }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Pinch.colors.surface) {
            if (canMoveEarlier) DropdownMenuItem(text = { Text("Move earlier") }, onClick = { menu = false; onMove(-1) })
            if (canMoveLater) DropdownMenuItem(text = { Text("Move later") }, onClick = { menu = false; onMove(1) })
            DropdownMenuItem(text = { Text("Remove page", color = Pinch.colors.cherry) }, onClick = { menu = false; onRemove() })
        }
    }
}

@Composable
private fun AddPageTile(empty: Boolean, onScan: () -> Unit, onPhotos: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .size(84.dp, 112.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(2.dp, Pinch.colors.jadeDeep.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .clickable(role = Role.Button) { menu = true },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.Add, null, tint = Pinch.colors.jadeDeep, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(6.dp))
            Text(if (empty) "Add a page" else "Add page", style = MaterialTheme.typography.labelSmall, color = Pinch.colors.jadeDeep)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Pinch.colors.surface) {
            DropdownMenuItem(text = { Text("Scan pages") }, leadingIcon = { Icon(Icons.Rounded.CameraAlt, null) }, onClick = { menu = false; onScan() })
            DropdownMenuItem(text = { Text("Choose photos") }, leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) }, onClick = { menu = false; onPhotos() })
        }
    }
}

@Composable
private fun ScannedText(raw: String, fromWebsite: Boolean) {
    var open by remember { mutableStateOf(false) }
    EditorCard {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (fromWebsite) "Show the text from the page" else "Show the scanned text", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = Pinch.colors.ink, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Pinch.colors.inkSoft)
        }
        AnimatedVisibility(open) {
            SelectionContainer {
                Text(
                    raw,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = Pinch.colors.inkSoft,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
    Text(
        if (fromWebsite) "The recipe as the website lists it, in case something landed in the wrong place. Touch and hold to copy."
        else "Everything Pinch read from your pages, in case something landed in the wrong place. Touch and hold to copy.",
        style = MaterialTheme.typography.bodySmall,
        color = Pinch.colors.inkSoft,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
    )
}

private val emojiChoices = listOf(
    "🍝", "🍕", "🍲", "🥗", "🍛", "🍜", "🌮", "🍔", "🥪", "🍗", "🥩", "🍖", "🐟", "🍤", "🍣", "🥟",
    "🍳", "🥞", "🥣", "🍞", "🥐", "🥯", "🧀", "🥔", "🌽", "🥕", "🥦", "🍄", "🥑", "🍅", "🌶️", "🧄",
    "🍰", "🎂", "🧁", "🥧", "🍪", "🍫", "🍩", "🍨", "🍮", "🍎", "🍋", "🍓", "🫐", "🍑", "🍌", "🎃",
    "☕️", "🍵", "🥤", "🍹", "🍷", "🫙", "🥢", "🍽️", "🧑‍🍳", "❤️", "⭐️", "🎄",
)

/** Emoji for covers, plus a field for any emoji at all. */
@Composable
private fun EmojiPickerDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pinch.colors.canvas,
        title = { Text("Pick an emoji") },
        text = {
            Column {
                LazyVerticalGrid(GridCells.Adaptive(52.dp), Modifier.heightIn(max = 360.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(emojiChoices) { emoji ->
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (emoji == current) Pinch.colors.jade.copy(alpha = 0.35f) else Pinch.colors.sunken)
                                .clickable { onPick(emoji) },
                            contentAlignment = Alignment.Center,
                        ) { Text(emoji, fontSize = 28.sp) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).clip(CircleShape).background(Pinch.colors.sunken).padding(horizontal = 16.dp, vertical = 12.dp)) {
                        PlainField(custom, { custom = it }, "Or type any emoji", singleLine = true)
                    }
                    TextButton(onClick = { custom.trim().takeIf { it.isNotEmpty() }?.let { onPick(firstGrapheme(it)) } }) { Text("Use") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun firstGrapheme(text: String): String {
    val iterator = java.text.BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val end = iterator.next()
    return if (end == java.text.BreakIterator.DONE) text else text.substring(0, end)
}
