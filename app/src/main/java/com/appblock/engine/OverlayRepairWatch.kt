package com.appblock.engine

/**
 * Decides when the settings-watch may stand its Settings tier down "because the overlay permission is
 * missing and the user has to be let through to re-grant it" — repair mode.
 *
 * ## The outage this exists to stop (S25, One UI 8, 2026-08-29, caught live)
 *
 * Repair mode used to be one expression, `!canDrawOverlay()`, evaluated per Settings event and
 * returning **before any rule ran**. On the phone that read came back false for several minutes while
 * `appops get com.appblock SYSTEM_ALERT_WINDOW` said `allow` throughout — nothing revoked, nothing
 * granted, only time passing. During that window the entire Settings tier was disarmed: the
 * accessibility toggle page, App info with Force stop and Uninstall, and the device-admin page were
 * all free. Six consecutive trials went unbounced; the same trials went 7/7 once the window closed,
 * with no change but the clock.
 *
 * Two things made it a P0 rather than a bug:
 *
 *  - **It was silent.** Nothing logged it, nothing notified, nothing on the Lock tab said the guard
 *    was down. The audit trail it left was two *separate* mysteries written up the same morning — a
 *    "self-defense regression" that could not be reproduced, and a watchdog nag that "lagged four
 *    minutes" — which were one event seen from two directions.
 *  - **It failed open.** Every other stand-down is something the user did on purpose (setup not
 *    finished, a change window opened at a price). This one is a sensor reading, and a blocker must
 *    never disarm itself on a sensor it has not cross-checked.
 *
 * ## What replaces it
 *
 * Two sources, and the *disagreement* is the signal:
 *
 *  - `canDraw` — `Settings.canDrawOverlays()`, which goes through `AppOpsManager.noteOp` and is the
 *    one that lied;
 *  - `opAllows` — the non-transactional `unsafeCheckOpNoThrow` read of the same op, which is what
 *    `appops get` prints and was right the whole time. See `overlayPermissionHeld`.
 *
 * When they agree that the permission is gone, repair mode engages **immediately**, exactly as before:
 * that is a real revoke, and the whole reason repair mode exists is that the page which restores the
 * permission must not be guarded (C-2, which cost an adb session). Nothing about the repair path gets
 * slower.
 *
 * When they disagree — `canDraw` says no, the op says allowed — the tier **stays armed**, because the
 * permission demonstrably is not gone. That alone would have closed the whole observed outage.
 *
 * ## Why the disagreement still expires
 *
 * [disagreementGraceMs] is the deliberate limit on that stubbornness. If appops ever lies the other
 * way — op stuck at allowed while the overlay genuinely cannot be drawn — an unconditional "trust the
 * op" would guard the app out of its own repair permanently, which is C-2 again with a longer fuse and
 * a worse ending. So after the grace the watch believes `canDraw` regardless and lets the user
 * through. Ten minutes is chosen against the two knowns: the measured transient was minutes, and a
 * real repair costs the user only that wait once, with the block screen already degraded to
 * kick-to-home rather than absent.
 *
 * ## The announcement
 *
 * [justEngaged] is true on exactly the pass that enters repair mode, so the caller can force the
 * watchdog's health report out immediately instead of waiting on its throttle. The requirement it
 * encodes is the first bullet above: whatever else repair mode is, it is never again silent.
 *
 * Pure Kotlin, and given both readings rather than taking them, so the whole state machine is
 * JVM-testable — the same reason [OcclusionHold] and [SettingsWatch] are shaped this way.
 */
class OverlayRepairWatch(
    private val disagreementGraceMs: Long = DEFAULT_DISAGREEMENT_GRACE_MS,
) {

    /** When `canDraw` first came back false in the current run of falses; null while it reads true. */
    private var missingSinceMs: Long? = null

    private var engaged = false

    /**
     * True on the single pass that flipped repair mode on — the caller's cue to nag. Deliberately not
     * a "currently engaged" flag: the nag must fire once per outage, not once per Settings event.
     */
    var justEngaged: Boolean = false
        private set

    /** Whether the Settings tier is currently standing down for a repair. */
    val isEngaged: Boolean get() = engaged

    /**
     * How long `canDrawOverlays` has been saying false while the app op said otherwise, or 0 when
     * there is no disagreement to report. Diagnostic only — it is the number that would have named the
     * 2026-08-29 outage as it happened.
     */
    fun disagreementMs(nowMs: Long): Long {
        val since = missingSinceMs ?: return 0L
        return if (engaged) 0L else nowMs - since
    }

    /**
     * Fold one pair of readings in; returns whether the Settings tier should stand down right now.
     *
     * [canDraw] is `Settings.canDrawOverlays()`. [opAllows] is the corroborating app-op read. Both are
     * taken by the caller so this class never touches a framework binder.
     */
    fun observe(canDraw: Boolean, opAllows: Boolean, nowMs: Long): Boolean {
        justEngaged = false
        if (canDraw) {
            // The permission is there on the read that matters most. Clear everything: an outage that
            // ends must re-arm the tier at once, and a later one must time itself from its own start.
            missingSinceMs = null
            engaged = false
            return false
        }
        val since = missingSinceMs ?: nowMs.also { missingSinceMs = it }
        // Both sources agree the permission is gone → repair, now. Corroboration is only ever allowed
        // to delay a stand-down that one source alone asked for.
        val standDown = !opAllows || nowMs - since >= disagreementGraceMs
        if (standDown && !engaged) justEngaged = true
        engaged = standDown
        return standDown
    }

    companion object {
        /** How long a `canDrawOverlays`-says-no / app-op-says-yes disagreement keeps the tier armed. */
        const val DEFAULT_DISAGREEMENT_GRACE_MS = 10L * 60 * 1_000
    }
}
