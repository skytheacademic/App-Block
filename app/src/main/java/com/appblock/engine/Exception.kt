package com.appblock.engine

/**
 * The temporary exception for one target: a bounded, delayed budget raise (see CONSTRAINTS.md §5).
 * All timing is monotonic (elapsedRealtime) so changing the phone clock can't shorten the wait.
 */
sealed interface ExceptionState {

    data object None : ExceptionState

    /** Requested; the 1-hour wait is running. Activates at [activeAtElapsedMs]. */
    data class Pending(
        val target: Target,
        val extraMinutes: Int,
        val windowMinutes: Int,
        val activeAtElapsedMs: Long,
    ) : ExceptionState

    /** Active: the target's cap is raised until [windowEndElapsedMs]. */
    data class Active(
        val target: Target,
        val extraMinutes: Int,
        val windowEndElapsedMs: Long,
    ) : ExceptionState
}

object ExceptionManager {

    /** The 1-hour wait before an exception activates — the entire gate (no QR). */
    const val WAIT_MS: Long = 60L * 60L * 1000L

    /** A window can be at most 1 day. */
    const val MAX_WINDOW_MINUTES: Int = 24 * 60

    /** Start the 1-hour wait for a bounded raise. [windowMinutes] is clamped to 1 day. */
    fun request(
        target: Target,
        extraMinutes: Int,
        windowMinutes: Int,
        nowElapsedMs: Long,
    ): ExceptionState.Pending =
        ExceptionState.Pending(
            target = target,
            extraMinutes = extraMinutes.coerceAtLeast(0),
            windowMinutes = windowMinutes.coerceIn(1, MAX_WINDOW_MINUTES),
            activeAtElapsedMs = nowElapsedMs + WAIT_MS,
        )

    /**
     * Advance the state machine using monotonic time. Pending→Active after the wait (the window then
     * starts counting); Active→None once the window ends.
     */
    fun tick(state: ExceptionState, nowElapsedMs: Long): ExceptionState =
        when (state) {
            is ExceptionState.None -> state
            is ExceptionState.Pending ->
                if (nowElapsedMs >= state.activeAtElapsedMs) {
                    ExceptionState.Active(
                        target = state.target,
                        extraMinutes = state.extraMinutes,
                        windowEndElapsedMs = nowElapsedMs + state.windowMinutes * 60_000L,
                    )
                } else {
                    state
                }
            is ExceptionState.Active ->
                if (nowElapsedMs >= state.windowEndElapsedMs) ExceptionState.None else state
        }

    /** Extra minutes currently granted for [target] (0 unless an Active exception matches it). */
    fun activeExtraMinutes(state: ExceptionState, target: Target): Int =
        if (state is ExceptionState.Active && state.target == target) state.extraMinutes else 0
}
