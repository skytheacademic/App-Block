package com.appblock.data

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.appblock.engine.BrowserTargets
import com.appblock.engine.SignalCanary

/**
 * Remembers, per browser and per browser *version*, that App-Block has successfully read that
 * browser's address bar — the durable half of the B-7 unreadable-address decision.
 *
 * ## Why this exists (the defect it fixes, 2026-08-03)
 *
 * [com.appblock.engine.AddressWatch] used to hold its whole vouch in memory, scoped to "since this
 * browser last came foreground", because a read only vouches for the tree it was taken from and
 * leaving the foreground is where a rebuilt tree shows up. That is sound about *trees* and wrong about
 * *ids*, and the gap between those two was a false block during ordinary browsing:
 *
 *  1. scroll a page in Chrome — modern Chrome hides the omnibox and keeps it hidden;
 *  2. switch to another app; `retain()` drops Chrome's watch;
 *  3. switch back. The tab is **still scrolled**, so the omnibox is still absent, but the vouch is
 *     gone and a fresh five-second grace is all that stands between the user and a block screen over
 *     a page they were reading, with nothing on it explaining why.
 *
 * Neither Gate F test could see it: the scrolling run never left Chrome (the vouch stayed alive), and
 * the leave-and-return run came back to a page whose toolbar was **visible** (the vouch was re-set
 * inside the grace). The source had written the risk as a conjunction — *"if the toolbar leaves the
 * tree **and** a fresh launch can restore an already-scrolled tab"* — and the checklist tested the two
 * halves separately. Two honest passes, and the bug lived in the `and`.
 *
 * ## Why versionCode is the right anchor
 *
 * The thing the vouch is really about is whether `<pkg>:id/url_bar` still names the omnibox, and a
 * resource id inside someone else's app only moves when that app is **updated**. So a successful read
 * confirms the id for exactly one versionCode and stays good until Chrome updates — which makes this
 * strictly *sharper* than the foreground-exit rule it replaces, not weaker:
 *
 *  - **The false block disappears.** Once any page has loaded with the toolbar visible on Chrome N,
 *    no later absence in Chrome N can reach [com.appblock.engine.BrowserTargets.Omnibox.Unreadable].
 *    Returning to a scrolled tab is silent, forever.
 *  - **The stale-vouch hole stays shut, and shuts more tightly.** The hole `retain()` was added for was
 *    "Chrome auto-updates overnight with a renamed url_bar and the old vouch keeps the blocklist
 *    enforcing nothing until reboot". An update is precisely what invalidates a version-keyed vouch, so
 *    that case is now caught *by construction* rather than by hoping the browser left the screen.
 *
 * This is [SignalCanary]'s exact case analysis, reused rather than re-derived — the same anchor it uses
 * for Instagram's reel pager, applied per browser package. Duplicating that walk is how `classify` and
 * `looseningReasons` drifted apart.
 *
 * Kept in the runtime prefs, not the engine store: none of this is policy, and losing it costs one
 * re-confirmation (open a browser, see the toolbar once), not a budget.
 */
class OmniboxWitnessStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The installed versionCode of [pkg], or null when it can't be read.
     *
     * Null is *not* treated as suspicious anywhere downstream. A canary that cannot read the app has
     * nothing to say about it ([SignalCanary.Health.NO_APP]), and guessing would only produce noise —
     * the same reasoning the reel canary states. For a browser we are actively reading a tree from,
     * this should be unreachable; it is handled rather than asserted.
     */
    fun installedVersion(pkg: String): Long? = runCatching {
        // PackageInfoCompat: `longVersionCode` itself is API 28 and minSdk is 26 (lint NewApi).
        PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(pkg, 0))
    }.getOrNull()

    fun load(pkg: String): SignalCanary.Witness = SignalCanary.Witness(
        confirmedVersion = prefs.getLong(key(pkg, CONFIRMED_VERSION), NONE).takeIf { it != NONE },
        confirmedAtMs = prefs.getLong(key(pkg, CONFIRMED_AT), 0L),
        installedVersion = prefs.getLong(key(pkg, INSTALLED_VERSION), NONE).takeIf { it != NONE },
        installedSeenAtMs = prefs.getLong(key(pkg, INSTALLED_SEEN_AT), 0L),
    )

    fun save(pkg: String, witness: SignalCanary.Witness) {
        prefs.edit()
            .putLong(key(pkg, CONFIRMED_VERSION), witness.confirmedVersion ?: NONE)
            .putLong(key(pkg, CONFIRMED_AT), witness.confirmedAtMs)
            .putLong(key(pkg, INSTALLED_VERSION), witness.installedVersion ?: NONE)
            .putLong(key(pkg, INSTALLED_SEEN_AT), witness.installedSeenAtMs)
            .apply()
    }

    /** Fold in what's installed now and report the verdict for [pkg]. */
    fun refresh(pkg: String, nowMs: Long): SignalCanary.Health {
        val witness = SignalCanary.observe(load(pkg), installedVersion(pkg), nowMs)
        save(pkg, witness)
        return SignalCanary.assess(witness, nowMs, GRACE_MS)
    }

    /** The omnibox was just read as a committed URL — the id is good for the installed version. */
    fun confirm(pkg: String, nowMs: Long) {
        save(pkg, SignalCanary.confirm(load(pkg), installedVersion(pkg), nowMs))
    }

    /**
     * The verdict for website blocking as a whole: the worst across every allowlisted browser that is
     * actually on the phone.
     *
     * A browser that isn't installed has no id to have drifted, and one that isn't allowlisted is
     * blocked outright as an app, so neither belongs in the verdict. That filter used to live in
     * [com.appblock.service.WatchdogWorker] alone; it is here now because the Lock protection row asks
     * the same question, and two callers deciding separately which browsers count is how the row and
     * the notification would come to disagree on screen.
     */
    fun installedHealth(nowMs: Long): SignalCanary.Health =
        worstHealth(BrowserTargets.allowlist.filter { installedVersion(it) != null }, nowMs)

    /** The worst verdict across the allowlisted browsers that are actually installed. */
    fun worstHealth(packages: Collection<String>, nowMs: Long): SignalCanary.Health {
        val healths = packages.map { refresh(it, nowMs) }
        return when {
            healths.any { it == SignalCanary.Health.STALE } -> SignalCanary.Health.STALE
            healths.any { it == SignalCanary.Health.PENDING } -> SignalCanary.Health.PENDING
            healths.any { it == SignalCanary.Health.CONFIRMED } -> SignalCanary.Health.CONFIRMED
            else -> SignalCanary.Health.NO_APP
        }
    }

    companion object {
        /**
         * How long after a browser update to keep allowing an unreadable address bar.
         *
         * Shorter than the reel canary's fourteen days because re-confirming here is not something the
         * user has to *choose* to do: any page load with the toolbar visible re-vouches, which happens
         * within seconds of ordinary browsing. So a week of browsing that never once produced a
         * readable omnibox is real evidence the id moved, not evidence of a quiet week.
         */
        const val GRACE_MS = 7L * 24 * 60 * 60 * 1_000

        /**
         * Whether an absent address bar may still be treated as innocent.
         *
         * [SignalCanary.Health.PENDING] counts: a browser that updated recently has simply not been
         * re-confirmed yet, and blocking there would recreate the false positive one notch further
         * along — first launch after a Chrome update onto a restored, already-scrolled tab.
         * [SignalCanary.Health.NO_APP] counts for the reason given on [installedVersion]: no reading is
         * not a bad reading. Only [SignalCanary.Health.STALE] withdraws the benefit of the doubt.
         */
        fun vouches(health: SignalCanary.Health): Boolean = health != SignalCanary.Health.STALE

        /** Shared with [com.appblock.service.Watchdog] and [SignalWitnessStore] — runtime, not policy. */
        private const val PREFS = "appblock_runtime"
        private const val CONFIRMED_VERSION = "confirmed_version"
        private const val CONFIRMED_AT = "confirmed_at"
        private const val INSTALLED_VERSION = "installed_version"
        private const val INSTALLED_SEEN_AT = "installed_seen_at"

        /** Namespaced per package so two browsers can never read each other's vouch. */
        private fun key(pkg: String, field: String) = "omnibox_${pkg}_$field"

        /** Sentinel for "no version recorded"; a real versionCode is never negative. */
        private const val NONE = -1L
    }
}
