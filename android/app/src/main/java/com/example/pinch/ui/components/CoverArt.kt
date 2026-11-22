package com.example.pinch.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.pinch.model.CoverPalette
import com.example.pinch.model.CoverPattern
import com.example.pinch.ui.theme.base
import java.io.File
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Tablecloth and recipe-tin patterns drawn in translucent white over the cover color. */
@Composable
fun CoverPattern(pattern: CoverPattern, cell: Dp, modifier: Modifier = Modifier, ink: Color = Color.White) {
    Canvas(modifier) {
        val c = cell.toPx()
        when (pattern) {
            CoverPattern.GINGHAM -> {
                val band = ink.copy(alpha = 0.27f)
                var x = 0f
                while (x < size.width) {
                    drawRect(band, Offset(x, 0f), Size(c / 2, size.height))
                    x += c
                }
                var y = 0f
                while (y < size.height) {
                    drawRect(band, Offset(0f, y), Size(size.width, c / 2))
                    y += c
                }
            }

            CoverPattern.STRIPES -> {
                val path = Path()
                var offset = -size.height
                while (offset < size.width + size.height) {
                    path.moveTo(offset, 0f)
                    path.lineTo(offset + c * 0.42f, 0f)
                    path.lineTo(offset + c * 0.42f - size.height, size.height)
                    path.lineTo(offset - size.height, size.height)
                    path.close()
                    offset += c
                }
                drawPath(path, ink.copy(alpha = 0.27f))
            }

            CoverPattern.DOTS -> {
                val radius = c * 0.17f
                val dot = ink.copy(alpha = 0.34f)
                var row = 0
                var y = c / 2
                while (y < size.height + c) {
                    var x = if (row % 2 == 0) c / 2 else 0f
                    while (x < size.width + c) {
                        drawCircle(dot, radius, Offset(x, y))
                        x += c
                    }
                    y += c * 0.8f
                    row++
                }
            }

            CoverPattern.WAVES -> {
                val path = Path()
                val amplitude = c * 0.16f
                val wavelength = c * 1.3f
                var y = c * 0.4f
                while (y < size.height + c) {
                    path.moveTo(0f, y)
                    var x = 0f
                    while (x <= size.width) {
                        path.lineTo(x, y + sin(x / wavelength * PI.toFloat() * 2) * amplitude)
                        x += 2f
                    }
                    y += c * 0.62f
                }
                drawPath(path, ink.copy(alpha = 0.36f), style = Stroke(width = c * 0.11f, cap = StrokeCap.Round))
            }

            CoverPattern.TILES -> {
                // Offset subway tiles, like a kitchen backsplash.
                val grout = ink.copy(alpha = 0.34f)
                val stroke = max(1.5f, c * 0.08f)
                val tileWidth = c * 1.4f
                val tileHeight = c * 0.7f
                var row = 0
                var y = 0f
                while (y < size.height) {
                    drawLine(grout, Offset(0f, y), Offset(size.width, y), stroke)
                    var x = if (row % 2 == 0) 0f else tileWidth / 2
                    while (x < size.width) {
                        drawLine(grout, Offset(x, y), Offset(x, y + tileHeight), stroke)
                        x += tileWidth
                    }
                    y += tileHeight
                    row++
                }
            }
        }
    }
}

/** The recipe's cover: its color and its pattern. */
@Composable
fun CoverArt(palette: CoverPalette, pattern: CoverPattern, modifier: Modifier = Modifier, cell: Dp = 22.dp, photo: File? = null) {
    Box(modifier.background(palette.base)) {
        CoverPattern(pattern, cell, Modifier.fillMaxSize())
        if (photo != null) {
            AsyncImage(model = photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

/** A round white sticker with the recipe's emoji, slightly tilted like it was stuck on by hand. */
@Composable
fun EmojiSticker(emoji: String, size: Dp, modifier: Modifier = Modifier, tilt: Float = -8f) {
    Box(
        modifier
            .rotate(tilt)
            .size(size)
            .shadow(size * 0.08f, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .background(Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = (size.value * 0.5f).sp)
    }
}
