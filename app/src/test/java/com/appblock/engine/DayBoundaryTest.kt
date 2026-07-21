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
}
