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

    fun ruleFor(target: Target): Rule? = rules.firstOrNull { it.target == target }
}
