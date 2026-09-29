package com.example.pinch.ui.cook

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pinch.AppContainer
import com.example.pinch.model.Recipe
import com.example.pinch.model.RecipeItem
import com.example.pinch.parsing.DurationParser
import com.example.pinch.parsing.IngredientParser
import com.example.pinch.ui.components.EmojiSticker
import com.example.pinch.ui.components.PrimaryButton
import com.example.pinch.ui.components.RichText
import com.example.pinch.ui.components.StepIngredients
import com.example.pinch.ui.components.StepText
import com.example.pinch.ui.components.TimerDock
import com.example.pinch.ui.components.rememberNotificationPermissionRequest
import com.example.pinch.ui.detail.IngredientRow
import com.example.pinch.ui.detail.IngredientsCard
import com.example.pinch.ui.theme.Pinch
import com.example.pinch.ui.theme.base
import com.example.pinch.ui.theme.deep
import com.example.pinch.ui.theme.tint
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class Entry(val item: RecipeItem, val number: Int, val heading: String?)

/** One step at a time, in big type, with the screen kept awake. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookModeScreen(container: AppContainer, recipeId: String, onClose: () -> Unit) {
    val recipe by container.repository.observe(recipeId).collectAsState(initial = container.repository.recipe(recipeId))
    val current = recipe ?: run {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val session = container.cooking
    val timers by container.timers.timers.collectAsState()
    val entries = remember(current.steps) {
        var heading: String? = null
        var n = 0
        buildList {
            current.steps.forEach { item ->
                if (item.isHeader) {
                    heading = item.text
                } else {
                    n++
                    add(Entry(item, n, heading))
                    heading = null
                }
            }
        }
    }
    val pager = rememberPagerState(initialPage = session.currentStep(recipeId).coerceIn(0, entries.size)) { entries.size + 1 }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var showIngredients by remember { mutableStateOf(false) }
    val askForNotifications = rememberNotificationPermissionRequest()

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(pager) {
        var previous = pager.currentPage
        snapshotFlow { pager.currentPage }.collect { page ->
            session.setCurrentStep(recipeId, page)
            // Moving on means the previous step is done.
            if (page > previous && previous < entries.size) session.setDone(entries[previous].item.id, recipeId, true)
            if (page != previous) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            previous = page
        }
    }

    Column(Modifier.fillMaxSize().background(current.palette.tint).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.Rounded.Close, "Close cook mode", onClose)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (pager.currentPage < entries.size) "Step ${pager.currentPage + 1} of ${entries.size}" else "All done",
                    style = MaterialTheme.typography.titleMedium,
                    color = Pinch.colors.ink,
                )
                Text(current.displayTitle, style = MaterialTheme.typography.labelSmall, color = Pinch.colors.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundButton(Icons.AutoMirrored.Rounded.FormatListBulleted, "All ingredients") { showIngredients = true }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(entries.size.coerceAtLeast(1)) { i ->
                val color by animateColorAsState(if (i <= pager.currentPage) current.palette.base else Pinch.colors.ink.copy(alpha = 0.1f), label = "segment")
                Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(color))
            }
        }

        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            if (page < entries.size) {
                StepPage(container, current, entries[page]) { seconds, label ->
                    askForNotifications()
                    container.timers.start(seconds, label, current)
                }
            } else {
                FinishedPage(current, visible = pager.currentPage == entries.size) {
                    container.repository.markCooked(current.id)
                    session.reset(current.id)
                    onClose()
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TimerDock(timers, container.timers, recipeId = current.id)
            if (pager.currentPage < entries.size) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val atStart = pager.currentPage == 0
                    Box(
                        Modifier
                            .alpha(if (atStart) 0.4f else 1f)
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Pinch.colors.surface)
                            .clickable(enabled = !atStart, role = Role.Button) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
                            .semantics { contentDescription = "Previous step" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null, tint = Pinch.colors.ink, modifier = Modifier.size(30.dp)) }
                    Spacer(Modifier.width(12.dp))
                    val last = pager.currentPage == entries.size - 1
                    PrimaryButton(
                        if (last) "Finish" else "Next step",
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        icon = if (last) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        trailingIcon = true,
                        height = 64,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showIngredients) {
        ModalBottomSheet(onDismissRequest = { showIngredients = false }, containerColor = Pinch.colors.canvas) {
            Box(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                IngredientsCard(current, session)
            }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Pinch.colors.surface).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Pinch.colors.ink) }
}

@Composable
private fun StepPage(container: AppContainer, recipe: Recipe, entry: Entry, onTimer: (Int, String) -> Unit) {
    val session = container.cooking
    val durations = remember(entry.item.text) { DurationParser.matches(entry.item.text) }
    val uses = remember(entry.item.text, recipe.ingredients) { StepIngredients.matching(entry.item.text, recipe) }
    val scale = session.scale(recipe.id)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        entry.heading?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = recipe.palette.deep) }
        Text(RichText.step(entry.item.text), style = MaterialTheme.typography.headlineMedium, color = Pinch.colors.ink)
        if (durations.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                durations.forEach { match ->
                    Row(
                        Modifier
                            .heightIn(min = 56.dp)
                            .clip(CircleShape)
                            .background(Pinch.colors.butter)
                            .clickable(role = Role.Button) { onTimer(match.seconds, StepText.timerLabel(entry.item.text, entry.number)) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Timer, null, tint = Color(0xFF17332C))
                        Spacer(Modifier.width(10.dp))
                        Text("Start ${match.label} timer", style = MaterialTheme.typography.titleMedium, color = Color(0xFF17332C))
                    }
                }
            }
        }
        if (uses.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Pinch.colors.surface).padding(18.dp)) {
                Text("For this step", style = MaterialTheme.typography.titleMedium, color = Pinch.colors.inkSoft, modifier = Modifier.padding(bottom = 4.dp))
                uses.forEach { item ->
                    IngredientRow(IngredientParser.scale(item.text, scale), recipe.palette.deep, session.isChecked(item.id, recipe.id)) {
                        session.toggleChecked(item.id, recipe.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun FinishedPage(recipe: Recipe, visible: Boolean, onDone: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val burst by animateFloatAsState(
        if (visible) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow),
        label = "burst",
    )
    LaunchedEffect(visible) { if (visible) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.height(280.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            repeat(10) { i ->
                val angle = i / 10.0 * 2 * PI
                Text(
                    if (i % 2 == 0) recipe.emoji else "✨",
                    fontSize = if (i % 2 == 0) 34.sp else 24.sp,
                    modifier = Modifier
                        .offset { IntOffset((cos(angle) * 120 * burst).dp.roundToPx(), (sin(angle) * 120 * burst).dp.roundToPx()) }
                        .alpha(burst)
                        .scale(0.3f + 0.7f * burst),
                )
            }
            EmojiSticker(recipe.emoji, 120.dp, Modifier.scale(0.8f + 0.2f * burst), tilt = -8f * burst)
        }
        Text("That's the last step", style = MaterialTheme.typography.headlineLarge, color = Pinch.colors.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enjoy your ${recipe.displayTitle}.",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
            color = Pinch.colors.inkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(36.dp))
        PrimaryButton("Done cooking", onClick = onDone, height = 64, modifier = Modifier.fillMaxWidth())
    }
}
