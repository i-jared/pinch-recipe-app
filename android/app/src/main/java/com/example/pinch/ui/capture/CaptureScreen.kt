package com.example.pinch.ui.capture

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.IconButton
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.pinch.AppContainer
import com.example.pinch.CaptureSession
import com.example.pinch.scan.RecipeImporter
import com.example.pinch.scan.WebRecipeImporter
import com.example.pinch.ui.components.PrimaryButton
import com.example.pinch.ui.components.SoftButton
import com.example.pinch.ui.editor.RecipeEditor
import com.example.pinch.ui.theme.Pinch
import com.example.pinch.ui.theme.PinchTypography

/** Read → review, after the scanner or photo picker hands over pages. */
@Composable
fun CaptureScreen(container: AppContainer, onDone: (String?) -> Unit) {
    val phase by container.capture.phase.collectAsState()
    // Idle on arrival means there's nothing to show (e.g. after process death). Idle later is
    // just the flow finishing while this screen animates away.
    val arrivedIdle = remember { container.capture.phase.value is CaptureSession.Phase.Idle }
    when (val p = phase) {
        CaptureSession.Phase.Idle ->
            if (arrivedIdle) LaunchedEffect(Unit) { onDone(null) } else Box(Modifier.fillMaxSize().background(Pinch.colors.canvas))
        is CaptureSession.Phase.Reading -> {
            val leave = {
                container.capture.finish()
                onDone(null)
            }
            BackHandler(onBack = leave)
            // Reading with the AI can take a little while, so it can be called off.
            val cancellable = p.stage is RecipeImporter.Stage.Fetching || p.stage is RecipeImporter.Stage.AiReading
            ReadingScreen(p, onCancel = if (cancellable) leave else null)
        }
        is CaptureSession.Phase.Reviewing -> RecipeEditor(
            container = container,
            initial = p.draft,
            isNew = true,
            readBy = p.readBy,
            onSave = { recipe ->
                container.repository.save(recipe)
                container.capture.finish()
                onDone(recipe.id)
            },
            onCancel = { draft ->
                draft.photoFiles.forEach(container.photos::delete)
                container.capture.finish()
                onDone(null)
            },
        )
        is CaptureSession.Phase.Failed -> FailedScreen(
            message = p.message,
            onTypeIt = { container.capture.startBlank() },
            onBack = {
                container.capture.finish()
                onDone(null)
            },
        )
    }
}

/** The one orchestrated moment in the flow: pages fan out while a highlighter sweeps them. */
@Composable
private fun ReadingScreen(phase: CaptureSession.Phase.Reading, onCancel: (() -> Unit)?) {
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sweep",
    )
    Column(
        Modifier.fillMaxSize().background(Pinch.colors.canvas).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.height(270.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val shown = phase.pages.take(3)
            if (shown.isEmpty()) {
                PlaceholderCard(
                    host = when (val stage = phase.stage) {
                        is RecipeImporter.Stage.Fetching -> stage.host
                        is RecipeImporter.Stage.AiReading -> if (stage.source == RecipeImporter.AiSource.Website) "" else null
                        else -> null
                    },
                )
            } else {
                shown.forEachIndexed { index, uri ->
                    val angle = (index - shown.size / 2) * 7f
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .offset(x = (index * 14 - 14).dp)
                            .rotate(angle)
                            .size(170.dp, 220.dp)
                            .shadow(10.dp, RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .border(4.dp, Color.White, RoundedCornerShape(16.dp)),
                    )
                }
            }
            val highlighter = Pinch.colors.butter.copy(alpha = 0.75f)
            Box(
                Modifier
                    .offset { IntOffset(0, (sweep * 95).dp.roundToPx()) }
                    .size(200.dp, 18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .drawBehind { drawRect(highlighter, blendMode = BlendMode.Multiply) },
            )
        }
        Spacer(Modifier.height(32.dp))
        val (title, detail) = readingText(phase.stage)
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Pinch.colors.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(detail, style = MaterialTheme.typography.bodyLarge, color = Pinch.colors.inkSoft, textAlign = TextAlign.Center)
        if (onCancel != null) {
            Spacer(Modifier.height(28.dp))
            SoftButton("Cancel", onClick = onCancel)
        }
    }
}

