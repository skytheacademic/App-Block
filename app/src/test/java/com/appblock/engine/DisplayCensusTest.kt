package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display census: scan order, the backfill rule, event attribution, the cross-display target merge,
 * and the diagnostic renderings.
 *
 * This file guards two things that fail **silently**:
 *  - **An ordering that flaps.** [DisplayCensus.mergeTargets] feeds `onForegroundTargets`, which banks
 *    time and re-picks the accruing target every time the list changes. An order that varied between
 *    passes on unchanged content would re-bank every pass and split one sitting between two budgets, so
 *    neither ever reaches its cap — a loosening failure produced by nothing but a map's iteration order.
 *  - **An instrument that lies.** Already on this repo's record: `diagnose`'s `HELD` field once reported
 *    zero while the hold was doing its job. The census carries the one field that can invalidate the
 *    whole detection half, so it may not be the untested part.
 *
 * ## The mutation these are marked against (M3)
 *
 * `order = displayIds.sorted()` with no default-display promotion; `mergeTargets = values.flatten()`;
 * `attribute = eventDisplayId ?: 0`; no backfill; and a census that reports only what accessibility
 * returned. BITE = fails under M3.
 */
class DisplayCensusTest {

    private val phone = 0
    private val monitor = 3

    private fun display(
        id: Int,
        enumerated: Boolean = true,
        windowCount: Int? = 0,
        topPackage: String? = null,
        target: String? = null,
        overlay: DisplayCensus.Overlay = DisplayCensus.Overlay.NONE,
    ) = DisplayCensus.Display(
        id = id,
        enumerated = enumerated,
        windowCount = windowCount,
        topPackage = topPackage,
        target = target,
        overlay = overlay,
    )

    // ---- mergeTargets ----

    /** GUARD. The identity assertion the whole change rests on: one display changes nothing. */
    @Test fun `one display merges to exactly the list it was given`() {
        val given = listOf(Target.INSTAGRAM_APP, Target.INSTAGRAM_REELS_EXPLORE)
        assertEquals(given, DisplayCensus.mergeTargets(mapOf(phone to given)))
    }

    /**
     * BITE — against `values.flatten()`, whose order is the map's rather than the device's.
     *
     * Note what it does *not* catch: replacing [DisplayCensus.order]'s explicit default-display
     * promotion with a plain ascending sort. Real display ids are non-negative, so 0 sorts first
     * anyway and that mutation is behaviourally identical. Said out loud because the promotion looks
     * load-bearing and is not.
     */
    @Test fun `the default display leads the merged order`() {
        val fedInReverse = LinkedHashMap<Int, List<Target>>().apply {
            put(monitor, listOf(Target.TIKTOK))
            put(phone, listOf(Target.INSTAGRAM_REELS_EXPLORE))
        }
        assertEquals(
            listOf(Target.INSTAGRAM_REELS_EXPLORE, Target.TIKTOK),
            DisplayCensus.mergeTargets(fedInReverse),
        )
    }

    /** BITE. The accrual test in disguise — see the class KDoc. */
    @Test fun `the merged order is stable for equal content`() {
        val a = LinkedHashMap<Int, List<Target>>().apply {
            put(phone, listOf(Target.INSTAGRAM_APP))
            put(monitor, listOf(Target.TIKTOK))
        }
        val b = LinkedHashMap<Int, List<Target>>().apply {
            put(monitor, listOf(Target.TIKTOK))
            put(phone, listOf(Target.INSTAGRAM_APP))
        }
        assertEquals(DisplayCensus.mergeTargets(a), DisplayCensus.mergeTargets(b))
    }

    /** GUARD. */
    @Test fun `first occurrence wins across displays`() {
        assertEquals(
            listOf(Target.INSTAGRAM_APP, Target.TIKTOK),
            DisplayCensus.mergeTargets(
                mapOf(
                    phone to listOf(Target.INSTAGRAM_APP),
                    monitor to listOf(Target.INSTAGRAM_APP, Target.TIKTOK),
                ),
            ),
        )
    }

    // ---- the backfill invariant ----

    /** BITE. **Invariant I2** — display 0's detection can never be worse than it is today. */
    @Test fun `display 0 is backfilled when the all-displays read omits it`() {
        assertTrue(DisplayCensus.mustBackfillDefault(setOf(monitor)))
        assertFalse(DisplayCensus.mustBackfillDefault(setOf(phone, monitor)))
        assertTrue(DisplayCensus.mustBackfillDefault(emptySet()))
    }

    // ---- event attribution ----

    /** BITE. Filing an unattributable event under display 0 would let it release the phone's hold. */
    @Test fun `an event the framework could not attribute is dropped, not filed under display 0`() {
        assertNull(DisplayCensus.attribute(null))
        assertNull(DisplayCensus.attribute(-1))          // Display.INVALID_DISPLAY
        assertEquals(monitor, DisplayCensus.attribute(monitor))
        assertEquals(phone, DisplayCensus.attribute(phone))
    }

    // ---- the instrument ----

    /**
     * BITE, and the single most valuable assertion here. It guarantees the log answers the load-bearing
     * open question — *does accessibility track the DeX display at all?* — on the first plug-in, instead
     * of leaving it silent. `untracked=[3]` means the detection half is dead on this hardware.
     */
    @Test fun `the census names a display DisplayManager listed but accessibility did not track`() {
        val line = DisplayCensus.line(
            displays = listOf(display(phone, windowCount = 4), display(monitor, windowCount = null)),
            allDisplaysApi = true,
            cover = setOf(phone),
            covered = setOf(phone),
            holds = "0=-",
        )
        assertTrue(line, line.contains("dm=[0, 3]"))
        assertTrue(line, line.contains("untracked=[3]"))
    }

