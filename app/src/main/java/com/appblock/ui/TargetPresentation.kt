package com.appblock.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.graphics.drawable.toBitmap
import com.appblock.R
import com.appblock.data.InstalledApps
import com.appblock.engine.Target

/**
 * How a [Target] is named and marked on screen.
 *
 * The glyphs are deliberately **neutral, not brand marks** — a blocker that reproduces the logo of
 * the thing it blocks spends its whole surface advertising it. TikTok is a music note, Instagram
 * Reels & Explore a film strip, X a speech bubble. An app the user added on-device is the exception:
 * it wears its own launcher icon, because there is no curated mark for it and its real icon is how
 * the user recognises it in the picker they just came from.
 */

/** The full name, for cards, sheets and pickers. */
@Composable
fun labelFor(target: Target): String = when (target) {
    Target.TIKTOK -> "TikTok"
    // Named for its scope, because it sits directly above the Reels row and the two limits are
    // genuinely different animals: this one closes the whole app by the clock, that one meters a
    // surface. A bare "Instagram" on both would read as one rule listed twice.
    Target.INSTAGRAM_APP -> "Instagram (whole app)"
    Target.INSTAGRAM_REELS_EXPLORE -> "Instagram Reels & Explore"
    Target.X -> "X (Twitter)"
    else -> {
        val context = LocalContext.current
        remember(target) { target.userPackage?.let { InstalledApps.labelFor(context, it) } ?: target.key }
    }
}

/**
 * The short name, for the Today table and the exception banner. "Instagram Reels & Explore" is the
 * honest name of what is limited, but at 13.5 sp beside a chip and a number cell it wraps; the table
 * has the scope note nowhere to put, so the Apps card carries the long form and this carries the
 * short one.
 */
@Composable
fun shortLabelFor(target: Target): String = when (target) {
    Target.TIKTOK -> "TikTok"
    // "Instagram hours", not "Instagram": the Today table lists both rows, and two cells reading
    // "Instagram" would be indistinguishable in the one place they appear side by side.
    Target.INSTAGRAM_APP -> "Instagram hours"
    Target.INSTAGRAM_REELS_EXPLORE -> "Instagram"
    Target.X -> "X"
    else -> labelFor(target)
}

/** The neutral identity glyph, or the app's own launcher icon for a user-added target. */
@Composable
fun iconFor(target: Target): Painter {
    val fallback = painterResource(builtInIcon(target))
    val pkg = target.userPackage ?: return fallback
    val context = LocalContext.current
    return remember(pkg) { launcherIcon(context, pkg) } ?: fallback
}

private fun builtInIcon(target: Target): Int = when (target) {
    Target.TIKTOK -> R.drawable.ic_ph_music_notes
    // A clock, not a film strip: this row is closing hours, and the glyph is the fastest way to see
    // at a glance which of the two Instagram rows is which.
    Target.INSTAGRAM_APP -> R.drawable.ic_ph_clock
    Target.INSTAGRAM_REELS_EXPLORE -> R.drawable.ic_ph_film_strip
    Target.X -> R.drawable.ic_ph_chat_circle
    else -> R.drawable.ic_ph_squares_four
}

/**
 * The package's launcher icon as a painter. Rasterised once and remembered — adaptive icons are
 * layered drawables, and re-rendering one every second under the Today tick would be pure waste.
 * Any failure (uninstalled since it was blocked, an icon pack that throws) falls back to the
 * generic glyph rather than leaving a hole in the row.
 */
private fun launcherIcon(context: Context, packageName: String): Painter? = runCatching {
    val drawable = context.packageManager.getApplicationIcon(packageName)
    BitmapPainter(drawable.toBitmap(width = ICON_PX, height = ICON_PX).asImageBitmap())
}.getOrNull()

/** 32 dp tile at ~4× density: enough for the S25's screen without holding a full-size bitmap. */
private const val ICON_PX = 128

/**
 * What the card would otherwise imply wrongly. Instagram is enforced by *surface*, not by package —
 * without this the card reads as though the whole app is capped (CONSTRAINTS.md §1).
 */
fun scopeNoteRes(target: Target): Int? = when (target) {
    Target.INSTAGRAM_APP -> R.string.scope_note_instagram_app
    Target.INSTAGRAM_REELS_EXPLORE -> R.string.scope_note_instagram
    else -> null
}
