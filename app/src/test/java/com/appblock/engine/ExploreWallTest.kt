package com.appblock.engine

import com.appblock.engine.ExploreWall.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Explore wall. Pixel values are the IG 450 capture on the S25 FE: action bar bottom 255, tab bar
 * top 2163.
 *
 * The two directions this fails:
 *  - **Comes down too easily** → the grid is free again, by a route like tap search → Back.
 *  - **Will not come down** → search is unusable, which is the one thing the page is still for.
 */
class ExploreWallTest {

    private val bar = 255
    private val tabs = 2163
    private val covered = Panel(bar, tabs)

    private val grid = ExploreWall.Read(
        searchTabSelected = true,
        searchTabTop = tabs,
        barVisible = true,
        barBottom = bar,
        gridVisible = true,
        typing = false,
    )

    /** What the wall itself does to the read: the thumbnails go quiet, the bar and the tabs do not. */
    private val underWall = grid.copy(gridVisible = false)

    /** The search page where it keeps the tab bar. On IG 452 it reads as null instead. */
    private val searchPage = grid.copy(barVisible = false, barBottom = null, gridVisible = false)

    private fun wall() = ExploreWall(releaseMs = 300)

    /** The search bar is the way past, so it is never covered, not even on the first frame. */
    @Test fun `the first sighting of the grid covers everything under the search bar`() {
        assertEquals(covered, wall().observe(grid, 1_000))
    }

    /** With no input the wall stays: only a change on screen takes it down. */
    @Test fun `the wall stays for as long as the page is there`() {
        val w = wall()
        w.observe(grid, 0)
        assertEquals(covered, w.observe(underWall, 600_000))
    }

    @Test fun `the search page keyboard takes the wall down at once`() {
        val w = wall()
        w.observe(grid, 0)
        assertNull(w.observe(underWall.copy(typing = true), 100))
    }

    /** IG 452: no tab bar on the search page, so every scan there is a null read. */
    @Test fun `the search page having no tab bar takes the wall down`() {
        val w = wall()
        w.observe(grid, 0)
        assertEquals(covered, w.observe(null, 100))
        assertNull(w.observe(null, 400))
    }

    @Test fun `the action bar leaving takes the wall down`() {
        val w = wall()
        w.observe(grid, 0)
        w.observe(searchPage, 100)
        assertNull(w.observe(searchPage, 400))
    }

    /** The tabs stay usable throughout. */
    @Test fun `another tab takes the wall down at once`() {
        val w = wall()
        w.observe(grid, 0)
        assertNull(w.observe(underWall.copy(searchTabSelected = false), 2_000))
    }

    /** The bypass a "search was tapped" latch would open. */
    @Test fun `returning from search to the grid puts the wall straight back`() {
        val w = wall()
        w.observe(grid, 0)
        w.observe(underWall.copy(typing = true), 100)
        assertEquals(covered, w.observe(grid, 9_000))
    }

    @Test fun `a bad read shorter than the release time does not disturb the wall`() {
        val w = wall()
        w.observe(grid, 0)
        assertEquals(covered, w.observe(null, 2_000))
        assertEquals(covered, w.observe(underWall, 2_200))
        assertEquals(covered, w.observe(null, 2_400))
        assertEquals(covered, w.observe(null, 2_500))
    }

    @Test fun `a read missing for the release time takes the wall down`() {
        val w = wall()
        w.observe(grid, 0)
        w.observe(null, 2_000)
        assertNull(w.observe(null, 2_300))
    }

    @Test fun `the wall does not start without a grid`() {
        assertNull(wall().observe(null, 0))
        assertNull(wall().observe(grid.copy(searchTabSelected = false), 0))
        assertNull(wall().observe(grid.copy(barVisible = false), 0))
        assertNull(wall().observe(grid.copy(typing = true), 0))
    }

    /** Search results may keep the action bar; they do not have the thumbnails. */
    @Test fun `the wall does not start on a page without thumbnails`() {
        assertNull(wall().observe(underWall, 0))
    }

    @Test fun `the wall does not start without its edges`() {
        assertNull(wall().observe(grid.copy(barBottom = null), 0))
        assertNull(wall().observe(grid.copy(searchTabTop = null), 0))
        assertNull(wall().observe(grid.copy(barBottom = tabs), 0))
    }

    @Test fun `the edges follow the bar and the tabs`() {
        val w = wall()
        w.observe(grid.copy(barBottom = 200, searchTabTop = 2_000), 0)
        assertEquals(covered, w.observe(underWall, 100))
    }

    @Test fun `an edge missing from a read keeps its last value`() {
        val w = wall()
        w.observe(grid, 0)
        assertEquals(covered, w.observe(underWall.copy(barBottom = null, searchTabTop = null), 100))
    }

    @Test fun `the wake-up comes for a pending release`() {
        val w = wall()
        w.observe(grid, 0)
        w.observe(null, 1_100)
        assertEquals(300L, w.delayUntilNext(1_100))
        assertEquals(100L, w.delayUntilNext(1_300))
    }

    @Test fun `a standing wall has nothing to wake for`() {
        val w = wall()
        w.observe(grid, 0)
        assertNull(w.delayUntilNext(5_000))
    }

    @Test fun `up tracks the wall and reset clears it`() {
        val w = wall()
        assertFalse(w.up)
        w.observe(grid, 0)
        assertTrue(w.up)
        w.reset()
        assertFalse(w.up)
        assertEquals(covered, w.observe(grid, 60_000))
    }
}
