package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {

    private val blocklist = setOf("reddit.com", "youtube.com")

    // --- host() parsing, including the on-device URL-bar forms ---

    @Test fun `bare host`() = assertEquals("reddit.com", DomainMatcher.host("reddit.com"))

    @Test fun `no-scheme host with path and query (Chrome omnibox form)`() =
        assertEquals("google.com", DomainMatcher.host("google.com/search?client=ms-android&q=x"))

    @Test fun `full https url with subdomain, path, fragment`() =
        assertEquals("old.reddit.com", DomainMatcher.host("https://old.reddit.com/r/kotlin#top"))

    @Test fun `port and userinfo are stripped`() {
        assertEquals("example.com", DomainMatcher.host("http://example.com:8080/x"))
        // Userinfo decoy: the real host is after the '@'.
        assertEquals("evil.com", DomainMatcher.host("http://reddit.com@evil.com/reddit.com"))
    }

    @Test fun `uppercase is normalized`() = assertEquals("reddit.com", DomainMatcher.host("HTTPS://Reddit.Com/"))

    @Test fun `non-hosts return null`() {
        assertNull(DomainMatcher.host(""))
        assertNull(DomainMatcher.host("   "))
        assertNull(DomainMatcher.host("Search or type URL"))   // placeholder text
        assertNull(DomainMatcher.host("cat videos"))           // typed search phrase (has a space)
        assertNull(DomainMatcher.host("reddit"))               // bare word, no dot
    }

    // --- whole-domain matching (subdomains yes, look-alikes no) ---

    @Test fun `domain and its subdomains match`() {
        assertTrue(DomainMatcher.hostMatchesDomain("reddit.com", "reddit.com"))
        assertTrue(DomainMatcher.hostMatchesDomain("old.reddit.com", "reddit.com"))
        assertTrue(DomainMatcher.hostMatchesDomain("np.reddit.com", "reddit.com"))
    }

    @Test fun `look-alikes and decoys do not match`() {
        assertFalse(DomainMatcher.hostMatchesDomain("notreddit.com", "reddit.com"))
        assertFalse(DomainMatcher.hostMatchesDomain("reddit.com.evil.com", "reddit.com"))
        assertFalse(DomainMatcher.hostMatchesDomain("myreddit.com", "reddit.com"))
    }

    // --- isBlocked end-to-end ---

    @Test fun `isBlocked covers subdomains and spares others`() {
        assertTrue(DomainMatcher.isBlocked("https://www.reddit.com/r/x", blocklist))
        assertTrue(DomainMatcher.isBlocked("m.youtube.com/watch?v=1", blocklist))
        assertFalse(DomainMatcher.isBlocked("https://news.ycombinator.com", blocklist))
        assertFalse(DomainMatcher.isBlocked("reddit.com.evil.com/phish", blocklist))
        assertFalse(DomainMatcher.isBlocked("Search or type URL", blocklist))
    }

    // --- normalizeDomain for user input ---

    @Test fun `normalizeDomain accepts bare, url, and www forms`() {
        assertEquals("reddit.com", DomainMatcher.normalizeDomain("reddit.com"))
        assertEquals("reddit.com", DomainMatcher.normalizeDomain("https://www.reddit.com/r/x"))
        assertEquals("reddit.com", DomainMatcher.normalizeDomain("  Reddit.COM  "))
        assertEquals("old.reddit.com", DomainMatcher.normalizeDomain("old.reddit.com"))
    }

    @Test fun `normalizeDomain rejects non-domains`() {
        assertNull(DomainMatcher.normalizeDomain("reddit"))
        assertNull(DomainMatcher.normalizeDomain("cat videos"))
        assertNull(DomainMatcher.normalizeDomain(""))
    }
}
