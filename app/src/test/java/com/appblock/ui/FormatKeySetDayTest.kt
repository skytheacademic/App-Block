package com.appblock.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** The Lock tab's `Key set 12 Jul`: the year appears only when it is not this one. */
class FormatKeySetDayTest {

    private val saved = Locale.getDefault()

    @Before fun english() = Locale.setDefault(Locale.UK)

    @After fun restore() = Locale.setDefault(saved)

    @Test fun `this year reads as day and month`() {
        assertEquals("12 Jul", formatKeySetDay(LocalDate.of(2026, 7, 12), today = LocalDate.of(2026, 10, 1)))
    }

    @Test fun `an earlier year carries the year`() {
        assertEquals("12 Jul 2025", formatKeySetDay(LocalDate.of(2025, 7, 12), today = LocalDate.of(2026, 10, 1)))
    }
}
