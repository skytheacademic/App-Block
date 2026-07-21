package com.appblock.engine

import java.time.LocalDate

/** Foreground seconds used on a target during a specific logical day. */
data class BudgetUsage(val secondsUsed: Long, val dayKey: LocalDate)

/**
 * Accrues foreground time per target and resets when the logical day rolls over. Pure: feed it the
 * foreground seconds since the last update, tagged with the current logical day; get updated usage.
 */
object UsageTracker {

    /** Add [foregroundSeconds] on [today], auto-resetting the counter when the logical day changes. */
    fun accrue(prev: BudgetUsage?, foregroundSeconds: Long, today: LocalDate): BudgetUsage {
        val base = if (prev != null && prev.dayKey == today) prev.secondsUsed else 0L
        return BudgetUsage(base + foregroundSeconds.coerceAtLeast(0L), today)
    }

    /** Seconds used on [today] (0 if the stored usage is from an earlier logical day). */
    fun secondsUsedOn(prev: BudgetUsage?, today: LocalDate): Long =
        if (prev != null && prev.dayKey == today) prev.secondsUsed else 0L
}
