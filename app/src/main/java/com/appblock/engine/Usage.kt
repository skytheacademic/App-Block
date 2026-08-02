package com.appblock.engine

import java.time.LocalDate

/** Foreground seconds used on a target during a specific logical day. */
data class BudgetUsage(val secondsUsed: Long, val dayKey: LocalDate)

/**
 * A *completed* logical day, archived when the counter rolls over.
 *
 * Read-only history: nothing in the engine ever decides anything from it. It exists so the UI can
 * draw the last week, and it is deliberately kept out of every policy path — a blocker that starts
 * consulting your past is a blocker with a second, unaudited source of truth about your time.
 */
data class DayUsage(val day: LocalDate, val secondsUsed: Long)

/**
 * Accrues foreground time per target and resets when the logical day rolls over. Pure: feed it the
 * foreground seconds since the last update, tagged with the current logical day; get updated usage.
 */
object UsageTracker {

    /** How many completed days the rolling history keeps — one week's strip on Today. */
    const val HISTORY_DAYS: Int = 7

    /** Add [foregroundSeconds] on [today], auto-resetting the counter when the logical day changes. */
    fun accrue(prev: BudgetUsage?, foregroundSeconds: Long, today: LocalDate): BudgetUsage {
        val base = if (prev != null && prev.dayKey == today) prev.secondsUsed else 0L
        return BudgetUsage(base + foregroundSeconds.coerceAtLeast(0L), today)
    }

    /** Seconds used on [today] (0 if the stored usage is from an earlier logical day). */
    fun secondsUsedOn(prev: BudgetUsage?, today: LocalDate): Long =
        if (prev != null && prev.dayKey == today) prev.secondsUsed else 0L

    /**
     * Fold a completed day into [history]: newest last, one entry per day, at most [HISTORY_DAYS].
     *
     * Re-archiving a day already present *replaces* it rather than appending, so an archive that runs
     * twice (two coordinators over one store, a process restart mid-rollover) can't put the same day
     * on the strip twice or push a real day off the end.
     */
    fun archive(history: List<DayUsage>, completed: DayUsage): List<DayUsage> =
        (history.filterNot { it.day == completed.day } + completed)
            .sortedBy { it.day }
            .takeLast(HISTORY_DAYS)
}
