package com.appblock.data

import android.content.Context
import com.appblock.engine.InstagramSurface
import com.appblock.engine.SignalCanary

/**
 * Persists what [SignalCanary] knows, and reads Instagram's installed version to compare against.
 *
 * Lives in the runtime prefs alongside the watchdog's setup flag rather than the engine store: none of
 * this is policy, and losing it costs one re-confirmation prompt rather than a budget.
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
        info.longVersionCode
    }.getOrNull()

    fun load(): SignalCanary.Witness = SignalCanary.Witness(
        confirmedVersion = prefs.getLong(KEY_CONFIRMED_VERSION, NONE).takeIf { it != NONE },
        confirmedAtMs = prefs.getLong(KEY_CONFIRMED_AT, 0L),
        installedVersion = prefs.getLong(KEY_INSTALLED_VERSION, NONE).takeIf { it != NONE },
        installedSeenAtMs = prefs.getLong(KEY_INSTALLED_SEEN_AT, 0L),
    )

    fun save(witness: SignalCanary.Witness) {
        prefs.edit()
            .putLong(KEY_CONFIRMED_VERSION, witness.confirmedVersion ?: NONE)
            .putLong(KEY_CONFIRMED_AT, witness.confirmedAtMs)
            .putLong(KEY_INSTALLED_VERSION, witness.installedVersion ?: NONE)
            .putLong(KEY_INSTALLED_SEEN_AT, witness.installedSeenAtMs)
            .apply()
    }

    /**
     * Fold in what's installed now and report the verdict. Cheap enough for the UI's poll; the service
     * throttles its own calls because [installedVersion] is a PackageManager hit.
     */
    fun refresh(nowMs: Long): SignalCanary.Health {
        val witness = SignalCanary.observe(load(), installedVersion(), nowMs)
        save(witness)
        return SignalCanary.assess(witness, nowMs)
    }

    /** The reel pager was just seen — the ids are good for the installed version. */
    fun confirm(nowMs: Long) {
        save(SignalCanary.confirm(load(), installedVersion(), nowMs))
    }

    private companion object {
        /** Shared with [com.appblock.service.Watchdog] — runtime state, not policy. */
        const val PREFS = "appblock_runtime"
        const val KEY_CONFIRMED_VERSION = "signal_confirmed_version"
        const val KEY_CONFIRMED_AT = "signal_confirmed_at"
        const val KEY_INSTALLED_VERSION = "signal_installed_version"
        const val KEY_INSTALLED_SEEN_AT = "signal_installed_seen_at"

        /** Sentinel for "no version recorded"; a real versionCode is never negative. */
        const val NONE = -1L
    }
}
