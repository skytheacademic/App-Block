package com.appblock.engine

import com.appblock.engine.BrowserTargets.Omnibox

/**
 * The website-blocking decision for whatever browser is foreground (CONSTRAINTS.md §2). Pure so the
 * Android layer only has to supply the runtime facts (foreground package, whether it's a browser, what
 * the address bar is showing) and this decides. Four moves:
 *  - An installed PWA (`org.chromium.webapk.*`) → [WebBlock.WEB_APP]. It has no address bar and isn't
 *    reachable by the installed-browser probe, so it can't be checked against anything.
 *  - A browser NOT on the [BrowserTargets] allowlist → [WebBlock.NON_ALLOWLISTED_BROWSER]. Blocking
 *    every unlisted browser is what stops "install another browser to get around it".
 *  - An allowlisted browser whose current URL host is on the private blocklist →
 *    [WebBlock.BLOCKED_SITE] (whole-domain incl. subdomains, via [DomainMatcher]).
 *  - An allowlisted browser whose address bar has proven unreadable → [WebBlock.UNREADABLE_ADDRESS].
 * A non-browser app, or an allowlisted browser on an allowed page, is none of its business → null.
 *
 * **On failing closed** (user's decision, 2026-07-26). This used to allow whenever no URL could be
 * read, which meant the entire blocklist was one renamed resource-id away from silently enforcing
 * nothing — the same class of failure as the reel pager, and just as quiet. It now blocks instead.
 * The reason that's safe to do is entirely in [Omnibox]'s four states: the ordinary reasons for
 * having no address (typing, a blank tab, a browser that just came foreground, a toolbar scrolled out
 * of view) resolve to [Omnibox.Editing] or [Omnibox.Unknown] and stay allowed. Only a sustained
 * absence with no successful read behind it reaches [Omnibox.Unreadable].
 */
object BrowserPolicy {

    enum class WebBlock { NON_ALLOWLISTED_BROWSER, BLOCKED_SITE, WEB_APP, UNREADABLE_ADDRESS }

    /**
     * @param pkg       the foreground package
     * @param isBrowser whether [pkg] can open web links (a VIEW-http handler on this device)
     * @param omnibox   what the allowlisted browser's address bar is showing; the caller owns the
     *                  timing that separates [Omnibox.Unknown] from [Omnibox.Unreadable]
     * @param blocklist the user's private blocked domains
     */
    fun decide(
        pkg: String,
        isBrowser: Boolean,
        omnibox: Omnibox,
        blocklist: Set<String>,
    ): WebBlock? = when {
        BrowserTargets.isWebApp(pkg) -> WebBlock.WEB_APP
        BrowserTargets.isAllowlisted(pkg) -> when (omnibox) {
            is Omnibox.Url ->
                if (DomainMatcher.isBlocked(omnibox.value, blocklist)) WebBlock.BLOCKED_SITE else null
            Omnibox.Editing, Omnibox.Unknown -> null
            Omnibox.Unreadable -> WebBlock.UNREADABLE_ADDRESS
        }
        isBrowser -> WebBlock.NON_ALLOWLISTED_BROWSER
        else -> null
    }
}
