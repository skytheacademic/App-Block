package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The omnibox read has to tell a *committed* URL from one being typed. Both failure modes here were
 * caught on hardware at Gate D (2026-07-24) with the naive "just read the text" version — see
 * [BrowserTargets.committedUrl].
 */
class CommittedUrlTest {

    @Test
    fun `a loaded page's url counts`() {
        assertEquals(
            "reddit.com",
            BrowserTargets.committedUrl("reddit.com", focused = false, showingHintText = false),
        )
    }

    @Test
    fun `full url with scheme and path counts`() {
        assertEquals(
            "https://old.reddit.com/r/all",
            BrowserTargets.committedUrl(
                "https://old.reddit.com/r/all", focused = false, showingHintText = false,
            ),
        )
    }

    @Test
    fun `mid-typing autocomplete does not count`() {
        // The real bug: typing "red" inline-autocompletes the omnibox to "reddit.com" before Enter.
        assertNull(BrowserTargets.committedUrl("reddit.com", focused = true, showingHintText = false))
    }

    @Test
    fun `searching for an unrelated word that autocompletes is not blocked`() {
        assertNull(BrowserTargets.committedUrl("redundancy", focused = true, showingHintText = false))
    }

    @Test
    fun `hint text of an empty omnibox does not count`() {
        assertNull(
            BrowserTargets.committedUrl(
                "Search Google or type URL", focused = false, showingHintText = true,
            ),
        )
    }

    @Test
    fun `hint text is still rejected when the hint flag is not set`() {
        // Second net: a real URL never contains whitespace, so OEM builds that don't set the flag
        // still can't feed us "Search Google or type URL" as an address.
        assertNull(
            BrowserTargets.committedUrl(
                "Search Google or type URL", focused = false, showingHintText = false,
            ),
        )
    }

    @Test
    fun `blank and null read as nothing`() {
        assertNull(BrowserTargets.committedUrl(null, focused = false, showingHintText = false))
        assertNull(BrowserTargets.committedUrl("   ", focused = false, showingHintText = false))
    }

    @Test
    fun `surrounding whitespace is trimmed, not treated as a search`() {
        assertEquals(
            "reddit.com",
            BrowserTargets.committedUrl("  reddit.com  ", focused = false, showingHintText = false),
        )
    }
}
