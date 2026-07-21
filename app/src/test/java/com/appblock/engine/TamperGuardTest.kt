package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The audit's headline bypass was "change the phone date → fresh budget" — these tests decouple the
 * wall and monotonic clocks (FakeClock.jumpWall) to prove the guard latches, blocks, clamps, and
 * recovers. They exercise BudgetCoordinator.guardClocks end-to-end through tick()/snapshot().
 */
class TamperGuardTest {

    private val tiktok = "com.zhiliaoapp.musically"
    private val minute = 60_000L
    private val day = 86_400_000L

    private fun setup(autoTime: Boolean): Setup {
        val clock = FakeClock()
        val store = InMemoryEngineStore()
        val integrity = FakeIntegrity(autoTime = autoTime)
        val c = BudgetCoordinator(clock, store, integrity)
        return Setup(clock, store, integrity, c)
    }

    private data class Setup(
        val clock: FakeClock,
        val store: InMemoryEngineStore,
        val integrity: FakeIntegrity,
        val c: BudgetCoordinator,
    )

    @Test fun `forward date jump with manual time latches and blocks`() {
        val s = setup(autoTime = false)
        s.c.onForeground(tiktok)
        assertEquals(Access.ALLOW, s.c.tick().access)   // primes the anchor

        s.clock.jumpWall(day)                            // "set the date to tomorrow"
        val d = s.c.tick()
        assertEquals(Access.BLOCK, d.access)
        assertNotNull(s.c.tamperReason())
    }

    @Test fun `backward clock jump with manual time latches too`() {
        val s = setup(autoTime = false)
        s.c.onForeground(tiktok)
        s.c.tick()

        s.clock.jumpWall(-2 * 60 * minute)               // wind the clock back 2 hours
        assertEquals(Access.BLOCK, s.c.tick().access)
        assertNotNull(s.c.tamperReason())
    }

    @Test fun `small drift within tolerance does not latch`() {
        val s = setup(autoTime = false)
        s.c.onForeground(tiktok)
        s.c.tick()
        s.clock.advance(10 * minute)
        s.clock.jumpWall(30_000L)                        // 30s NTP-ish nudge < 90s tolerance
        assertEquals(Access.ALLOW, s.c.tick().access)
        assertNull(s.c.tamperReason())
    }

    @Test fun `turning automatic time back on clears the latch`() {
        val s = setup(autoTime = false)
        s.c.onForeground(tiktok)
        s.c.tick()
        s.clock.jumpWall(day)
        assertEquals(Access.BLOCK, s.c.tick().access)

        s.integrity.autoTime = true                      // user re-enables "set time automatically"
        s.clock.jumpWall(-day)                           // OS snaps the clock back to the real time
        assertEquals(Access.ALLOW, s.c.tick().access)    // low usage → allowed again
        assertNull(s.c.tamperReason())
    }

    @Test fun `date rollback cannot re-grant a spent day`() {
        val s = setup(autoTime = true)                   // even a trusted rollback (timezone) clamps
        s.c.onForeground(tiktok)
        s.clock.advance(31 * minute)                     // spend past the 30-min cap
        assertEquals(Access.BLOCK, s.c.tick().access)
        val spent = s.store.loadUsage(Target.TIKTOK)!!.secondsUsed

        s.clock.jumpWall(-day)                           // stored dayKey is now "ahead of today"
        assertEquals(Access.BLOCK, s.c.tick().access)    // count re-keyed onto today, still spent
        val usage = s.store.loadUsage(Target.TIKTOK)!!
        assertEquals(spent, usage.secondsUsed)
        assertEquals(LocalDate.of(2026, 7, 23), usage.dayKey)
    }

    @Test fun `reboot drops an in-flight exception`() {
        val s = setup(autoTime = true)
        s.c.onForeground(tiktok)
        s.clock.advance(31 * minute)
        s.c.tick()
        s.c.requestException(Target.TIKTOK, extraMinutes = 30, windowMinutes = 120)
        s.clock.advance(ExceptionManager.WAIT_MS)
        assertTrue(s.store.loadException(Target.TIKTOK) is ExceptionState.Pending)

        s.integrity.boot = 2                             // reboot: monotonic clock restarts
        s.clock.elapsed = 1_000L
        s.clock.wall += 2 * minute                       // the reboot took ~2 real minutes
        s.c.tick()
        assertEquals(ExceptionState.None, s.store.loadException(Target.TIKTOK))
    }

    @Test fun `reboot with manual time latches`() {
        val s = setup(autoTime = false)
        s.c.onForeground(tiktok)
        s.c.tick()

        s.integrity.boot = 2
        s.clock.elapsed = 1_000L
        s.clock.wall += 2 * minute
        assertEquals(Access.BLOCK, s.c.tick().access)
        assertNotNull(s.c.tamperReason())
    }

    @Test fun `corrupt stored usage burns the day instead of granting a fresh budget`() {
        val s = setup(autoTime = true)
        s.store.corruptUsage.add(Target.TIKTOK)
        s.c.onForeground(tiktok)
        assertEquals(Access.BLOCK, s.c.tick().access)
        // Burned to the exception ceiling → even a raised cap can't re-open it today.
        assertEquals(60 * 60L, s.store.loadUsage(Target.TIKTOK)!!.secondsUsed)
    }

    @Test fun `exception dies at the 4am day rollover`() {
        val s = setup(autoTime = true)
        s.clock.local = LocalDateTime.of(2026, 7, 24, 2, 50)  // logical day = Jul 23
        s.c.onForeground(tiktok)
        s.clock.advance(31 * minute)
        s.c.tick()
        s.c.requestException(Target.TIKTOK, extraMinutes = 30, windowMinutes = 120)

        // The 1-hour wait ends after 4am: new logical day (fresh budget) → the exception is void.
        s.clock.advance(80 * minute)
        s.clock.local = LocalDateTime.of(2026, 7, 24, 4, 10)  // logical day = Jul 24
        s.c.tick()
        assertEquals(ExceptionState.None, s.store.loadException(Target.TIKTOK))
        val status = s.c.snapshot().first { it.target == Target.TIKTOK }
        assertEquals(30, status.effectiveCapMinutes)          // normal cap, not a raised one
    }

    @Test fun `sub-second remainders survive app switches`() {
        val s = setup(autoTime = true)
        s.c.onForeground(tiktok)
        s.clock.advance(1_500L)
        s.c.onForeground("com.android.launcher")   // banks 1s, carries 500ms
        s.clock.advance(10_000L)
        s.c.onForeground(tiktok)
        s.clock.advance(600L)                      // 500ms carry + 600ms = 1.1s → 1 more second
        s.c.tick()
        assertEquals(2L, s.store.loadUsage(Target.TIKTOK)!!.secondsUsed)
    }

    @Test fun `latched state freezes accrual`() {
        val s = setup(autoTime = false)
        s.c.onForeground(tiktok)
        s.clock.advance(5 * minute)
        s.c.tick()
        val before = s.store.loadUsage(Target.TIKTOK)!!.secondsUsed

        s.clock.jumpWall(day)                      // latch
        s.c.tick()
        s.clock.advance(30 * minute)               // sitting behind the tamper block screen
        s.c.tick()
        assertEquals(before, s.store.loadUsage(Target.TIKTOK)!!.secondsUsed)
    }
}
