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
     * One successful read for this browser vouches for every later absence — indefinitely, because the
     * page was necessarily loaded with the toolbar visible.
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

    /** The switch also restarts the clock, so the new browser gets its own full settle time. */
    @Test fun `switching browsers restarts the grace`() {
        val watch = watch(graceMs = 5_000)
        watch.observe(null, chrome, nowMs = 0)
        assertEquals(unreadable, watch.observe(null, chrome, nowMs = 6_000))
        // Brave arrives 6s in — it has only just been seen, so it is still settling, not broken.
        assertEquals(unknown, watch.observe(null, brave, nowMs = 6_000))
        assertEquals(unknown, watch.observe(null, brave, nowMs = 10_000))
        assertEquals(unreadable, watch.observe(null, brave, nowMs = 11_000))
    }

    /**
     * Coming back to a browser re-arms the grace rather than resuming where it left off — the return is
     * a fresh foreground, with the same cold tree the first arrival had.
     */
    @Test fun `returning to a browser re-arms the grace instead of failing closed at once`() {
        val watch = watch(graceMs = 5_000)
        watch.observe(url, chrome, nowMs = 0)
        watch.observe(url, brave, nowMs = 10_000)
        assertEquals(unknown, watch.observe(null, chrome, nowMs = 20_000))
        assertEquals(unreadable, watch.observe(null, chrome, nowMs = 25_000))
    }

    /**
     * The cost of re-arming on every switch: two allowlisted browsers alternating pass-to-pass (split
     * screen, or the window walk changing its mind about which one it reads) never accumulate grace, so
     * neither can ever be called unreadable. Deliberate — it errs toward allowing, and the alternative
     * would be a stale clock deciding for a browser that has only just been looked at.
     */
    @Test fun `alternating browsers keep re-arming and never fail closed`() {
        val watch = watch(graceMs = 5_000)
        repeat(20) {
            val nowMs = 5_000L * it
            assertEquals(unknown, watch.observe(null, chrome, nowMs))
            assertEquals(unknown, watch.observe(null, brave, nowMs + 1_000))
        }
    }
}
