package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One occlusion hold per display.
 *
 * The two directions this can fail, and both have already cost this project something:
 *  - **Release too eagerly** → the block drops on a display the user is still looking at. That is the
 *    bypass this class exists to prevent, and with one global hold it is *user-drivable*: tap the phone,
 *    the monitor's block screen comes down.
 *  - **Never release** → the block screen follows the user to the launcher on a display they left, with
 *    only its own Close button as the way out. That is the lock-out C-1's fix had to avoid.
 *
 * ## The mutation these tests are marked against (M1)
 *
 * None of these can "fail against today's code" literally — the class does not exist there. So the bite
 * standard is a **mutation**: implement [DisplayHolds] as **one shared [OcclusionHold]** keyed on one
 * global last package, i.e. lift today's single-display semantics verbatim into a map-shaped wrapper.
 * That is precisely what the service does today the instant a second display exists. Tests marked BITE
 * fail under M1; the rest are labelled guards and pass under it too.
 *
 * Device under test throughout: display 0 = the phone, display **3** = the monitor. The id 3 is
 * arbitrary on purpose — One UI 8 appears to regenerate DeX display ids per session, so no test may
 * assume 2.
 */
class DisplayHoldsTest {

    private val phone = 0
    private val monitor = 3
    private val limit = 60_000L

    private fun holds() = DisplayHolds<String>(holdLimitMs = limit)

    /** Everything is blockable unless it is the literal string "free". */
    private val blockable: (String) -> Boolean = { it != "free" }

    private val INSTAGRAM = "com.instagram.android"
    private val TIKTOK = "com.zhiliaoapp.musically"
    private val LAUNCHER = "com.sec.android.app.launcher"

    /**
     * Every read in this file stands for "the blocked app on this display is Instagram", so the hold is
     * justified by Instagram alone and [LAUNCHER] is the package that means *left*. That keeps each test
     * below saying exactly what it said when the hold armed on a single `lastPackage` string, so their
     * BITE/GUARD marks against M1 still hold — only the split-screen tests at the bottom need a set with
     * more than one package in it.
     */
    private val justifiedBy: (String) -> Set<String> = { setOf(INSTAGRAM) }

    // ---- the cross-display bypass ----

    /** BITE. The user-drivable bypass, verbatim: one tap on the phone must not uncover the monitor. */
    @Test fun `one display's foreground event does not release another display's hold`() {
        val h = holds()
        h.noteForegroundEvent(monitor, "com.instagram.android")
        h.effective(mapOf(monitor to "reel"), blockable, justifiedBy, covered = setOf(monitor), nowMs = 0)
        // The user taps the launcher — on the PHONE.
        h.noteForegroundEvent(phone, "com.sec.android.app.launcher")
        val out = h.effective(emptyMap(), blockable, justifiedBy, covered = setOf(monitor), nowMs = 1_000)
        assertEquals("reel", out[monitor])
    }

    /** GUARD. Retiring the cross-display release must not cost the real exit. */
    @Test fun `a display releases when its own foreground moves on`() {
        val h = holds()
        h.noteForegroundEvent(monitor, "com.instagram.android")
        h.effective(mapOf(monitor to "reel"), blockable, justifiedBy, setOf(monitor), 0)
        h.noteForegroundEvent(monitor, "com.sec.android.app.launcher")
        val out = h.effective(emptyMap(), blockable, justifiedBy, setOf(monitor), 1_000)
        assertNull(out[monitor])
    }

    // ---- the fold ----

    /** BITE. A fully-opaque overlay drops its display from the window list entirely. */
    @Test fun `a held display missing from the scan map is still folded and still blocks`() {
        val h = holds()
        h.effective(mapOf(monitor to "reel"), blockable, justifiedBy, setOf(monitor), 0)
        val out = h.effective(mapOf(phone to "free"), blockable, justifiedBy, setOf(monitor), 500)
        assertEquals("reel", out[monitor])
    }

    /** GUARD. The per-display form of today's `if (overlayView == null) release()` branch. */
    @Test fun `an uncovered display releases its hold and reports its raw read`() {
        val h = holds()
        h.effective(mapOf(monitor to "reel"), blockable, justifiedBy, setOf(monitor), 0)
        val out = h.effective(mapOf(monitor to "free"), blockable, justifiedBy, covered = emptySet(), nowMs = 500)
        assertEquals("free", out[monitor])
        assertFalse(h.isArmed(monitor))
    }

