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

    // ---- release condition 2: the timeout backstop, and what disarms it ----

    /** With no foreground event ever seen, the moved-on test has no channel and the backstop is the exit. */
    @Test fun `holds right up to the limit and lets go on it`() {
        val hold = hold(limitMs = 60_000)
        hold.arm(reel, instagram, nowMs = 0)
        assertTrue(hold.backstopArmed)
        assertEquals(reel, hold.sustain(instagram, nowMs = 59_999))
        assertNull(hold.sustain(instagram, nowMs = 60_000))
    }

    /**
     * **The C-1 regression (Gate F Phase 3, 2026-07-27).** This is the flash itself: sitting still on a
     * blocked page, the overlay dropped for 148–256 ms once every 60 s, forever, leaving the page
     * unobstructed and tappable. The timeout was firing while the moved-on test was demonstrably
     * working, so it released on a pruned read it had already decided not to trust.
     *
     * Fails against the pre-fix `sustain`, which returned null here.
     */
    @Test fun `a working event stream retires the backstop, so a live block never expires`() {
        val hold = hold(limitMs = 60_000)
        hold.noteForegroundEvent()
        hold.arm(reel, instagram, nowMs = 0)
        assertFalse(hold.backstopArmed)
        assertEquals(reel, hold.sustain(instagram, nowMs = 60_000))
        assertEquals(reel, hold.sustain(instagram, nowMs = 600_000))
        assertTrue(hold.isArmed)
    }

    /** Retiring the backstop must not cost the exit it was standing in for. */
    @Test fun `moving on still releases once the backstop is retired`() {
        val hold = hold(limitMs = 60_000)
        hold.noteForegroundEvent()
        hold.arm(reel, instagram, nowMs = 0)
        assertNull(hold.sustain(launcher, nowMs = 300_000))
        assertFalse(hold.isArmed)
    }

    /**
     * The proof is about the device, not about one block, so it outlives the hold that observed it —
     * otherwise the flash would simply return on the next overlay.
     */
    @Test fun `the proof survives a release`() {
        val hold = hold(limitMs = 60_000)
        hold.noteForegroundEvent()
        hold.arm(reel, instagram, nowMs = 0)
        hold.release()
        hold.arm(reel, instagram, nowMs = 1_000)
        assertEquals(reel, hold.sustain(instagram, nowMs = 500_000))
    }

    /**
     * An event arriving mid-hold counts too — the channel is proven the moment it speaks, and the hold
     * it interrupts is exactly the one that would otherwise have flashed.
     */
    @Test fun `an event arriving mid-hold retires the backstop`() {
        val hold = hold(limitMs = 60_000)
        hold.arm(reel, instagram, nowMs = 0)
        assertEquals(reel, hold.sustain(instagram, nowMs = 30_000))
        hold.noteForegroundEvent()
        assertEquals(reel, hold.sustain(instagram, nowMs = 120_000))
    }

    /**
     * A hold armed with no package has no moved-on test of its own, but the backstop is a statement
     * about the *stream*, not about this hold. Once the stream is proven, an event will name the next
     * package too — so this must not silently become a permanent hold with no exit at all... which is
     * why the caller releases on [OcclusionHold.release] paths the overlay owns (Close, allow, engine
     * exits). Pinned so the trade is deliberate rather than discovered later.
     */
    @Test fun `a proven stream plus an unknown armed package leaves the moved-on test as the only exit`() {
        val hold = hold(limitMs = 10_000)
        hold.noteForegroundEvent()
        hold.arm(reel, foregroundPackage = null, nowMs = 0)
        assertEquals(reel, hold.sustain(launcher, nowMs = 100_000))
        hold.release()
        assertFalse(hold.isArmed)
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
