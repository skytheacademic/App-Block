package com.appblock.engine

/**
 * When each display's block screen leaves on its own: the same exit as tapping Close, [dwellMs] after
 * the block screen went up, so a blocked app ends at the home screen without anyone touching anything.
 *
 * ## Why a dwell and not an instant kick
 *
 * The block screen is the one place that says *why* (budget, hours, a blocked site) and the two facts
 * every block raises: when it lifts on its own, and the way through. An instant Home would turn that into
 * an unexplained bounce, which reads as a crash rather than a decision. A few seconds is long enough to
 * read the reason line and short enough that sitting on the block screen stops being a place to stay.
 *
 * ## Why it only ever tightens
 *
 * The exit is the Close button's, unchanged: Home for an app block, the browser steered to a blank page
 * for a blocked site. Nothing here can take a block screen down early or keep a blocked app reachable —
 * if the exit fails to land (the launcher refuses, a display will not take the intent), the blocked app
 * is still on screen, the next pass covers it again, and the dwell starts over. The worst case is the
 * block screen coming back, never the app.
 *
 * ## One timer per display, keyed on coverage
 *
 * A display's dwell starts the first time it is seen covered and is forgotten the moment it is not, so
 * Close, a detach the platform did behind our back ([DisplayOverlays.noteDetached]) and the engine
 * lifting the block all reset it with no extra state. A display that stays covered while its *cause*
 * changes (one blocked site, then another) keeps its clock: the user has been looking at a block screen
 * the whole time, and that is the thing being timed.
 *
 * Times are a monotonic clock (`SystemClock.elapsedRealtime`), never the wall clock — the tamper guard
 * exists because the wall clock is the one input the user can set.
 */
class AutoExit(private val dwellMs: Long = DEFAULT_DWELL_MS) {

    private val coveredSince = LinkedHashMap<Int, Long>()

    /** Displays whose dwell is running — diagnostics. */
    val timing: Set<Int> get() = coveredSince.keys.toSet()

    /**
     * Reconcile with what is covered **now**: a newly covered display starts its dwell at [nowMs], a
     * display no longer covered forgets its own, and one still covered keeps counting.
     */
    fun observe(covered: Set<Int>, nowMs: Long) {
        coveredSince.keys.retainAll(covered)
        for (id in DisplayCensus.order(covered)) {
            if (id !in coveredSince) coveredSince[id] = nowMs
        }
    }

    /** The displays whose block screen has been up for [dwellMs] or longer, default display first. */
    fun due(nowMs: Long): List<Int> =
        DisplayCensus.order(coveredSince.filterValues { nowMs - it >= dwellMs }.keys)

    /** How long until the next display is [due], or null when nothing is timing. Never negative. */
    fun delayUntilNext(nowMs: Long): Long? =
        coveredSince.values.minOrNull()?.let { (it + dwellMs - nowMs).coerceAtLeast(0L) }

    /**
     * [displayId]'s exit has just been taken. Its dwell is dropped, so a block screen that comes straight
     * back (the exit did not land) is timed afresh instead of being exited again on the very next pass.
     */
    fun fired(displayId: Int) {
        coveredSince.remove(displayId)
    }

    companion object {
        /** Long enough to read the reason line; short enough that the block screen is not a place to stay. */
        const val DEFAULT_DWELL_MS = 3_000L

        /**
         * Whether the plain `GLOBAL_ACTION_HOME` lands on [displayId], or a HOME intent must be aimed at it
         * instead.
         *
         * The global action injects a HOME key event, which goes to the **input-focused** display. A tap on
         * Close moves focus to the display it was tapped on, so Close never needs this. An automatic exit
         * has no tap: with a block screen on the DeX monitor and the phone in the user's hand, the global
         * action would send the *phone* Home (kicking out whatever was open there) and leave the monitor's
         * blocked app where it was. With no active display known, the default display is the one HOME
         * reaches, which is today's single-display behaviour exactly.
         */
        fun globalHomeLands(displayId: Int, activeDisplayId: Int?): Boolean =
            displayId == (activeDisplayId ?: DisplayCensus.DEFAULT_DISPLAY)
    }
}
