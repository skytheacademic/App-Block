package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExceptionManagerTest {

    private val friday = LocalDate.of(2026, 7, 24)
    private val saturday = LocalDate.of(2026, 7, 25)

    @Test fun `stays pending until the 1-hour wait elapses`() {
        val start = 1_000_000L
        val pending = ExceptionManager.request(Target.TIKTOK, extraMinutes = 20, windowMinutes = 120, nowElapsedMs = start, day = friday)
        assertTrue(ExceptionManager.tick(pending, start + 59 * 60_000L, friday) is ExceptionState.Pending)
        assertTrue(ExceptionManager.tick(pending, start + ExceptionManager.WAIT_MS, friday) is ExceptionState.Active)
    }

    @Test fun `window is anchored to the end of the wait`() {
        val pending = ExceptionManager.request(Target.TIKTOK, extraMinutes = 20, windowMinutes = 30, nowElapsedMs = 0L, day = friday)
        val active = ExceptionManager.tick(pending, ExceptionManager.WAIT_MS, friday) as ExceptionState.Active
        assertEquals(ExceptionManager.WAIT_MS + 30 * 60_000L, active.windowEndElapsedMs)
    }

    @Test fun `late observation cannot park the window for later`() {
        val pending = ExceptionManager.request(Target.TIKTOK, extraMinutes = 20, windowMinutes = 30, nowElapsedMs = 0L, day = friday)
        // First looked at 10 min after activation: window still ends WAIT+30min, not now+30min.
        val active = ExceptionManager.tick(pending, ExceptionManager.WAIT_MS + 10 * 60_000L, friday) as ExceptionState.Active
        assertEquals(ExceptionManager.WAIT_MS + 30 * 60_000L, active.windowEndElapsedMs)
        // First looked at only after the whole window would have passed: it's simply gone.
        assertEquals(
            ExceptionState.None,
            ExceptionManager.tick(pending, ExceptionManager.WAIT_MS + 31 * 60_000L, friday),
        )
    }

    @Test fun `active window expires`() {
        val active = ExceptionState.Active(Target.TIKTOK, extraMinutes = 20, windowEndElapsedMs = 5_000L, dayKey = friday)
        assertTrue(ExceptionManager.tick(active, 4_999L, friday) is ExceptionState.Active)
        assertEquals(ExceptionState.None, ExceptionManager.tick(active, 5_000L, friday))
    }

    @Test fun `pending and active die at the day rollover`() {
        val pending = ExceptionManager.request(Target.TIKTOK, extraMinutes = 20, windowMinutes = 120, nowElapsedMs = 0L, day = friday)
        assertEquals(ExceptionState.None, ExceptionManager.tick(pending, 1_000L, saturday))
        val active = ExceptionState.Active(Target.TIKTOK, extraMinutes = 20, windowEndElapsedMs = Long.MAX_VALUE, dayKey = friday)
        assertEquals(ExceptionState.None, ExceptionManager.tick(active, 1_000L, saturday))
    }

    @Test fun `window is clamped to one day`() {
        val pending = ExceptionManager.request(Target.X, extraMinutes = 10, windowMinutes = 99_999, nowElapsedMs = 0L, day = friday)
        assertEquals(ExceptionManager.MAX_WINDOW_MINUTES, pending.windowMinutes)
    }

    @Test fun `activeExtraMinutes only counts a matching active exception`() {
        val active = ExceptionState.Active(Target.TIKTOK, extraMinutes = 25, windowEndElapsedMs = Long.MAX_VALUE, dayKey = friday)
        assertEquals(25, ExceptionManager.activeExtraMinutes(active, Target.TIKTOK))
        assertEquals(0, ExceptionManager.activeExtraMinutes(active, Target.X))
        assertEquals(0, ExceptionManager.activeExtraMinutes(ExceptionState.None, Target.TIKTOK))
    }

    @Test fun `pending grants no extra minutes yet`() {
        val pending = ExceptionManager.request(Target.TIKTOK, extraMinutes = 30, windowMinutes = 60, nowElapsedMs = 0L, day = friday)
        assertEquals(0, ExceptionManager.activeExtraMinutes(pending, Target.TIKTOK))
    }
}
