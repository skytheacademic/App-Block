package com.appblock.engine

/**
 * The v1.1 rule set from CONSTRAINTS.md. On-device, editing these is a "durable change" (requires the
 * stashed QR or the computer) — see CONSTRAINTS.md §6.
 */
object DefaultRules {

    val rules: List<Rule> = listOf(
        Rule(Target.TIKTOK, RuleMode.DailyBudget(weekdayMinutes = 30, weekendMinutes = 30, exceptionMaxMinutes = 60)),
        Rule(Target.INSTAGRAM_REELS_EXPLORE, RuleMode.DailyBudget(weekdayMinutes = 10, weekendMinutes = 10, exceptionMaxMinutes = 30)),
        Rule(Target.X, RuleMode.DailyBudget(weekdayMinutes = 15, weekendMinutes = 20, exceptionMaxMinutes = 40)),
    )

    /**
     * Throwaway QA rule set with 1-minute caps, used only by the `debugFast` build variant so the
     * block can be verified in ~90s. Same shape as [rules]; exception ceilings kept ≥5 above the cap
     * so the "Request more time" stepper behaves normally. NOT for real use.
     */
    val fastRules: List<Rule> = listOf(
        Rule(Target.TIKTOK, RuleMode.DailyBudget(weekdayMinutes = 1, weekendMinutes = 1, exceptionMaxMinutes = 6)),
        Rule(Target.INSTAGRAM_REELS_EXPLORE, RuleMode.DailyBudget(weekdayMinutes = 1, weekendMinutes = 1, exceptionMaxMinutes = 6)),
        Rule(Target.X, RuleMode.DailyBudget(weekdayMinutes = 1, weekendMinutes = 2, exceptionMaxMinutes = 6)),
    )

    fun ruleFor(target: Target): Rule? = rules.firstOrNull { it.target == target }
}
