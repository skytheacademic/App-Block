package com.appblock.engine

import java.time.LocalDate

/**
 * The temporary exception for one target: a bounded, delayed budget raise (see CONSTRAINTS.md §5).
 * Timing is monotonic (elapsedRealtime) so changing the phone clock can't shorten the wait, and every
 * state carries the logical day it was requested on: an exception raises *that day's* cap only, so it
 * can never leak onto the next day's fresh budget after the 4am reset.
 */
sealed interface ExceptionState {

    data object None : ExceptionState

    /** Requested; the 1-hour wait is running. Activates at [activeAtElapsedMs]. */
    data class Pending(
        val target: Target,
        val extraMinutes: Int,
        val windowMinutes: Int,
        val activeAtElapsedMs: Long,
        val dayKey: LocalDate,
    ) : ExceptionState

    /** Active: the target's cap is raised until [windowEndElapsedMs]. */
    data class Active(
        val target: Target,
        val extraMinutes: Int,
        val windowEndElapsedMs: Long,
        val dayKey: LocalDate,
    ) : ExceptionState
}

object ExceptionManager {

    /** The 1-hour wait before an exception activates — the entire gate (no QR). */
    const val WAIT_MS: Long = 60L * 60L * 1000L

    /** A window can be at most 1 day (and is cut short at the 4am day roll regardless). */
    const val MAX_WINDOW_MINUTES: Int = 24 * 60

    /** Start the 1-hour wait for a bounded raise. [windowMinutes] is clamped to 1 day. */
    fun request(
        target: Target,
        extraMinutes: Int,
        windowMinutes: Int,
        nowElapsedMs: Long,
        day: LocalDate,
    ): ExceptionState.Pending =
        ExceptionState.Pending(
            target = target,
            extraMinutes = extraMinutes.coerceAtLeast(0),
            windowMinutes = windowMinutes.coerceIn(1, MAX_WINDOW_MINUTES),
            activeAtElapsedMs = nowElapsedMs + WAIT_MS,
            dayKey = day,
        )

    /**
     * Advance the state machine. Pending→Active once the wait has elapsed; the window is anchored to
     * the wait's *end* ([ExceptionState.Pending.activeAtElapsedMs]), not to when we happened to look —
     * a request can't be parked overnight and cashed in later, and a late first observation can find
     * the window already over. Any state whose [dayKey] isn't [today] dies: the day it was for ended.
     */
    fun tick(state: ExceptionState, nowElapsedMs: Long, today: LocalDate): ExceptionState =
        when (state) {
            is ExceptionState.None -> state
            is ExceptionState.Pending -> when {
                state.dayKey != today -> ExceptionState.None
                nowElapsedMs >= state.activeAtElapsedMs -> {
                    val windowEnd = state.activeAtElapsedMs + state.windowMinutes * 60_000L
                    if (nowElapsedMs >= windowEnd) {
                        ExceptionState.None
                    } else {
                        ExceptionState.Active(
                            target = state.target,
                            extraMinutes = state.extraMinutes,
                            windowEndElapsedMs = windowEnd,
                            dayKey = state.dayKey,
                        )
                    }
                }
                else -> state
            }
            is ExceptionState.Active ->
                if (state.dayKey != today || nowElapsedMs >= state.windowEndElapsedMs) {
                    ExceptionState.None
                } else {
                    state
                }
        }

    /** Extra minutes currently granted for [target] (0 unless an Active exception matches it). */
    fun activeExtraMinutes(state: ExceptionState, target: Target): Int =
        if (state is ExceptionState.Active && state.target == target) state.extraMinutes else 0
}
