package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay bookkeeping — which display holds a view, which window manager may remove it, and whether
 * what we think is covered actually is.
 *
 * The two ways this fails:
 *  - **A view that can never be removed** (removed through the wrong window manager, or never detached
 *    because its display vanished) — a permanent cover with no exit, which is worse than the bug.
 *  - **A display that believes it is covered when it is not** — a silent bypass, and precisely what
 *    `return overlayView != null` becomes once two overlays can exist.
 *
 * ## The mutation these are marked against (M4)
 *
 * One shared window manager for every remove; `remove` computed from the live display list rather than
 * from what is attached; and one shared key/facts cache across displays. BITE = fails under M4.
 *
 * Robolectric was rejected for this: its multi-display shadowing is thin, so a green test there would be
 * evidence about Robolectric rather than about One UI. The bug class here is bookkeeping, and a fake
 * exercises bookkeeping exactly.
 */
class DisplayOverlaysTest {

    private val phone = 0
    private val monitor = 3

    private class FakeWm(val id: Int) {
        override fun toString() = "wm$id"
    }

    private class FakeView(val displayId: Int) {
        var message: CharSequence = ""
        var facts: String = ""
        override fun toString() = "view$displayId"
    }

    /** Records every platform call, so the ORDER and the pairing can be asserted, not just the outcome. */
    private class FakeHost {
        var mayDraw = true
        var addSucceeds: (Int) -> Boolean = { true }
        var removeThrowsOn: Int? = null
        val liveDisplays = linkedSetOf(0, 3)
        val calls = mutableListOf<String>()
        val managers = HashMap<Int, FakeWm>()

        /**
         * `WindowManager.removeView` dispatches `onViewDetachedFromWindow`, and the service's listener
         * calls straight back into `noteDetached`. Modelling that re-entrancy is the point: the guard
         * that our own removal is a no-op is only worth anything if the callback actually happens.
         */
        var dispatchDetachOnRemove = false
        var overlaysRef: DisplayOverlays<FakeWm, FakeView, String>? = null

        /** Every view minted per display, newest last. */
        val inflated = HashMap<Int, MutableList<FakeView>>()

        /** What `covered()` reported at the instant each detach was dispatched. */
        val coveredDuringDetach = mutableListOf<Set<Int>>()

        fun wmFor(id: Int): FakeWm? =
            if (id in liveDisplays) managers.getOrPut(id) { FakeWm(id) } else null

        fun overlays() = buildOverlays().also { overlaysRef = it }

        private fun buildOverlays() = DisplayOverlays<FakeWm, FakeView, String>(
            mayDraw = { mayDraw },
            defaultWindowManager = { wmFor(0) },
            secondaryWindowManager = { wmFor(it) },
            inflate = { id, _ -> FakeView(id).also { inflated.getOrPut(id) { mutableListOf() }.add(it) } },
            add = { wm, view ->
                calls.add("add $wm $view")
                addSucceeds(view.displayId)
            },
            remove = { wm, view ->
                calls.add("remove $wm $view")
                if (dispatchDetachOnRemove) overlaysRef?.let {
                    coveredDuringDetach.add(it.covered())
                    it.noteDetached(view.displayId, view)
                }
                if (view.displayId == removeThrowsOn) throw IllegalStateException("detached")
            },
            bindMessage = { view, msg -> calls.add("msg $view"); view.message = msg },
            bindFacts = { view, facts -> calls.add("facts $view"); view.facts = facts },
        )
    }

    /**
     * The view the fake attached to [displayId]. `inflate` mints a fresh [FakeView] per attach, so this
     * is what the service's `OnAttachStateChangeListener` would be handed — the only honest way to test
     * the identity check rather than asserting against a view we invented.
     */
    private fun FakeHost.attachedView(displayId: Int): FakeView =
        inflated.getValue(displayId).last()

    private fun content(key: String, message: String = key, facts: String = key) =
        DisplayOverlays.Content(message = message, key = key, facts = facts)

    // ---- reconcile ----

    /** BITE. Also pins the order: the stale view goes before anything new is attached. */
    @Test fun `an overlay is added on a display that wants one and removed from one that does not`() {
        val host = FakeHost()
        val overlays = host.overlays()
        assertEquals(setOf(phone), overlays.reconcile(mapOf(phone to content("a"))))
        host.calls.clear()
        assertEquals(setOf(monitor), overlays.reconcile(mapOf(monitor to content("a"))))
        val removeAt = host.calls.indexOfFirst { it.startsWith("remove") }
        val addAt = host.calls.indexOfFirst { it.startsWith("add") }
        assertTrue(host.calls.toString(), removeAt in 0 until addAt)
    }

