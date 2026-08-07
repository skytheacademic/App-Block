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
        BudgetCoordinator(clock, store, FakeIntegrity())

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

    @Test fun `instagram package alone spends nothing - the whole-app rule has no budget`() {
        val clock = FakeClock()
        val c = coordinator(clock)
        // Being in the Instagram package (feed / DMs / stories) now resolves to the whole-app target,
        // but that target is ScheduleOnly: inside its hours it neither blocks nor bills. The claim
        // this test defends is unchanged from when it read `assertNull` — **feed and DM time must
        // never cost reel minutes** — so it is asserted directly rather than via "nothing resolved".
        // 14:00, deliberately: FakeClock's default 10:00 now sits *inside* Instagram's closed
        // 06:00–11:00 span, so the default would have this test proving the schedule works rather
        // than the thing it is here for.
        clock.local = LocalDateTime.of(2026, 7, 24, 14, 0)
        c.onForeground("com.instagram.android")
        clock.advance(20 * minute)
        assertEquals(Access.ALLOW, c.tick().access)
        assertEquals(0L, c.snapshot().first { it.target == Target.INSTAGRAM_REELS_EXPLORE }.usedSeconds)
        assertTrue(c.snapshot().any { it.target == Target.INSTAGRAM_REELS_EXPLORE })
    }

    @Test fun `instagram hours block the whole app, and reels keep their own budget beside it`() {
        val clock = FakeClock()
        val c = coordinator(clock)
        // 07:00 on a Monday — inside the closed 06:00–11:00 span.
        clock.local = LocalDateTime.of(2026, 8, 10, 7, 0)
        c.onForegroundTargets(listOf(Target.INSTAGRAM_APP, Target.INSTAGRAM_REELS_EXPLORE))
        val closed = c.tick()
        assertEquals(Access.BLOCK, closed.access)
        // Attributed to the schedule, not the budget: it is the block the user cannot pay off, and
        // the block screen quotes whichever target it is handed.
        assertEquals(BlockReason.SCHEDULE, closed.reason)
        assertEquals(Target.INSTAGRAM_APP, closed.target)

        // 14:00 — open again, and the reels cap is doing its own job, undisturbed.
        clock.local = LocalDateTime.of(2026, 8, 10, 14, 0)
        c.onForegroundTargets(listOf(Target.INSTAGRAM_APP, Target.INSTAGRAM_REELS_EXPLORE))
        assertEquals(Access.ALLOW, c.tick().access)
        clock.advance(11 * minute)                       // past the 10-min reels pool
        val spent = c.tick()
        assertEquals(Access.BLOCK, spent.access)
        assertEquals(BlockReason.BUDGET, spent.reason)
        assertEquals(Target.INSTAGRAM_REELS_EXPLORE, spent.target)
    }

    @Test fun `instagram reels surface accrues and blocks at its cap`() {
        val clock = FakeClock()
        val c = coordinator(clock)
        // The accessibility layer resolves the reel player to the target and drives it directly.
        c.onForegroundTarget(Target.INSTAGRAM_REELS_EXPLORE)

        clock.advance(9 * minute)
        assertEquals(Access.ALLOW, c.tick().access)      // under the 10-min Reels+Explore pool

        clock.advance(2 * minute)                        // 11 min total > 10 min cap
        val d = c.tick()
        assertEquals(Target.INSTAGRAM_REELS_EXPLORE, d.target)
        assertEquals(Access.BLOCK, d.access)
        assertEquals(BlockReason.BUDGET, d.reason)
    }

    @Test fun `leaving the reel surface for a free instagram surface stops accrual`() {
        val clock = FakeClock()
        val store = InMemoryEngineStore()
        val c = coordinator(clock, store)
        c.onForegroundTarget(Target.INSTAGRAM_REELS_EXPLORE)
        clock.advance(3 * minute)
        c.tick()
        val used = store.loadUsage(Target.INSTAGRAM_REELS_EXPLORE)!!.secondsUsed

        // Swipe back to the feed / a DM-shared single reel: surface resolves to null → time stops.
        c.onForegroundTarget(null)
        clock.advance(5 * minute)
        assertNull(c.tick().target)
        assertEquals(used, store.loadUsage(Target.INSTAGRAM_REELS_EXPLORE)!!.secondsUsed)
    }

    @Test fun `the day roll archives the day that just ended and starts the new one at zero`() {
        val clock = FakeClock()
        val store = InMemoryEngineStore()
        val c = coordinator(clock, store)
        c.onForeground(tiktok)
        clock.advance(12 * minute)
        c.tick()
        assertEquals(12 * 60L, store.loadUsage(Target.TIKTOK)!!.secondsUsed)

        // Past 4am the next morning: a new logical day, so the counter resets.
        clock.local = LocalDateTime.of(2026, 7, 25, 10, 0)
        clock.advance(3 * minute)
        c.tick()

        assertEquals(3 * 60L, store.loadUsage(Target.TIKTOK)!!.secondsUsed)
        val archived = store.loadHistory(Target.TIKTOK).single()
        assertEquals(java.time.LocalDate.of(2026, 7, 24), archived.day)
        assertEquals(12 * 60L, archived.secondsUsed)
    }

    /** History is a chart, never an input — a full archived week must not change today's decision. */
    @Test fun `history is not consulted by any decision`() {
        val clock = FakeClock()
        val store = InMemoryEngineStore()
        store.recordHistory(Target.TIKTOK, DayUsage(java.time.LocalDate.of(2026, 7, 23), 24 * 3600L))
        val c = coordinator(clock, store)
        c.onForeground(tiktok)
        clock.advance(minute)
        assertEquals(Access.ALLOW, c.tick().access)
    }
}
