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

    /**
     * Chrome mints an installed PWA ("Add to Home screen") as its own package, `org.chromium.webapk.`
     * plus a hash. It renders the site full-screen with **no address bar at all**, and it doesn't
     * answer a wildcard http VIEW intent — only its own host — so the installed-browser probe that
     * catches Firefox and Samsung Internet steps right past it.
     *
     * The result before this: instagram.com on the blocklist, blocked in Chrome, and one "Add to Home
     * screen" away from being an unblocked icon on the home screen that looks and behaves like the app.
     * Nothing about it is inspectable, so it can't be enforced — it's blocked outright, the same
     * reasoning as a non-allowlisted browser.
     */
    const val WEBAPK_PREFIX = "org.chromium.webapk."

    fun isWebApp(pkg: String): Boolean = pkg.startsWith(WEBAPK_PREFIX)

    /**
     * Browsers recognised by name, on top of the installed-browser probe — never instead of it.
     *
     * The probe asks *this* user's package manager, so it cannot see an app installed only in another
     * profile. Found on hardware 2026-11-03: Samsung Internet was uninstalled from user 0 and present
     * only in Secure Folder (user 150), whose window accessibility hands us like any other — so it read
     * as "not a browser" and went unblocked on every site. Any browser installed only in the container
     * has the same shape; this list catches the common ones. Never contains an [allowlist] entry.
     */
    val knownBrowsers: Set<String> = setOf(
        "com.sec.android.app.sbrowser",       // Samsung Internet
        "com.sec.android.app.sbrowser.beta",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",                  // Firefox Nightly
        "org.mozilla.focus",
        "org.mozilla.klar",
        "org.torproject.torbrowser",
        "com.microsoft.emmx",                 // Edge
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "com.ecosia.android",
        "com.UCMobile.intl",
        "com.mi.globalbrowser",
        "com.yandex.browser",
        "mark.via.gp",
        "com.chrome.beta",                    // Chrome channels other than stable are not allowlisted
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
    )

    fun isKnownBrowser(pkg: String): Boolean = pkg in knownBrowsers

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

    /**
     * What the address bar is telling us right now. Four states rather than a nullable string, because
     * "no URL" used to conflate cases that need opposite handling — and treating them alike is what
     * made an unreadable browser fail *open*.
     *
     * The split that matters is [Editing] versus [Unreadable]: both produce no address, but the first
     * is the user typing (block there and the browser fights you on every keystroke) and the second is
     * the enforcement being blind (allow there and the blocklist is one broken resource-id away from
     * doing nothing at all).
     */
    sealed interface Omnibox {

        /** The address bar was found and holds a committed URL — the only judgeable state. */
        data class Url(val value: String) : Omnibox

        /** Found, but showing no committed address: mid-typing, or a blank new tab. Never blocked. */
        data object Editing : Omnibox

        /**
         * Not found yet, and not for long enough to mean anything. A freshly-foregrounded browser has
         * no tree for a moment, and Chrome's toolbar slides away as you scroll — blocking on either
         * would be a false positive on ordinary use, so this reads as allowed.
         */
        data object Unknown : Omnibox

        /**
         * Not found, and it has stayed that way past the settle grace *without the address bar ever
         * having been readable* since this browser came to the foreground. That's the signature of the
         * resource-id having moved, or of something wearing an allowlisted browser's package without
         * its UI — the cases where continuing to allow means enforcing nothing.
         */
        data object Unreadable : Omnibox
    }
}