    /** BITE. Getting this wrong leaks a view that cannot be taken down — a cover with no exit. */
    @Test fun `the window manager that removes a view is the one that added it`() {
        val host = FakeHost()
        val overlays = host.overlays()
        overlays.reconcile(mapOf(phone to content("a"), monitor to content("a")))
        host.calls.clear()
        overlays.reconcile(mapOf(phone to content("a")))
        assertEquals(listOf("remove wm3 view3"), host.calls.filter { it.startsWith("remove") })
    }

    /** BITE. **Invariant I4** — the monitor is unplugged, so no enumeration lists it any more. */
    @Test fun `a view on a display that has vanished is still removed`() {
        val host = FakeHost()
        val overlays = host.overlays()
        overlays.reconcile(mapOf(phone to content("a"), monitor to content("a")))
        host.liveDisplays.remove(monitor)          // the monitor is gone from every enumeration
        host.calls.clear()
        overlays.reconcile(mapOf(phone to content("a")))
        assertEquals(listOf("remove wm3 view3"), host.calls.filter { it.startsWith("remove") })
        assertEquals(setOf(phone), overlays.covered())
    }

    /** BITE. A refused add must not read as covered — and the retry needs no extra state. */
    @Test fun `a failed add leaves the display uncovered and reported as failed`() {
        val host = FakeHost()
        host.addSucceeds = { it != monitor }
        val overlays = host.overlays()
        assertEquals(setOf(phone), overlays.reconcile(mapOf(phone to content("a"), monitor to content("a"))))
        assertEquals(setOf(monitor), overlays.failed())
        assertFalse(DisplayCoverage.satisfied(setOf(phone, monitor), overlays.covered()))
        host.addSucceeds = { true }
        assertEquals(
            setOf(phone, monitor),
            overlays.reconcile(mapOf(phone to content("a"), monitor to content("a"))),
        )
        assertEquals(emptySet<Int>(), overlays.failed())
    }

    /**
     * BITE. Catches the shared-cache bug: with one service-wide key/facts pair the second display's view
     * compares equal, the write is skipped, and the DeX block screen renders the layout's placeholder
     * fact rows forever — rows whose own record is that they quoted a wrong price.
     */
    @Test fun `each display caches its own key and facts`() {
        val host = FakeHost()
        val overlays = host.overlays()
        overlays.reconcile(
            mapOf(
                phone to content("web", message = "site blocked", facts = "72h"),
                monitor to content("budget", message = "out of minutes", facts = "04:00"),
            ),
        )
        assertEquals("site blocked", overlays.contentOn(phone)?.message)
        assertEquals("out of minutes", overlays.contentOn(monitor)?.message)
        assertEquals("72h", overlays.contentOn(phone)?.facts)
        assertEquals("04:00", overlays.contentOn(monitor)?.facts)
    }

    /** GUARD. Preserves today's key-gated message and always-compared facts behaviour. */
    @Test fun `an unchanged tick writes nothing`() {
        val host = FakeHost()
        val overlays = host.overlays()
        overlays.reconcile(mapOf(phone to content("budget", facts = "in 8 h")))
        host.calls.clear()
        overlays.reconcile(mapOf(phone to content("budget", facts = "in 8 h")))
        assertEquals(emptyList<String>(), host.calls)
        // The cause has not changed but the countdown has: rewrite the facts, leave the message alone.
        overlays.reconcile(mapOf(phone to content("budget", facts = "in 7 h")))
        assertEquals(listOf("facts view0"), host.calls)
    }

    /** GUARD. Same gate and same position in the order as today's `Settings.canDrawOverlays` check. */
    @Test fun `a revoked draw permission stops a new overlay and leaves the existing ones up`() {
        val host = FakeHost()
        val overlays = host.overlays()
        overlays.reconcile(mapOf(phone to content("a")))
        host.mayDraw = false
        assertEquals(setOf(phone), overlays.reconcile(mapOf(phone to content("a"), monitor to content("a"))))
    }

