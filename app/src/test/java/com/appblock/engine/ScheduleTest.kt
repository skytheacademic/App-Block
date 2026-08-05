package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- nextOpening: what a schedule block counts down to on the block screen ----

    @Test fun `next opening is later the same day`() {
        val s = Schedule(mapOf(DayOfWeek.MONDAY to listOf(TimeWindow(18 * 60, 20 * 60))))
        assertEquals(LocalDateTime.of(2026, 7, 20, 18, 0), s.nextOpening(monMorning))
    }

    @Test fun `next opening skips the window already running`() {
        val s = Schedule(
            mapOf(DayOfWeek.MONDAY to listOf(TimeWindow(0, 6 * 60), TimeWindow(18 * 60, 20 * 60))),
        )
        // 05:00 sits inside the first window; the answer is the *next* one, not the one underfoot.
        assertEquals(
            LocalDateTime.of(2026, 7, 20, 18, 0),
            s.nextOpening(LocalDateTime.of(2026, 7, 20, 5, 0)),
        )
    }

    @Test fun `next opening rolls to another day when today is finished`() {
        val s = Schedule(mapOf(DayOfWeek.TUESDAY to listOf(TimeWindow(9 * 60, 11 * 60))))
        assertEquals(LocalDateTime.of(2026, 7, 21, 9, 0), s.nextOpening(monEvening))
    }

    /**
     * The eighth day earning its place. Sunday 22:00, with the only window all week at Sunday 09:00:
     * a seven-day scan gets to next Sunday's *date* only at offset 7, and stopping at 6 would return
     * null — reporting "never reopens" for a schedule that opens in eleven hours.
     */
    @Test fun `next opening finds the same weekday a week on`() {
        val s = Schedule(mapOf(DayOfWeek.SUNDAY to listOf(TimeWindow(9 * 60, 11 * 60))))
        val sun2200 = LocalDateTime.of(2026, 7, 26, 22, 0)   // 2026-07-26 is a Sunday
        assertEquals(LocalDateTime.of(2026, 8, 2, 9, 0), s.nextOpening(sun2200))
    }

    /**
     * Null is "never", not "not soon" — and it is reachable, because clearing every day is a
     * tightening and therefore saves for free. The block screen has to say so rather than count down
     * to a time that isn't coming.
     */
    @Test fun `a schedule with no windows anywhere never opens`() {
        assertNull(Schedule(emptyMap()).nextOpening(monMorning))
        assertNull(Schedule(mapOf(DayOfWeek.MONDAY to emptyList())).nextOpening(monMorning))
    }

    @Test fun `an overnight span opens at the evening half`() {
        // 22:00–02:00 is authored as two windows; from Monday afternoon the next opening is 22:00.
        val s = Schedule(
            mapOf(
                DayOfWeek.MONDAY to listOf(TimeWindow(22 * 60, TimeWindow.DAY_MINUTES)),
                DayOfWeek.TUESDAY to listOf(TimeWindow(0, 2 * 60)),
            ),
        )
        assertEquals(LocalDateTime.of(2026, 7, 20, 22, 0), s.nextOpening(monEvening))
    }
}
