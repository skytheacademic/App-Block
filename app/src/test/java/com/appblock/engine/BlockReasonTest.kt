package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

/** The overlay explains *why* it blocked — the coordinator must attach the right [BlockReason]. */
class BlockReasonTest {

    private val tiktok = "com.zhiliaoapp.musically"
    private val minute = 60_000L

    private fun budgetRule(capMinutes: Int = 1, schedule: Schedule? = null) = listOf(
        Rule(Target.TIKTOK, RuleMode.DailyBudget(capMinutes, capMinutes, 6), schedule),
    )

    @Test fun `allow carries no reason`() {
        val c = BudgetCoordinator(FakeClock(), InMemoryEngineStore(), FakeIntegrity(), RuleSource { budgetRule() })
        c.onForeground(tiktok)
        val d = c.tick()
        assertEquals(Access.ALLOW, d.access)
        assertNull(d.reason)
    }

    @Test fun `budget exhaustion reads BUDGET`() {
        val clock = FakeClock()
        val c = BudgetCoordinator(clock, InMemoryEngineStore(), FakeIntegrity(), RuleSource { budgetRule() })
        c.onForeground(tiktok)
        clock.advance(2 * minute)
        val d = c.tick()
        assertEquals(Access.BLOCK, d.access)
        assertEquals(BlockReason.BUDGET, d.reason)
    }

    @Test fun `outside allowed hours reads SCHEDULE`() {
        val clock = FakeClock(local = LocalDateTime.of(2026, 7, 20, 21, 0)) // Mon 21:00
        val rules = budgetRule(
            capMinutes = 60,
            schedule = Schedule(mapOf(DayOfWeek.MONDAY to listOf(TimeWindow(18 * 60, 20 * 60)))),
        )
        val c = BudgetCoordinator(clock, InMemoryEngineStore(), FakeIntegrity(), RuleSource { rules })
        c.onForeground(tiktok)
        val d = c.tick()
        assertEquals(Access.BLOCK, d.access)
        assertEquals(BlockReason.SCHEDULE, d.reason)
    }

    @Test fun `hard block reads HARD_BLOCK`() {
        val rules = listOf(Rule(Target.TIKTOK, RuleMode.HardBlock))
        val c = BudgetCoordinator(FakeClock(), InMemoryEngineStore(), FakeIntegrity(), RuleSource { rules })
        c.onForeground(tiktok)
        val d = c.tick()
        assertEquals(Access.BLOCK, d.access)
        assertEquals(BlockReason.HARD_BLOCK, d.reason)
    }

    @Test fun `tamper latch reads TAMPER`() {
        val clock = FakeClock()
        val integrity = FakeIntegrity(autoTime = false)
        val c = BudgetCoordinator(clock, InMemoryEngineStore(), integrity, RuleSource { budgetRule() })
        c.onForeground(tiktok)
        c.tick()                       // establishes the clock anchor
        clock.jumpWall(10 * minute)    // hand-set clock with auto time off → latch
        val d = c.tick()
        assertEquals(Access.BLOCK, d.access)
        assertEquals(BlockReason.TAMPER, d.reason)
    }

    @Test fun `coordinator passes its injected exception wait through`() {
        val clock = FakeClock()
        val store = InMemoryEngineStore()
        val c = BudgetCoordinator(
            clock, store, FakeIntegrity(), RuleSource { budgetRule(capMinutes = 30) },
            exceptionWaitMs = 5_000L,
        )
        c.requestException(Target.TIKTOK, extraMinutes = 5, windowMinutes = 10)
        clock.advance(5_000L)
        val status = c.snapshot().first { it.target == Target.TIKTOK }
        assertTrue(status.exception is ExceptionState.Active)
    }
}
