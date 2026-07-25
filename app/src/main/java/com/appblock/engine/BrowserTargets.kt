package com.appblock.engine

/**
 * The browser allowlist for website blocking (CONSTRAINTS.md §2, decided at Gate A 2026-07-22).
 *
 * Only these browsers are permitted; the accessibility layer URL-watches them (reads the omnibox and
 * blocks a page whose host is on the private blocklist). **Every other browser is blocked outright as
 * an app** — the commitment device: an impulsively-installed browser is dead on arrival, so there's no
 * "just grab another browser" bypass. Chrome + Brave were the user's choice; Samsung Internet is
 * deliberately excluded (blocked as an app despite being the system default).
 *
 * Chromium browsers all expose the omnibox as `<package>:id/url_bar` (an EditText holding the live URL)
 * — verified on-device for both Chrome and Brave (see Dropbox `ig-dumps/BROWSER-URLWATCH.md`).
 */
object BrowserTargets {

    val allowlist: Set<String> = setOf(
        "com.android.chrome",   // Chrome
        "com.brave.browser",    // Brave (Chromium fork)
    )

    fun isAllowlisted(pkg: String): Boolean = pkg in allowlist

    /** The omnibox EditText resource-id whose text is the current URL, for an allowlisted browser. */
    fun urlBarId(pkg: String): String = "$pkg:id/url_bar"

    /**
     * The omnibox text, but only when it's a **committed** URL — the address of the page actually on
     * screen — rather than something the user is still typing.
     *
     * Found on hardware at Gate D (2026-07-24), where the naive read blocked far too eagerly:
     *  - **Mid-typing autocomplete.** Type "red" and Chrome inline-autocompletes the omnibox to
     *    "reddit.com" *before Enter*. Reading that blocks the browser while the user is typing
     *    something else entirely — searching "redundancy" would trip a reddit.com rule. The omnibox
     *    holds input [focused] only while it's being edited (on a loaded page the on-device dump
     *    shows `focused="false"`), so focus is the discriminator: while editing, there is no
     *    committed URL to judge.
     *  - **Hint text.** `AccessibilityNodeInfo.getText()` falls back to the hint when the field is
     *    empty, so a blank omnibox reads as "Search Google or type URL" — seen in the live log.
     *    [showingHintText] rejects it; the whitespace check is a second net for builds where that
     *    flag isn't set, since a real URL never contains a space.
     *
     * Fails toward *not* blocking, which is the right direction here — the alternative punishes the
     * user for keystrokes they haven't committed to. The cost is that holding the omnibox focused
     * suppresses the check; that isn't a usable bypass, since focusing it covers the page with the
     * suggestion list, and the service re-reads within a tick of focus dropping.
     */
    fun committedUrl(text: String?, focused: Boolean, showingHintText: Boolean): String? {
        if (focused || showingHintText) return null
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        return trimmed
    }
}
