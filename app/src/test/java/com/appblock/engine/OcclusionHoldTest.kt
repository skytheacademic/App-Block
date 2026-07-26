package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hold that keeps blocking while our own overlay is what pruned the accessibility tree.
 *
 * Two failure directions to hold apart, and they pull against each other:
 *  - release too eagerly → the once-a-second block/unblock oscillation returns, handing back free
 *    frames of the reel. That is the bypass.
 *  - never release → the block screen follows the user to the launcher and Close is the only exit.
 *    That is the lock-out.
 */
class OcclusionHoldTest {

    private val reel = "reel-player"
    private val blockedSite = "blocked-site"

    private val instagram = "com.instagram.android"
    private val launcher = "com.sec.android.app.launcher"

    private fun hold(limitMs: Long = OcclusionHold.DEFAULT_HOLD_LIMIT_MS) =
        OcclusionHold<String>(holdLimitMs = limitMs)

    @Test fun `holds nothing until a real read arms it`() {
        val hold = hold()
        assertFalse(hold.isArmed)
        assertNull(hold.sustain(instagram, nowMs = 0))
    }

    @Test fun `keeps the last real read while the tree is pruned`() {
        val hold = hold()
        hold.arm(reel, instagram, nowMs = 0)
        assertTrue(hold.isArmed)
        assertEquals(reel, hold.sustain(instagram, nowMs = 1_000))
    }

    /** The overlay came down, or the engine allowed the target — the hold has no business surviving. */
    @Test fun `release drops it`() {
        val hold = hold()
        hold.arm(reel, instagram, nowMs = 0)
        hold.release()
        assertFalse(hold.isArmed)
        assertNull(hold.sustain(instagram, nowMs = 1_000))
    }

    // ---- release condition 1: the user moved on (audit finding C-1) ----

    /**
     * The lock-out this class was written for. Home works behind an overlay, so without this the block
     * screen sits on top of the launcher until the user finds the Close button.
     */
    @Test fun `lets go once the foreground moves to another app`() {
        val hold = hold()
        hold.arm(reel, instagram, nowMs = 0)
        assertNull(hold.sustain(launcher, nowMs = 1_000))
        assertFalse(hold.isArmed)
    }

    @Test fun `staying in the same app is not moving on`() {
        val hold = hold()
        hold.arm(reel, instagram, nowMs = 0)
        // Instagram fires window-state changes for its own dialogs and sheets constantly.
        repeat(20) { assertEquals(reel, hold.sustain(instagram, nowMs = 1_000L * it)) }
    }

    /** Once it has let go it stays gone; the next real read is what re-arms it. */
    @Test fun `does not creep back after releasing`() {
        val hold = hold()
        hold.arm(reel, instagram, nowMs = 0)
        hold.sustain(launcher, nowMs = 1_000)
        assertNull(hold.sustain(instagram, nowMs = 2_000))
    }

    /**
     * No package information means no basis for the moved-on test, so it must not guess — the timeout
     * is then the only exit. Erring the other way (treating unknown as moved) would drop the block
     * every time an event arrived without a package.
     */
    @Test fun `an unknown foreground never counts as moving on`() {
        val hold = hold()
        hold.arm(reel, instagram, nowMs = 0)
        assertEquals(reel, hold.sustain(null, nowMs = 1_000))
    }

    @Test fun `arming with an unknown package leaves only the timeout`() {
        val hold = hold(limitMs = 10_000)
        hold.arm(reel, foregroundPackage = null, nowMs = 0)
        assertEquals(reel, hold.sustain(launcher, nowMs = 1_000))
        assertNull(hold.sustain(launcher, nowMs = 10_000))
    }

    // ---- release condition 2: the timeout backstop ----

    @Test fun `holds right up to the limit and lets go on it`() {
        val hold = hold(limitMs = 60_000)
        hold.arm(reel, instagram, nowMs = 0)
        assertEquals(reel, hold.sustain(instagram, nowMs = 59_999))
        assertNull(hold.sustain(instagram, nowMs = 60_000))
    }

    /** Every read that gets through the pruning restarts the countdown, so a live block never expires. */
    @Test fun `a real read restarts the countdown`() {
        val hold = hold(limitMs = 60_000)
        hold.arm(reel, instagram, nowMs = 0)
        hold.arm(reel, instagram, nowMs = 50_000)
        assertEquals(reel, hold.sustain(instagram, nowMs = 100_000))
    }

    /**
     * The seeding path must not restart it, though. The service seeds with its *effective* read, which
     * is often the held value itself — refreshing off that would keep the hold alive on its own echo
     * and the timeout would never fire.
     */
    @Test fun `seeding does not restart the countdown or overwrite what is held`() {
        val hold = hold(limitMs = 60_000)
        hold.seed(reel, instagram, nowMs = 0)
        hold.seed(blockedSite, instagram, nowMs = 30_000)
        assertEquals(reel, hold.sustain(instagram, nowMs = 59_000))
        assertNull(hold.sustain(instagram, nowMs = 60_000))
    }

    @Test fun `seeding an empty hold arms it`() {
        val hold = hold()
        hold.seed(blockedSite, instagram, nowMs = 0)
        assertEquals(blockedSite, hold.sustain(instagram, nowMs = 1_000))
    }

    /**
     * A blocked website holds exactly like a blocked app: Chrome's `url_bar` reads empty behind the
     * overlay for the same reason the reel pager disappears.
     */
    @Test fun `works the same for a website block`() {
        val hold = hold()
        hold.arm(blockedSite, "com.android.chrome", nowMs = 0)
        assertEquals(blockedSite, hold.sustain("com.android.chrome", nowMs = 1_000))
        assertNull(hold.sustain(launcher, nowMs = 2_000))
    }

    /** After a timeout release the next real read re-arms cleanly, with a full fresh countdown. */
    @Test fun `re-arms after expiring`() {
        val hold = hold(limitMs = 10_000)
        hold.arm(reel, instagram, nowMs = 0)
        assertNull(hold.sustain(instagram, nowMs = 10_000))
        hold.arm(reel, instagram, nowMs = 11_000)
        assertEquals(reel, hold.sustain(instagram, nowMs = 20_000))
    }
}
