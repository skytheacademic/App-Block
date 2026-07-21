package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExceptionManagerTest {

    @Test fun `stays pending until the 1-hour wait elapses`() {
        val start = 1_000_000L
        val pending = ExceptionManager.request(Target.TIKTOK, extraMinutes = 20, windowMinutes = 120, nowElapsedMs = start)
        assertTrue(ExceptionManager.tick(pending, start + 59 * 60_000L) is ExceptionState.Pending)
        assertTrue(ExceptionManager.tick(pending, start + ExceptionManager.WAIT_MS) is ExceptionState.Active)
    }

    @Test fun `window starts only after activation`() {
        val pending = ExceptionManager.request(Target.TIKTOK, extraMinutes = 20, windowMinutes = 30, nowElapsedMs = 0L)
        val active = ExceptionManager.tick(pending, ExceptionManager.WAIT_MS) as ExceptionState.Active
        assertEquals(ExceptionManager.WAIT_MS + 30 * 60_000L, active.windowEndElapsedMs)
    }

    @Test fun `active window expires`() {
        val active = ExceptionState.Active(Target.TIKTOK, extraMinutes = 20, windowEndElapsedMs = 5_000L)
        assertTrue(ExceptionManager.tick(active, 4_999L) is ExceptionState.Active)
        assertEquals(ExceptionState.None, ExceptionManager.tick(active, 5_000L))
    }

    @Test fun `window is clamped to one day`() {
        val pending = ExceptionManager.request(Target.X, extraMinutes = 10, windowMinutes = 99_999, nowElapsedMs = 0L)
        assertEquals(ExceptionManager.MAX_WINDOW_MINUTES, pending.windowMinutes)
    }

    @Test fun `activeExtraMinutes only counts a matching active exception`() {
        val active = ExceptionState.Active(Target.TIKTOK, extraMinutes = 25, windowEndElapsedMs = Long.MAX_VALUE)
        assertEquals(25, ExceptionManager.activeExtraMinutes(active, Target.TIKTOK))
        assertEquals(0, ExceptionManager.activeExtraMinutes(active, Target.X))
        assertEquals(0, ExceptionManager.activeExtraMinutes(ExceptionState.None, Target.TIKTOK))
    }

    @Test fun `pending grants no extra minutes yet`() {
        val pending = ExceptionManager.request(Target.TIKTOK, extraMinutes = 30, windowMinutes = 60, nowElapsedMs = 0L)
        assertEquals(0, ExceptionManager.activeExtraMinutes(pending, Target.TIKTOK))
    }
}
