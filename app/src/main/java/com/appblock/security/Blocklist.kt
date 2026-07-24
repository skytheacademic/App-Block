package com.appblock.security

import android.content.Context
import com.appblock.engine.DomainMatcher

/**
 * The private website blocklist (CONSTRAINTS.md §2). Domains are entered on-device and stored
 * app-private — **never in the repo** (no committed seed file), so the list of what the user blocks
 * stays personal. Clearing app data wipes it, the same accepted friction-only limit as the rest of the
 * durable state (CONSTRAINTS §8).
 *
 * Asymmetric by design: [add] is instant (tightening). [remove] is gated — the caller must hold an
 * open **websites** durable-unlock window (72-h wait, [com.appblock.engine.UnlockCategory.WEBSITES]);
 * [removeIfAuthorized] enforces that so a removal can't slip through the shorter apps gate.
 */
class BlocklistStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The blocked domains, normalized + sorted for a stable UI. */
    fun domains(): List<String> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().sorted()

    fun contains(domain: String): Boolean =
        DomainMatcher.normalizeDomain(domain)?.let { it in current() } ?: false

    /**
     * Add a domain (tightening — instant). Input may be a bare domain or a pasted URL; it's normalized
     * to a bare host (`www.` dropped). Returns the stored form, or null if the input isn't a domain.
     */
    fun add(input: String): String? {
        val domain = DomainMatcher.normalizeDomain(input) ?: return null
        write(current() + domain)
        return domain
    }

    /**
     * Remove a domain — only succeeds when a websites unlock window is open ([authorized]). Returns
     * true if it was authorized and the domain was present. Loosening the blocklist any other way is
     * refused, mirroring the gated rule edits (CONSTRAINTS §6).
     */
    fun removeIfAuthorized(domain: String, authorized: Boolean): Boolean {
        if (!authorized) return false
        val normalized = DomainMatcher.normalizeDomain(domain) ?: domain
        val set = current()
        if (normalized !in set) return false
        write(set - normalized)
        return true
    }

    private fun current(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()

    private fun write(domains: Set<String>) {
        // Store a fresh copy — getStringSet's returned set must not be mutated in place.
        prefs.edit().putStringSet(KEY, HashSet(domains)).apply()
    }

    private companion object {
        const val PREFS = "appblock_blocklist"
        const val KEY = "domains"
    }
}
