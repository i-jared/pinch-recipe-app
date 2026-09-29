package com.example.pinch.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.pinch.model.Recipe
import com.example.pinch.model.RecipeItem
import com.example.pinch.parsing.DurationParser
import com.example.pinch.parsing.IngredientParser
import com.example.pinch.parsing.TextKit
import com.example.pinch.timers.KitchenTimer
import com.example.pinch.timers.TimerManager
import com.example.pinch.ui.theme.Pinch
import kotlinx.coroutines.delay

/** The main action: a jade capsule with dark text, big enough to hit with a floury thumb. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailingIcon: Boolean = false,
    enabled: Boolean = true,
    height: Int = 56,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .scale(if (pressed) 0.97f else 1f)
            .heightIn(min = height.dp)
            .clip(CircleShape)
            .background(if (enabled) Pinch.colors.jade else Pinch.colors.jade.copy(alpha = 0.45f))
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null && !trailingIcon) {
            Icon(icon, null, tint = Pinch.colors.onJade, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = Pinch.colors.onJade)
        if (icon != null && trailingIcon) {
            Spacer(Modifier.width(8.dp))
            Icon(icon, null, tint = Pinch.colors.onJade, modifier = Modifier.size(22.dp))
        }
    }
}

/** A quieter capsule on the sunken surface color. */
@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(Pinch.colors.sunken)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Pinch.colors.ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = Pinch.colors.ink.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

/** A small icon + text capsule for times and servings. */
@Composable
fun MetaPill(icon: ImageVector, text: String, modifier: Modifier = Modifier, tint: Color = Pinch.colors.ink) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Pinch.colors.sunken)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Filter chip, shaped like the tab on a recipe box divider. */
@Composable
fun TabChip(title: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
    Row(
        Modifier
            .height(38.dp)
            .clip(shape)
            .background(if (selected) Pinch.colors.jade else Pinch.colors.sunken)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) Pinch.colors.onJade else Pinch.colors.ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(title, style = MaterialTheme.typography.labelMedium, color = if (selected) Pinch.colors.onJade else Pinch.colors.ink)
    }
}

@Composable
fun SectionHeading(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = Pinch.colors.ink,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing()
    }
}

/** Fades content out behind floating bottom buttons so text never sits under them. */
fun Modifier.bottomFade(color: Color): Modifier = background(
    Brush.verticalGradient(0f to color.copy(alpha = 0f), 0.35f to color.copy(alpha = 0.92f), 1f to color)
)

/** Running timers, as a stack of pills above the bottom of the screen. */
@Composable
fun TimerDock(timers: List<KitchenTimer>, manager: TimerManager, modifier: Modifier = Modifier, recipeId: String? = null) {
    val mine = recipeId?.let { id -> timers.filter { it.recipeId == id } }.orEmpty()
    val visible = if (mine.isEmpty()) timers else mine
    // No size animation here: it would clip the pills' shadows.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visible.forEach { timer -> key(timer.id) { TimerPill(timer, manager) } }
    }
}

@Composable
fun TimerPill(timer: KitchenTimer, manager: TimerManager) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer.endAtMillis, timer.isFinished) {
        while (!timer.isFinished) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val finished = timer.isFinished
    val content = if (finished) Color.White else Pinch.colors.ink
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(10.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(if (finished) Pinch.colors.cherry else Pinch.colors.butter)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .semantics(mergeDescendants = false) {
                contentDescription = if (finished) "Time's up: ${timer.label}" else "${timer.label}, ${DurationParser.clock(timer.remainingSeconds(now))} left"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (finished) Icons.Rounded.NotificationsActive else Icons.Rounded.Timer, null, tint = content, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (finished) "Time's up" else DurationParser.clock(timer.remainingSeconds(now)),
                style = MaterialTheme.typography.titleLarge,
                color = content,
            )
            Text(timer.label, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.3f))
                .clickable(role = Role.Button) { manager.addMinute(timer.id) }
                .semantics { contentDescription = "Add one minute" }
                .defaultMinSize(minHeight = 36.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("+1 min", style = MaterialTheme.typography.labelSmall, color = content)
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.3f))
                .clickable(role = Role.Button) { manager.cancel(timer.id) }
                .semantics { contentDescription = if (finished) "Dismiss timer" else "Cancel timer" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (finished) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = content, modifier = Modifier.size(20.dp))
        }
    }
}

/** Asks for notification permission the first time a timer starts (Android 13+). */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(context) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

val BarPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)

// Rich text

object RichText {
    /** Bolds the quantity and unit so they're easy to find mid-recipe. */
    fun ingredient(text: String, accent: Color, dimmed: Boolean): AnnotatedString = buildAnnotatedString {
        append(text)
        IngredientParser.highlightSpan(text)?.let { span ->
            val end = minOf(span.last + 1, text.length)
            if (span.first < end) {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = if (dimmed) Color.Unspecified else accent), span.first, end)
            }
        }
    }

    private val temperature = TextKit.regex("""\b\d{2,3}\s?°\s?[FC]?\b|\b\d{2,3}\s?degrees(?:\s?[FC]\b)?|\b\d{2,3}\s?°""")

    /** Bolds durations and oven temperatures in a step. */
    fun step(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        val bold = SpanStyle(fontWeight = FontWeight.Bold)
        DurationParser.matches(text).forEach { addStyle(bold, it.location, it.location + it.length) }
        temperature.findAll(text).forEach { addStyle(bold, it.range.first, it.range.last + 1) }
    }
}

object StepText {
    private val trailingFiller = setOf("in", "the", "a", "an", "for", "to", "with", "of", "and", "or", "on", "into", "until", "about", "over", "at")

    /** A short name for a timer from the start of the step: "Simmer gently". */
    fun timerLabel(text: String, number: Int): String {
        val cut = text.indexOfFirst { it in ",.;:(" }.let { if (it < 0) text.length else it }
        var words = text.substring(0, cut).split(" ").filter { it.isNotEmpty() }
        if (words.joinToString(" ").length > 26) words = words.take(4)
        while (words.isNotEmpty() && (words.last().lowercase() in trailingFiller || words.last().first().isDigit())) words = words.dropLast(1)
        return words.joinToString(" ").ifEmpty { "Step $number" }
    }
}

/** Which ingredients a step mentions, so cook mode can show just those. Same rules as iOS. */
object StepIngredients {
    private val generic = setOf("powder", "extract", "juice", "sauce", "paste", "baking", "seed", "leaf", "ground", "light", "dark", "brown", "white", "red", "green", "yellow", "black")

    fun matching(stepText: String, recipe: Recipe): List<RecipeItem> {
        val stepWords = TextKit.words(stepText).map(TextKit::singular).toSet()
        return recipe.ingredientLines.filter { item ->
            val keys = IngredientParser.keyWords(item.text)
            if (keys.isEmpty()) return@filter false
            if (keys.last() in stepWords) return@filter true
            keys.any { it !in generic && it in stepWords }
        }
    }
}
