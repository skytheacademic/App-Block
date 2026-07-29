package com.appblock.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * "Lamp" — the design token set (handoff 2026-07-29). A warm near-black ground, bone text, one amber
 * accent, and two nine-step ramps.
 *
 * It lives beside, not inside, the Material [androidx.compose.material3.ColorScheme]: a ColorScheme
 * has no way to express a 100–900 ramp, and this design leans on the ramp constantly (a row rule is
 * `text @ 8%`, a chip is `neutral-800` on `neutral-200`, a spent bar is `accent-500` against an
 * under-cap `accent-700`). Squeezing nine steps into `surfaceVariant`/`outlineVariant` would lose the
 * distinctions the screens are drawn from. [Theme.kt] maps the subset Material components need into
 * `darkColorScheme(...)`; everything else reads [LocalLamp].
 *
 * The one rule that outranks the palette: **nothing is red.** A spent budget, a closed schedule and a
 * lapsed permission are facts about a commitment device working as designed, not errors. They render
 * as neutral chips and accent marks. `colorScheme.error` stays in the scheme for genuine failures,
 * of which there are currently none on screen.
 */
@Immutable
data class LampColors(
    /** App ground — every screen. */
    val bg: Color,
    /** Cards, bottom sheets, banners, toasts — the one elevation step, expressed as fill not shadow. */
    val surface: Color,
    /** The block overlay only: a deeper room than the app, so being blocked doesn't look like the app. */
    val blockGround: Color,
    val text: Color,
    /** Active tab, button outlines, rules, markers, chart bars. A line, a mark, a bar — never a fill. */
    val accent: Color,
    /** Section rules, input borders, the tab bar's top edge. `text` at 16%. */
    val divider: Color,
    /** Between table rows. `text` at 8% — half the divider, because tables repeat it. */
    val rowRule: Color,

    val neutral100: Color,
    val neutral200: Color,
    val neutral300: Color,
    val neutral400: Color,
    val neutral500: Color,
    val neutral600: Color,
    val neutral700: Color,
    val neutral800: Color,
    val neutral900: Color,

    val accent100: Color,
    val accent300: Color,
    val accent500: Color,
    val accent600: Color,
    val accent700: Color,
    val accent800: Color,
    val accent900: Color,
)

val LampDark = LampColors(
    bg = Color(0xFF17130F),
    surface = Color(0xFF241D17),
    blockGround = Color(0xFF120E0A),
    text = Color(0xFFEFE7DC),
    accent = Color(0xFFD9A05C),
    divider = Color(0xFFEFE7DC).copy(alpha = 0.16f),
    rowRule = Color(0xFFEFE7DC).copy(alpha = 0.08f),

    neutral100 = Color(0xFFFBF9F6),
    neutral200 = Color(0xFFEFE7DC),
    neutral300 = Color(0xFFE3DBD0),
    neutral400 = Color(0xFFC8BDAE),
    neutral500 = Color(0xFFA5988A),
    neutral600 = Color(0xFF857A6D),
    neutral700 = Color(0xFF625A50),
    neutral800 = Color(0xFF47413A),
    neutral900 = Color(0xFF2E2923),

    accent100 = Color(0xFFFBF3E6),
    accent300 = Color(0xFFF2D9B8),
    accent500 = Color(0xFFD9A05C),
    accent600 = Color(0xFFB8834A),
    accent700 = Color(0xFF8F6538),
    accent800 = Color(0xFF66482A),
    accent900 = Color(0xFF3D2C1B),
)

/**
 * The ramp, for everything the Material scheme can't carry. `static` because the palette never
 * changes at runtime — the app is dark-only by design (a commitment device that repaints itself in
 * daylight would just be another surface to fiddle with).
 */
val LocalLamp = staticCompositionLocalOf { LampDark }

/** Geometry the screens repeat. Sizes are from the handoff; CSS px map 1:1 to dp. */
object LampDimens {
    /** Screen padding, both sides, every screen. */
    val screenPadding: Dp = 16.dp

    /** Card / banner: 8 dp radius, 13 dp padding, `surface` fill, no shadow. */
    val cardRadius: Dp = 8.dp
    val cardPadding: Dp = 13.dp

    /** Bottom sheet: 14 dp top corners, 16 dp sides, 22 dp bottom, a 32 × 4 dp handle. */
    val sheetRadius: Dp = 14.dp
    val sheetBottomPadding: Dp = 22.dp
    val sheetHandleWidth: Dp = 32.dp
    val sheetHandleHeight: Dp = 4.dp

    /** The 32 dp app-identity tile with a 16 sp glyph in it. */
    val iconTile: Dp = 32.dp
    val iconTileRadius: Dp = 7.dp

    /** Toggle: 38 × 22 dp, 11 dp radius, 16 dp knob. */
    val toggleWidth: Dp = 38.dp
    val toggleHeight: Dp = 22.dp
    val toggleKnob: Dp = 16.dp

    /**
     * Stepper buttons. 44 dp is a floor, not a preference: 0 → 30 min is six taps, so a short target
     * is felt rather than merely measured.
     */
    val stepperButton: Dp = 44.dp
    val stepperValueWidth: Dp = 70.dp

    /** The used/cap number column — one cell, right-aligned, so the slash never drifts. */
    val numberColumn: Dp = 96.dp
    val cardNumberColumn: Dp = 92.dp

    /** The Apps day strip: 7 dp tall, 2 dp radius, spanning the logical day 04:00 → 04:00. */
    val dayStripHeight: Dp = 7.dp
    val dayStripRadius: Dp = 2.dp

    /** Where a section rule stops fading in / starts fading out. */
    val ruleFadeInset: Dp = 24.dp
}

/**
 * A section rule that fades at both ends — `transparent → divider → divider → transparent`. Used
 * between the major bands of a screen; inside a table the rules are solid [LampColors.rowRule],
 * because a table's rules are structure and a section's rule is punctuation.
 */
fun fadingRuleBrush(color: Color, widthPx: Float, insetPx: Float): Brush {
    if (widthPx <= 0f) return Brush.horizontalGradient(listOf(color, color))
    val inset = (insetPx / widthPx).coerceIn(0f, 0.5f)
    return Brush.horizontalGradient(
        0f to Color.Transparent,
        inset to color,
        (1f - inset) to color,
        1f to Color.Transparent,
    )
}
