package com.appblock.engine

/**
 * Keeps the last real read alive while our own block overlay is the thing hiding the evidence.
 *
 * The problem it solves (measured on the S25, Gate B 2026-07-23): the instant the overlay goes up, the
 * framework prunes the occluded app's accessibility tree — Instagram drops from 692 nodes to 1–2, and at
 * full opacity the app vanishes from `getWindows()` entirely. The reel signal disappears, the target
 * resolves to null, the overlay comes down, the tree comes back, and the whole thing oscillates about
 * once a second. So the blocker cannot use "is the blocked app still on screen?" as its release
 * condition: it blinded itself by blocking.
 *
 * The hold therefore assumes the blocked thing is still there — but it can't assume that *forever*,
 * which is the bug this class exists to fix. Home still works behind an overlay, so without a release
 * condition the block screen follows the user to the launcher and only the overlay's own Close button
 * gets them out. Two independent ways out, both erring toward staying blocked:
 *
 *  1. **The foreground moved.** Window-state events keep arriving even while `getWindows()` is pruned,
 *     and they carry a package name. A state change from a package other than the one we armed on means
 *     the user left — release. Costs at worst a brief flicker if it misfires (the read comes back
 *     blockable and the overlay returns within a frame or two).
 *  2. **[holdLimitMs] since the last real read.** A backstop for "the events never came" — unverified
 *     on hardware, so it must not be the only exit. One minute bounds the stuck-overlay case; the price
 *     when the hold is doing real work is one sub-second re-read per minute.
 *
 * Generic over the read type so [com.appblock.service.AppBlockerAccessibilityService]'s `Foreground`
 * stays private to the service. This class owns the held value outright — the service must not keep its
 * own copy, or the two can disagree about whether anything is held.
 */
class OcclusionHold<T : Any>(
    private val holdLimitMs: Long = DEFAULT_HOLD_LIMIT_MS,
) {

    private var held: T? = null
    private var heldPackage: String? = null
    private var heldSinceMs = 0L

    /** Whether anything is currently held — for the caller's "seed me once" check and diagnostics. */
    val isArmed: Boolean get() = held != null

    /**
     * A real read got through the pruning: (re)arm on it and restart the [holdLimitMs] countdown.
     * [foregroundPackage] is the package the *event stream* last reported, which may legitimately be
     * null — we then have no basis for the moved-on test and only the timeout can release.
     */
    fun arm(value: T, foregroundPackage: String?, nowMs: Long) {
        held = value
        heldPackage = foregroundPackage
        heldSinceMs = nowMs
    }

    /**
     * Arm only if nothing is held yet.
     *
     * Deliberately not a plain [arm]: the caller's value is the *effective* read, which may itself be
     * the held one. Re-arming on that would refresh the countdown off held data and the timeout would
     * never fire. So the clock always measures time since the last read that genuinely got through.
     */
    fun seed(value: T, foregroundPackage: String?, nowMs: Long) {
        if (held == null) arm(value, foregroundPackage, nowMs)
    }

    /** Drop the hold — the overlay came down, or the engine allowed the target. */
    fun release() {
        held = null
        heldPackage = null
        heldSinceMs = 0L
    }

    /**
     * The value to keep blocking on, or null when the hold has no claim left and the caller should
     * believe the (pruned) read instead. Releases on the way out so the next pass starts clean.
     */
    fun sustain(foregroundPackage: String?, nowMs: Long): T? {
        val current = held ?: return null
        if (nowMs - heldSinceMs >= holdLimitMs) {
            release()
            return null
        }
        val armedOn = heldPackage
        if (armedOn != null && foregroundPackage != null && foregroundPackage != armedOn) {
            release()
            return null
        }
        return current
    }

    companion object {
        /** See the class doc — the backstop, not the primary exit. */
        const val DEFAULT_HOLD_LIMIT_MS = 60_000L
    }
}
