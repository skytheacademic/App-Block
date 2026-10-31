package com.appblock.engine

/**
 * The wall over Instagram's Explore grid while the Reels + Explore pool is spent (owner, 2026-10-31).
 *
 * The grid used to stay free after the cap (CONSTRAINTS §1), which left a page of reel thumbnails to
 * browse with nothing to open. Search still has to work, because finding a person is what the page is
 * for. So the wall is not a block: it covers the grid for good and the search bar for the first
 * [countdownMs], and tapping search is how you get past it.
 *
 * What it looks like on the phone: open the magnifying-glass tab with the cap spent → a dark panel down
 * to the tab bar counting 5, 4, 3, 2, 1 → the panel shrinks to below the search bar → tap search, type
 * a name. Back out to the grid and the wall starts again from 5.
 *
 * ## Not a [DisplayOverlays] block screen
 *
 * Nothing about it may reach the block machinery: it is not coverage, it never sends Home, the occlusion
 * hold never sees it, and no minute is charged for it. The bottom tabs stay usable throughout.
 *
 * That last point is also what makes it readable. A full block screen prunes Instagram's tree, which is
 * why [OcclusionHold] exists; the wall always leaves the tab bar showing, so Instagram stays on screen
 * and [Read.searchTabSelected] stays live. Only the nodes under the wall go quiet, which is why [holds]
 * does not ask for the search bar during the countdown.
 *
 * ## How it comes down
 *
 * The real search bar is uncovered, so a tap on it is Instagram's own and no tap detection exists here.
 * Instagram navigates, this class stops seeing the grid, and the wall drops.
 *
 * ⚠️ That means there is no "search was tapped" latch, on purpose: a latch would turn tap search → Back
 * into a free grid. The wall follows what is on screen and nothing else.
 *
 * ⚠️ **Unverified on hardware:** no capture of Instagram's search page exists. [Read.typing] (a focused
 * text field) is the expected signal for it and the action bar disappearing is the second one. If
 * neither holds, the wall comes back over the search page. `adb logcat -s AppBlockWall` prints each read.
 *
 * Times are `SystemClock.elapsedRealtime`, like every other timer the user could otherwise reset.
 */
class ExploreWall(
    private val countdownMs: Long = DEFAULT_COUNTDOWN_MS,
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
        /** Bottom of the action bar, so the wall's top edge once the countdown ends. */
        val barBottom: Int?,
        /** A thumbnail of the grid is on screen, which the search page does not have. */
        val gridVisible: Boolean,
        /** A text field has focus: the search page is open and the keyboard is up. */
        val typing: Boolean,
    )

    sealed interface Phase {
        data object None : Phase

        /** Covering from the top of the screen down to [bottom]. [secondsLeft] runs 5 → 1. */
        data class Counting(val secondsLeft: Int, val bottom: Int) : Phase

        /** Covering from [top], under the search bar, down to [bottom], until search is tapped. */
        data class Open(val top: Int, val bottom: Int) : Phase
    }

    private var startedAt: Long? = null
    private var missingSince: Long? = null
    private var top = 0
    private var bottom = 0

    /** Whether the wall is up. */
    val up: Boolean get() = startedAt != null

    /**
     * The wall for this scan. [read] is null when the scan found no tab bar, which is also what a single
     * bad read looks like, so a missing grid only counts once it has lasted [releaseMs]: a blink in the
     * tree must not restart the countdown.
     */
    fun observe(read: Read?, nowMs: Long): Phase {
        when {
            startedAt == null -> if (read != null && arms(read)) start(read, nowMs)
            read != null && gone(read) -> reset()
            read != null && holds(read, phaseAt(nowMs)) -> {
                missingSince = null
                read.searchTabTop?.let { bottom = it }
                read.barBottom?.takeIf { read.barVisible }?.let { top = it }
            }
            else -> {
                val since = missingSince ?: nowMs.also { missingSince = it }
                if (nowMs - since >= releaseMs) reset()
            }
        }
        return phaseAt(nowMs)
    }

    /** How long until [observe] could answer differently with the same input, or null if it never will. */
    fun delayUntilNext(nowMs: Long): Long? {
        val started = startedAt ?: return null
        val elapsed = nowMs - started
        val nextSecond = if (elapsed < countdownMs) SECOND_MS - elapsed % SECOND_MS else null
        val release = missingSince?.let { (it + releaseMs - nowMs).coerceAtLeast(0L) }
        return listOfNotNull(nextSecond, release).minOrNull()
    }

    /** Forget everything, so the next sighting of the grid starts from the top of the countdown. */
    fun reset() {
        startedAt = null
        missingSince = null
    }

    private fun start(read: Read, nowMs: Long) {
        startedAt = nowMs
        missingSince = null
        top = read.barBottom ?: 0
        bottom = read.searchTabTop ?: 0
    }

    private fun phaseAt(nowMs: Long): Phase {
        val started = startedAt ?: return Phase.None
        val left = countdownMs - (nowMs - started)
        if (left <= 0) return Phase.Open(top, bottom)
        return Phase.Counting(((left + SECOND_MS - 1) / SECOND_MS).toInt(), bottom)
    }

    /** The Explore grid, actually on screen, with somewhere to put the wall. */
    private fun arms(read: Read): Boolean {
        val top = read.barBottom ?: return false
        val bottom = read.searchTabTop ?: return false
        return read.searchTabSelected && read.barVisible && read.gridVisible && !read.typing && top < bottom
    }

    /** Definite evidence the user left: another tab, or the search page's keyboard. */
    private fun gone(read: Read): Boolean = !read.searchTabSelected || read.typing

    /**
     * Still on the grid. During the countdown the bar is under the wall and may read as not visible;
     * once it is uncovered it has to be there.
     */
    private fun holds(read: Read, phase: Phase): Boolean = phase is Phase.Counting || read.barVisible

    companion object {
        const val DEFAULT_COUNTDOWN_MS = 5_000L

        /** Long enough to outlast one churned read, short enough not to linger after leaving. */
        const val DEFAULT_RELEASE_MS = 300L

        private const val SECOND_MS = 1_000L
    }
}
