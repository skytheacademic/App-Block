package com.appblock.engine

import com.appblock.engine.ExploreWall.Phase
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

    private val grid = ExploreWall.Read(
        searchTabSelected = true,
        searchTabTop = tabs,
        barVisible = true,
        barBottom = bar,
        gridVisible = true,
        typing = false,
    )

    /** What the wall itself does to the read: nodes under it go quiet, the tab bar does not. */
    private val underWall = grid.copy(barVisible = false, barBottom = null, gridVisible = false)

    private fun wall() = ExploreWall(countdownMs = 5_000, releaseMs = 300)

    @Test fun `the grid starts the countdown at 5`() {
        assertEquals(Phase.Counting(5, tabs), wall().observe(grid, 1_000))
    }

    @Test fun `the countdown runs 5 4 3 2 1`() {
        val w = wall()
        w.observe(grid, 0)
        val shown = listOf(0L, 999L, 1_000L, 2_500L, 3_999L, 4_999L)
            .map { (w.observe(underWall, it) as Phase.Counting).secondsLeft }
        assertEquals(listOf(5, 5, 4, 3, 2, 1), shown)
    }

    @Test fun `at zero the search bar is uncovered and the grid stays covered`() {
        val w = wall()
        w.observe(grid, 0)
        assertEquals(Phase.Open(bar, tabs), w.observe(grid.copy(gridVisible = false), 5_000))
    }

    /** With no input the wall stays: only a change on screen takes it down. */
    @Test fun `the open wall stays for as long as the grid is there`() {
        val w = wall()
        w.observe(grid, 0)
        assertEquals(Phase.Open(bar, tabs), w.observe(grid, 600_000))
    }

    @Test fun `the search page keyboard takes the wall down at once`() {
        val w = wall()
        w.observe(grid, 0)
        w.observe(grid, 5_000)
        assertEquals(Phase.None, w.observe(grid.copy(typing = true), 5_100))
    }

    @Test fun `the action bar leaving takes the wall down`() {
        val w = wall()
        w.observe(grid, 0)
        w.observe(grid, 5_000)
        val searchPage = grid.copy(barVisible = false, barBottom = null, gridVisible = false)
        w.observe(searchPage, 5_100)
        assertEquals(Phase.None, w.observe(searchPage, 5_400))
    }

    /** The tabs stay usable throughout, including during the countdown. */
    @Test fun `another tab takes the wall down at once, even mid-countdown`() {
        val w = wall()
        w.observe(grid, 0)
        assertEquals(Phase.None, w.observe(underWall.copy(searchTabSelected = false), 2_000))
    }

    /** The bypass a "search was tapped" latch would open. */
    @Test fun `returning from search to the grid starts again from 5`() {
        val w = wall()
        w.observe(grid, 0)
        w.observe(grid, 5_000)
        w.observe(grid.copy(typing = true), 5_100)
        assertEquals(Phase.Counting(5, tabs), w.observe(grid, 9_000))
    }

    @Test fun `the search bar under the wall does not end the countdown`() {
        val w = wall()
        w.observe(grid, 0)
        assertEquals(Phase.Counting(4, tabs), w.observe(underWall, 1_000))
        assertEquals(Phase.Counting(1, tabs), w.observe(underWall, 4_500))
    }

    @Test fun `one bad read does not disturb the countdown`() {
        val w = wall()
        w.observe(grid, 0)
        assertEquals(Phase.Counting(3, tabs), w.observe(null, 2_000))
        assertEquals(Phase.Counting(3, tabs), w.observe(underWall, 2_200))
        assertEquals(Phase.Counting(3, tabs), w.observe(null, 2_400))
        assertEquals(Phase.Counting(3, tabs), w.observe(null, 2_500))
    }

    @Test fun `a read missing for the release time takes the wall down`() {
        val w = wall()
        w.observe(grid, 0)
        w.observe(null, 2_000)
        assertEquals(Phase.None, w.observe(null, 2_300))
    }

    @Test fun `the wall does not start without a grid`() {
        assertEquals(Phase.None, wall().observe(null, 0))
        assertEquals(Phase.None, wall().observe(grid.copy(searchTabSelected = false), 0))
        assertEquals(Phase.None, wall().observe(grid.copy(barVisible = false), 0))
        assertEquals(Phase.None, wall().observe(grid.copy(typing = true), 0))
    }

    /** Search results may keep the action bar; they do not have the thumbnails. */
    @Test fun `the wall does not start on a page without thumbnails`() {
        assertEquals(Phase.None, wall().observe(grid.copy(gridVisible = false), 0))
    }

    @Test fun `the wall does not start without its edges`() {
        assertEquals(Phase.None, wall().observe(grid.copy(barBottom = null), 0))
        assertEquals(Phase.None, wall().observe(grid.copy(searchTabTop = null), 0))
        assertEquals(Phase.None, wall().observe(grid.copy(barBottom = tabs), 0))
    }

    @Test fun `the search bar edge follows the bar while it is visible`() {
        val w = wall()
        w.observe(grid.copy(barBottom = 200), 0)
        assertEquals(Phase.Open(bar, tabs), w.observe(grid, 5_000))
    }

    @Test fun `the wake-up lands on the next second`() {
        val w = wall()
        w.observe(grid, 1_000)
        assertEquals(1_000L, w.delayUntilNext(1_000))
        assertEquals(250L, w.delayUntilNext(2_750))
    }

    @Test fun `the wake-up comes sooner for a pending release`() {
        val w = wall()
        w.observe(grid, 0)
        w.observe(null, 1_100)
        assertEquals(300L, w.delayUntilNext(1_100))
        assertEquals(100L, w.delayUntilNext(1_300))
    }

    @Test fun `an open wall has nothing to wake for`() {
        val w = wall()
        w.observe(grid, 0)
        w.observe(grid, 5_000)
        assertNull(w.delayUntilNext(5_000))
    }

    @Test fun `up tracks the wall and reset clears it`() {
        val w = wall()
        assertFalse(w.up)
        w.observe(grid, 0)
        assertTrue(w.up)
        w.reset()
        assertFalse(w.up)
        assertEquals(Phase.Counting(5, tabs), w.observe(grid, 60_000))
    }
}
