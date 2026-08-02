package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The timing that decides whether a missing address bar is harmless or is the enforcement going blind.
 *
 * Two failure directions, and the cost is lopsided:
 *  - too eager → Chrome hides its toolbar on a scrolled article and the browser blocks itself out from
 *    under the user, for a reason nothing on screen explains. The worst outcome in the feature.
 *  - too lax → a renamed `url_bar` resource-id makes the whole blocklist enforce nothing, silently,
 *    and the only symptom is that blocking has stopped happening. That is the bypass.
 *
 * [AddressWatch.retain] is the service telling the watch which browsers are still on screen; the tests
 * below drive it by hand, one call per window scan, exactly where the service makes it.
 */
class AddressWatchTest {

    private val chrome = "com.android.chrome"
    private val brave = "com.brave.browser"

    private val url = BrowserTargets.Omnibox.Url("https://example.com/a")
    private val editing = BrowserTargets.Omnibox.Editing
    private val unknown = BrowserTargets.Omnibox.Unknown
    private val unreadable = BrowserTargets.Omnibox.Unreadable

    private fun watch(graceMs: Long = AddressWatch.DEFAULT_GRACE_MS) =
        AddressWatch(graceMs = graceMs)

    // ---- a node that was found is a real answer; the watch has no business rewriting it ----

    @Test fun `a committed URL passes through untouched`() {
        val watch = watch()
        assertEquals(url, watch.observe(url, chrome, nowMs = 0))
        assertEquals(url, watch.observe(url, chrome, nowMs = 60_000))
    }

    /** Mid-typing and a blank new tab. Blocking here would fight the user on every keystroke. */
    @Test fun `editing passes through untouched`() {
        val watch = watch()
        assertEquals(editing, watch.observe(editing, chrome, nowMs = 0))
        assertEquals(editing, watch.observe(editing, chrome, nowMs = 60_000))
    }

    // ---- absence: not yet, versus not ever ----

    /** A freshly-foregrounded browser has no tree for a moment; that decides nothing. */
    @Test fun `an absence inside the grace is only unknown`() {
        val watch = watch(graceMs = 5_000)
        assertEquals(unknown, watch.observe(null, chrome, nowMs = 0))
        assertEquals(unknown, watch.observe(null, chrome, nowMs = 4_000))
    }

    /**
     * The case the whole fail-closed design exists for: the address bar has never once been readable
     * here, and it has been long enough that "still settling" no longer explains it — a renamed
     * resource-id, or something wearing Chrome's package without its UI.
     */
    @Test fun `an absence past the grace with nothing ever read is unreadable`() {
        val watch = watch(graceMs = 5_000)
        watch.observe(null, chrome, nowMs = 0)
        assertEquals(unreadable, watch.observe(null, chrome, nowMs = 6_000))
    }

    /**
     * The expensive false positive: the user scrolled a long article and Chrome slid the toolbar away.
     * One successful read vouches for every later absence for as long as the browser stays on screen,
     * because the page was necessarily loaded with the toolbar visible.
     */
    @Test fun `one successful read makes every later absence unknown`() {
        val watch = watch(graceMs = 5_000)
        watch.observe(url, chrome, nowMs = 0)
        assertEquals(unknown, watch.observe(null, chrome, nowMs = 6_000))
        assertEquals(unknown, watch.observe(null, chrome, nowMs = 600_000))
    }

    /**
     * [BrowserTargets.Omnibox.Editing] is not that proof. A focused, empty field is what the omnibox
     * looks like when the *node* is present, which is already enough to pass through — it says nothing
     * about a later absence, so the fail-closed path must stay reachable behind it.
     */
    @Test fun `editing does not vouch for a later absence`() {
        val watch = watch(graceMs = 5_000)
        watch.observe(editing, chrome, nowMs = 0)
        watch.observe(editing, chrome, nowMs = 1_000)
        assertEquals(unreadable, watch.observe(null, chrome, nowMs = 6_000))
    }

    /** The grace is measured from the first sighting of this browser, not from service start. */
    @Test fun `the very first read of a browser is never unreadable`() {
        val watch = watch(graceMs = 5_000)
        assertEquals(unknown, watch.observe(null, chrome, nowMs = 9_999_999))
    }