    /** GUARD. A throwing `removeView` must not leave a display believing it is still covered. */
    @Test fun `removeAll detaches everything, tolerating a throwing remove`() {
        val host = FakeHost()
        host.removeThrowsOn = phone
        val overlays = DisplayOverlays<FakeWm, FakeView, String>(
            mayDraw = { host.mayDraw },
            defaultWindowManager = { host.wmFor(0) },
            secondaryWindowManager = { host.wmFor(it) },
            inflate = { id, _ -> FakeView(id) },
            add = { _, _ -> true },
            // The service wraps removeView in runCatching; mirror that here rather than pretending
            // the platform call cannot throw.
            remove = { wm, view -> runCatching { host.calls.add("remove $wm $view"); if (view.displayId == host.removeThrowsOn) error("detached") } },
            bindMessage = { view, msg -> view.message = msg },
            bindFacts = { view, facts -> view.facts = facts },
        )
        overlays.reconcile(mapOf(phone to content("a"), monitor to content("a")))
        host.calls.clear()
        overlays.removeAll()
        assertEquals(emptySet<Int>(), overlays.covered())
        assertTrue(host.calls.toString(), host.calls.any { it == "remove wm3 view3" })
    }

    // ---- noteDetached: covered() is a fact, not a memory of one ----
    //
    // Finding [P1] 2026-08-30. The audit reported it against `showOverlay`'s old
    // `return overlayView != null`; that expression is gone, but the SHAPE survived the multi-display
    // rewrite — `covered()` was the set of adds that had once returned true, and nothing ever asked
    // again. Every test below is one question: can this class claim a display is covered when it isn't?

    /**
     * BITE. The defect itself. One UI takes our window down — a revoked "Appear on top", a display
     * teardown we did not see — and without this the block screen is gone while the engine is certain
     * it is blocking.
     */
    @Test fun `a view detached by the platform stops counting as covered`() {
        val host = FakeHost()
        val overlays = host.overlays()
        overlays.reconcile(mapOf(phone to content("a")))
        val view = host.attachedView(phone)
        overlays.noteDetached(phone, view)
        assertEquals(emptySet<Int>(), overlays.covered())
    }

    /**
     * BITE, and this is the consequence that matters. The two callers the memory was lying to: a
     * silently-detached phone overlay must make `satisfied` false and the kick-to-home fire.
     */
    @Test fun `a silent detach reaches satisfied and the home fallback`() {
        val host = FakeHost()
        val overlays = host.overlays()
        val want = setOf(phone)
        overlays.reconcile(mapOf(phone to content("a")))
        assertTrue(DisplayCoverage.satisfied(want, overlays.covered()))
        assertFalse(DisplayCoverage.homeFallback(want, overlays.covered(), phone))

        overlays.noteDetached(phone, host.attachedView(phone))

        assertFalse(DisplayCoverage.satisfied(want, overlays.covered()))
        assertTrue(DisplayCoverage.homeFallback(want, overlays.covered(), phone))
    }

    /** BITE. Self-healing: the next pass puts it back in `plan.add`, with no extra state to hold. */
    @Test fun `a detached display is re-attached on the next reconcile`() {
        val host = FakeHost()
        val overlays = host.overlays()
        overlays.reconcile(mapOf(phone to content("a")))
        overlays.noteDetached(phone, host.attachedView(phone))
        host.calls.clear()

        assertEquals(setOf(phone), overlays.reconcile(mapOf(phone to content("a"))))
        assertTrue(host.calls.toString(), host.calls.any { it.startsWith("add wm0") })
    }

    /** BITE. And it must be visible: `failed()` is what prints `ov=FAIL` in the census line. */
    @Test fun `a detached display is reported as failed, not merely absent`() {
        val host = FakeHost()
        val overlays = host.overlays()
        overlays.reconcile(mapOf(phone to content("a"), monitor to content("a")))
        overlays.noteDetached(monitor, host.attachedView(monitor))
        assertEquals(setOf(monitor), overlays.failed())
        assertEquals(setOf(phone), overlays.covered())
    }

    /**
     * GUARD, and the one that keeps the fix from being worse than the bug. Our own `hideOn` calls
     * `removeView`, which dispatches the detach straight back in here. If that echo were treated as a
     * platform teardown, every ordinary hide would mark the display FAILED and the next tick would
     * re-add an overlay the engine had just decided to take down — a block screen that will not close.
     */
    @Test fun `our own hideOn does not mark the display failed through the detach echo`() {
        val host = FakeHost()
        val overlays = host.overlays()
        host.dispatchDetachOnRemove = true
        overlays.reconcile(mapOf(phone to content("a")))

        overlays.hideOn(phone)

        assertEquals(emptySet<Int>(), overlays.covered())
        assertEquals(emptySet<Int>(), overlays.failed())
    }

