package com.appblock.engine

/**
 * Whole-domain URL blocking (CONSTRAINTS.md §2). A blocklist entry `reddit.com` blocks that domain and
 * every subdomain (`www.reddit.com`, `old.reddit.com`, `np.reddit.com`) but never a look-alike
 * (`notreddit.com`) or a decoy authority (`reddit.com.evil.com`, `reddit.com@evil.com`).
 *
 * Matching is host-only and case-insensitive: scheme, userinfo, port, path, query and fragment are all
 * stripped first. Input comes from a browser's URL bar (Android accessibility), which on-device we saw
 * can be a bare host, a full `host/path?query` with no scheme, or a "Search or type URL" placeholder /
 * a typed search phrase — the parser returns null for anything that isn't a real host.
 */
object DomainMatcher {

    /**
     * The lowercased host of [raw], or null if it holds no host (empty, a search phrase with spaces,
     * or a bare word with no dot). Handles the no-scheme URL-bar form and strips a userinfo decoy so
     * `http://reddit.com@evil.com` resolves to `evil.com`, not `reddit.com`.
     */
    fun host(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        // Authority = up to the first path/query/fragment delimiter.
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        // Strip userinfo (user:pass@) — the real host is after the last '@'.
        s = s.substringAfterLast('@')
        // Strip port.
        s = s.substringBefore(':')
        s = s.trim().trimEnd('.').lowercase()
        // A real host has no spaces and at least one dot (a bare search word like "reddit" is not one).
        if (s.isEmpty() || s.any { it.isWhitespace() } || '.' !in s) return null
        return s
    }

    /** True when [host] is [domain] itself or a subdomain of it. Both are lowercased/undotted first. */
    fun hostMatchesDomain(host: String, domain: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        val d = domain.trim().trimEnd('.').lowercase().removePrefix(".")
        if (d.isEmpty()) return false
        return h == d || h.endsWith(".$d")
    }

    /** True when [url]'s host is on [blocklist] (whole-domain, subdomains included). */
    fun isBlocked(url: String, blocklist: Set<String>): Boolean {
        val host = host(url) ?: return false
        return blocklist.any { hostMatchesDomain(host, it) }
    }

    /**
     * Normalize a domain a user typed for the blocklist: accept a bare domain or a pasted URL, drop the
     * scheme/path/`www.`, lowercase. Returns null if it doesn't reduce to a real host (so the UI can
     * reject it). `www.` is dropped because blocking `reddit.com` already covers `www.reddit.com`.
     */
    fun normalizeDomain(input: String): String? {
        val h = host(input) ?: return null          // host() already accepts a bare "reddit.com"
        return h.removePrefix("www.").takeIf { '.' in it }
    }
}
