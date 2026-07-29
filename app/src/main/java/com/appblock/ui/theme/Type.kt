package com.appblock.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.appblock.R

/**
 * Inter, weights 400 and 500 only — **never bolder than 500**. Hierarchy in this design comes from
 * size and space, not weight; a 600 anywhere makes the screen shout, which is the opposite of what a
 * blocker should do when it tells you a budget is spent.
 *
 * Only two static instances are vendored (`res/font/inter_regular.ttf`, `inter_medium.ttf`), so a
 * stray `FontWeight.Bold` synthesises rather than loading a heavier face. That's deliberate: the
 * synthetic result looks wrong enough to catch in review.
 */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
)

/**
 * Tabular figures. Every duration, cap and count on screen is a number that ticks — `18:04` becomes
 * `18:03` a second later — and proportional digits make the whole row twitch as it does. `tnum` is
 * an Inter feature, so this is a one-line fix rather than a monospace fallback.
 *
 * Applied via `TextStyle.tabular()` at each call site rather than baked into the theme: prose must
 * *not* get tabular figures (they look mechanical mid-sentence), and the split between the two is a
 * per-string decision.
 */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * The roles from the handoff, named for what they are on screen rather than for a Material slot.
 * Screens read these directly; [AppBlockTypography] only exists to stop stray Material components
 * (dialogs, sheets, the odd `Text` with no style) rendering in Roboto.
 */
object LampType {

    /** Today's remaining time. Letter-spacing −0.03em, line-height 1.0 — it must read as one block. */
    val hero = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 46.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.03).em(46f),
        fontFeatureSettings = "tnum",
    )

    /** The unlock countdown — the same block, larger, because it is the only thing on that screen. */
    val heroLarge = hero.copy(fontSize = 52.sp, lineHeight = 52.sp, letterSpacing = (-0.03).em(52f))

    /** "Today", "Apps", "Sites", "Lock". */
    val screenTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 19.sp)

    /** Bottom-sheet titles, "Locked". */
    val sectionTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 17.sp)

    /** App names, domains, list rows. */
    val rowTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.5.sp)

    /** The slightly larger row title used inside cards and sheets. */
    val rowTitleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp)

    /** `used / cap`. Tabular, always. */
    val number = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        fontFeatureSettings = "tnum",
    )

    /** The same cell inside an app card, which is narrower. */
    val numberSmall = number.copy(fontSize = 13.sp)

    /** Explanatory sentences. */
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.5.sp)

    /** Sub-lines and hints. */
    val meta = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 11.5.sp)
    val metaSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 11.sp)

    /** Row sub-lines, notes. */
    val micro = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 10.5.sp)

    /** "target", "used / cap", "loosening costs" — uppercase, tracked out, neutral-600. */
    val sectionLabel = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 9.5.sp,
        letterSpacing = 0.1.em(9.5f),
    )

    /** "Apps window · open" — the one place accent carries type. */
    val kicker = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.14.em(10f),
    )

    /** The pending-change diff and the key code. Monospace so a diff lines up and a code is countable. */
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        lineHeight = 20.7.sp,
    )

    /** The key code on the setup screen: bigger, tracked out, six groups of four. */
    val monoCode = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        letterSpacing = 0.05.em(14.5f),
    )

    /** The hour labels under the day strip, and the day letters under the week strip. */
    val tick = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 9.sp)
    val dayLetter = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 9.5.sp)

    /** Buttons and the tab bar's labels. */
    val button = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    val buttonSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.5.sp)
    val tabLabel = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 10.sp)
}

/** CSS `em` letter-spacing against a known font size, since Compose wants it in sp. */
private fun Double.em(fontSizePx: Float) = (this * fontSizePx).sp

/**
 * Material's own slots, so any component we don't draw ourselves still lands in Inter at a sane size.
 * Deliberately thin — the screens use [LampType], not this.
 */
val AppBlockTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        displayMedium = displayMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        displaySmall = displaySmall.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        headlineLarge = headlineLarge.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        headlineMedium = headlineMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        headlineSmall = headlineSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        titleLarge = titleLarge.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        titleMedium = titleMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = Inter, fontWeight = FontWeight.Normal),
        bodyMedium = bodyMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Normal),
        bodySmall = bodySmall.copy(fontFamily = Inter, fontWeight = FontWeight.Normal),
        labelLarge = labelLarge.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
    )
}
