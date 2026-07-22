package com.appblock.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class ScheduleTest {

    // 2026-07-20 is a Monday; -21 Tuesday.
    private val monMorning = LocalDateTime.of(2026, 7, 20, 9, 0)
    private val monEvening = LocalDateTime.of(2026, 7, 20, 18, 30)
    private val tueEvening = LocalDateTime.of(2026, 7, 21, 18, 30)

    @Test fun `time window is half-open`() {
        val w = TimeWindow(18 * 60, 20 * 60)
        assertTrue(w.contains(18 * 60))
        assertTrue(w.contains(19 * 60 + 59))
        assertFalse(w.contains(20 * 60))       // end exclusive
        assertFalse(w.contains(18 * 60 - 1))
    }

    @Test fun `all-day window covers the whole day`() {
        assertTrue(TimeWindow.ALL_DAY.contains(0))
        assertTrue(TimeWindow.ALL_DAY.contains(TimeWindow.DAY_MINUTES - 1))
    }

    @Test fun `allows only within a day's windows`() {
        val s = Schedule(mapOf(DayOfWeek.MONDAY to listOf(TimeWindow(18 * 60, 20 * 60))))
        assertTrue(s.allows(monEvening))
        assertFalse(s.allows(monMorning))
        assertFalse(s.allows(tueEvening))       // no Tuesday entry → blocked all Tuesday
    }

    @Test fun `a day with no windows is blocked all day`() {
        val s = Schedule(mapOf(DayOfWeek.MONDAY to emptyList()))
        assertFalse(s.allows(monEvening))
    }

    @Test fun `multiple windows in a day`() {
        val s = Schedule(mapOf(DayOfWeek.MONDAY to listOf(TimeWindow(0, 6 * 60), TimeWindow(18 * 60, 20 * 60))))
        assertTrue(s.allows(LocalDateTime.of(2026, 7, 20, 5, 0)))   // early window
        assertFalse(s.allows(monMorning))                            // 09:00 gap
        assertTrue(s.allows(monEvening))                             // evening window
    }

    @Test fun `no schedule means always allowed`() {
        assertTrue(PolicyEngine.scheduleAllows(null, monMorning))
        assertTrue(PolicyEngine.scheduleAllows(null, tueEvening))
    }
}
