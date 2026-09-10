package com.appblock.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.appblock.engine.ShortcutTargets
import com.appblock.engine.ShortcutTargetSweep

/**
 * Takes App-Block back out of `accessibility_button_targets` whenever Android puts it there, which
 * is what turns "clear the floating button" from a thing done once on a cable into a thing that
 * holds across a restart. The same for `accessibility_gesture_targets`, the gesture's twin of that
 * list, which Android fills in the same way (see [ShortcutTargets.GESTURE_TARGETS]).
 *
 * ✅ **Verified on the S25 2026-09-10 (0.9.0):** two restarts, no tab on the edge after either,
 * checked by eye before the app was opened. Across the second, `dumpsys settings` shows the button
 * key rewritten during boot and the last write to it made by `com.appblock` — the re-add and the
 * removal this class exists for, both observed. The gesture key was rewritten by the system at both
 * boots and ours after the second, which is why it is swept too.
 *
 * ## The bug this closes
 *
 * The button was cleared with `adb shell settings put secure accessibility_button_targets ""` and
 * came back at the next reboot, as a tab on the edge of the screen. Nothing had been re-enabled by
 * hand. The setting is a *derived* one: the service requests the accessibility button (N-1, and it
 * has to, or the volume chord becomes a keyless off switch) and is enabled, so every time the
 * framework reads its accessibility configuration it decides App-Block belongs in that list and
 * writes it back. Boot is one of those reads. A single write can never win against that, so the
 * answer is to be there for every one of them.
 *
 * ## Three ways in, for one reason each
 *
 *  1. **[start] at [AppBlockerAccessibilityService.onServiceConnected]** — the boot path. The system
 *     binds an enabled accessibility service on every start, so this runs on every start.
 *  2. **The [ContentObserver]** — because (1) races. Our connect and the framework's own write both
 *     happen during the same accessibility config read, and nothing says ours is second; if the
 *     target lands after we swept, the observer is what notices. It also covers a re-add made hours
 *     later from the shortcut picker.
 *  3. **[sweepOnce] from the watchdog and from the Lock tab's refresh** — the channels that still run
 *     when the service does not. The watchdog's is the slow retry after a budget has been spent; the
 *     Lock tab's is what makes opening the app clear a button the user is looking at.
 *
 * ## The permission, and what happens without it
 *
 * `WRITE_SECURE_SETTINGS` is signature|privileged: it cannot be self-granted, cannot be granted from
 * any Settings screen, and is given once over adb:
 *
 * ```
 * adb shell pm grant com.appblock android.permission.WRITE_SECURE_SETTINGS
 * ```
 *
 * The grant survives reboots and in-place updates; a fresh install needs it again. Without it every
 * sweep returns [Outcome.NO_PERMISSION] and the app behaves exactly as it did before this file
 * existed: the button stays, and the Lock tab names it and carries that command. That is the point
 * of checking rather than catching. Nothing here is load-bearing for blocking, and a phone that
 * never sees a cable is no worse off than it was.
 *
 * The grant is also *narrower* than it looks from the permission's name, in the direction that
 * matters: it is only ever used to remove our own entry from the two [ShortcutTargets.WRITE_KEYS]
 * lists. Other services' entries are carried through untouched by [ShortcutTargets.withoutSelf], and
 * no other Secure setting is written.
 */
class ShortcutTargetGuard(
    context: Context,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val component = ComponentName(appContext, AppBlockerAccessibilityService::class.java)
    /** One budget per key: a fight over one list must not spend the clears the other one needs. */
    private val sweeps = ShortcutTargets.WRITE_KEYS.associateWith { ShortcutTargetSweep() }

    /**
     * The main looper, matching [ClockSettingsWatch] and the service's own callbacks: the guard holds
     * mutable budget state and this keeps every touch of it on one thread, with no locks and nothing
     * volatile.
     */
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            sweepNow()
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            sweepNow()
        }
    }

    /**
     * What one sweep did. Returned rather than logged, so the tests can state the whole behaviour.
     *
     * Declared worst first, because a sweep covers two keys and reports the worse of the two: a claim
     * left standing on either one is the thing worth hearing about, and a clean clear of the other
     * must not hide it.
     */
    enum class Outcome {
        /** The write was allowed and threw anyway. Left to the next channel. */
        FAILED,

        /** [ShortcutTargetSweep]'s budget for this window is gone. */
        BUDGET_SPENT,

        /** The adb grant was never given. Expected, and not a failure of anything. */
        NO_PERMISSION,

        /** Our entry was there and is not any more. */
        CLEARED,

        /** Nothing of ours in the setting. The steady state, and what every re-entrant sweep sees. */
        ALREADY_CLEAR,
    }

    /** Registers the observers and sweeps once, in that order, so a write during the sweep is caught. */
    fun start() {
        for (key in ShortcutTargets.WRITE_KEYS) {
            runCatching {
                resolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer)
            }
        }
        sweepNow()
    }

    fun stop() {
        runCatching { resolver.unregisterContentObserver(observer) }
    }

    /**
     * Reads each key, decides, and writes at most one value back per key. Reports the worst of the
     * two outcomes (see [Outcome]).
     *
     * Our own write re-fires the observer, and that re-entry is the loop's floor rather than its
     * start: the values no longer name us, so the second pass returns [Outcome.ALREADY_CLEAR] having
     * written nothing and spent nothing.
     *
     * Wrapped end to end, per key, because the service calls it from [start], and
     * `onServiceConnected` must never throw: a throw there kills the service, and the watchdog would
     * not notice for fifteen minutes. Per key so that one key's throw cannot stop the other's clear.
     */
    fun sweepNow(): Outcome =
        sweeps.entries
            .map { (key, sweep) ->
                runCatching { sweepKey(key, sweep) }.getOrDefault(Outcome.FAILED)
            }
            .minOrNull() ?: Outcome.ALREADY_CLEAR

    private fun sweepKey(key: String, sweep: ShortcutTargetSweep): Outcome {
        val current = runCatching { Settings.Secure.getString(resolver, key) }.getOrNull()
        // Asked before the permission, so a phone with no claim on it never reports a missing grant
        // it has no use for. The Lock tab reads the same order.
        if (!ShortcutTargets.claims(current, component.packageName, component.className)) {
            return Outcome.ALREADY_CLEAR
        }
        if (!canWrite()) return Outcome.NO_PERMISSION
        val decision = sweep.decide(
            elapsedMs(),
            current,
            component.packageName,
            component.className,
        )
        return when (decision) {
            is ShortcutTargetSweep.Decision.AlreadyClear -> Outcome.ALREADY_CLEAR
            is ShortcutTargetSweep.Decision.Spent -> Outcome.BUDGET_SPENT
            is ShortcutTargetSweep.Decision.Clear -> {
                val wrote = runCatching {
                    Settings.Secure.putString(resolver, key, decision.value)
                }.getOrDefault(false)
                if (wrote) Outcome.CLEARED else Outcome.FAILED
            }
        }
    }

    private fun canWrite(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {

        /**
         * One sweep with no observer and no state kept, for the callers that are not the service: the
         * watchdog's fifteen-minute pass and the Lock tab's on-resume refresh.
         *
         * A fresh [ShortcutTargetSweep] each time means a fresh budget each time, which sounds like it
         * defeats the budget and does not: both callers are already rate-limited by what they are
         * (a periodic worker, a screen the user opened), so the most either can do is one write per
         * visit. The budget exists to bound a *callback* loop, and neither of these has one.
         */
        fun sweepOnce(context: Context): Outcome = ShortcutTargetGuard(context).sweepNow()
    }
}