    /** GUARD. The core Gate B case, per display: behind our overlay the read comes back pruned. */
    @Test fun `a covered display with a pruned read keeps blocking`() {
        val h = holds()
        h.effective(mapOf(monitor to "reel"), blockable, justifiedBy, setOf(monitor), 0)
        val out = h.effective(mapOf(monitor to "free"), blockable, justifiedBy, setOf(monitor), 500)
        assertEquals("reel", out[monitor])
    }

    /**
     * BITE. `effective` returns `held ?: raw` and never drops the entry. This is what keeps
     * `instagramVisible` / `browserVisible` alive and the 5-second tick running — dropping non-blockable
     * reads would be a single-display fail-open introduced by a multi-display change.
     */
    @Test fun `a covered display whose read is not blockable keeps the raw read when nothing is held`() {
        val h = holds()
        val out = h.effective(mapOf(phone to "free"), blockable, justifiedBy, covered = setOf(phone), nowMs = 0)
        assertEquals("free", out[phone])
    }

    // ---- the backstop latch ----

    /**
     * BITE. The latch is per channel, so one display's working events cannot retire another's backstop.
     *
     * The event names **Instagram, not the launcher**, and that is load-bearing rather than cosmetic:
     * this test is about the backstop, so the moved-on test must not fire and decide it first. It named
     * the launcher until 2026-08-30, which was invisible only because the hold armed on whatever package
     * the event stream last said — so the armed package and the noted package were the same string by
     * construction and the moved-on test could never fire here. Once the hold arms on what the *read*
     * found, "the launcher is on screen" means exactly what it says.
     */
    @Test fun `each display retires its own backstop`() {
        val h = holds()
        h.noteForegroundEvent(phone, INSTAGRAM)
        h.effective(mapOf(phone to "x", monitor to "reel"), blockable, justifiedBy, setOf(phone, monitor), 0)
        val out = h.effective(emptyMap(), blockable, justifiedBy, setOf(phone, monitor), 2 * limit)
        assertEquals("x", out[phone])         // its backstop was retired by a real event
        assertNull(out[monitor])              // its own backstop is still armed, and expired
    }

    /** BITE. Guards C-1 returning through the side door: a late-created hold must inherit the latch. */
    @Test fun `a hold created after its display's first event inherits the retired backstop`() {
        val h = holds()
        h.noteForegroundEvent(monitor, "com.instagram.android")     // before any hold exists
        h.effective(mapOf(monitor to "reel"), blockable, justifiedBy, setOf(monitor), 0)
        val out = h.effective(emptyMap(), blockable, justifiedBy, setOf(monitor), 2 * limit)
        assertEquals("reel", out[monitor])
    }

    // ---- retain ----

    /** BITE. A departed display must not keep claiming an overlay. */
    @Test fun `retain evicts a departed display so its hold cannot claim an overlay`() {
        val h = holds()
        h.effective(mapOf(monitor to "reel"), blockable, justifiedBy, setOf(monitor), 0)
        assertTrue(h.isArmed(monitor))
        h.retain(setOf(phone))
        assertFalse(h.isArmed(monitor))
        assertFalse(monitor in h.armedDisplays)
    }

    /** BITE. A total enumeration failure must never uncover a live block. */
    @Test fun `retain ignores an empty live set`() {
        val h = holds()
        h.effective(mapOf(phone to "a", monitor to "b"), blockable, justifiedBy, setOf(phone, monitor), 0)
        h.retain(emptySet())
        val out = h.effective(emptyMap(), blockable, justifiedBy, setOf(phone, monitor), 100)
        assertEquals("a", out[phone])
        assertEquals("b", out[monitor])
    }

    /** BITE. A momentarily empty window map must not drop the phone's own block. */
    @Test fun `retain never evicts the default display`() {
        val h = holds()
        h.effective(mapOf(phone to "a"), blockable, justifiedBy, setOf(phone), 0)
        h.retain(setOf(monitor))
        assertEquals("a", h.effective(emptyMap(), blockable, justifiedBy, setOf(phone), 100)[phone])
    }

