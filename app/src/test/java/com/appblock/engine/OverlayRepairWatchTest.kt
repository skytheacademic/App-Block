package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outage these tests are written from happened on the S25 on 2026-08-29 and was caught in the act:
 * `Settings.canDrawOverlays()` said false for several minutes while `appops get com.appblock
 * SYSTEM_ALERT_WINDOW` said `allow` throughout. Repair mode was one expression, `!canDrawOverlays()`,
 * so the whole Settings tier stood down — six consecutive unbounced trials, then 7/7 with nothing
 * changed but time — and it did so silently.
 */
class OverlayRepairWatchTest {

    private val grace = OverlayRepairWatch.DEFAULT_DISAGREEMENT_GRACE_MS

    // ---- the outage ----

    /**
     * The whole finding in one assertion. `canDrawOverlays` says no, the app op says the permission is
     * held, and the tier stays armed — which is what would have bounced those six trials.
     */
    @Test fun `a canDrawOverlays glitch alone does not stand the tier down`() {
        val watch = OverlayRepairWatch()
        assertFalse(watch.observe(canDraw = false, opAllows = true, nowMs = 0))
        assertFalse(watch.observe(canDraw = false, opAllows = true, nowMs = 60_000))
        assertFalse(watch.observe(canDraw = false, opAllows = true, nowMs = 5L * 60 * 1_000))
        assertFalse(watch.isEngaged)
    }

    /** And the outage ending re-arms nothing, because nothing was ever disarmed. */
    @Test fun `the tier is armed again the moment canDrawOverlays recovers`() {
        val watch = OverlayRepairWatch()
        watch.observe(canDraw = false, opAllows = true, nowMs = 0)
        assertFalse(watch.observe(canDraw = true, opAllows = true, nowMs = 90_000))
        assertFalse(watch.isEngaged)
    }

    // ---- a real revoke still repairs, immediately ----

    /**
     * The direction that must not get slower. Losing "Appear on top" makes the block screen undrawable,
     * and the page that restores it is guarded by rule 2 — so a delay here is a lockout (C-2, which
     * cost an adb session). Both sources agreeing is proof enough, so there is no confirmation wait at
     * all: corroboration may only ever *delay a stand-down one source asked for alone*.
     */
    @Test fun `both sources agreeing engages repair mode at once`() {
        val watch = OverlayRepairWatch()
        assertTrue(watch.observe(canDraw = false, opAllows = false, nowMs = 0))
        assertTrue(watch.isEngaged)
    }

    @Test fun `regaining the permission leaves repair mode`() {
        val watch = OverlayRepairWatch()
        assertTrue(watch.observe(canDraw = false, opAllows = false, nowMs = 0))
        assertFalse(watch.observe(canDraw = true, opAllows = true, nowMs = 1_000))
        assertFalse(watch.isEngaged)
    }

    /**
     * A revoke that the app op has not caught up with yet still repairs — the disagreement grace is the
     * *upper* bound on stubbornness, not a fixed wait.
     */
    @Test fun `a disagreement that outlives its grace gives way`() {
        val watch = OverlayRepairWatch()
        assertFalse(watch.observe(canDraw = false, opAllows = true, nowMs = 0))
        assertFalse(watch.observe(canDraw = false, opAllows = true, nowMs = grace - 1))
        assertTrue(watch.observe(canDraw = false, opAllows = true, nowMs = grace))
    }

    /**
     * ...and the clock runs from the start of *this* run of falses, not from the service starting. A
     * recovery in between must reset it, or a phone that glitches once an hour eventually accumulates
     * its way into a stand-down it never earned.
     */
    @Test fun `a recovery restarts the grace`() {
        val watch = OverlayRepairWatch()
        watch.observe(canDraw = false, opAllows = true, nowMs = 0)
        watch.observe(canDraw = true, opAllows = true, nowMs = grace - 1)
        assertFalse(watch.observe(canDraw = false, opAllows = true, nowMs = grace))
        assertTrue(watch.observe(canDraw = false, opAllows = true, nowMs = grace * 2))
    }

    // ---- never silent again ----

    /**
     * The other half of why this was a P0. The stand-down announced itself nowhere, so the two symptoms
     * it produced were written up the same morning as two unrelated mysteries. [OverlayRepairWatch.justEngaged]
     * is the caller's cue to push the health notification out past its throttle.
     */
    @Test fun `entering repair mode is announced exactly once`() {
        val watch = OverlayRepairWatch()
        watch.observe(canDraw = false, opAllows = false, nowMs = 0)
        assertTrue(watch.justEngaged)
        watch.observe(canDraw = false, opAllows = false, nowMs = 5_000)
        assertFalse("still engaged is not a new outage", watch.justEngaged)
    }

    @Test fun `a second outage is announced again`() {
        val watch = OverlayRepairWatch()
        watch.observe(canDraw = false, opAllows = false, nowMs = 0)
        watch.observe(canDraw = true, opAllows = true, nowMs = 1_000)
        assertFalse(watch.justEngaged)
        watch.observe(canDraw = false, opAllows = false, nowMs = 2_000)
        assertTrue(watch.justEngaged)
    }

    @Test fun `staying healthy announces nothing`() {
        val watch = OverlayRepairWatch()
        watch.observe(canDraw = true, opAllows = true, nowMs = 0)
        assertFalse(watch.justEngaged)
        assertFalse(watch.isEngaged)
    }

    // ---- the diagnostic ----

    /**
     * `disagreementMs` is what the `AppBlockWatch` line prints, and it exists so this state can never
     * again run unnamed. It reports only the case it is for: a live disagreement, still holding.
     */
    @Test fun `reports how long the two sources have disagreed`() {
        val watch = OverlayRepairWatch()
        watch.observe(canDraw = false, opAllows = true, nowMs = 1_000)
        assertEquals(29_000L, watch.disagreementMs(30_000))
        watch.observe(canDraw = true, opAllows = true, nowMs = 31_000)
        assertEquals(0L, watch.disagreementMs(40_000))
    }

    @Test fun `reports nothing while actually in repair mode`() {
        val watch = OverlayRepairWatch()
        watch.observe(canDraw = false, opAllows = false, nowMs = 0)
        assertEquals(0L, watch.disagreementMs(60_000))
    }
}
