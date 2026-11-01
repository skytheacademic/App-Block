package com.appblock.engine

/**
 * The wall over Instagram's Explore grid while the Reels + Explore pool is spent (owner, 2026-10-31).
 *
 * The grid used to stay free after the cap (CONSTRAINTS §1), which left a page of reel thumbnails to
 * browse with nothing to open. Search still has to work, because finding a person is what the page is
 * for. So the wall is not a block: it covers the grid and nothing else, and the search bar above it is
 * the way past.
 *
 * What it looks like on the phone: open the magnifying-glass tab with the cap spent → a dark panel from
 * under the search bar down to the tab bar → tap search, type a name. Back out to the grid and the panel
 * is there again.
 *
 * ## Not a [DisplayOverlays] block screen
 *
 * Nothing about it may reach the block machinery: it is not coverage, it never sends Home, the occlusion
 * hold never sees it, and no minute is charged for it. The bottom tabs stay usable throughout.
 *
 * That last point is also what makes it readable. A full block screen prunes Instagram's tree, which is
 * why [OcclusionHold] exists; the wall always leaves the search bar and the tab bar showing, so Instagram
 * stays on screen and [Read.searchTabSelected] and [Read.barVisible] stay live. Only the thumbnails under
 * the wall go quiet, which is why [observe] asks for [Read.gridVisible] to put it up and never after.
 *
 * ## How it comes down
 *
 * The real search bar is uncovered, so a tap on it is Instagram's own and no tap detection exists here.
 * Instagram navigates, this class stops seeing the grid, and the wall drops.
 *
 * ⚠️ That means there is no "search was tapped" latch, on purpose: a latch would turn tap search → Back
 * into a free grid. The wall follows what is on screen and nothing else.
 *
 * On IG 452 (2026-10-31) the search page has no `search_tab`, so its scans arrive here as a null read and
 * the wall drops after [releaseMs]. [Read.typing] and the action bar leaving are the fallbacks for a
 * version that keeps the tab bar. `adb logcat -s AppBlockWall` prints each read on a QA build.
 *
 * Times are `SystemClock.elapsedRealtime`, like every other timer the user could otherwise reset.
 */
class ExploreWall(
    private val releaseMs: Long = DEFAULT_RELEASE_MS,
) {

    /** What one scan found on Instagram's Explore page. Pixels are screen coordinates. */
    data class Read(
        /** The magnifying-glass tab is the selected one. */
        val searchTabSelected: Boolean,
        /** Top of that tab, which is the top of the tab bar, so the wall's bottom edge. */
        val searchTabTop: Int?,
        /** `explore_action_bar` is on screen. */
        val barVisible: Boolean,
        /** Bottom of the action bar, so the wall's top edge. */
        val barBottom: Int?,
        /** A thumbnail of the grid is on screen, which the search page does not have. */
        val gridVisible: Boolean,
        /** A text field has focus: the search page is open and the keyboard is up. */
        val typing: Boolean,
    )

    /** The wall's edges: from [top], under the search bar, down to [bottom], the top of the tab bar. */
    data class Panel(val top: Int, val bottom: Int)

    private var panel: Panel? = null
    private var missingSince: Long? = null

    /** Whether the wall is up. */
    val up: Boolean get() = panel != null

    /**
     * The wall for this scan, or null for none. [read] is null when the scan found no tab bar, which is
     * also what a single bad read looks like, so a missing grid only counts once it has lasted
     * [releaseMs]: a blink in the tree must not flicker the wall.
     */
    fun observe(read: Read?, nowMs: Long): Panel? {
        val standing = panel
        when {
            standing == null -> panel = read?.let(::arm)
            read != null && gone(read) -> reset()
            read != null && read.barVisible -> {
                missingSince = null
                panel = Panel(read.barBottom ?: standing.top, read.searchTabTop ?: standing.bottom)
            }
            else -> {
                val since = missingSince ?: nowMs.also { missingSince = it }
                if (nowMs - since >= releaseMs) reset()
            }
        }
        return panel
    }

    /** How long until [observe] could answer differently with the same input, or null if it never will. */
    fun delayUntilNext(nowMs: Long): Long? = missingSince?.let { (it + releaseMs - nowMs).coerceAtLeast(0L) }

    /** Forget everything, so the wall only comes back on the next sighting of the grid. */
    fun reset() {
        panel = null
        missingSince = null
    }

    /** The wall for the Explore grid, if it is actually on screen with somewhere to put the wall. */
    private fun arm(read: Read): Panel? {
        val top = read.barBottom ?: return null
        val bottom = read.searchTabTop ?: return null
        val onGrid = read.searchTabSelected && read.barVisible && read.gridVisible && !read.typing
        return if (onGrid && top < bottom) Panel(top, bottom) else null
    }

    /** Definite evidence the user left: another tab, or the search page's keyboard. */
    private fun gone(read: Read): Boolean = !read.searchTabSelected || read.typing

    companion object {
        /** Long enough to outlast one churned read, short enough not to linger after leaving. */
        const val DEFAULT_RELEASE_MS = 300L
    }
}