    /** BITE. A returning id is not evidence the old display's event channel worked. */
    @Test fun `a re-used display id starts with a fresh backstop and a fresh package`() {
        val h = holds()
        h.noteForegroundEvent(monitor, "com.instagram.android")
        h.retain(setOf(phone))
        assertTrue(h.backstopArmed(monitor))
        assertNull(h.packageOn(monitor))
        h.effective(mapOf(monitor to "reel"), blockable, justifiedBy, setOf(monitor), 0)
        assertNull(h.effective(emptyMap(), blockable, justifiedBy, setOf(monitor), 2 * limit)[monitor])
    }

    /**
     * BITE. **Invariant I1 as a test.** Event coalescing is keyed by event type alone with no display
     * dimension, so no scan may ever be selected by an event's display id — every display in the map is
     * folded whether or not an event has ever named it.
     */
    @Test fun `every display in the map is folded, including one no event has ever named`() {
        val h = holds()
        h.noteForegroundEvent(phone, "com.sec.android.app.launcher")
        val covered = h.effective(mapOf(phone to "a", monitor to "b"), blockable, justifiedBy, setOf(phone, monitor), 0)
        assertEquals(setOf(phone, monitor), covered.keys)
        val uncovered = h.effective(mapOf(phone to "a", monitor to "b"), blockable, justifiedBy, emptySet(), 10)
        assertEquals(setOf(phone, monitor), uncovered.keys)
        assertFalse(h.isArmed(monitor))
    }

    // ---- lifecycle ----

    /** GUARD. Mirrors `OcclusionHoldTest`'s equivalent, at map level. */
    @Test fun `seeding does not restart a display's countdown or overwrite what is held`() {
        val h = holds()
        h.seed(monitor, "first", setOf(INSTAGRAM), 0)
        h.seed(monitor, "second", setOf(INSTAGRAM), 30_000)
        val out = h.effective(emptyMap(), blockable, justifiedBy, setOf(monitor), 30_000)
        assertEquals("first", out[monitor])
        // The countdown still measures from the first seed, so it expires at the original deadline.
        assertNull(h.effective(emptyMap(), blockable, justifiedBy, setOf(monitor), limit)[monitor])
    }

    /** GUARD. */
    @Test fun `releaseAll drops every display`() {
        val h = holds()
        h.effective(mapOf(phone to "a", monitor to "b"), blockable, justifiedBy, setOf(phone, monitor), 0)
        h.releaseAll()
        assertEquals(emptySet<Int>(), h.armedDisplays)
    }

    /**
     * GUARD — and deliberately so. This is the executable form of *"provably a no-op on a single
     * display"*, which is the invariant the whole change is allowed to ship on. It must pass under M1
     * too; that is exactly the point of it.
     */
    @Test fun `single-display parity - DisplayHolds drives display 0 exactly as one OcclusionHold`() {
        val one = OcclusionHold<String>(limit)
        val many = holds()
        // (read, event package or null, covered, now) — includes a pruned read, a move-on event, the
        // backstop retirement and a seed.
        val script = listOf(
            Script("reel", null, true, 0),
            Script("free", null, true, 500),
            Script("reel", "com.instagram.android", true, 1_000),
            Script("free", null, true, 1_500),
            Script("free", null, false, 2_000),
            Script("reel", null, true, 2_500),
            Script("free", null, true, limit + 3_000),
        )
        for (step in script) {
            step.eventPackage?.let {
                one.noteForegroundEvent()
                many.noteForegroundEvent(phone, it)
            }
            val expected = single(one, step, lastPackage(script, step))
            val actual = many.effective(mapOf(phone to step.read), blockable, justifiedBy, coveredOf(step), step.now)
            assertEquals("at t=${step.now}", expected, actual[phone])
        }
    }

    private data class Script(
        val read: String,
        val eventPackage: String?,
        val covered: Boolean,
        val now: Long,
    )

    private fun coveredOf(step: Script) = if (step.covered) setOf(phone) else emptySet()

    private fun lastPackage(script: List<Script>, upTo: Script): String? =
        script.takeWhile { it.now <= upTo.now }.mapNotNull { it.eventPackage }.lastOrNull()

    /** Today's `holdThroughOcclusion` body, run against a bare [OcclusionHold] for comparison. */
    private fun single(hold: OcclusionHold<String>, step: Script, pkg: String?): String? {
        if (!step.covered) {
            hold.release()
            return step.read
        }
        if (blockable(step.read)) {
            hold.arm(step.read, setOfNotNull(pkg), step.now)
            return step.read
        }
        return hold.sustain(pkg, step.now) ?: step.read
    }

