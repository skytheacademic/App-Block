package com.appblock.engine

/**
 * A tile over Instagram's Reels tab while the Reels + Explore pool is closed (owner, 2026-11-13).
 *
 * The full Reels block stays, and stays the enforcement: this only takes away the one-tap way in. It also
 * ends a trap: a Reels block sends you Home, Instagram reopens on the Reels tab, and every launch bounced
 * until Instagram was force-stopped. With the tab covered, the trip there never starts.
 *
 * What it looks like on the phone: pool spent → open Instagram → a dark tile over the Reels icon → tap it
 * and nothing happens. Home, Messages, Search and Profile work as always.
 *
 * ## Not a block
 *
 * Like [ExploreWall]: no coverage, no Home, no occlusion hold, no minute charged. If it fails to appear
 * (Instagram renamed `clips_tab`, a tap faster than the first scan), a tap on the tab reaches the Reels
 * tab and meets the full block, so nothing about it can loosen anything.
 *
 * ## Which reads it trusts
 *
 *  - **The Reels tab's bounds, not its visibility.** Once the tile covers it, accessibility may report the
 *    tab as not visible to the user, and a cover that read that as "gone" would take itself down.
 *  - **Another tab, visible and in the same row,** as the proof that the bar is on screen. The DM thread,
 *    stories and a reel opened from Explore have no tab bar in the tree at all.
 *  - **A visible bottom sheet across the row** takes it down at once: comments on a feed post keep the
 *    tab bar in the tree under the sheet, and the tile would sit on the comment box.
 *  - **The Reels tab selected** takes it down at once: the full block owns that page.
 *
 * Anything short of that (a null read, the other tabs not visible) only counts once it has lasted
 * [releaseMs], so a blink in the tree does not flicker the tile.
 *
 * Times are `SystemClock.elapsedRealtime`.
 */
class ReelsTabCover(private val releaseMs: Long = DEFAULT_RELEASE_MS) {

    /** A rectangle in screen pixels, as accessibility reports it. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val valid: Boolean get() = left < right && top < bottom

        /** Shares some of [other]'s rows. */
        fun sameRow(other: Box): Boolean = top < other.bottom && bottom > other.top
    }

    /** What one scan found of Instagram's tab bar. */
    data class Read(
        /** `clips_tab`'s bounds, whether or not it reads as visible. Null if it was not in the tree. */
        val reelsTab: Box?,
        /** The Reels tab is the selected one. */
        val reelsTabSelected: Boolean,
        /** Bounds of one of the other tabs that is visible to the user, or null if none is. */
        val visibleTab: Box?,
        /** Bounds of a visible `bottom_sheet_container`, or null. */
        val sheet: Box?,
    )

    private var tile: Box? = null
    private var missingSince: Long? = null

    /** Whether the tile is up. */
    val up: Boolean get() = tile != null

    /** The tile for this scan, or null for none. [read] is null when the scan found no tab bar at all. */
    fun observe(read: Read?, nowMs: Long): Box? {
        if (read != null) {
            if (read.reelsTabSelected) return none()
            val bar = read.visibleTab
            if (bar != null) {
                val spot = read.reelsTab?.takeIf { it.valid && it.sameRow(bar) }
                    ?: tile?.takeIf { it.sameRow(bar) }
                if (spot != null) {
                    if (read.sheet?.sameRow(spot) == true) return none()
                    missingSince = null
                    tile = spot
                    return spot
                }
            }
        }
        val standing = tile ?: return null
        val since = missingSince ?: nowMs.also { missingSince = it }
        if (nowMs - since >= releaseMs) return none()
        return standing
    }

    /** How long until [observe] could answer differently with the same input, or null if it never will. */
    fun delayUntilNext(nowMs: Long): Long? =
        missingSince?.let { (it + releaseMs - nowMs).coerceAtLeast(0L) }

    /** Forget everything, so the tile only comes back on the next sighting of the tab bar. */
    fun reset() {
        tile = null
        missingSince = null
    }

    private fun none(): Box? {
        reset()
        return null
    }

    companion object {
        /** Same as [ExploreWall.DEFAULT_RELEASE_MS]: outlasts one churned read, short enough not to linger. */
        const val DEFAULT_RELEASE_MS = 300L
    }
}
