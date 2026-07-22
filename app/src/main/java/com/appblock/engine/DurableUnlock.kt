package com.appblock.engine

/**
 * The delayed, single-use authorization for a durable *loosening* change (CONSTRAINTS.md §6, revised
 * 2026-07-22). Flow: prove possession of the stashed key → **2-hour wait** → a **15-minute window**
 * opens (announced by a notification) → make one change → it relocks. Missing the window, or a reboot
 * during the wait, drops back to Locked and the whole cycle must be repeated.
 *
 * Timing is monotonic (elapsedRealtime) so a clock change can't shorten the wait, and every state
 * carries the boot count: elapsedRealtime resets to ~0 on reboot, so a boot-count change means the
 * stored deadlines are from a previous boot and can't be trusted → restart (the user's chosen
 * behavior, and the fail-safe one). Tightening never uses this path — it stays free.
 */
sealed interface DurableUnlockState {

    /** No change window — loosening is blocked. */
    data object Locked : DurableUnlockState

    /** The 2-hour wait is running; the window opens at [activeAtElapsedMs]. */
    data class Pending(
        val activeAtElapsedMs: Long,
        val windowEndElapsedMs: Long,
        val bootCount: Int,
    ) : DurableUnlockState

    /** The 15-minute window is open until [windowEndElapsedMs] — one change, then relock. */
    data class Open(
        val windowEndElapsedMs: Long,
        val bootCount: Int,
    ) : DurableUnlockState
}

object DurableUnlockManager {

    /** The wait before the change window opens (real build). */
    const val DEFAULT_WAIT_MS: Long = 2L * 60L * 60L * 1000L

    /** How long the change window stays open once it does (real build). */
    const val DEFAULT_WINDOW_MS: Long = 15L * 60L * 1000L

    /** Begin the wait. [waitMs]/[windowMs] are injected so the fast build can use short values. */
    fun request(
        nowElapsedMs: Long,
        bootCount: Int,
        waitMs: Long = DEFAULT_WAIT_MS,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): DurableUnlockState.Pending {
        val activeAt = nowElapsedMs + waitMs
        return DurableUnlockState.Pending(
            activeAtElapsedMs = activeAt,
            windowEndElapsedMs = activeAt + windowMs,
            bootCount = bootCount,
        )
    }

    /**
     * Advance the state machine. A boot-count change restarts (→ Locked); otherwise Pending→Open at the
     * wait's end and Open→Locked at the window's end. A late first observation can find the window
     * already over (→ Locked) — it can't be parked and cashed in later.
     */
    fun tick(state: DurableUnlockState, nowElapsedMs: Long, bootCount: Int): DurableUnlockState =
        when (state) {
            is DurableUnlockState.Locked -> state
            is DurableUnlockState.Pending -> when {
                state.bootCount != bootCount -> DurableUnlockState.Locked
                nowElapsedMs >= state.windowEndElapsedMs -> DurableUnlockState.Locked
                nowElapsedMs >= state.activeAtElapsedMs ->
                    DurableUnlockState.Open(state.windowEndElapsedMs, state.bootCount)
                else -> state
            }
            is DurableUnlockState.Open -> when {
                state.bootCount != bootCount -> DurableUnlockState.Locked
                nowElapsedMs >= state.windowEndElapsedMs -> DurableUnlockState.Locked
                else -> state
            }
        }

    /** Whether a change may be made right now. */
    fun isOpen(state: DurableUnlockState): Boolean = state is DurableUnlockState.Open

    /** The single-use close: after one accepted change, relock. */
    fun consume(): DurableUnlockState = DurableUnlockState.Locked

    /** Ms left on the 2-hour wait (0 unless Pending). */
    fun msUntilOpen(state: DurableUnlockState, nowElapsedMs: Long): Long =
        if (state is DurableUnlockState.Pending) (state.activeAtElapsedMs - nowElapsedMs).coerceAtLeast(0L) else 0L

    /** Ms left in the open window (0 unless Open). */
    fun msUntilClose(state: DurableUnlockState, nowElapsedMs: Long): Long =
        if (state is DurableUnlockState.Open) (state.windowEndElapsedMs - nowElapsedMs).coerceAtLeast(0L) else 0L
}
