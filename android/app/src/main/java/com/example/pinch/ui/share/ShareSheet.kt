package com.example.pinch.ui.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.pinch.model.CoverPalette
import com.example.pinch.model.CoverPattern
import com.example.pinch.model.Recipe
import com.example.pinch.parsing.IngredientParser
import com.example.pinch.ui.components.CoverArt
import com.example.pinch.ui.components.EmojiSticker
import com.example.pinch.ui.components.PrimaryButton
import com.example.pinch.ui.components.RichText
import com.example.pinch.ui.theme.LightPinchColors
import com.example.pinch.ui.theme.LocalPinchColors
import com.example.pinch.ui.theme.Pinch
import com.example.pinch.ui.theme.base
import com.example.pinch.ui.theme.deep
import com.example.pinch.ui.theme.onBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Preview of the card with a share button, so you see exactly what they'll get. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(recipe: Recipe, scale: Double, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var card by remember { mutableStateOf<Pair<Bitmap, File>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val layer = rememberGraphicsLayer()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Pinch.colors.canvas,
    ) {
        // The card is composed off-screen at a fixed 3x density, so the picture is
        // 1200 pixels wide and looks the same whatever the phone's text size.
        Box(Modifier.layout { measurable, _ ->
            val placeable = measurable.measure(Constraints())
            layout(0, 0) { placeable.place(0, 0) }
        }) {
            CompositionLocalProvider(LocalDensity provides Density(3f, 1f), LocalPinchColors provides LightPinchColors) {
                Box(Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() } }) {
                    ShareCard(recipe, scale)
                }
            }
        }
        LaunchedEffect(recipe) {
            withFrameNanos { }
            withFrameNanos { }
            card = runCatching {
                val bitmap = layer.toImageBitmap().asAndroidBitmap()
                bitmap to withContext(Dispatchers.IO) { writePng(context, bitmap, recipe.displayTitle) }
            }.getOrNull()
            failed = card == null
        }

        // A fixed, tall sheet: the card image arrives a moment after the sheet opens.
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Share as a picture", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = Pinch.colors.ink)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                val shown = card
                when {
                    shown != null -> Image(
                        shown.first.asImageBitmap(),
                        contentDescription = "Recipe card for ${recipe.displayTitle}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().shadow(16.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)),
                    )
                    failed -> Text("Couldn't draw the recipe card. Try again in a moment.", color = Pinch.colors.inkSoft, modifier = Modifier.padding(40.dp))
                    else -> CircularProgressIndicator(color = Pinch.colors.jadeDeep, modifier = Modifier.padding(80.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Sent as a picture, so anyone can open it. They don't need Pinch.",
                style = TextStyle(fontSize = 13.sp),
                color = Pinch.colors.inkSoft,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                "Share recipe card",
                icon = Icons.Rounded.Share,
                enabled = card != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = { card?.let { share(context, it.second, recipe.displayTitle) } },
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun writePng(context: Context, bitmap: Bitmap, title: String): File {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val safe = title.filter { it.isLetterOrDigit() || it in " -'" }.trim().take(60).ifEmpty { "Recipe" }
    val file = File(dir, "$safe.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}

private fun share(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TITLE, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri(title, uri)
    context.startActivity(Intent.createChooser(intent, "Share recipe card"))
}

/**
 * The picture people receive: the same cover, title, pills, ingredients and steps as the
 * app, as one tall card. Always drawn in light colors. Mirrors the iOS share card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareCard(recipe: Recipe, scale: Double) {
    val ink = Color(0xFF17332C)
    val inkSoft = Color(0xFF587068)
    val deep = recipe.palette.deep(dark = false)
    Column(Modifier.width(400.dp).background(Color.White)) {
        Box(Modifier.fillMaxWidth().height(168.dp)) {
            CoverArt(recipe.palette, recipe.pattern, Modifier.fillMaxWidth().height(168.dp), cell = 26.dp)
            EmojiSticker(recipe.emoji, 88.dp, Modifier.align(Alignment.BottomStart).offset(x = 26.dp, y = 36.dp), tilt = -7f)
        }
        Column(Modifier.padding(start = 26.dp, end = 26.dp, top = 52.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(recipe.displayTitle, style = TextStyle(fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold), color = ink)
                if (recipe.summary.isNotBlank()) Text(recipe.summary, style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp), color = inkSoft)
                val pills = buildList {
                    if (recipe.prepTime.isNotBlank()) add(Icons.Rounded.Schedule to "Prep ${recipe.prepTime}")
                    if (recipe.cookTime.isNotBlank()) add(Icons.Rounded.LocalFireDepartment to "Cook ${recipe.cookTime}")
                    if (recipe.totalTime.isNotBlank()) add(Icons.Rounded.HourglassEmpty to "Total ${recipe.totalTime}")
                    scaledServings(recipe, scale)?.let { add(Icons.Rounded.Group to it) }
                    if (recipe.source.isNotBlank()) add(Icons.AutoMirrored.Rounded.MenuBook to recipe.source)
                }
                if (pills.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pills.forEach { (icon, text) ->
                            Row(
                                Modifier.clip(CircleShape).background(Color(0xFFE3ECE5)).padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(icon, null, tint = ink, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(text, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = ink)
                            }
                        }
                    }
                }
            }
            if (recipe.ingredients.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    CardHeading("Ingredients", ink)
                    recipe.ingredients.forEach { item ->
                        if (item.isHeader) {
                            Text(item.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = deep, modifier = Modifier.padding(top = 6.dp))
                        } else {
                            Row(verticalAlignment = Alignment.Top) {
                                Box(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(recipe.palette.base))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    RichText.ingredient(IngredientParser.scale(item.text, scale), deep, dimmed = false),
                                    style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
                                    color = ink,
                                )
                            }
                        }
                    }
                }
            }
            if (recipe.steps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CardHeading("Steps", ink)
                    var number = 0
                    recipe.steps.forEach { item ->
                        if (item.isHeader) {
                            Text(item.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = deep)
                        } else {
                            number++
                            Row(verticalAlignment = Alignment.Top) {
                                Box(Modifier.size(26.dp).clip(CircleShape).background(recipe.palette.base), contentAlignment = Alignment.Center) {
                                    Text("$number", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Black), color = recipe.palette.onBase)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(RichText.step(item.text), style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp), color = ink, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }
            }
            if (recipe.notes.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFFFF3C4)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Notes", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = Color(0xFF5A4A12))
                    Text(recipe.notes, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = Color(0xFF5A4A12))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverArt(CoverPalette.JADE, CoverPattern.GINGHAM, Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)), cell = 6.dp)
                Spacer(Modifier.width(8.dp))
                Text("Shared from Pinch", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = inkSoft)
            }
        }
    }
}

@Composable
private fun CardHeading(text: String, color: Color) {
    Text(text, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = color, modifier = Modifier.padding(bottom = 2.dp))
}

private fun scaledServings(recipe: Recipe, scale: Double): String? {
    val base = recipe.servingsCount
    if (kotlin.math.abs(scale - 1) < 0.001 || base == null) return recipe.servingsLabel
    val n = Math.round(base * scale).toInt()
    return if (n == 1) "1 serving" else "$n servings"
}
