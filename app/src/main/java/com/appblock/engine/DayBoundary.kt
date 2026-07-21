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
}