    /** GUARD. Same echo, via the reconcile path that removes a display the plan no longer wants. */
    @Test fun `reconciling a display away does not mark it failed through the detach echo`() {
        val host = FakeHost()
        val overlays = host.overlays()
        host.dispatchDetachOnRemove = true
        overlays.reconcile(mapOf(phone to content("a"), monitor to content("a")))

        assertEquals(setOf(phone), overlays.reconcile(mapOf(phone to content("a"))))
        assertEquals(emptySet<Int>(), overlays.failed())
    }

    /**
     * GUARD. The identity check, and why it is not paranoia: if the platform posts the detach instead of
     * dispatching it inline, it can land after a *new* view is already attached for that id. Acting on
     * the old view's echo would take down a live, correct overlay — the bug this class exists to
     * prevent, introduced by its own fix.
     */
    @Test fun `a late detach for a replaced view leaves the live overlay alone`() {
        val host = FakeHost()
        val overlays = host.overlays()
        overlays.reconcile(mapOf(phone to content("a")))
        val stale = host.attachedView(phone)
        overlays.hideOn(phone)
        overlays.reconcile(mapOf(phone to content("b")))
        val live = host.attachedView(phone)
        assertTrue("the fake must hand out a fresh view", stale !== live)

        overlays.noteDetached(phone, stale)

        assertEquals(setOf(phone), overlays.covered())
        assertEquals(emptySet<Int>(), overlays.failed())
    }

    /** GUARD. Nothing attached there, nothing to correct — and certainly nothing to call FAILED. */
    @Test fun `noteDetached for an uncovered display is a no-op`() {
        val host = FakeHost()
        val overlays = host.overlays()
        overlays.reconcile(mapOf(phone to content("a")))

        overlays.noteDetached(monitor, FakeView(monitor))

        assertEquals(setOf(phone), overlays.covered())
        assertEquals(emptySet<Int>(), overlays.failed())
    }

    /** GUARD. One display's teardown says nothing about the other's — the whole reason this is a map. */
    @Test fun `a detach on one display leaves the other covered`() {
        val host = FakeHost()
        val overlays = host.overlays()
        overlays.reconcile(mapOf(phone to content("a"), monitor to content("a")))

        overlays.noteDetached(phone, host.attachedView(phone))

        assertEquals(setOf(monitor), overlays.covered())
    }

    /**
     * BITE (M5). The ordering *inside* `hideOn`, pinned directly rather than through its outcome.
     *
     * The echo guard is carried by two mechanisms that cover for each other — the record is dropped
     * before `remove` is called, **and** `hideOn` clears the failed mark afterwards — so reversing
     * either one alone changes no result and no outcome-level test can see it. That redundancy is
     * fine to have and dangerous to rely on unexamined, so each half gets a test of its own: this one
     * says the callback finds nothing left to act on, and the next says the mark is cleared.
     */
    @Test fun `our own removal drops the record before the platform detach can land`() {
        val host = FakeHost()
        val overlays = host.overlays()
        host.dispatchDetachOnRemove = true
        overlays.reconcile(mapOf(phone to content("a")))

        overlays.hideOn(phone)

        assertEquals(1, host.coveredDuringDetach.size)
        assertFalse(
            "the detach callback must not find a live record for the view being removed",
            phone in host.coveredDuringDetach.single(),
        )
    }

    /**
     * BITE (M4). The other half. A display we stop wanting must not stay marked FAILED — the census
     * prints `ov=FAIL` from that set, and a stale mark is a permanent false alarm about a monitor that
     * is no longer plugged in.
     */
    @Test fun `hiding a display clears an earlier failed mark`() {
        val host = FakeHost()
        host.addSucceeds = { it != monitor }
        val overlays = host.overlays()
        overlays.reconcile(mapOf(phone to content("a"), monitor to content("a")))
        assertEquals(setOf(monitor), overlays.failed())

        overlays.hideOn(monitor)

        assertEquals(emptySet<Int>(), overlays.failed())
    }
}
