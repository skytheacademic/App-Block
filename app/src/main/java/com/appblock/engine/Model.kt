package com.appblock.engine

/**
 * A blockable target — a whole app or a specific in-app surface. [key] is a stable id the engine and
 * storage use; the mapping to a real package / on-screen detection signal lives in the Android layer
 * (Phase 2b). See CONSTRAINTS.md §1.
 */
enum class Target(val key: String) {
    TIKTOK("tiktok"),
    INSTAGRAM_REELS_EXPLORE("ig_reels_explore"),
    X("x"),
}

/** Which kind of day a logical day is — X's cap differs on weekends. */
enum class DayType { WEEKDAY, WEEKEND }

/** The allow/block outcome for whatever surface is on screen. */
enum class Access { ALLOW, BLOCK }

/** How a target is limited. */
sealed interface RuleMode {

    /** Always blocked (no budget). */
    data object HardBlock : RuleMode

    /**
     * Allowed up to a daily cap that differs weekday/weekend, and can be temporarily raised by an
     * exception up to [exceptionMaxMinutes] — the hard ceiling even an exception can't exceed.
     */
    data class DailyBudget(
        val weekdayMinutes: Int,
        val weekendMinutes: Int,
        val exceptionMaxMinutes: Int,
    ) : RuleMode {
        fun normalMinutes(dayType: DayType): Int =
            if (dayType == DayType.WEEKEND) weekendMinutes else weekdayMinutes
    }
}

/**
 * A target, how it's limited ([mode]), and optionally *when* it's allowed ([schedule]). A null
 * schedule means no time-of-day restriction (budget-only). Budget and schedule compose: the target is
 * open only if it's inside an allowed window AND under its cap.
 */
data class Rule(val target: Target, val mode: RuleMode, val schedule: Schedule? = null)