    /**
     * Pins the inclusive/exclusive choice: strictly *inside* the grace is unknown, landing exactly on
     * it is unreadable. Same direction as [OcclusionHold] — the threshold belongs to the strict side.
     */
    @Test fun `the grace boundary fails closed on the tick itself`() {
        val watch = watch(graceMs = 5_000)
        watch.observe(null, chrome, nowMs = 1_000)          // watch starts here
        assertEquals(unknown, watch.observe(null, chrome, nowMs = 5_999))
        assertEquals(unreadable, watch.observe(null, chrome, nowMs = 6_000))
    }

    /** Nothing self-heals: while the node stays gone the answer stays blocked, pass after pass. */
    @Test fun `unreadable persists while the node stays gone`() {
        val watch = watch(graceMs = 5_000)
        watch.observe(null, chrome, nowMs = 0)
        repeat(10) { assertEquals(unreadable, watch.observe(null, chrome, nowMs = 6_000 + 5_000L * it)) }
    }

    /**
     * ...and it recovers the moment the address bar comes back. A blocked page whose toolbar reappears
     * must stop being treated as a broken browser, or the block would outlive its own evidence.
     */
    @Test fun `a read arriving after an unreadable stretch clears it`() {
        val watch = watch(graceMs = 5_000)
        watch.observe(null, chrome, nowMs = 0)
        assertEquals(unreadable, watch.observe(null, chrome, nowMs = 6_000))
        assertEquals(url, watch.observe(url, chrome, nowMs = 7_000))
        assertEquals(unknown, watch.observe(null, chrome, nowMs = 20_000))
    }

    // ---- the watch is per-browser ----

    /**
     * Chrome having a working omnibox is no evidence about Brave's. If the flag leaked, one healthy
     * browser would vouch for a broken one for as long as the session lasted.
     */
    @Test fun `a successful read does not vouch for a different browser`() {
        val watch = watch(graceMs = 5_000)
        watch.observe(url, chrome, nowMs = 0)
        watch.observe(null, brave, nowMs = 1_000)
        assertEquals(unreadable, watch.observe(null, brave, nowMs = 7_000))
    }

    /** Each browser's grace runs from the first time *that* browser was looked at. */
    @Test fun `each browser gets its own grace clock`() {
        val watch = watch(graceMs = 5_000)
        watch.observe(null, chrome, nowMs = 0)
        assertEquals(unreadable, watch.observe(null, chrome, nowMs = 6_000))
        // Brave arrives 6s in — it has only just been seen, so it is still settling, not broken.
        assertEquals(unknown, watch.observe(null, brave, nowMs = 6_000))
        assertEquals(unknown, watch.observe(null, brave, nowMs = 10_000))
        assertEquals(unreadable, watch.observe(null, brave, nowMs = 11_000))
    }

    /**
     * Split screen, and the window walk changing its mind about which pane it reads. With a single
     * watch slot between them the two browsers evicted each other pass after pass, so neither could
     * ever accumulate enough grace to be called unreadable — two broken browsers side by side were
     * permanently allowed. Separate clocks, both of which keep running while the other is being read.
     */
    @Test fun `two browsers side by side each keep their own clock and both fail closed`() {
        val watch = watch(graceMs = 5_000)
        val both = setOf(chrome, brave)
        assertEquals(unknown, watch.observe(null, chrome, nowMs = 0))
        watch.retain(both)
        assertEquals(unknown, watch.observe(null, brave, nowMs = 1_000))
        watch.retain(both)
        assertEquals(unreadable, watch.observe(null, chrome, nowMs = 5_000))
        watch.retain(both)
        assertEquals(unreadable, watch.observe(null, brave, nowMs = 6_000))
    }

    /** Being read is not what keeps a watch alive — being on screen is. */
    @Test fun `a browser in the other pane is not evicted by the one being read`() {
        val watch = watch(graceMs = 5_000)
        val both = setOf(chrome, brave)
        watch.observe(url, brave, nowMs = 0)
        watch.retain(both)
        watch.observe(url, chrome, nowMs = 1_000)
        watch.retain(both)
        assertEquals(unknown, watch.observe(null, brave, nowMs = 30_000))
    }

