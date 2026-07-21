package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PolicyEngineTest {

    private val friday = LocalDate.of(2026, 7, 24)   // weekday
    private val saturday = LocalDate.of(2026, 7, 25) // weekend
    private val x = DefaultRules.ruleFor(Target.X)!!

    @Test fun `under the cap allows`() {
        val usage = BudgetUsage(secondsUsed = 5 * 60L, dayKey = friday)
        assertEquals(Access.ALLOW, PolicyEngine.decide(x, usage, ExceptionState.None, friday))
    }

    @Test fun `at the cap blocks`() {
        val usage = BudgetUsage(secondsUsed = 15 * 60L, dayKey = friday) // X weekday = 15 min
        assertEquals(Access.BLOCK, PolicyEngine.decide(x, usage, ExceptionState.None, friday))
    }

    @Test fun `weekend gives X the larger cap`() {
        val usage = BudgetUsage(secondsUsed = 17 * 60L, dayKey = saturday) // 17 < 20 weekend cap
        assertEquals(Access.ALLOW, PolicyEngine.decide(x, usage, ExceptionState.None, saturday))
    }

    @Test fun `usage from an earlier day does not count`() {
        val staleUsage = BudgetUsage(secondsUsed = 999 * 60L, dayKey = LocalDate.of(2026, 7, 1))
        assertEquals(Access.ALLOW, PolicyEngine.decide(x, staleUsage, ExceptionState.None, friday))
    }

    @Test fun `active exception raises the cap`() {
        val usage = BudgetUsage(secondsUsed = 25 * 60L, dayKey = friday) // over normal 15
        val exc = ExceptionState.Active(Target.X, extraMinutes = 20, windowEndElapsedMs = Long.MAX_VALUE, dayKey = friday)
        // effective cap = min(15 + 20, 40) = 35 → 25 < 35 → allow
        assertEquals(Access.ALLOW, PolicyEngine.decide(x, usage, exc, friday))
    }

    @Test fun `exception cannot exceed the per-app max`() {
        val usage = BudgetUsage(secondsUsed = 41 * 60L, dayKey = friday)
        val exc = ExceptionState.Active(Target.X, extraMinutes = 999, windowEndElapsedMs = Long.MAX_VALUE, dayKey = friday)
        // effective cap capped at 40 → 41 >= 40 → block
        assertEquals(Access.BLOCK, PolicyEngine.decide(x, usage, exc, friday))
    }

    @Test fun `an exception for another app is ignored`() {
        val usage = BudgetUsage(secondsUsed = 15 * 60L, dayKey = friday)
        val excForTikTok = ExceptionState.Active(Target.TIKTOK, extraMinutes = 30, windowEndElapsedMs = Long.MAX_VALUE, dayKey = friday)
        assertEquals(Access.BLOCK, PolicyEngine.decide(x, usage, excForTikTok, friday))
    }

    @Test fun `hard block always blocks`() {
        val rule = Rule(Target.TIKTOK, RuleMode.HardBlock)
        assertEquals(Access.BLOCK, PolicyEngine.decide(rule, null, ExceptionState.None, friday))
    }

    @Test fun `effective cap math`() {
        val mode = RuleMode.DailyBudget(weekdayMinutes = 15, weekendMinutes = 20, exceptionMaxMinutes = 40)
        assertEquals(15, PolicyEngine.effectiveCapMinutes(mode, DayType.WEEKDAY, 0))
        assertEquals(35, PolicyEngine.effectiveCapMinutes(mode, DayType.WEEKDAY, 20))
        assertEquals(40, PolicyEngine.effectiveCapMinutes(mode, DayType.WEEKDAY, 100))
        assertEquals(20, PolicyEngine.effectiveCapMinutes(mode, DayType.WEEKEND, 0))
    }
}
