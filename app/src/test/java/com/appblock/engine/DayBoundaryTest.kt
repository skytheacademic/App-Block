package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DayBoundaryTest {

    @Test fun `before 4am belongs to the previous day`() {
        val at2am = LocalDateTime.of(2026, 7, 21, 2, 0)
        assertEquals(LocalDate.of(2026, 7, 20), DayBoundary.logicalDay(at2am))
    }

    @Test fun `at 4am starts the new day`() {
        val at4am = LocalDateTime.of(2026, 7, 21, 4, 0)
        assertEquals(LocalDate.of(2026, 7, 21), DayBoundary.logicalDay(at4am))
    }

    @Test fun `just before 4am is still the previous day`() {
        val at359 = LocalDateTime.of(2026, 7, 21, 3, 59, 59)
        assertEquals(LocalDate.of(2026, 7, 20), DayBoundary.logicalDay(at359))
    }

    @Test fun `saturday 2am counts as friday, a weekday`() {
        // 2026-07-25 is a Saturday; 2am belongs to Friday's logical day (2026-07-24).
        val sat2am = LocalDateTime.of(2026, 7, 25, 2, 0)
        val day = DayBoundary.logicalDay(sat2am)
        assertEquals(LocalDate.of(2026, 7, 24), day)
        assertEquals(DayType.WEEKDAY, DayBoundary.dayType(day))
    }

    @Test fun `saturday 10am is a weekend`() {
        val sat10am = LocalDateTime.of(2026, 7, 25, 10, 0)
        assertEquals(DayType.WEEKEND, DayBoundary.dayType(DayBoundary.logicalDay(sat10am)))
    }

    @Test fun `the reset countdown crosses midnight`() {
        // 19:12 → 04:00 is the block screen's own example, 8 h 48 m.
        val at1912 = LocalDateTime.of(2026, 7, 20, 19, 12)
        assertEquals((8 * 60 + 48) * 60L, DayBoundary.secondsUntilReset(at1912))
    }

    @Test fun `the reset countdown counts within the same morning`() {
        val at0130 = LocalDateTime.of(2026, 7, 21, 1, 30)
        assertEquals((2 * 60 + 30) * 60L, DayBoundary.secondsUntilReset(at0130))
    }

    /**
     * The regression the seconds-resolution form exists for.
     *
     * `minutesUntil * 60 - now.second` — the obvious way to write this, and the way the unused copy in
     * `ui/Format.kt` was written before it moved here — returns **−30** at 04:00:30, because the whole
     * minutes to the boundary are zero and the seconds hand is then subtracted from nothing. A block
     * screen would render that as a countdown running backwards for the first minute of every day.
     */
    @Test fun `the reset countdown never goes negative just after the boundary`() {
        val justAfter = LocalDateTime.of(2026, 7, 21, 4, 0, 30)
        assertEquals(24 * 60 * 60L - 30, DayBoundary.secondsUntilReset(justAfter))
    }

    @Test fun `the reset countdown is zero exactly on the boundary`() {
        assertEquals(0L, DayBoundary.secondsUntilReset(LocalDateTime.of(2026, 7, 21, 4, 0, 0)))
    }
}
