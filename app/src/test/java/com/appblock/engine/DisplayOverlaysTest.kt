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

        fun wmFor(id: Int): FakeWm? =
            if (id in liveDisplays) managers.getOrPut(id) { FakeWm(id) } else null

        fun overlays() = DisplayOverlays<FakeWm, FakeView, String>(
            mayDraw = { mayDraw },
            defaultWindowManager = { wmFor(0) },
            secondaryWindowManager = { wmFor(it) },
            inflate = { id, _ -> FakeView(id) },
            add = { wm, view ->
                calls.add("add $wm $view")
                addSucceeds(view.displayId)
            },
            remove = { wm, view ->
                calls.add("remove $wm $view")
                if (view.displayId == removeThrowsOn) throw IllegalStateException("detached")
            },
            bindMessage = { view, msg -> calls.add("msg $view"); view.message = msg },
            bindFacts = { view, facts -> calls.add("facts $view"); view.facts = facts },
        )
    }

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
}
