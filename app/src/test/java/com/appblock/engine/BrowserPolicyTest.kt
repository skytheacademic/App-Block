package com.appblock.engine

import com.appblock.engine.BrowserTargets.Omnibox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserPolicyTest {

    private val chrome = "com.android.chrome"
    private val brave = "com.brave.browser"
    private val firefox = "org.mozilla.firefox"
    private val samsung = "com.sec.android.app.sbrowser"
    private val blocklist = setOf("reddit.com", "instagram.com")

    private fun decide(
        pkg: String,
        isBrowser: Boolean = true,
        omnibox: Omnibox = Omnibox.Unknown,
    ) = BrowserPolicy.decide(pkg, isBrowser, omnibox, blocklist)

    @Test fun `allowlisted browser on a blocked site is blocked`() {
        assertEquals(
            BrowserPolicy.WebBlock.BLOCKED_SITE,
            decide(chrome, omnibox = Omnibox.Url("https://old.reddit.com/r/x")),
        )
        assertEquals(
            BrowserPolicy.WebBlock.BLOCKED_SITE,
            decide(brave, omnibox = Omnibox.Url("reddit.com")),
        )
    }

    @Test fun `allowlisted browser on an allowed site is fine`() {
        assertNull(decide(chrome, omnibox = Omnibox.Url("https://news.ycombinator.com")))
    }

    @Test fun `non-allowlisted browser is blocked outright regardless of address`() {
        assertEquals(
            BrowserPolicy.WebBlock.NON_ALLOWLISTED_BROWSER,
            decide(firefox, omnibox = Omnibox.Url("https://news.ycombinator.com")),
        )
        // Samsung Internet is the system default but deliberately not allowlisted.
        assertEquals(BrowserPolicy.WebBlock.NON_ALLOWLISTED_BROWSER, decide(samsung))
    }

    @Test fun `non-browser app is none of its business`() {
        assertNull(decide("com.some.app", isBrowser = false))
    }

    @Test fun `allowlist beats the isBrowser flag`() {
        // Even if the browser-set query somehow omitted Chrome, being allowlisted means URL-watch, not block.
        assertNull(decide(chrome, isBrowser = false, omnibox = Omnibox.Url("https://news.ycombinator.com")))
    }

    // ---- installed PWAs (audit finding B-7) ----

    /**
     * The gap: instagram.com blocked in Chrome, then "Add to Home screen" turns it into
     * `org.chromium.webapk.<hash>` — its own package, no address bar, and invisible to the
     * installed-browser probe because it only answers for its own host. An unblocked icon that looks
     * and behaves exactly like the app you blocked.
     */
    @Test fun `an installed PWA is blocked`() {
        assertEquals(
            BrowserPolicy.WebBlock.WEB_APP,
            decide("org.chromium.webapk.a4e8f2c1b", isBrowser = false),
        )
    }

    @Test fun `a PWA is blocked whatever the browser probe thinks of it`() {
        assertEquals(
            BrowserPolicy.WebBlock.WEB_APP,
            decide("org.chromium.webapk.deadbeef", isBrowser = true),
        )
    }

    @Test fun `an ordinary chromium package is not mistaken for a PWA`() {
        assertNull(decide("org.chromium.chrome.chromiumtest", isBrowser = false))
    }

    // ---- failing closed on an unreadable address bar (user's call, 2026-07-26) ----

    /**
     * The finding this closes: reading no URL used to mean "allow", so the entire blocklist was one
     * renamed resource-id away from silently enforcing nothing — the same quiet failure as the reel
     * pager. Only the state that has ruled out every innocent explanation blocks.
     */
    @Test fun `an address bar that has proven unreadable blocks`() {
        assertEquals(BrowserPolicy.WebBlock.UNREADABLE_ADDRESS, decide(chrome, omnibox = Omnibox.Unreadable))
    }

    /**
     * ...and the innocent explanations must not. Typing in the omnibox produces no committed address
     * on every keystroke; blocking there would make the browser fight you as you use it.
     */
    @Test fun `typing in the address bar is never a block`() {
        assertNull(decide(chrome, omnibox = Omnibox.Editing))
    }

    /**
     * Nor a browser that has only just come foreground, or one whose toolbar has scrolled out of
     * view — both read as no address bar, and both are ordinary browsing.
     */
    @Test fun `an address bar that is merely not visible yet is not a block`() {
        assertNull(decide(chrome, omnibox = Omnibox.Unknown))
    }

    /** A non-allowlisted browser is blocked for being itself; the address state never enters into it. */
    @Test fun `the unreadable rule does not change unlisted browsers`() {
        assertEquals(
            BrowserPolicy.WebBlock.NON_ALLOWLISTED_BROWSER,
            decide(firefox, omnibox = Omnibox.Unreadable),
        )
    }
}
