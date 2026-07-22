package com.appblock.engine

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The allow/block decision for the target on screen. Pure function of the rule, today's usage, and any
 * active exception — no Android, no side effects → fully unit-testable (Phase 2a).
 */
object PolicyEngine {

    /**
     * Whether the target's schedule permits access at [now] (true if it has no schedule). The
     * time-of-day gate that composes with the budget: a target is open only if this is true *and* it's
     * under its cap. Kept separate so the coordinator can apply it for both the live decision and the
     * UI snapshot without duplicating the window math.
     */
    fun scheduleAllows(schedule: Schedule?, now: LocalDateTime): Boolean =
        schedule == null || schedule.allows(now)

    /** Normal cap + exception extra, never above the per-app ceiling. */
    fun effectiveCapMinutes(mode: RuleMode.DailyBudget, dayType: DayType, activeExtraMinutes: Int): Int {
        val normal = mode.normalMinutes(dayType)
        return (normal + activeExtraMinutes.coerceAtLeast(0)).coerceAtMost(mode.exceptionMaxMinutes)
    }

    fun decide(
        rule: Rule,
        usage: BudgetUsage?,
        exception: ExceptionState,
        logicalDay: LocalDate,
    ): Access =
        when (val mode = rule.mode) {
            is RuleMode.HardBlock -> Access.BLOCK
            is RuleMode.DailyBudget -> {
                val dayType = DayBoundary.dayType(logicalDay)
                val extra = ExceptionManager.activeExtraMinutes(exception, rule.target)
                val capSeconds = effectiveCapMinutes(mode, dayType, extra) * 60L
                val used = UsageTracker.secondsUsedOn(usage, logicalDay)
                if (used >= capSeconds) Access.BLOCK else Access.ALLOW
            }
        }
}
