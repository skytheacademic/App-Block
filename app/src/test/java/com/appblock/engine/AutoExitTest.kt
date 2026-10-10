package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The block screen's automatic exit: Home as soon as it is up, again only if it is still up later.
 *
 * The two directions this fails:
 *  - **Exits too often** → a pass runs on every window event and Home's own transition fires a burst of
 *    them, so "exit whenever covered" presses Home a dozen times per block and re-shows the reason card
 *    each time.
 *  - **Never exits again** → a Home that did not land leaves the block screen sitting there until Close
 *    is tapped, which is the behaviour this replaces.
 */
class AutoExitTest {

    private val phone = 0
    private val monitor = 3
    private val retry = 3_000L

    private fun autoExit() = AutoExit(retry)

    @Test fun `a new block screen is due at once`() {
        val a = autoExit()
        a.observe(setOf(phone), 1_000)
        assertEquals(listOf(phone), a.due(1_000))
        assertEquals(0L, a.delayUntilNext(1_000))
    }

    /** The burst of events from Home's own transition must not press Home again. */
    @Test fun `passes after the exit do not exit again before the retry`() {
        val a = autoExit()
        a.observe(setOf(phone), 1_000)
        a.fired(phone, 1_000)
        a.observe(setOf(phone), 1_050)
        a.observe(setOf(phone), 1_400)
        assertEquals(emptyList<Int>(), a.due(1_400))
        assertEquals(emptyList<Int>(), a.due(3_999))
    }

    /** The Home did not land: the block screen is still up, so it is sent again. */
    @Test fun `a block screen still up after the retry interval is due again`() {
        val a = autoExit()
        a.observe(setOf(phone), 1_000)
        a.fired(phone, 1_000)
        a.observe(setOf(phone), 2_000)
        assertEquals(listOf(phone), a.due(4_000))
        a.fired(phone, 4_000)
        assertEquals(emptyList<Int>(), a.due(5_000))
        assertEquals(listOf(phone), a.due(7_000))
    }

    @Test fun `the wake-up after an exit is for the retry`() {
        val a = autoExit()
        a.observe(setOf(phone), 1_000)
        a.fired(phone, 1_000)
        assertEquals(2_500L, a.delayUntilNext(1_500))
        assertEquals(0L, a.delayUntilNext(9_000))
    }

    /** The exit landed and the block screen came down; reopening the app is a new block screen. */
    @Test fun `a block screen that came down is forgotten and the next one exits at once`() {
        val a = autoExit()
        a.observe(setOf(phone), 1_000)
        a.fired(phone, 1_000)
        a.observe(emptySet(), 1_800)
        assertNull(a.delayUntilNext(1_800))
        a.observe(setOf(phone), 2_000)
        assertEquals(listOf(phone), a.due(2_000))
    }

    @Test fun `an uncovered display is never due`() {
        val a = autoExit()
        a.observe(setOf(phone), 1_000)
        a.observe(emptySet(), 1_100)
        assertEquals(emptyList<Int>(), a.due(10_000))
        assertNull(a.delayUntilNext(10_000))
    }

    /** A Close tap or the engine lifting the block before the posted exit ran: nothing to exit. */
    @Test fun `firing a display that is no longer covered records nothing`() {
        val a = autoExit()
        a.observe(setOf(phone), 1_000)
        a.observe(emptySet(), 1_010)
        a.fired(phone, 1_020)
        assertEquals(emptySet<Int>(), a.tracking)
    }

    /** Each display has its own record: the phone's exit does not hold back the monitor's. */
    @Test fun `each display exits on its own record`() {
        val a = autoExit()
        a.observe(setOf(phone), 1_000)
        a.fired(phone, 1_000)
        a.observe(setOf(phone, monitor), 1_500)
        assertEquals(listOf(monitor), a.due(1_500))
        a.fired(monitor, 1_500)
        assertEquals(listOf(phone), a.due(4_000))
        assertEquals(listOf(phone, monitor), a.due(4_500))
    }

    @Test fun `due displays come default display first`() {
        val a = autoExit()
        a.observe(setOf(monitor, phone), 1_000)
        assertEquals(listOf(phone, monitor), a.due(1_000))
    }

    @Test fun `tracking lists the covered displays`() {
        val a = autoExit()
        a.observe(setOf(phone, monitor), 1_000)
        a.observe(setOf(phone), 1_100)
        assertEquals(setOf(phone), a.tracking)
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