    // ---- leaving the foreground is what retires a watch ----

    /**
     * The silent fail-open the vouch would otherwise cause. Chrome reads fine at 9am; overnight Chrome
     * auto-updates with a renamed `url_bar`; our process never died, so a vouch taken against the *old*
     * tree is still standing and every absence in the new one reads as harmless — the blocklist quietly
     * enforces nothing until the next reboot. Leaving the foreground is the event that means "different
     * tree", and it is what retires the vouch.
     */
    @Test fun `leaving the browser drops a vouch that could outlive its tree`() {
        val watch = watch(graceMs = 5_000)
        watch.observe(url, chrome, nowMs = 0)
        watch.retain(setOf(chrome))
        watch.retain(emptySet())                    // Instagram is foreground; Chrome has no window
        assertEquals(unknown, watch.observe(null, chrome, nowMs = 60_000))
        watch.retain(setOf(chrome))
        assertEquals(unreadable, watch.observe(null, chrome, nowMs = 66_000))
    }

    /**
     * The other half of the same rule, in the opposite direction. Tap Chrome, get a new tab with the
     * cursor already in the omnibox — present, but never a committed URL, so nothing vouches. Swipe to
     * Instagram three seconds later. Ten minutes on, come back: the tree isn't built yet on the first
     * pass, and against a ten-minute-old clock that would be an instant block screen over a browser
     * that has done nothing wrong.
     */
    @Test fun `a browser returning after minutes away still gets its settle time`() {
        val watch = watch(graceMs = 5_000)
        assertEquals(editing, watch.observe(editing, chrome, nowMs = 0))
        watch.retain(emptySet())
        assertEquals(unknown, watch.observe(null, chrome, nowMs = 600_000))
    }

    /**
     * The bug that started this: the watch reset on *seeing a different browser*, not on the browser
     * leaving, so the identical user action resolved two different ways depending on whether the app
     * visited in between happened to be a browser. Both paths must now agree.
     */
    @Test fun `it makes no difference whether the app in between was a browser`() {
        val viaBrowser = watch(graceMs = 5_000)
        viaBrowser.observe(url, chrome, nowMs = 0)
        viaBrowser.retain(setOf(brave))             // Brave full-screen
        viaBrowser.retain(setOf(chrome))            // and back

        val viaApp = watch(graceMs = 5_000)
        viaApp.observe(url, chrome, nowMs = 0)
        viaApp.retain(emptySet())                   // Instagram full-screen
        viaApp.retain(setOf(chrome))                // and back

        assertEquals(unknown, viaBrowser.observe(null, chrome, nowMs = 20_000))
        assertEquals(unknown, viaApp.observe(null, chrome, nowMs = 20_000))
        assertEquals(unreadable, viaBrowser.observe(null, chrome, nowMs = 26_000))
        assertEquals(unreadable, viaApp.observe(null, chrome, nowMs = 26_000))
    }

    /**
     * The expensive false positive must survive the fix: an article read for half an hour with the
     * toolbar scrolled away never leaves the foreground, so its vouch is never retired. This is why the
     * vouch expires on an *event* and not on a timer — a timer would run out exactly here.
     */
    @Test fun `staying on screen keeps the vouch through a scrolled-away toolbar`() {
        val watch = watch(graceMs = 5_000)
        watch.observe(url, chrome, nowMs = 0)
        repeat(20) {
            watch.retain(setOf(chrome))
            assertEquals(unknown, watch.observe(null, chrome, nowMs = 10_000 + 90_000L * it))
        }
    }

    /** The service calls retain on every scan, including the ones with no browser in them at all. */
    @Test fun `retaining nothing before any read is harmless`() {
        val watch = watch(graceMs = 5_000)
        watch.retain(emptySet())
        watch.retain(setOf(chrome))
        assertEquals(unknown, watch.observe(null, chrome, nowMs = 0))
    }
}
