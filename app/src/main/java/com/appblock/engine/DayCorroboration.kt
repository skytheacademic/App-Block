package com.appblock.engine

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The logical day the engine charges, corroborated by uptime: **within one boot, a day can't
 * advance faster than the monotonic clock says it could have.**
 *
 * The wall clock is the only source that knows *which* day it is, but it is also the one input the
 * user can move — and with both automatic toggles on it moves without a latch (a zone the OS picked
 * from a mock location, a date change laundered through toggle-off / change / toggle-on between two
 * passes). So the anchor carries a second, monotonic **model** of the day: the day the last pass
 * charged and the `elapsedRealtime` reading at which that day's 04:00 reset falls. Between passes
 * the model advances only as far as uptime has actually run; the day that gets charged is
 * `min(wall day, model day)`.
 *
 * What this costs when the wall clock is honest:
 *  - Nothing on an ordinary day: the model's reset and the wall's coincide, and the model is
 *    re-derived from the wall on every pass where the two agree (moving the reset *later* only —
 *    never sooner — so an agreeing pass can't be used to pull the next reset closer).
 *  - Flying east (or a DST spring-forward): local time jumps ahead, the model doesn't, and the day
 *    rolls when uptime reaches the old zone's 04:00 — up to the zone delta late, once, and the
 *    schedule then stays on that offset until the next reboot re-syncs it (a reboot starts the
 *    model from the wall clock, which is the accepted reboot-class escape). Strict side, bounded.
 *  - Flying west: local time falls behind the model; the wall day is charged (the regression re-key
 *    keeps the larger count), and the model re-syncs the moment the two agree again.
 *
 * Across a boot-count change the model is rebuilt from the wall clock — a reboot with the clock
 * untrusted latches separately (see BudgetCoordinator.guardClocks), and a reboot with it trusted is
 * the OS vouching for the date. Pure, so the arithmetic is tested on its own.
 */
object DayCorroboration {

    const val DAY_MS: Long = 24L * 60L * 60L * 1000L

    /**
     * @property today the day to charge.
     * @property modelDay the day the monotonic model stands on (never earlier than [today]).
     * @property modelEndsElapsedMs the uptime reading at which [modelDay] ends.
     */
    data class Resolution(val today: LocalDate, val modelDay: LocalDate, val modelEndsElapsedMs: Long)

    /**
     * [toleranceMs] lets the day roll that much *before* the model says it should — the same slack
     * the drift check allows, so an NTP nudge of a few seconds around 04:00 can't hold the day back.
     */
    fun resolve(
        anchor: ClockAnchor?,
        bootCount: Int,
        nowElapsedMs: Long,
        nowLocal: LocalDateTime,
        toleranceMs: Long,
    ): Resolution {
        val wallDay = DayBoundary.logicalDay(nowLocal)
        val wallEnds = nowElapsedMs + DayBoundary.secondsUntilReset(nowLocal) * 1000L
        val modelDay = anchor?.dayKey
        val modelEnds = anchor?.dayEndsElapsedMs
        if (anchor == null || anchor.bootCount != bootCount || modelDay == null || modelEnds == null) {
            // No model to corroborate against: a reboot, or an anchor from before the model existed.
            return Resolution(wallDay, wallDay, wallEnds)
        }
        var day = modelDay
        var ends = modelEnds
        if (nowElapsedMs >= ends - toleranceMs) {
            val extraDays = (nowElapsedMs - ends + toleranceMs) / DAY_MS
            day = day.plusDays(1 + extraDays)
            ends += (1 + extraDays) * DAY_MS
        }
        return when {
            wallDay > day -> Resolution(day, day, ends)               // wall ahead of uptime: capped
            wallDay < day -> Resolution(wallDay, day, ends)           // wall behind: charge the wall day
            else -> Resolution(day, day, maxOf(ends, wallEnds))        // agree: re-sync, later only
        }
    }
}
