package com.appblock.engine

import com.appblock.engine.DisplayCoverage.Cause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which displays the block screen goes on.
 *
 * The two directions this fails:
 *  - **Cover too little** → the monitor plays a blocked app in plain view. That is the bug.
 *  - **Cover too much** → a mirrored monitor, a Chromecast or a presentation goes black, and the phone
 *    becomes unusable because of what is on a screen nobody is looking at. A blocker that makes the
 *    phone unusable gets uninstalled, which is the fail-open that matters most.
 *
 * ## The mutation these are marked against (M2)
 *
 * Cover **every enumerated display** while the engine is blocking — the naive DeX fix — with
 * `remove = displays - cover`, `satisfied = covered.isNotEmpty()`, and Home only when nothing at all is
 * covered. BITE = fails under M2.
 */
class DisplayCoverageTest {

    private val phone = 0
    private val monitor = 3
    private val none = emptySet<Int>()

    // ---- causes ----

    /** GUARD. The single-display identity: today's behaviour, expressed in the new vocabulary. */
    @Test fun `one display carrying the block is covered, exactly as today`() {
        assertEquals(mapOf(phone to Cause.TARGET), DisplayCoverage.causes(none, setOf(phone), true))
        assertEquals(emptyMap<Int, Cause>(), DisplayCoverage.causes(none, setOf(phone), false))
    }

    /** GUARD. `applyDecision`'s web-first precedence, applied per display instead of globally. */
    @Test fun `a web block covers its own display even while the engine allows`() {
        assertEquals(mapOf(phone to Cause.WEB), DisplayCoverage.causes(setOf(phone), none, false))
    }

    /** GUARD. */
    @Test fun `web wins over target on the same display`() {
        assertEquals(
            mapOf(phone to Cause.WEB),
            DisplayCoverage.causes(setOf(phone), setOf(phone), true),
        )
    }

    /**
     * BITE. Catches "cover every enumerated display", which would slam the block screen onto the phone
     * whenever anything blocked on the monitor.
     */
    @Test fun `an idle display is never covered because another one is blocked`() {
        assertEquals(mapOf(monitor to Cause.TARGET), DisplayCoverage.causes(none, setOf(monitor), true))
    }

    /**
     * BITE. **Invariant I3 as a test:** coverage follows evidence, never enumeration. A mirrored monitor
     * contributes no windows of its own and a recorder's virtual display is hidden from us entirely, so
     * neither can ever appear in these sets — and no blocklist is needed anywhere in the file.
     */
    @Test fun `a display that contributed no windows is never covered - the mirror and recorder property`() {
        for (blocking in listOf(true, false)) {
            val covered = DisplayCoverage.causes(none, setOf(phone), blocking)
            assertFalse("blocking=$blocking", monitor in covered)
        }
    }

    /**
     * BITE. Catches the narrow rule, which is exploitable: `decideCurrent` returns the FIRST blocking
     * target, so a schedule-blocked app parked on the phone would name itself and leave a different
     * blocked app playing free on the monitor indefinitely.
     */
    @Test fun `a second display carrying a target is covered too`() {
        assertEquals(
            mapOf(phone to Cause.TARGET, monitor to Cause.TARGET),
            DisplayCoverage.causes(none, setOf(phone, monitor), true),
        )
    }

    /** GUARD. */
    @Test fun `nothing blocked covers nothing`() {
        assertEquals(emptyMap<Int, Cause>(), DisplayCoverage.causes(none, setOf(phone, monitor), false))
    }

    // ---- plan ----

    /** BITE. **Invariant I4** — an unplugged monitor is in no enumeration, but we still hold its view. */
    @Test fun `an overlay on a display that has vanished is still removed`() {
        assertEquals(listOf(monitor), DisplayCoverage.plan(setOf(phone), setOf(phone, monitor)).remove)
    }

    /** GUARD. */
    @Test fun `add keep and remove partition the union with no overlap`() {
        val pairs = listOf(
            setOf(phone) to setOf(phone, monitor),
            setOf(phone, monitor) to setOf(monitor),
            none to setOf(phone),
            setOf(monitor, 7) to none,
            none to none,
        )
        for ((cover, covered) in pairs) {
            val plan = DisplayCoverage.plan(cover, covered)
            assertEquals("$cover/$covered", DisplayCensus.order(cover), DisplayCensus.order(plan.add + plan.keep))
            assertEquals("$cover/$covered", DisplayCensus.order(covered), DisplayCensus.order(plan.keep + plan.remove))
            assertTrue("$cover/$covered", (plan.add intersect plan.keep.toSet()).isEmpty())
            assertTrue("$cover/$covered", (plan.add intersect plan.remove.toSet()).isEmpty())
            assertTrue("$cover/$covered", (plan.keep intersect plan.remove.toSet()).isEmpty())
        }
    }

    /** GUARD. Determinism, so the deduplicated log line does not churn on map iteration order. */
    @Test fun `the plan is ordered with the default display first`() {
        assertEquals(listOf(0, 3, 7), DisplayCoverage.plan(setOf(7, 0, 3), none).add)
    }

    // ---- satisfied ----

    /** BITE. The exact failure of `return overlayView != null` once two overlays can exist. */
    @Test fun `a display that took no overlay is not reported as covered`() {
        assertFalse(DisplayCoverage.satisfied(setOf(phone, monitor), setOf(phone)))
        assertTrue(DisplayCoverage.satisfied(setOf(phone), setOf(phone)))
        assertTrue(DisplayCoverage.satisfied(none, setOf(phone)))
    }

    // ---- homeFallback ----

    /**
     * BITE. The first two rows are today's behaviour verbatim. The interesting rows are the last three:
     * HOME is an injected key event that follows **input focus**, so kicking is worth it exactly when
     * the bare display is the one the user is driving — and is worse than useless otherwise.
     */
    @Test fun `home is kicked when nothing is covered, and when the active or default display is bare`() {
        assertTrue(DisplayCoverage.homeFallback(setOf(phone), none, phone))
        assertFalse(DisplayCoverage.homeFallback(setOf(phone), setOf(phone), phone))
        assertFalse(DisplayCoverage.homeFallback(setOf(phone, monitor), setOf(phone), phone))
        assertTrue(DisplayCoverage.homeFallback(setOf(phone, monitor), setOf(phone), monitor))
        assertTrue(DisplayCoverage.homeFallback(setOf(phone, monitor), setOf(monitor), monitor))
        assertFalse(DisplayCoverage.homeFallback(none, none, phone))
    }

    // ---- bounceDisplay ----

    /**
     * BITE. In DeX dual mode the phone can hold input focus while a Settings page about App-Block sits
     * open on the monitor, so the global HOME hits the wrong screen — a fail-open on the tier that
     * guards every other tier.
     */
    @Test fun `a watched settings screen away from the focused display gets a targeted bounce`() {
        assertEquals(monitor, DisplayCoverage.bounceDisplay(setOf(monitor), phone))
        assertNull(DisplayCoverage.bounceDisplay(setOf(monitor), monitor))
        assertNull(DisplayCoverage.bounceDisplay(none, phone))
    }
}
