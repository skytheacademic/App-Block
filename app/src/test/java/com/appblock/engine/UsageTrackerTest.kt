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

    @Test fun `archive keeps days in order, oldest first`() {
        var h = UsageTracker.archive(emptyList(), DayUsage(d2, 120L))
        h = UsageTracker.archive(h, DayUsage(d1, 60L))
        assertEquals(listOf(d1, d2), h.map { it.day })
    }

    /** Two coordinators share one store, so the same rollover can be seen — and archived — twice. */
    @Test fun `archiving the same day again replaces it rather than duplicating`() {
        var h = UsageTracker.archive(emptyList(), DayUsage(d1, 60L))
        h = UsageTracker.archive(h, DayUsage(d1, 90L))
        assertEquals(1, h.size)
        assertEquals(90L, h.single().secondsUsed)
    }

    @Test fun `archive keeps only the last seven days`() {
        var h = emptyList<DayUsage>()
        for (i in 0 until 10) h = UsageTracker.archive(h, DayUsage(d1.plusDays(i.toLong()), i * 60L))
        assertEquals(UsageTracker.HISTORY_DAYS, h.size)
        assertEquals(d1.plusDays(3), h.first().day)
        assertEquals(d1.plusDays(9), h.last().day)
    }
}