    /** GUARD. On an Android 16 phone `api=legacy` means the SDK guard is inverted and the fix never ran. */
    @Test fun `the census says which window-map API produced it`() {
        val displays = listOf(display(phone))
        assertTrue(DisplayCensus.line(displays, true, emptySet(), emptySet(), "").contains("api=all"))
        assertTrue(DisplayCensus.line(displays, false, emptySet(), emptySet(), "").contains("api=legacy"))
    }

    /**
     * BITE. Keeps the `AppBlockFg` line character-for-character what it is today on a phone with no
     * monitor, so every existing log note and grep in the repo stays valid and the line's own
     * string-equality dedup does not start churning.
     */
    @Test fun `a single display adds no +d blocks to the foreground line`() {
        assertEquals("", DisplayCensus.blocks(listOf(display(phone, windowCount = 4))))
        val two = DisplayCensus.blocks(
            listOf(display(phone, windowCount = 4), display(monitor, windowCount = 3, target = "TIKTOK")),
        )
        assertTrue(two, two.startsWith(" +d3{"))
        assertTrue(two, two.contains("target=TIKTOK"))
    }

    /** GUARD. The caller dedups on string equality; an instrument that floods its own log is not one. */
    @Test fun `the census line is stable for an unchanged state`() {
        val displays = listOf(display(phone, windowCount = 4), display(monitor, windowCount = 3))
        val once = DisplayCensus.line(displays, true, setOf(monitor), setOf(monitor), "3=HELD")
        val twice = DisplayCensus.line(displays, true, setOf(monitor), setOf(monitor), "3=HELD")
        assertEquals(once, twice)
    }

    // ---- the plug-in and unplug transients (2026-10-01) ----

    /**
     * BITE. The attach frame: a monitor that only just appeared and has no window list yet must NOT read
     * `untracked=[3]` — that is the A1 stop-condition, and it resolved 22 ms later on the S25.
     */
    @Test fun `a display that only just appeared reads as settling, not untracked`() {
        val displays = listOf(display(phone), display(monitor, windowCount = null))
        val line = DisplayCensus.line(displays, true, emptySet(), emptySet(), "", settling = setOf(monitor))
        assertTrue(line, line.contains("untracked=[] "))
        assertTrue(line, line.contains("settling=[3]"))
    }

    /** GUARD. Once settled, a display that is still blind says so in the field the stop-condition reads. */
    @Test fun `a settled display that is still blind is untracked`() {
        val displays = listOf(display(phone), display(monitor, windowCount = null))
        val line = DisplayCensus.line(displays, true, emptySet(), emptySet(), "", settling = emptySet())
        assertTrue(line, line.contains("untracked=[3]"))
        assertFalse(line, line.contains("settling="))
    }

    /** GUARD. Settling is only ever said about a blind display; a tracked one has nothing to explain. */
    @Test fun `a settling display that is already tracked adds no field`() {
        val displays = listOf(display(phone), display(monitor, windowCount = 2))
        val line = DisplayCensus.line(displays, true, emptySet(), emptySet(), "", settling = setOf(monitor))
        assertFalse(line, line.contains("settling="))
    }

    /** BITE. The detach frame: a covered id that no longer exists is named, so `sat=false` has a reason. */
    @Test fun `a covered display that is gone is named`() {
        val line = DisplayCensus.line(listOf(display(phone)), true, setOf(monitor), emptySet(), "")
        assertTrue(line, line.contains("cover=[3]"))
        assertTrue(line, line.contains("gone=[3]"))
    }

    @Test fun `no gone field while every covered display exists`() {
        val displays = listOf(display(phone), display(monitor, windowCount = 2))
        val line = DisplayCensus.line(displays, true, setOf(monitor), setOf(monitor), "")
        assertFalse(line, line.contains("gone="))
    }

    /** GUARD. A display present when the service starts was not "just plugged in" — blind from boot shows. */
    @Test fun `displays present at the first observation never settle`() {
        val arrivals = DisplayCensus.Arrivals(settleMs = 250)
        assertEquals(emptySet<Int>(), arrivals.observe(setOf(phone, monitor), nowMs = 1_000))
    }

    @Test fun `a display that appears later settles for the settle time, then stops`() {
        val arrivals = DisplayCensus.Arrivals(settleMs = 250)
        arrivals.observe(setOf(phone), nowMs = 1_000)
        assertEquals(setOf(monitor), arrivals.observe(setOf(phone, monitor), nowMs = 2_000))
        assertEquals(setOf(monitor), arrivals.observe(setOf(phone, monitor), nowMs = 2_249))
        assertEquals(emptySet<Int>(), arrivals.observe(setOf(phone, monitor), nowMs = 2_250))
    }

    /** A monitor unplugged and plugged back in is new again — its window list starts from nothing too. */
    @Test fun `a re-plugged display settles again`() {
        val arrivals = DisplayCensus.Arrivals(settleMs = 250)
        arrivals.observe(setOf(phone, monitor), nowMs = 1_000)
        arrivals.observe(setOf(phone), nowMs = 2_000)
        assertEquals(setOf(monitor), arrivals.observe(setOf(phone, monitor), nowMs = 3_000))
    }
}
