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
}
