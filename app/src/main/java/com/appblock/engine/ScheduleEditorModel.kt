package com.appblock.engine

import java.time.DayOfWeek

/**
 * One authored schedule row: "these days, this From→To range". [endMin] ≤ [startMin] means the range
 * wraps past midnight — 22:00→02:00 is authored as one gesture and [ScheduleEditorModel.toSchedule]
 * compiles it to two engine windows (evening + next-day morning), because [TimeWindow] is
 * deliberately non-wrapping. Pure Kotlin (no Compose/Android) so the mapping is JVM-testable.
 */
data class WindowRule(val days: Set<DayOfWeek>, val startMin: Int, val endMin: Int) {
    val overnight: Boolean get() = endMin <= startMin
}

/** The schedule editor's authoring model ↔ the engine's per-day [Schedule]. */
object ScheduleEditorModel {

    private const val DAY = TimeWindow.DAY_MINUTES

    /**
     * Compile rules → the engine's per-day windows. An overnight rule splits at midnight and its
     * morning part lands on the *next* day (Sunday 23:00→01:00 spills into Monday). A rule with no
     * days contributes nothing.
     */
    fun toSchedule(rules: List<WindowRule>): Schedule {
        val byDay = linkedMapOf<DayOfWeek, MutableList<TimeWindow>>()
        for (rule in rules) for (day in rule.days) {
            if (!rule.overnight) {
                byDay.getOrPut(day) { mutableListOf() } += TimeWindow(rule.startMin, rule.endMin)
            } else {
                if (rule.startMin < DAY) {
                    byDay.getOrPut(day) { mutableListOf() } += TimeWindow(rule.startMin, DAY)
                }
                if (rule.endMin > 0) {
                    byDay.getOrPut(day.plus(1)) { mutableListOf() } += TimeWindow(0, rule.endMin)
                }
            }
        }
        return Schedule(byDay)
    }

    /**
     * Best-effort inverse for display: a window ending at 24:00 pairs with the next day's window
     * starting at 00:00 into one overnight rule, then identical ranges group across days into one
     * rule. Round-trips anything [toSchedule] produced. Hand-built schedules degrade gracefully to
     * one rule per distinct range — and an intentional "MON 22–24" + "TUE 00–02" pair displays as
     * the (semantically identical) overnight rule.
     */
    fun decompose(schedule: Schedule): List<WindowRule> {
        data class Piece(val day: DayOfWeek, val start: Int, val end: Int)
        val pieces = schedule.allowedByDay
            .flatMap { (day, windows) -> windows.map { Piece(day, it.startMinuteOfDay, it.endMinuteOfDay) } }
            .toMutableList()

        // 1. Pair (s..24:00) with the next day's (00:00..e) → one overnight piece on the evening day.
        val overnights = mutableListOf<Piece>()
        for (evening in pieces.filter { it.end == DAY && it.start > 0 }.sortedBy { it.day }) {
            val morning = pieces.firstOrNull { it.day == evening.day.plus(1) && it.start == 0 && it.end < DAY }
                ?: continue
            pieces.remove(evening)
            pieces.remove(morning)
            overnights += Piece(evening.day, evening.start, morning.end)
        }

        val rules = mutableListOf<WindowRule>()
        for ((range, group) in overnights.groupBy { it.start to it.end }) {
            rules += WindowRule(group.map { it.day }.toSet(), range.first, range.second)
        }
        // 2. Remaining plain windows, grouped by identical range. An until-midnight end (24:00)
        //    becomes the wrapped 0 so the clock stepper can represent it.
        for ((range, group) in pieces.groupBy { it.start to (if (it.end == DAY) 0 else it.end) }) {
            rules += WindowRule(group.map { it.day }.toSet(), range.first, range.second)
        }
        return rules.sortedWith(compareBy({ it.startMin }, { it.endMin }))
    }

    /**
     * A clock stepper step that wraps around midnight — 23:30 + 30 → 00:00, and stepping To past
     * midnight IS the one-gesture overnight authoring. Skips [skip] so From and To never land on
     * the same minute (which would read as an empty/24-hour window).
     */
    fun stepClock(value: Int, deltaMin: Int, skip: Int): Int {
        var v = Math.floorMod(value + deltaMin, DAY)
        if (v == skip) v = Math.floorMod(v + deltaMin, DAY)
        return v
    }
}
