package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

/** The schedule gate composes with the budget in the live coordinator: open only if in-window AND under cap. */
class ScheduleCoordinatorTest {

    private val tiktok = "com.zhiliaoapp.musically"
    private val minute = 60_000L

    // TikTok allowed only Monday 18:00–20:00, with a generous budget so the schedule is the gate.
    private fun scheduledRules(): List<Rule> = listOf(
        Rule(
            Target.TIKTOK,
            RuleMode.DailyBudget(weekdayMinutes = 60, weekendMinutes = 60, exceptionMaxMinutes = 90),
            Schedule(mapOf(DayOfWeek.MONDAY to listOf(TimeWindow(18 * 60, 20 * 60)))),
        ),
    )

    @Test fun `blocked outside the window, allowed inside`() {
        val clock = FakeClock(local = LocalDateTime.of(2026, 7, 20, 21, 0)) // Mon 21:00 — outside
        val c = BudgetCoordinator(clock, InMemoryEngineStore(), FakeIntegrity(), RuleSource { scheduledRules() })
        c.onForeground(tiktok)
        assertEquals(Access.BLOCK, c.tick().access)

        clock.local = LocalDateTime.of(2026, 7, 20, 19, 0) // Mon 19:00 — inside
        assertEquals(Access.ALLOW, c.tick().access)
    }

    @Test fun `snapshot flags the schedule block`() {
        val clock = FakeClock(local = LocalDateTime.of(2026, 7, 20, 21, 0))
        val c = BudgetCoordinator(clock, InMemoryEngineStore(), FakeIntegrity(), RuleSource { scheduledRules() })
        val s = c.snapshot().first { it.target == Target.TIKTOK }
        assertEquals(Access.BLOCK, s.access)
        assertTrue(s.blockedBySchedule)
    }

    @Test fun `budget still blocks inside the window`() {
        val clock = FakeClock(local = LocalDateTime.of(2026, 7, 20, 18, 0)) // Monday, all-day window below
        val c = BudgetCoordinator(clock, InMemoryEngineStore(), FakeIntegrity(), RuleSource {
            listOf(
                Rule(
                    Target.TIKTOK,
                    RuleMode.DailyBudget(1, 1, 6),
                    Schedule(mapOf(DayOfWeek.MONDAY to listOf(TimeWindow.ALL_DAY))),
                ),
            )
        })
        c.onForeground(tiktok)
        clock.advance(2 * minute) // over the 1-min cap (local stays Monday → still in-window)
        val s = c.snapshot().first { it.target == Target.TIKTOK }
        assertEquals(Access.BLOCK, c.tick().access)
        assertFalse(s.blockedBySchedule) // blocked by budget, not the schedule
    }
}
