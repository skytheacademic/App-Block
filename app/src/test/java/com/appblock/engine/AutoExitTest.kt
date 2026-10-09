package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The block screen's automatic exit.
 *
 * The two directions this fails:
 *  - **Exits too early or too often** → the block screen comes down before it can be read, or a display
 *    that keeps coming back is kicked on every pass instead of once per dwell.
 *  - **Never exits** → the block screen sits there until Close is tapped, which is the behaviour this
 *    replaces. A timer that a pass can keep resetting is the quiet version of this one.
 */
class AutoExitTest {

    private val phone = 0
    private val monitor = 3
    private val dwell = 3_000L

    private fun autoExit() = AutoExit(dwell)

    @Test fun `nothing is due before the dwell is up`() {
        val a = autoExit()
        a.observe(setOf(phone), 1_000)
        assertEquals(emptyList<Int>(), a.due(1_000))
        assertEquals(emptyList<Int>(), a.due(3_999))
    }

    @Test fun `a block screen is due exactly when the dwell is up`() {
        val a = autoExit()
        a.observe(setOf(phone), 1_000)
        assertEquals(listOf(phone), a.due(4_000))
    }

    /** The pump runs every few hundred ms while a block is up; each pass must not restart the clock. */
    @Test fun `passes while the block screen stays up do not restart its dwell`() {
        val a = autoExit()
        a.observe(setOf(phone), 1_000)
        a.observe(setOf(phone), 2_000)
        a.observe(setOf(phone), 3_500)
        assertEquals(listOf(phone), a.due(4_000))
    }

    /** Close, a platform detach, or the engine lifting the block: the next block screen is a new one. */
    @Test fun `a display that stops being covered forgets its dwell`() {
        val a = autoExit()
        a.observe(setOf(phone), 1_000)
        a.observe(emptySet(), 2_000)
        a.observe(setOf(phone), 3_000)
        assertEquals(emptyList<Int>(), a.due(4_000))
        assertEquals(listOf(phone), a.due(6_000))
    }

    @Test fun `an uncovered display is never due`() {
        val a = autoExit()
        a.observe(setOf(phone), 1_000)
        a.observe(emptySet(), 2_000)
        assertEquals(emptyList<Int>(), a.due(10_000))
        assertNull(a.delayUntilNext(10_000))
    }

    /** The exit did not land and the block screen came straight back: once per dwell, not every pass. */
    @Test fun `a fired display is timed afresh if its block screen comes back`() {
        val a = autoExit()
        a.observe(setOf(phone), 1_000)
        assertEquals(listOf(phone), a.due(4_000))
        a.fired(phone)
        a.observe(setOf(phone), 4_100)
        assertEquals(emptyList<Int>(), a.due(4_200))
        assertEquals(listOf(phone), a.due(7_100))
    }

    /** Each display has its own clock: the monitor's newer block screen is not cut short by the phone's. */
    @Test fun `each display keeps its own dwell`() {
        val a = autoExit()
        a.observe(setOf(phone), 1_000)
        a.observe(setOf(phone, monitor), 2_500)
        assertEquals(listOf(phone), a.due(4_000))
        a.fired(phone)
        assertEquals(listOf(monitor), a.due(5_500))
    }

    @Test fun `due displays come default display first`() {
        val a = autoExit()
        a.observe(setOf(monitor, phone), 1_000)
        assertEquals(listOf(phone, monitor), a.due(4_000))
    }

    @Test fun `the wake-up is for the earliest dwell and never negative`() {
        val a = autoExit()
        assertNull(a.delayUntilNext(0))
        a.observe(setOf(phone), 1_000)
        a.observe(setOf(phone, monitor), 2_000)
        assertEquals(2_500L, a.delayUntilNext(1_500))
        assertEquals(0L, a.delayUntilNext(9_000))
    }

    @Test fun `timing lists the displays whose dwell is running`() {
        val a = autoExit()
        a.observe(setOf(phone, monitor), 1_000)
        a.fired(monitor)
        assertEquals(setOf(phone), a.timing)
    }

    @Test fun `a zero dwell exits on the first pass`() {
        val a = AutoExit(0)
        a.observe(setOf(phone), 1_000)
        assertEquals(listOf(phone), a.due(1_000))
        assertEquals(0L, a.delayUntilNext(1_000))
    }

    // ---- globalHomeLands ----

    /** Single display, today's path: the global HOME is the one that was measured on hardware. */
    @Test fun `the phone's own block screen uses the global home`() {
        assertTrue(AutoExit.globalHomeLands(phone, phone))
        assertTrue(AutoExit.globalHomeLands(phone, null))
    }

    @Test fun `a block screen on the focused monitor uses the global home`() {
        assertTrue(AutoExit.globalHomeLands(monitor, monitor))
    }

    /** The global HOME would kick the phone and leave the monitor's blocked app where it was. */
    @Test fun `a block screen on an unfocused monitor gets its own home`() {
        assertFalse(AutoExit.globalHomeLands(monitor, phone))
        assertFalse(AutoExit.globalHomeLands(monitor, null))
    }

    /** The phone is blocked while the user types on the monitor: aim at the phone, leave the monitor be. */
    @Test fun `the phone's block screen gets its own home while the monitor has focus`() {
        assertFalse(AutoExit.globalHomeLands(phone, monitor))
    }
}
