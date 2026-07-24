package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserPolicyTest {

    private val chrome = "com.android.chrome"
    private val brave = "com.brave.browser"
    private val firefox = "org.mozilla.firefox"
    private val samsung = "com.sec.android.app.sbrowser"
    private val blocklist = setOf("reddit.com")

    @Test fun `allowlisted browser on a blocked site is blocked`() {
        assertEquals(
            BrowserPolicy.WebBlock.BLOCKED_SITE,
            BrowserPolicy.decide(chrome, isBrowser = true, url = "https://old.reddit.com/r/x", blocklist = blocklist),
        )
        assertEquals(
            BrowserPolicy.WebBlock.BLOCKED_SITE,
            BrowserPolicy.decide(brave, isBrowser = true, url = "reddit.com", blocklist = blocklist),
        )
    }

    @Test fun `allowlisted browser on an allowed site is fine`() {
        assertNull(BrowserPolicy.decide(chrome, isBrowser = true, url = "https://news.ycombinator.com", blocklist = blocklist))
    }

    @Test fun `allowlisted browser with an unreadable url is not blocked`() {
        // Blank tab / omnibox not readable — don't block on a guess.
        assertNull(BrowserPolicy.decide(chrome, isBrowser = true, url = null, blocklist = blocklist))
    }

    @Test fun `non-allowlisted browser is blocked outright regardless of url`() {
        assertEquals(
            BrowserPolicy.WebBlock.NON_ALLOWLISTED_BROWSER,
            BrowserPolicy.decide(firefox, isBrowser = true, url = "https://news.ycombinator.com", blocklist = blocklist),
        )
        // Samsung Internet is the system default but deliberately not allowlisted.
        assertEquals(
            BrowserPolicy.WebBlock.NON_ALLOWLISTED_BROWSER,
            BrowserPolicy.decide(samsung, isBrowser = true, url = null, blocklist = blocklist),
        )
    }

    @Test fun `non-browser app is none of its business`() {
        assertNull(BrowserPolicy.decide("com.some.app", isBrowser = false, url = null, blocklist = blocklist))
    }

    @Test fun `allowlist beats the isBrowser flag`() {
        // Even if the browser-set query somehow omitted Chrome, being allowlisted means URL-watch, not block.
        assertNull(BrowserPolicy.decide(chrome, isBrowser = false, url = "https://news.ycombinator.com", blocklist = blocklist))
    }
}
