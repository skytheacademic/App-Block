package com.appblock.engine

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * A single allowed time-of-day window: [startMinuteOfDay, endMinuteOfDay) in local minutes past
 * midnight (0..1440). Non-wrapping — start < end, within one calendar day. An overnight span like
 * 22:00–02:00 is authored as two windows (22:00–24:00 on the evening day, 00:00–02:00 the next),
 * which keeps the engine free of cross-midnight edge cases.
 */
data class TimeWindow(val startMinuteOfDay: Int, val endMinuteOfDay: Int) {

    fun contains(minuteOfDay: Int): Boolean = minuteOfDay in startMinuteOfDay until endMinuteOfDay

    companion object {
        const val DAY_MINUTES: Int = 24 * 60
        val ALL_DAY: TimeWindow = TimeWindow(0, DAY_MINUTES)
    }
}

/**
 * A weekly access schedule: the allowed windows for each weekday, in local wall-clock time. A target
 * carrying a schedule is open only during one of that day's windows; a day with no windows is blocked
 * all day. A target with **no** schedule (null on the [Rule]) is unconstrained by time — the
 * budget-only behavior. The two compose: an app is open only if it's inside an allowed window AND
 * under its daily cap.
 *
 * Time-of-day reads straight off the wall clock — the real day-of-week, not the 4am budget day — so a
 * schedule and a budget can disagree about "which day" near midnight. That's intentional: a schedule
 * is about clock time. The wall clock is protected by the same tamper guard as the budgets
 * ([BudgetCoordinator.guardClocks]): with automatic time off, a manual change latches → everything
 * blocks, so the clock can't be nudged to slip into an allowed window.
 */
data class Schedule(val allowedByDay: Map<DayOfWeek, List<TimeWindow>>) {

    fun allows(now: LocalDateTime): Boolean {
        val minuteOfDay = now.hour * 60 + now.minute
        return allowedByDay[now.dayOfWeek].orEmpty().any { it.contains(minuteOfDay) }
    }

    /**
     * When this schedule next opens, at the first window start strictly after [now] — the moment a
     * schedule block counts down to ("reopens · 18:00 · in 3 h 12 m").
     *
     * **Null means never, and null is not a rounding of "not soon".** A schedule with no windows on
     * any day blocks its target permanently, and the caller has to say so rather than print a
     * countdown to a time that isn't coming. That state is reachable: the editor lets every day be
     * cleared, and doing so is a *tightening*, so it saves for free.
     *
     * Only sensible when the schedule is currently closed — inside a window the answer is the *next*
     * opening, not this one — which is the only way it is called. That falls out of the strict `>`:
     * a window already running has a start in the past and is skipped.
     *
     * Eight days, not seven: today is scanned twice, once for whatever is left of it and once as the
     * same weekday a week on. Seven would drop the case where the only window all week is earlier
     * today — Sunday 09:00 asked at Sunday 22:00 — and return null, i.e. report "never" for a
     * schedule that opens in eleven hours.
     */
    fun nextOpening(now: LocalDateTime): LocalDateTime? {
        val midnight = now.toLocalDate().atStartOfDay()
        val minuteOfDay = now.hour * 60 + now.minute
        for (dayOffset in 0..7) {
            val day = midnight.plusDays(dayOffset.toLong())
            val start = allowedByDay[day.dayOfWeek].orEmpty()
                .map { it.startMinuteOfDay }
                .filter { dayOffset > 0 || it > minuteOfDay }
                .minOrNull() ?: continue
            return day.plusMinutes(start.toLong())
        }
        return null
    }
}
