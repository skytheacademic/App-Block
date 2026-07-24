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
}
