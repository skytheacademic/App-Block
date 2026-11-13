package com.appblock.engine

import com.appblock.engine.ReelsTabCover.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Reels-tab tile. Pixel values are the IG 450 capture on the S25 FE (2026-10-11): tab bar
 * `[0,2163][1080,2298]`, Reels tab `[216,2163][432,2298]`, comments sheet `[0,978][1080,2298]`.
 *
 * The two directions this fails:
 *  - **Comes down too easily** → the tab is one tap away again (the full block still catches it).
 *  - **Will not come down** → a dark square sits on top of whatever Instagram puts there next, such as
 *    the comment box.
 */
class ReelsTabCoverTest {

    private val reels = Box(216, 2163, 432, 2298)
    private val home = Box(0, 2163, 216, 2298)
    private val comments = Box(0, 978, 1080, 2298)

    private val feed = ReelsTabCover.Read(reelsTab = reels, reelsTabSelected = false, visibleTab = home, sheet = null)

    /** What the tile may do to the read: the Reels tab drops out, the other tabs stay. */
    private val underTile = feed.copy(reelsTab = null)

    /** DM thread, story, reel from Explore: no tab bar in the tree. */
    private val noBar: ReelsTabCover.Read? = null

    /** The bar's nodes are there but none is visible: Instagram slid it away. */
    private val barHidden = feed.copy(visibleTab = null)

    private fun cover() = ReelsTabCover(releaseMs = 300)

    @Test fun `the first sighting of the tab bar covers exactly the Reels tab`() {
        assertEquals(reels, cover().observe(feed, 0))
    }

    @Test fun `the tile stays for as long as the bar is there`() {
        val c = cover()
        c.observe(feed, 0)
        assertEquals(reels, c.observe(feed, 600_000))
    }

    /** The load-bearing case: the cover must not read its own effect as the tab leaving. */
    @Test fun `the Reels tab dropping out under the tile keeps the tile`() {
        val c = cover()
        c.observe(feed, 0)
        assertEquals(reels, c.observe(underTile, 100))
        assertEquals(reels, c.observe(underTile, 10_000))
    }

    @Test fun `no Reels tab ever seen means no tile`() {
        assertNull(cover().observe(underTile, 0))
    }

    @Test fun `the Reels tab selected takes the tile down at once`() {
        val c = cover()
        c.observe(feed, 0)
        assertNull(c.observe(feed.copy(reelsTabSelected = true), 10))
        assertFalse(c.up)
    }

    @Test fun `a bottom sheet across the tab row takes the tile down at once`() {
        val c = cover()
        c.observe(feed, 0)
        assertNull(c.observe(feed.copy(sheet = comments), 10))
        assertFalse(c.up)
    }

    @Test fun `a bottom sheet already open means no tile`() {
        assertNull(cover().observe(feed.copy(sheet = comments), 0))
    }

    /** A half-height sheet that stops above the bar is not over the tab. */
    @Test fun `a sheet that stops above the tab row leaves the tile`() {
        val c = cover()
        assertEquals(reels, c.observe(feed.copy(sheet = Box(0, 978, 1080, 2100)), 0))
    }

    @Test fun `one null read does not flicker the tile`() {
        val c = cover()
        c.observe(feed, 0)
        assertEquals(reels, c.observe(noBar, 100))
        assertEquals(reels, c.observe(feed, 200))
        assertEquals(reels, c.observe(noBar, 450))
    }

    @Test fun `a page with no tab bar takes the tile down after the grace`() {
        val c = cover()
        c.observe(feed, 0)
        assertEquals(reels, c.observe(noBar, 100))
        assertEquals(reels, c.observe(noBar, 399))
        assertNull(c.observe(noBar, 400))
    }

    @Test fun `the bar sliding away takes the tile down after the grace`() {
        val c = cover()
        c.observe(feed, 0)
        assertEquals(reels, c.observe(barHidden, 50))
        assertNull(c.observe(barHidden, 350))
    }

    /** A visible tab in some other row (a toolbar reusing an id) is not the tab bar. */
    @Test fun `a visible tab in another row does not prove the bar`() {
        val elsewhere = Box(0, 100, 216, 230)
        assertNull(cover().observe(feed.copy(visibleTab = elsewhere), 0))
    }

    @Test fun `an empty Reels tab rectangle is never covered`() {
        assertNull(cover().observe(feed.copy(reelsTab = Box(216, 2163, 216, 2298)), 0))
    }

    @Test fun `the tile follows the tab when the bar moves`() {
        val c = cover()
        c.observe(feed, 0)
        val raised = Box(216, 1500, 432, 1635)
        assertEquals(raised, c.observe(feed.copy(reelsTab = raised, visibleTab = Box(0, 1500, 216, 1635)), 10))
    }

    /** With no fresh Reels bounds, a standing tile is only kept if it is still in the bar's row. */
    @Test fun `a standing tile in a stale row is not kept on neighbour evidence`() {
        val c = cover()
        c.observe(feed, 0)
        val moved = underTile.copy(visibleTab = Box(0, 1500, 216, 1635))
        assertEquals(reels, c.observe(moved, 10))     // grace, not confirmation
        assertNull(c.observe(moved, 310))
    }

    @Test fun `a fresh sighting ends the grace`() {
        val c = cover()
        c.observe(feed, 0)
        c.observe(noBar, 100)
        assertEquals(reels, c.observe(feed, 300))
        assertEquals(reels, c.observe(noBar, 500))     // grace restarts at 500
        assertEquals(reels, c.observe(noBar, 799))
        assertNull(c.observe(noBar, 800))
    }

    @Test fun `delayUntilNext is the rest of the grace, or nothing`() {
        val c = cover()
        assertNull(c.delayUntilNext(0))
        c.observe(feed, 0)
        assertNull(c.delayUntilNext(0))
        c.observe(noBar, 100)
        assertEquals(250L, c.delayUntilNext(150))
        assertEquals(0L, c.delayUntilNext(1_000))
    }

    @Test fun `reset forgets the tile`() {
        val c = cover()
        c.observe(feed, 0)
        assertTrue(c.up)
        c.reset()
        assertFalse(c.up)
        assertNull(c.observe(underTile, 10))
    }
}