    // ---- split-screen: two budgeted apps, one display (measured on hardware 2026-08-30) ----
    //
    // The mutation these are marked against (M2) is the shipped-and-measured behaviour: arm the hold on
    // ONE package — whichever the event stream named most recently — instead of the set the read found.
    // Under M2 the first test fails on the very first pass, which is what the phone did: the block
    // screen dropped for ~120 ms every 2-3 s in split-screen, visible to the naked eye.

    /** BITE. The other pane's events are not the user leaving. This is the measured defect, verbatim. */
    @Test fun `an event from the other budgeted pane does not release the hold`() {
        val h = holds()
        val bothPanes: (String) -> Set<String> = { setOf(INSTAGRAM, TIKTOK) }
        h.noteForegroundEvent(phone, INSTAGRAM)
        h.effective(mapOf(phone to "x"), blockable, bothPanes, setOf(phone), 0)
        // TikTok is in the other split-screen pane and emits its own window-state events, forever.
        h.noteForegroundEvent(phone, TIKTOK)
        val out = h.effective(emptyMap(), blockable, bothPanes, setOf(phone), 1_000)
        assertEquals("x", out[phone])
        assertTrue(h.isArmed(phone))
    }

    /** GUARD. Holding for two panes must not cost the real exit — Home still releases. */
    @Test fun `leaving both panes for the launcher still releases`() {
        val h = holds()
        val bothPanes: (String) -> Set<String> = { setOf(INSTAGRAM, TIKTOK) }
        h.noteForegroundEvent(phone, INSTAGRAM)
        h.effective(mapOf(phone to "x"), blockable, bothPanes, setOf(phone), 0)
        h.noteForegroundEvent(phone, LAUNCHER)
        val out = h.effective(emptyMap(), blockable, bothPanes, setOf(phone), 1_000)
        assertNull(out[phone])
        assertFalse(h.isArmed(phone))
    }

    /**
     * BITE. A blocked URL has no app target at all, so its hold is justified by the browser alone.
     * Under an implementation that only ever collects *app* packages this set is empty and the hold
     * dies on Chrome's own next event — the block screen flickering on every page it blocks.
     */
    @Test fun `a web block is justified by the browser package`() {
        val h = holds()
        val chrome = "com.android.chrome"
        val byBrowser: (String) -> Set<String> = { setOf(chrome) }
        h.noteForegroundEvent(phone, chrome)
        h.effective(mapOf(phone to "x"), blockable, byBrowser, setOf(phone), 0)
        h.noteForegroundEvent(phone, chrome)
        val out = h.effective(emptyMap(), blockable, byBrowser, setOf(phone), 1_000)
        assertEquals("x", out[phone])
    }

    /**
     * GUARD, and it documents a deliberate choice rather than an accident. An empty justification set
     * means "no basis to hold against a named package", and the safe reading of that is *release*:
     * releasing wrongly costs a frame and the next read restores the overlay, whereas holding wrongly is
     * a block screen with no exit once the backstop has stood down.
     */
    @Test fun `an empty justification set releases on the first named package`() {
        val h = holds()
        val nothing: (String) -> Set<String> = { emptySet() }
        h.noteForegroundEvent(phone, INSTAGRAM)
        h.effective(mapOf(phone to "x"), blockable, nothing, setOf(phone), 0)
        h.noteForegroundEvent(phone, INSTAGRAM)
        val out = h.effective(emptyMap(), blockable, nothing, setOf(phone), 1_000)
        assertNull(out[phone])
    }

    /** GUARD. The cross-display rule still holds when each display has its own pair of panes. */
    @Test fun `split-screen on one display does not hold another display open`() {
        val h = holds()
        val bothPanes: (String) -> Set<String> = { setOf(INSTAGRAM, TIKTOK) }
        h.noteForegroundEvent(monitor, INSTAGRAM)
        h.effective(mapOf(monitor to "x"), blockable, bothPanes, setOf(monitor), 0)
        h.noteForegroundEvent(monitor, LAUNCHER)
        val out = h.effective(emptyMap(), blockable, bothPanes, setOf(monitor), 1_000)
        assertNull(out[monitor])
    }
}
