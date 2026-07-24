package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The delayed, single-use change window (CONSTRAINTS.md §6, 2026-07-22). Short test durations. */
class DurableUnlockManagerTest {

    private val waitMs = 100L
    private val windowMs = 20L

    private fun request(now: Long = 1000L, boot: Int = 5, category: UnlockCategory = UnlockCategory.APPS) =
        DurableUnlockManager.request(now, boot, category, waitMs, windowMs)

    @Test fun `request starts a pending wait`() {
        val s = request()
        assertEquals(1100L, s.activeAtElapsedMs)
        assertEquals(1120L, s.windowEndElapsedMs)
        assertEquals(5, s.bootCount)
        assertEquals(UnlockCategory.APPS, s.category)
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

    // ---- Per-category waits (CONSTRAINTS §2: apps 2 h · websites 72 h) ----

    @Test fun `real default waits are per-category`() {
        val apps = DurableUnlockManager.request(0L, 1)
        assertEquals(2L * 60L * 60L * 1000L, apps.activeAtElapsedMs)
        assertEquals(UnlockCategory.APPS, apps.category)

        val web = DurableUnlockManager.request(0L, 1, UnlockCategory.WEBSITES)
        assertEquals(72L * 60L * 60L * 1000L, web.activeAtElapsedMs)
        assertEquals(UnlockCategory.WEBSITES, web.category)
    }

    @Test fun `category survives the pending-to-open transition`() {
        val opened = DurableUnlockManager.tick(request(category = UnlockCategory.WEBSITES), 1100L, 5)
        assertEquals(UnlockCategory.WEBSITES, (opened as DurableUnlockState.Open).category)
    }

    @Test fun `a window only authorizes its own category`() {
        // The bypass this exists to prevent: sit out the short 2-h apps wait, then use that window
        // to remove a website (which owes 72 h). Each direction must fail.
        val appsOpen = DurableUnlockManager.tick(request(), 1100L, 5)
        assertTrue(DurableUnlockManager.isOpenFor(appsOpen, UnlockCategory.APPS))
        assertFalse(DurableUnlockManager.isOpenFor(appsOpen, UnlockCategory.WEBSITES))

        val webOpen = DurableUnlockManager.tick(request(category = UnlockCategory.WEBSITES), 1100L, 5)
        assertFalse(DurableUnlockManager.isOpenFor(webOpen, UnlockCategory.APPS))
        assertTrue(DurableUnlockManager.isOpenFor(webOpen, UnlockCategory.WEBSITES))

        // The any-category check still reports an open window (settings-watch stand-down).
        assertTrue(DurableUnlockManager.isOpen(webOpen))
        assertFalse(DurableUnlockManager.isOpenFor(DurableUnlockState.Locked, UnlockCategory.APPS))
    }
}
