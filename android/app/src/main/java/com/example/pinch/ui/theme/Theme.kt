package com.example.pinch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.pinch.model.CoverPalette

/**
 * Pinch's palette: vintage jadeite kitchenware. Enamel-white canvas, jade for actions,
 * deep pine ink, cherry only for favorites and timers, butter for notes. Same as iOS.
 */
@Immutable
data class PinchColors(
    val canvas: Color,
    val surface: Color,
    val sunken: Color,
    val ink: Color,
    val inkSoft: Color,
    val jade: Color,
    val jadeDeep: Color,
    val onJade: Color,
    val cherry: Color,
    val butter: Color,
    val note: Color,
    val noteInk: Color,
    val line: Color,
    val isDark: Boolean,
)

val LightPinchColors = PinchColors(
    canvas = Color(0xFFF2F6F1), surface = Color(0xFFFFFFFF), sunken = Color(0xFFE3ECE5),
    ink = Color(0xFF17332C), inkSoft = Color(0xFF587068), jade = Color(0xFF74C69D),
    jadeDeep = Color(0xFF1F7A57), onJade = Color(0xFF10291F), cherry = Color(0xFFE0393E),
    butter = Color(0xFFFFD966), note = Color(0xFFFFF3C4), noteInk = Color(0xFF5A4A12),
    line = Color(0xFFD5E1D8), isDark = false,
)

val DarkPinchColors = PinchColors(
    canvas = Color(0xFF0E1A17), surface = Color(0xFF16251F), sunken = Color(0xFF1F322B),
    ink = Color(0xFFE8F1EB), inkSoft = Color(0xFF9FB5AB), jade = Color(0xFF74C69D),
    jadeDeep = Color(0xFF8FD6B0), onJade = Color(0xFF10291F), cherry = Color(0xFFFF6B6B),
    butter = Color(0xFFFFD966), note = Color(0xFF3A3420), noteInk = Color(0xFFF4E7B5),
    line = Color(0xFF27392F), isDark = true,
)

val LocalPinchColors = staticCompositionLocalOf { LightPinchColors }

object Pinch {
    val colors: PinchColors
        @Composable @ReadOnlyComposable get() = LocalPinchColors.current
}

private val base = TextStyle(letterSpacing = 0.sp)

val PinchTypography = Typography(
    displaySmall = base.copy(fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em),
    headlineLarge = base.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.015).em),
    headlineMedium = base.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
    headlineSmall = base.copy(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
    titleLarge = base.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = base.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold),
    titleSmall = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.copy(fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = base.copy(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = base.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = base.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    labelMedium = base.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun PinchTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkPinchColors else LightPinchColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.jade, onPrimary = c.onJade, primaryContainer = c.jade, onPrimaryContainer = c.onJade,
            secondary = c.jadeDeep, background = c.canvas, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.sunken, onSurfaceVariant = c.inkSoft, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface, surfaceContainerLow = c.canvas, outline = c.line, outlineVariant = c.line,
            error = c.cherry,
        )
    } else {
        lightColorScheme(
            primary = c.jadeDeep, onPrimary = Color.White, primaryContainer = c.jade, onPrimaryContainer = c.onJade,
            secondary = c.jadeDeep, background = c.canvas, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.sunken, onSurfaceVariant = c.inkSoft, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface, surfaceContainerLow = c.canvas, outline = c.line, outlineVariant = c.line,
            error = c.cherry,
        )
    }
    CompositionLocalProvider(LocalPinchColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = PinchTypography, content = content)
    }
}

// Cover palettes, identical to iOS.

val CoverPalette.base: Color
    get() = when (this) {
        CoverPalette.TOMATO -> Color(0xFFF26B4F)
        CoverPalette.JADE -> Color(0xFF74C69D)
        CoverPalette.BUTTER -> Color(0xFFF6C945)
        CoverPalette.BLUEBERRY -> Color(0xFF6D87DB)
        CoverPalette.PLUM -> Color(0xFFA976C6)
        CoverPalette.SAFFRON -> Color(0xFFF59B3A)
        CoverPalette.BEET -> Color(0xFFD6577F)
        CoverPalette.SKY -> Color(0xFF6DBFE3)
        CoverPalette.SAGE -> Color(0xFF9DB88A)
        CoverPalette.COCOA -> Color(0xFFB07B5C)
    }

/** Text color that reads on [base]. */
val CoverPalette.onBase: Color
    get() = when (this) {
        CoverPalette.TOMATO, CoverPalette.BLUEBERRY, CoverPalette.PLUM, CoverPalette.BEET, CoverPalette.COCOA -> Color.White
        else -> Color(0xFF17332C)
    }

/** A darker shade for text and accents. */
fun CoverPalette.deep(dark: Boolean): Color = Color(
    when (this) {
        CoverPalette.TOMATO -> if (dark) 0xFFFF8E77 else 0xFFC23F27
        CoverPalette.JADE -> if (dark) 0xFF8FD6B0 else 0xFF1F7A57
        CoverPalette.BUTTER -> if (dark) 0xFFF6D366 else 0xFF946B00
        CoverPalette.BLUEBERRY -> if (dark) 0xFF9BB0F0 else 0xFF3B55AB
        CoverPalette.PLUM -> if (dark) 0xFFC9A0E2 else 0xFF74439F
        CoverPalette.SAFFRON -> if (dark) 0xFFF8B56A else 0xFFA85A0C
        CoverPalette.BEET -> if (dark) 0xFFF08BAA else 0xFFA32C55
        CoverPalette.SKY -> if (dark) 0xFF93D3EE else 0xFF1F7299
        CoverPalette.SAGE -> if (dark) 0xFFB9D0A8 else 0xFF55733F
        CoverPalette.COCOA -> if (dark) 0xFFD7A889 else 0xFF7A4B30
    }
)

/** A pale wash of the color for large backgrounds. */
fun CoverPalette.tint(dark: Boolean): Color = Color(
    when (this) {
        CoverPalette.TOMATO -> if (dark) 0xFF3A221D else 0xFFFDE4DD
        CoverPalette.JADE -> if (dark) 0xFF1C3529 else 0xFFDDF3E7
        CoverPalette.BUTTER -> if (dark) 0xFF3A3218 else 0xFFFDF3D1
        CoverPalette.BLUEBERRY -> if (dark) 0xFF1F2742 else 0xFFE2E8FA
        CoverPalette.PLUM -> if (dark) 0xFF2F2240 else 0xFFEFE3F6
        CoverPalette.SAFFRON -> if (dark) 0xFF3A2A17 else 0xFFFDEAD3
        CoverPalette.BEET -> if (dark) 0xFF3A1C27 else 0xFFF9DEE7
        CoverPalette.SKY -> if (dark) 0xFF183341 else 0xFFDEF1FA
        CoverPalette.SAGE -> if (dark) 0xFF26301F else 0xFFE9F0E2
        CoverPalette.COCOA -> if (dark) 0xFF33251D else 0xFFF2E5DC
    }
)

val CoverPalette.deep: Color @Composable @ReadOnlyComposable get() = deep(Pinch.colors.isDark)
val CoverPalette.tint: Color @Composable @ReadOnlyComposable get() = tint(Pinch.colors.isDark)
