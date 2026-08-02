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

    private fun setup(autoTime: Boolean, autoTimeZone: Boolean = autoTime): Setup {
        val clock = FakeClock()
        val store = InMemoryEngineStore()
        val integrity = FakeIntegrity(autoTime = autoTime, autoTimeZone = autoTimeZone)
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

    /**
     * Recovery from a *date* change, with the zone toggle left on throughout so this stays a test of
     * the AUTO_TIME dimension alone. Clearing the latch needs every clock input back under OS
     * control — `both toggles must be on before the latch clears` covers the combined case.
     */
    @Test fun `turning automatic time back on clears the latch`() {
        val s = setup(autoTime = false, autoTimeZone = true)
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

    // ---- timezone: the second, independent way to move local time (audit finding B-1) ----

    /**
     * The bypass exactly as it was performed: spend the cap, turn off *automatic time zone* only,
     * jump to GMT+14. Before the fix this handed back a full fresh budget, because the drift check
     * compares UTC epoch millis and a zone change moves those by zero.
     */
    @Test fun `timezone hop cannot hand back a spent budget`() {
        val s = setup(autoTime = true, autoTimeZone = false)
        s.c.onForeground(tiktok)
        s.clock.advance(31 * minute)                     // spend past the 30-min cap
        assertEquals(Access.BLOCK, s.c.tick().access)

        s.clock.changeZone(14 * 3600)                    // Kiritimati: local +18h, UTC epoch unmoved
        assertEquals(Access.BLOCK, s.c.tick().access)
        assertNotNull(s.c.tamperReason())
    }

    /** The loop that made it unlimited: hop east, hop west, hop east again. Every pass stays latched. */
    @Test fun `hopping back and forth never re-opens the budget`() {
        val s = setup(autoTime = true, autoTimeZone = false)
        s.c.onForeground(tiktok)
        s.clock.advance(31 * minute)
        s.c.tick()

        repeat(3) {
            s.clock.changeZone(14 * 3600)
            assertEquals(Access.BLOCK, s.c.tick().access)
            s.clock.changeZone(-11 * 3600)
            assertEquals(Access.BLOCK, s.c.tick().access)
        }
    }

    /** A UTC-epoch drift of zero is the whole trap — assert the guard no longer needs one to fire. */
    @Test fun `zone change latches even though the wall clock never drifts`() {
        val s = setup(autoTime = true, autoTimeZone = false)
        s.c.onForeground(tiktok)
        s.c.tick()
        val wallBefore = s.clock.wall

        s.clock.changeZone(14 * 3600)
        assertEquals(Access.BLOCK, s.c.tick().access)
        assertEquals("the zone hop must not move UTC epoch", wallBefore, s.clock.wall)
        assertNotNull(s.c.tamperReason())
    }

    /** Real travel: the OS owns the zone, so the offset moving is legitimate and must not latch. */
    @Test fun `flying with automatic time zone on is not tampering`() {
        val s = setup(autoTime = true, autoTimeZone = true)
        s.c.onForeground(tiktok)
        s.c.tick()

        s.clock.changeZone(9 * 3600)                     // landed in Tokyo, OS re-zoned the phone
        assertEquals(Access.ALLOW, s.c.tick().access)
        assertNull(s.c.tamperReason())
    }

    /**
     * Migration. An anchor written before this guard existed carries no offset, and inventing 0 for
     * it would read as a multi-hour zone jump on the very first pass after the update — latching the
     * blocker for every user not sitting on UTC. Unknown must mean "record it, compare next time".
     */
    @Test fun `an anchor with no recorded zone does not latch on the first pass`() {
        val s = setup(autoTime = true, autoTimeZone = false)
        s.c.onForeground(tiktok)
        s.store.saveClockAnchor(
            ClockAnchor(
                wallMs = s.clock.wall,
                elapsedMs = s.clock.elapsed,
                bootCount = s.integrity.boot,
                zoneOffsetSeconds = null,          // legacy anchor
            ),
        )

        assertEquals(Access.ALLOW, s.c.tick().access)
        assertNull(s.c.tamperReason())
        // ...and the pass records the offset, so the guard is armed from here on.
        assertEquals(s.clock.zoneOffset, s.store.loadClockAnchor()!!.zoneOffsetSeconds)
        s.clock.changeZone(14 * 3600)
        assertEquals(Access.BLOCK, s.c.tick().access)
    }

    /**
     * Clearing needs BOTH toggles now, which the overlay text has to promise accurately — turning
     * only date & time back on must not read as recovered.
     */
    @Test fun `both toggles must be on before the latch clears`() {
        val s = setup(autoTime = true, autoTimeZone = false)
        s.c.onForeground(tiktok)
        s.c.tick()
        s.clock.changeZone(14 * 3600)
        assertEquals(Access.BLOCK, s.c.tick().access)

        s.integrity.autoTime = true                      // already on; the zone toggle is the missing one
        assertEquals(Access.BLOCK, s.c.tick().access)
        assertNotNull(s.c.tamperReason())

        s.integrity.autoTimeZone = true
        assertEquals(Access.ALLOW, s.c.tick().access)
        assertNull(s.c.tamperReason())
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
