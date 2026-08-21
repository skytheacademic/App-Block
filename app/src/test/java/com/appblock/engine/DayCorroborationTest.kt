package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The day model on its own: pure arithmetic over an anchor and the two clocks. The end-to-end
 * consequences (what gets charged, what gets blocked) live in TamperGuardTest.
 */
class DayCorroborationTest {

    private val hour = 3_600_000L
    private val day = DayCorroboration.DAY_MS
    private val tol = 90_000L
    private val jul24 = LocalDate.of(2026, 7, 24)

    private fun anchor(day: LocalDate?, ends: Long?, boot: Int = 1) =
        ClockAnchor(wallMs = 0L, elapsedMs = 0L, bootCount = boot, zoneOffsetSeconds = 0, dayKey = day, dayEndsElapsedMs = ends)

    @Test fun `no anchor starts the model from the wall clock`() {
        val r = DayCorroboration.resolve(null, 1, 1_000L, LocalDateTime.of(2026, 7, 24, 10, 0), tol)
        assertEquals(jul24, r.today)
        assertEquals(jul24, r.modelDay)
        assertEquals(1_000L + 18 * hour, r.modelEndsElapsedMs)     // 10:00 → 04:00 is 18 h away
    }

    @Test fun `a legacy anchor without a model starts it from the wall clock too`() {
        val r = DayCorroboration.resolve(anchor(null, null), 1, 5 * hour, LocalDateTime.of(2026, 7, 24, 10, 0), tol)
        assertEquals(jul24, r.today)
        assertEquals(5 * hour + 18 * hour, r.modelEndsElapsedMs)
    }

    @Test fun `a reboot starts the model from the wall clock`() {
        val r = DayCorroboration.resolve(anchor(jul24, 18 * hour, boot = 1), 2, 1_000L, LocalDateTime.of(2026, 7, 25, 10, 0), tol)
        assertEquals(jul24.plusDays(1), r.today)
    }

    @Test fun `the wall day cannot run ahead of uptime`() {
        // Anchored at 10:00 Jul 24; the wall says Jul 25 after only an hour of uptime.
        val r = DayCorroboration.resolve(anchor(jul24, 18 * hour), 1, 1 * hour, LocalDateTime.of(2026, 7, 25, 10, 0), tol)
        assertEquals(jul24, r.today)
        assertEquals(jul24, r.modelDay)
        assertEquals(18 * hour, r.modelEndsElapsedMs)              // the model did not move
    }

    @Test fun `uptime carries the day across the reset`() {
        val r = DayCorroboration.resolve(anchor(jul24, 18 * hour), 1, 18 * hour, LocalDateTime.of(2026, 7, 25, 4, 0), tol)
        assertEquals(jul24.plusDays(1), r.today)
        assertEquals(18 * hour + day, r.modelEndsElapsedMs)
    }

    @Test fun `tolerance lets the day roll a little early but not a lot`() {
        val early = DayCorroboration.resolve(anchor(jul24, 18 * hour), 1, 18 * hour - tol + 1, LocalDateTime.of(2026, 7, 25, 4, 0), tol)
        assertEquals(jul24.plusDays(1), early.today)
        val tooEarly = DayCorroboration.resolve(anchor(jul24, 18 * hour), 1, 18 * hour - tol - 1, LocalDateTime.of(2026, 7, 25, 4, 0), tol)
        assertEquals(jul24, tooEarly.today)
    }

    @Test fun `several days of uptime advance the model by several days`() {
        val r = DayCorroboration.resolve(anchor(jul24, 18 * hour), 1, 18 * hour + 2 * day + hour, LocalDateTime.of(2026, 7, 27, 5, 0), tol)
        assertEquals(jul24.plusDays(3), r.today)
        assertEquals(18 * hour + 3 * day, r.modelEndsElapsedMs)
    }

    @Test fun `a wall clock behind the model charges the wall day and leaves the model alone`() {
        // Flew west: uptime says Jul 25, the phone (re-zoned by the OS) says Jul 24 evening.
        val r = DayCorroboration.resolve(anchor(jul24, 18 * hour), 1, 20 * hour, LocalDateTime.of(2026, 7, 24, 22, 0), tol)
        assertEquals(jul24, r.today)
        assertEquals(jul24.plusDays(1), r.modelDay)
        assertEquals(18 * hour + day, r.modelEndsElapsedMs)
    }

    @Test fun `an agreeing pass re-syncs the reset later, never sooner`() {
        // Agree on Jul 24; the wall's next reset is later than the model's (the model was built in a
        // zone further east) → take the wall's.
        val later = DayCorroboration.resolve(anchor(jul24, 18 * hour), 1, 2 * hour, LocalDateTime.of(2026, 7, 24, 6, 0), tol)
        assertEquals(2 * hour + 22 * hour, later.modelEndsElapsedMs)
        // The wall's next reset is sooner than the model's (a clock nudged forward within the day) →
        // keep the model's; an agreeing pass must not be a way to pull the reset closer.
        val sooner = DayCorroboration.resolve(anchor(jul24, 18 * hour), 1, 2 * hour, LocalDateTime.of(2026, 7, 25, 1, 0), tol)
        assertEquals(jul24, sooner.today)
        assertEquals(18 * hour, sooner.modelEndsElapsedMs)
    }
}
