package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class UsageTrackerTest {

    private val d1 = LocalDate.of(2026, 7, 24)
    private val d2 = LocalDate.of(2026, 7, 25)

    @Test fun `accrues within the same day`() {
        var u = UsageTracker.accrue(null, 60L, d1)
        u = UsageTracker.accrue(u, 90L, d1)
        assertEquals(150L, u.secondsUsed)
        assertEquals(d1, u.dayKey)
    }

    @Test fun `resets when the day rolls over`() {
        val prev = BudgetUsage(secondsUsed = 600L, dayKey = d1)
        val u = UsageTracker.accrue(prev, 30L, d2)
        assertEquals(30L, u.secondsUsed)
        assertEquals(d2, u.dayKey)
    }

    @Test fun `secondsUsedOn ignores a stale day`() {
        val prev = BudgetUsage(secondsUsed = 600L, dayKey = d1)
        assertEquals(0L, UsageTracker.secondsUsedOn(prev, d2))
        assertEquals(600L, UsageTracker.secondsUsedOn(prev, d1))
    }

    @Test fun `negative deltas are ignored`() {
        val u = UsageTracker.accrue(BudgetUsage(100L, d1), -50L, d1)
        assertEquals(100L, u.secondsUsed)
    }
}
