package com.appblock.engine

/**
 * The v1.1 rule set from CONSTRAINTS.md. On-device, editing these is a "durable change" (requires the
 * stashed QR or the computer) — see CONSTRAINTS.md §6.
 */
object DefaultRules {

    /**
     * Instagram's closing hours (user's call, 2026-08-06): **shut 06:00–11:00 on weekdays and
     * 06:00–10:00 at weekends**, open the rest of the day.
     *
     * Authored as *allowed* windows because that is what [Schedule] stores, so a closed span becomes
     * the two open spans either side of it. Windows never wrap midnight by construction
     * ([TimeWindow]), and 00:00–06:00 / 11:00–24:00 already respect that, so no day needs splitting.
     *
     * ⚠️ **This deliberately closes DMs, feed and stories too**, which CONSTRAINTS §1 otherwise keeps
     * always-free. That exemption is about the *cap* — pricing feed time against the reels budget —
     * and it still holds: nothing here counts a minute. Closing hours are a different axis, and the
     * user asked for them app-wide precisely so the morning can't be spent in DMs instead.
     */
    val MORNINGS_CLOSED: Schedule = Schedule(
        java.time.DayOfWeek.entries.associateWith { day ->
            val reopen = if (day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY) 10 * 60 else 11 * 60
            listOf(TimeWindow(0, 6 * 60), TimeWindow(reopen, TimeWindow.DAY_MINUTES))
        },
    )

    val rules: List<Rule> = listOf(
        Rule(Target.TIKTOK, RuleMode.DailyBudget(weekdayMinutes = 30, weekendMinutes = 30, exceptionMaxMinutes = 60)),
        Rule(Target.INSTAGRAM_APP, RuleMode.ScheduleOnly, MORNINGS_CLOSED),
        Rule(Target.INSTAGRAM_REELS_EXPLORE, RuleMode.DailyBudget(weekdayMinutes = 10, weekendMinutes = 10, exceptionMaxMinutes = 30)),
        Rule(Target.X, RuleMode.DailyBudget(weekdayMinutes = 15, weekendMinutes = 20, exceptionMaxMinutes = 40)),
    )

    /**
     * Targets seeded **present but switched off** — listed on the Apps tab, not enforced.
     *
     * TikTok, 2026-08-05, the user's call: they don't want a TikTok time block. Its caps stay in
     * [rules] so the row keeps sensible numbers the moment it is switched back on, which is a
     * *tightening* and therefore free — see [DurableSettings.from] for why "off" beats deleting the
     * rule outright.
     *
     * ⚠️ **Applies to [rules] only, deliberately.** [fastRules] is the QA variant whose entire job is
     * verifying that a block fires in ~90 s; seeding its TikTok off would quietly retire the fastest
     * on-device block test in the project.
     */
    val seededOff: Set<Target> = setOf(Target.TIKTOK)

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
