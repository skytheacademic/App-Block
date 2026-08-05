package com.appblock.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Defines when a "day" starts. A day begins at [DEFAULT_RESET_HOUR] (local) and runs ~24h, so 02:00
 * belongs to the *previous* calendar date's logical day and 05:00 to the current one. Both the daily
 * budget reset and the weekday/weekend choice key off this shifted day — so late-night scrolling
 * counts against the day you were already in, and a Friday 2am still counts as a weekday. See
 * CONSTRAINTS.md §3.
 */
object DayBoundary {

    /** A "day" runs 04:00 → 03:59:59 the next morning. */
    const val DEFAULT_RESET_HOUR = 4

    fun logicalDay(now: LocalDateTime, resetHour: Int = DEFAULT_RESET_HOUR): LocalDate =
        if (now.hour < resetHour) now.toLocalDate().minusDays(1) else now.toLocalDate()

    fun dayType(logicalDay: LocalDate): DayType =
        when (logicalDay.dayOfWeek) {
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> DayType.WEEKEND
            else -> DayType.WEEKDAY
        }

    /**
     * Seconds from [now] until the next reset — what the block screen counts down when a budget is
     * spent ("day resets · 04:00 · in 8 h 48 m").
     *
     * Lives here rather than beside the formatters because it is the same fact as [DEFAULT_RESET_HOUR]
     * asked from the other end, and a second copy that read the hour independently is how a screen
     * ends up counting down to a boundary the engine doesn't use.
     *
     * Counted in **seconds**, not in whole minutes less the seconds hand. The obvious form —
     * `minutesUntil * 60 - now.second` — goes negative for the first 59 seconds after the boundary
     * (04:00:30 reads as −30), and a countdown that briefly runs backwards on the one screen the user
     * meets when they have already lost the argument is exactly the wrong place to be sloppy.
     */
    fun secondsUntilReset(now: LocalDateTime, resetHour: Int = DEFAULT_RESET_HOUR): Long {
        val secondOfDay = now.hour * 3600L + now.minute * 60L + now.second
        return Math.floorMod(resetHour * 3600L - secondOfDay, DAY_SECONDS)
    }

    private const val DAY_SECONDS = 24L * 60L * 60L
}