private fun readingText(stage: RecipeImporter.Stage): Pair<String, String> = when (stage) {
    is RecipeImporter.Stage.Reading ->
        (if (stage.total > 1) "Reading page ${stage.page} of ${stage.total}" else "Reading your recipe") to
            "Picking out the words on each page."
    is RecipeImporter.Stage.Fetching -> "Opening the page" to "Getting the recipe from ${stage.host}."
    is RecipeImporter.Stage.AiReading -> when (val source = stage.source) {
        is RecipeImporter.AiSource.Pages -> "Reading your recipe" to
            "The AI is reading ${if (source.count == 1) "the page" else "all ${source.count} pages"} and sorting the ingredients from the steps. This takes a few seconds."
        RecipeImporter.AiSource.Website -> "Finding the recipe" to "The AI is picking out the recipe and leaving out everything else on the page."
        RecipeImporter.AiSource.Text -> "Reading your recipe" to "Sorting the ingredients from the steps."
    }
}

@Composable
private fun PlaceholderCard(host: String? = null) {
    Column(
        Modifier
            .rotate(-4f)
            .size(190.dp, 220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Pinch.colors.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (host != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Language, null, tint = Pinch.colors.jadeDeep, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(host, style = MaterialTheme.typography.labelMedium, color = Pinch.colors.jadeDeep, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(Pinch.colors.cherry, RoundedCornerShape(3.dp)))
        repeat(5) { i ->
            Box(Modifier.width(if (i == 0) 90.dp else 140.dp).height(8.dp).background(Pinch.colors.line, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
private fun FailedScreen(message: String, onTypeIt: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().background(Pinch.colors.canvas).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🔍", style = PinchTypography.displaySmall)
        Spacer(Modifier.height(16.dp))
        Text("Couldn't read that one", style = MaterialTheme.typography.headlineSmall, color = Pinch.colors.ink)
        Spacer(Modifier.height(10.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = Pinch.colors.inkSoft, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Type it in instead", onClick = onTypeIt, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        SoftButton("Back to recipes", onClick = onBack)
    }
}

/** Paste a recipe copied from a website, note or message. */
@Composable
fun PasteRecipeDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pinch.colors.canvas,
        title = { Text("Paste a recipe", color = Pinch.colors.ink) },
        text = {
            Column {
                Text(
                    "Paste the whole recipe, name and all. Pinch sorts out the ingredients and steps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Pinch.colors.inkSoft,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 320.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Pinch.colors.surface)
                        .padding(14.dp),
                ) {
                    if (text.isEmpty()) {
                        Text("Grandma's Banana Bread\n3 ripe bananas\n⅓ cup melted butter\n…", style = MaterialTheme.typography.bodyLarge, color = Pinch.colors.inkSoft.copy(alpha = 0.6f))
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Pinch.colors.ink),
                        cursorBrush = SolidColor(Pinch.colors.jadeDeep),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    SoftButton("Paste", icon = Icons.Rounded.ContentPaste, onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { text = it }
                    })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) { Text("Read recipe") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Takes a recipe's web address, typed or pasted. */
@Composable
fun WebRecipeDialog(aiConnected: Boolean, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun submit() {
        val url = WebRecipeImporter.normalizedUrl(text)
        if (url == null) invalid = true else onSubmit(url)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pinch.colors.canvas,
        title = { Text("From a website", color = Pinch.colors.ink) },
        text = {
            Column {
                Text(
                    "Paste the link to any recipe page. Pinch copies the recipe and leaves out the ads and life stories.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Pinch.colors.inkSoft,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Pinch.colors.surface)
                        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Link, null, tint = Pinch.colors.jadeDeep, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        if (text.isEmpty()) Text("https://…", style = MaterialTheme.typography.bodyLarge, color = Pinch.colors.inkSoft.copy(alpha = 0.6f))
                        BasicTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                invalid = false
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Pinch.colors.ink),
                            cursorBrush = SolidColor(Pinch.colors.jadeDeep),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onGo = { submit() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    IconButton(onClick = {
                        if (text.isNotEmpty()) {
                            text = ""
                        } else {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.let { text = it }
                        }
                        invalid = false
                    }) {
                        Icon(
                            if (text.isNotEmpty()) Icons.Rounded.Close else Icons.Rounded.ContentPaste,
                            contentDescription = if (text.isNotEmpty()) "Clear" else "Paste",
                            tint = Pinch.colors.jadeDeep,
                        )
                    }
                }
                if (invalid) {
                    Spacer(Modifier.height(8.dp))
                    Text("That doesn't look like a web address.", style = MaterialTheme.typography.bodySmall, color = Pinch.colors.cherry)
                }
                if (!aiConnected) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Most recipe sites work as is. Sign in to Pinch to read pages without a recipe card.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Pinch.colors.inkSoft,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = text.isNotBlank()) { Text("Get recipe") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
