package com.appblock.engine

/**
 * When each display's block screen sends the user Home by itself: **as soon as it is up**, and again
 * every [retryMs] for as long as it stays up.
 *
 * ## The order this serves
 *
 * Cover, then leave, then explain. The full block screen still goes up the instant a blocked thing is
 * on screen, because it is the only cover that lands in a frame or two — the app never shows and never
 * takes a tap. Home is then sent **with the block screen still up**, which is the same thing as the user
 * pressing Home on it: the launcher comes forward behind the overlay, its window event releases the
 * occlusion hold, and the next pass takes the overlay down. The reason follows on the home screen as a
 * small card the service shows at the moment of the exit.
 *
 * That replaced a 3-second dwell on the block screen, which is how 0.11.0 was first written and never
 * released. Owner, 2026-10-10: bounce to the home screen and then show the reason.
 *
 * ## Why a retry, and why not every pass
 *
 * The block screen is **meant** to come down within a frame or two of the exit. If it is still up
 * [retryMs] later, the exit did not land — the launcher refused, a DeX display would not take the HOME
 * intent, a browser came back in front of the launcher after being steered — so it is sent again. Not on
 * every pass: a pass runs on every window event, and Home's own transition fires a burst of them, so
 * "exit whenever covered" would press Home a dozen times per block and re-show the card each time.
 *
 * Nothing here can take a block screen down or keep a blocked app reachable. If the exit never lands,
 * the block screen stays up, with its own Close button, which is where this project was before.
 *
 * ## One record per display, keyed on coverage
 *
 * A display is remembered from the first time it is seen covered until the first time it is not, so a
 * block screen coming down by any route (the exit landing, Close, a platform detach, the engine lifting
 * the block) forgets it, and the next block screen there is a new one that exits straight away.
 *
 * Times are a monotonic clock (`SystemClock.elapsedRealtime`), never the wall clock — the tamper guard
 * exists because the wall clock is the one input the user can set.
 */
class AutoExit(private val retryMs: Long = DEFAULT_RETRY_MS) {

    /** Covered displays → when their exit was last taken, or null while it has not been taken yet. */
    private val exitedAt = LinkedHashMap<Int, Long?>()

    /** Covered displays this class is tracking — diagnostics. */
    val tracking: Set<Int> get() = exitedAt.keys.toSet()

    /**
     * Reconcile with what is covered **now**: a newly covered display is due at once, a display no
     * longer covered is forgotten, and one still covered keeps its record.
     */
    fun observe(covered: Set<Int>, nowMs: Long) {
        exitedAt.keys.retainAll(covered)
        for (id in DisplayCensus.order(covered)) {
            if (id !in exitedAt) exitedAt[id] = null
        }
    }

    /**
     * The displays to exit now, default display first: every block screen not yet exited, and every one
     * still up [retryMs] after its last exit.
     */
    fun due(nowMs: Long): List<Int> =
        DisplayCensus.order(exitedAt.filterValues { it == null || nowMs - it >= retryMs }.keys)

    /** How long until the next display is [due], or null when nothing is covered. Never negative. */
    fun delayUntilNext(nowMs: Long): Long? =
        exitedAt.values.minOfOrNull { at -> if (at == null) 0L else (at + retryMs - nowMs).coerceAtLeast(0L) }

    /** [displayId]'s exit was just taken at [nowMs]; it is not due again until [retryMs] later. */
    fun fired(displayId: Int, nowMs: Long) {
        if (displayId in exitedAt) exitedAt[displayId] = nowMs
    }

    companion object {
        /**
         * How long a block screen may stay up after its exit before the exit is sent again. The exit
         * normally clears it in a few hundred ms; this only matters when it did not land.
         */
        const val DEFAULT_RETRY_MS = 3_000L

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
