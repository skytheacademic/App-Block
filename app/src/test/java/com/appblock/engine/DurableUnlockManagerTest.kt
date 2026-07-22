package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 2-hour delayed, single-use change window (CONSTRAINTS.md §6, 2026-07-22). Short test durations. */
class DurableUnlockManagerTest {

    private val waitMs = 100L
    private val windowMs = 20L

    private fun request(now: Long = 1000L, boot: Int = 5) =
        DurableUnlockManager.request(now, boot, waitMs, windowMs)

    @Test fun `request starts a pending wait`() {
        val s = request()
        assertEquals(1100L, s.activeAtElapsedMs)
        assertEquals(1120L, s.windowEndElapsedMs)
        assertEquals(5, s.bootCount)
    }

    @Test fun `stays pending during the wait, then opens`() {
        val p = request()
        assertTrue(DurableUnlockManager.tick(p, 1050L, 5) is DurableUnlockState.Pending)
        val opened = DurableUnlockManager.tick(p, 1100L, 5)
        assertTrue(opened is DurableUnlockState.Open)
        assertTrue(DurableUnlockManager.isOpen(opened))
    }

    @Test fun `the open window closes when it elapses`() {
        val opened = DurableUnlockManager.tick(request(), 1100L, 5)
        assertTrue(DurableUnlockManager.tick(opened, 1119L, 5) is DurableUnlockState.Open)
        assertEquals(DurableUnlockState.Locked, DurableUnlockManager.tick(opened, 1120L, 5))
    }

    @Test fun `a reboot during the wait restarts (locks)`() {
        assertEquals(DurableUnlockState.Locked, DurableUnlockManager.tick(request(), 1100L, bootCount = 6))
    }

    @Test fun `a reboot during the open window locks`() {
        val opened = DurableUnlockManager.tick(request(), 1100L, 5)
        assertEquals(DurableUnlockState.Locked, DurableUnlockManager.tick(opened, 1110L, bootCount = 6))
    }

    @Test fun `a window missed entirely goes straight to locked`() {
        // First observation only happens after the whole window already ended — can't be cashed in late.
        assertEquals(DurableUnlockState.Locked, DurableUnlockManager.tick(request(), 5000L, 5))
    }

    @Test fun `consume relocks (single use)`() {
        assertEquals(DurableUnlockState.Locked, DurableUnlockManager.consume())
    }
}
