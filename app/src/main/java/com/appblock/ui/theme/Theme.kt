package com.appblock.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * The app's theme: the Lamp ramp on [LocalLamp], Inter on the Material typography slots, and the
 * subset of the ramp Material components actually need mapped into a [darkColorScheme].
 *
 * Dark-only, and not `isSystemInDarkTheme()`-driven any more. The old file was a bare
 * `darkColorScheme()` / `lightColorScheme()` pair, which is why every card on the old settings screen
 * had the same weight — with no tokens there is no hierarchy to express. There is one palette now;
 * following the system into a light theme would mean maintaining a second one nobody has drawn.
 */
private val LampColorScheme = darkColorScheme(
    // The accent is a line, a mark and a bar. `onPrimary` is the ground because the only filled
    // accent surface in the design is the toggle's track, whose knob is the ground colour.
    primary = LampDark.accent,
    onPrimary = LampDark.bg,
    primaryContainer = LampDark.accent900,
    onPrimaryContainer = LampDark.accent100,

    secondary = LampDark.accent300,
    onSecondary = LampDark.bg,

    background = LampDark.bg,
    onBackground = LampDark.text,
    surface = LampDark.bg,
    onSurface = LampDark.text,

    // Elevation in this design is the fill step bg → surface, never a shadow, so every container
    // level lands on the same one step up.
    surfaceContainerLowest = LampDark.surface,
    surfaceContainerLow = LampDark.surface,
    surfaceContainer = LampDark.surface,
    surfaceContainerHigh = LampDark.surface,
    surfaceContainerHighest = LampDark.surface,
    surfaceVariant = LampDark.surface,
    onSurfaceVariant = LampDark.neutral500,

    outline = LampDark.divider,
    outlineVariant = LampDark.neutral800,

    // Kept for genuine failures only — nothing currently on screen is an error. A spent budget, a
    // closed schedule and a lapsed permission are facts, and they render neutral + accent.
    error = Color(0xFFE0755E),
    onError = LampDark.bg,

    scrim = Color(0x80000000),
)

private val LampShapes = Shapes(
    extraSmall = RoundedCornerShape(5.dp),
    small = RoundedCornerShape(7.dp),
    medium = RoundedCornerShape(LampDimens.cardRadius),
    large = RoundedCornerShape(LampDimens.sheetRadius),
    extraLarge = RoundedCornerShape(LampDimens.sheetRadius),
)

@Composable
fun AppBlockTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLamp provides LampDark) {
        MaterialTheme(
            colorScheme = LampColorScheme,
            typography = AppBlockTypography,
            shapes = LampShapes,
            content = content,
        )
    }
}
