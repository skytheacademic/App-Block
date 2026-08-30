package com.appblock.data

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.appblock.engine.InstagramSurface
import com.appblock.engine.SignalCanary

/**
 * Persists what [SignalCanary] knows about Instagram's resource-ids, and reads Instagram's installed
 * version to compare against.
 *
 * Lives in the runtime prefs alongside the watchdog's setup flag rather than the engine store: none of
 * this is policy, and losing it costs one re-confirmation prompt rather than a budget.
 *
 * ## One witness per id (2026-08-29)
 *
 * This used to hold a single witness, because the surface rule rested on a single id
 * ([InstagramSurface.REEL_PAGER]). The Explore press-and-hold rule added on 2026-08-29 rests on two
 * more, and a canary watching one id out of three is the failure mode the canary exists to catch,
 * pointed at itself: Instagram renames `explore_action_bar`, the Explore-preview rule silently stops
 * firing, and the drift notification stays quiet because the *pager* is still confirmed.
 *
 * So the witness is keyed by resource-id, exactly as [OmniboxWitnessStore] keys its own by browser
 * package. Which ids are tracked is [InstagramSurface.WITNESSED_IDS]' call, not this class's — an id
 * nobody exercises must not be witnessed, and the reasoning for each belongs beside the ids themselves.
 */
class SignalWitnessStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Instagram's versionCode, or null when it isn't installed *or* can't be seen. Both collapse to
     * "nothing to verify" ([SignalCanary.Health.NO_APP]) on purpose — a canary that can't read the app
     * has nothing to say about it, and guessing would only produce noise.
     *
     * Visible without QUERY_ALL_PACKAGES thanks to the manifest's MAIN/LAUNCHER `<queries>` block.
     */
    fun installedVersion(): Long? = runCatching {
        val info = app.packageManager.getPackageInfo(InstagramSurface.PACKAGE, 0)
        // PackageInfoCompat: `longVersionCode` itself is API 28 and minSdk is 26 (lint NewApi).
        PackageInfoCompat.getLongVersionCode(info)
    }.getOrNull()

    /**
     * What is known about [signalId].
     *
     * **Reads the pre-0.8.0 un-namespaced keys as a fallback for the pager**, so upgrading does not
     * throw away a confirmation the phone has already earned. Without it every existing install would
     * come out of the update un-confirmed, sit in the grace period, and then post a drift notification
     * about an id that never moved — the canary crying wolf on its own release day. The fallback is
     * read-only and one-directional: the first [confirm] writes the namespaced keys, which then win,
     * so it decays on its own without a migration flag to get stuck.
     */
    fun load(signalId: String): SignalCanary.Witness {
        val stored = witnessAt(
            key(signalId, CONFIRMED_VERSION), key(signalId, CONFIRMED_AT),
            key(signalId, INSTALLED_VERSION), key(signalId, INSTALLED_SEEN_AT),
        )
        if (stored != EMPTY || signalId != InstagramSurface.REEL_PAGER) return stored
        return witnessAt(
            LEGACY_CONFIRMED_VERSION, LEGACY_CONFIRMED_AT,
            LEGACY_INSTALLED_VERSION, LEGACY_INSTALLED_SEEN_AT,
        )
    }

    fun save(signalId: String, witness: SignalCanary.Witness) {
        prefs.edit()
            .putLong(key(signalId, CONFIRMED_VERSION), witness.confirmedVersion ?: NONE)
            .putLong(key(signalId, CONFIRMED_AT), witness.confirmedAtMs)
            .putLong(key(signalId, INSTALLED_VERSION), witness.installedVersion ?: NONE)
            .putLong(key(signalId, INSTALLED_SEEN_AT), witness.installedSeenAtMs)
            .apply()
    }

    /** Fold in what's installed now and report the verdict for [signalId]. */
    fun refresh(signalId: String, nowMs: Long): SignalCanary.Health {
        val witness = SignalCanary.observe(load(signalId), installedVersion(), nowMs)
        save(signalId, witness)
        // No confirmation ceiling, unlike the omnibox store: an expiry here would fire at the user who
        // stopped watching reels, which is success rather than a fault. See SignalCanary's KDoc.
        return SignalCanary.assess(witness, nowMs)
    }

    /**
     * [signalId] was just seen on screen — the id is good for whatever Instagram version is installed
     * right now.
     */
    fun confirm(signalId: String, nowMs: Long) {
        save(signalId, SignalCanary.confirm(load(signalId), installedVersion(), nowMs))
    }

    /**
     * The verdict for Instagram surface detection as a whole: the worst across every witnessed id.
     *
     * The Lock protection row and [com.appblock.service.Watchdog]'s drift notification both ask exactly
     * this, so it is answered in one place — two callers deciding separately which ids count is how a
     * row and a notification come to disagree on screen. The same reasoning, and the same shape, as
     * [OmniboxWitnessStore.installedHealth].
     */
    fun installedHealth(nowMs: Long): SignalCanary.Health =
        SignalCanary.worst(InstagramSurface.WITNESSED_IDS.map { refresh(it, nowMs) })

    private fun witnessAt(cv: String, ca: String, iv: String, isa: String) = SignalCanary.Witness(
        confirmedVersion = prefs.getLong(cv, NONE).takeIf { it != NONE },
        confirmedAtMs = prefs.getLong(ca, 0L),
        installedVersion = prefs.getLong(iv, NONE).takeIf { it != NONE },
        installedSeenAtMs = prefs.getLong(isa, 0L),
    )

    private companion object {
        /** Shared with [com.appblock.service.Watchdog] — runtime state, not policy. */
        const val PREFS = "appblock_runtime"

        const val CONFIRMED_VERSION = "confirmed_version"
        const val CONFIRMED_AT = "confirmed_at"
        const val INSTALLED_VERSION = "installed_version"
        const val INSTALLED_SEEN_AT = "installed_seen_at"

        /**
         * The single-witness key names used up to 0.7.0, kept only so [load] can still read a
         * confirmation earned before the store became per-id. Never written to again.
         */
        const val LEGACY_CONFIRMED_VERSION = "signal_confirmed_version"
        const val LEGACY_CONFIRMED_AT = "signal_confirmed_at"
        const val LEGACY_INSTALLED_VERSION = "signal_installed_version"
        const val LEGACY_INSTALLED_SEEN_AT = "signal_installed_seen_at"

        /** Namespaced per resource-id so two signals can never read each other's confirmation. */
        fun key(signalId: String, field: String) = "signal_${signalId}_$field"

        /** Sentinel for "no version recorded"; a real versionCode is never negative. */
        const val NONE = -1L

        /** What [load] returns when nothing has been stored — the trigger for the legacy fallback. */
        val EMPTY = SignalCanary.Witness()
    }
}
