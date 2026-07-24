package com.appblock.engine

/**
 * The website-blocking decision for whatever browser is foreground (CONSTRAINTS.md §2). Pure so the
 * Android layer only has to supply the runtime facts (foreground package, whether it's a browser, the
 * URL-bar text) and this decides. Two moves:
 *  - A browser NOT on the [BrowserTargets] allowlist → [WebBlock.NON_ALLOWLISTED_BROWSER]. Blocking
 *    every unlisted browser is what stops "install another browser to get around it".
 *  - An allowlisted browser whose current URL host is on the private blocklist →
 *    [WebBlock.BLOCKED_SITE] (whole-domain incl. subdomains, via [DomainMatcher]).
 * A non-browser app, or an allowlisted browser on an allowed page, is none of its business → null.
 */
object BrowserPolicy {

    enum class WebBlock { NON_ALLOWLISTED_BROWSER, BLOCKED_SITE }

    /**
     * @param pkg       the foreground package
     * @param isBrowser whether [pkg] can open web links (a VIEW-http handler on this device)
     * @param url       the allowlisted browser's current URL-bar text, if the node was readable
     * @param blocklist the user's private blocked domains
     */
    fun decide(pkg: String, isBrowser: Boolean, url: String?, blocklist: Set<String>): WebBlock? = when {
        BrowserTargets.isAllowlisted(pkg) ->
            if (url != null && DomainMatcher.isBlocked(url, blocklist)) WebBlock.BLOCKED_SITE else null
        isBrowser -> WebBlock.NON_ALLOWLISTED_BROWSER
        else -> null
    }
}
