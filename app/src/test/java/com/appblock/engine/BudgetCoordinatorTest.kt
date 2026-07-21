package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BudgetCoordinatorTest {

    private val tiktok = "com.zhiliaoapp.musically"
    private val minute = 60_000L

    private fun coordinator(clock: FakeClock, store: EngineStore = InMemoryEngineStore()) =
        BudgetCoordinator(clock, store)

    @Test fun `non-budgeted foreground is always allowed and untargeted`() {
        val clock = FakeClock()
        val c = coordinator(clock)
        c.onForeground("com.android.launcher")
        val d = c.tick()
        assertNull(d.target)
        assertEquals(Access.ALLOW, d.access)
    }

    @Test fun `allows under the cap and blocks once used up`() {
        val clock = FakeClock()
        val c = coordinator(clock)
        c.onForeground(tiktok)

        clock.advance(10 * minute)
        assertEquals(Access.ALLOW, c.tick().access)

        clock.advance(21 * minute) // 31 min total > 30 min cap
        val d = c.tick()
        assertEquals(Target.TIKTOK, d.target)
        assertEquals(Access.BLOCK, d.access)
    }

    @Test fun `usage freezes while the app is blocked`() {
        val clock = FakeClock()
        val store = InMemoryEngineStore()
        val c = coordinator(clock, store)
        c.onForeground(tiktok)

        clock.advance(31 * minute)
        assertEquals(Access.BLOCK, c.tick().access)
        val frozen = store.loadUsage(Target.TIKTOK)!!.secondsUsed

        // Sit on the blocked app for another half hour — the overlay is up, so nothing should accrue.
        clock.advance(30 * minute)
        assertEquals(Access.BLOCK, c.tick().access)
        assertEquals(frozen, store.loadUsage(Target.TIKTOK)!!.secondsUsed)
    }

    @Test fun `exception re-opens the app only after the 1-hour wait`() {
        val clock = FakeClock()
        val c = coordinator(clock)
        c.onForeground(tiktok)

        clock.advance(31 * minute)
        assertEquals(Access.BLOCK, c.tick().access)

        c.requestException(Target.TIKTOK, extraMinutes = 30, windowMinutes = 120)

        // 30 min into the wait: still pending → still blocked.
        clock.advance(30 * minute)
        assertEquals(Access.BLOCK, c.tick().access)

        // Finish the hour: exception activates, cap becomes 60, ~31 used → allowed again.
        clock.advance(ExceptionManager.WAIT_MS)
        val d = c.tick()
        assertEquals(Access.ALLOW, d.access)
    }

    @Test fun `snapshot reflects the raised cap while an exception is active`() {
        val clock = FakeClock()
        val c = coordinator(clock)
        c.onForeground(tiktok)
        clock.advance(31 * minute)
        c.tick()
        c.requestException(Target.TIKTOK, extraMinutes = 30, windowMinutes = 120)
        clock.advance(31 * minute + ExceptionManager.WAIT_MS)
        c.tick()

        val s = c.snapshot().first { it.target == Target.TIKTOK }
        assertEquals(30, s.normalCapMinutes)
        assertEquals(60, s.effectiveCapMinutes)
        assertTrue(s.exception is ExceptionState.Active)
    }

    @Test fun `X uses the weekend cap on Saturday`() {
        val clock = FakeClock(local = LocalDateTime.of(2026, 7, 25, 10, 0)) // Saturday
        val c = coordinator(clock)
        val s = c.snapshot().first { it.target == Target.X }
        assertEquals(20, s.effectiveCapMinutes) // 15 weekday, 20 weekend
    }

    @Test fun `instagram target is reported but not enforced yet`() {
        val clock = FakeClock()
        val c = coordinator(clock)
        // Opening the real Instagram package is a no-op: it isn't mapped, so it counts as untargeted.
        c.onForeground("com.instagram.android")
        assertNull(c.tick().target)
        // But it still appears in the status list so the UI can show it as "not enforced yet".
        assertTrue(c.snapshot().any { it.target == Target.INSTAGRAM_REELS_EXPLORE })
    }
}
