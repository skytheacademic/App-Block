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
 *     and they carry a package name. A state change from a package that is **not one of the packages
 *     this block is about** means the user left — release. Costs at worst a brief flicker if it misfires
 *     (the read comes back blockable and the overlay returns within a frame or two).
 *
 *     ⚠️ **"Not one of them", not "different from the one we armed on"** — see [heldPackages]. The
 *     singleton phrasing was this class's own wording until 2026-08-30 and it was measurably wrong the
 *     moment two budgeted apps shared a screen: each pane's events released the other pane's hold, so
 *     the "at worst a brief flicker" above stopped being a misfire and became the steady state, ~120 ms
 *     every 2–3 s for as long as split-screen was open.
 *  2. **[holdLimitMs] since the last real read, but only while (1) has never been seen to work.** A
 *     backstop for "the events never came", which is the *only* thing it was ever for. See below — it
 *     used to run unconditionally, and that was a defect.
 *
 * ## Why the backstop is conditional (C-1, Gate F Phase 3, 2026-07-27)
 *
 * It used to fire regardless, and on hardware that was a **bypass**, not a safety net. Sitting on a
 * blocked page, the overlay dropped for 148–256 ms once every 60 s, indefinitely — measured five times,
 * one per minute to the second, and visible to the naked eye. During the gap the blocked page is not
 * merely showing: it is unobstructed and **tappable**.
 *
 * The mechanism is that the backstop acts on evidence it has already decided not to trust. Behind the
 * overlay every read comes back pruned; the hold exists precisely because those reads are worthless.
 * When the timeout expired it released anyway and returned null, so the caller believed the pruned read,
 * took the overlay down, the tree un-pruned, the page re-read as blocked, and the overlay was redrawn a
 * fifth of a second later. Then it repeated, forever. The class doc used to price this as "one
 * sub-second re-read per minute", which was numerically right and the wrong judgement: nobody had asked
 * whether a person can see it. They can.
 *
 * Raising the limit was rejected — a longer fuse makes the exposure rarer, not absent, and the exposure
 * is the whole problem. The fix is that condition (1) had already been proven on this hardware and the
 * code was still hedging against it: Gate F Phase 1 released 5/5 in 0.8–0.9 s, dwell time irrelevant,
 * and the 60-s backstop never once fired in anger. So while the event stream is demonstrably reporting
 * foreground changes, the moved-on test covers the stuck-overlay case on its own and the timeout has
 * nothing left to protect. [noteForegroundEvent] is that demonstration.
 *
 * **The accepted cost, stated plainly:** on a device where events genuinely stopped naming packages
 * *after* having named one, the block screen would follow the user to the launcher and stay there. Its
 * own Close button still exits, the engine's exits are untouched, and no such device has been observed.
 * That is an annoyance with a visible way out; the behaviour it replaces was a commitment device that
 * unlocked itself for a fifth of a second every minute. A blocker must never talk itself out of
 * blocking, and the unconditional backstop did exactly that.
 *
 * Generic over the read type so [com.appblock.service.AppBlockerAccessibilityService]'s `Foreground`
 * stays private to the service. This class owns the held value outright — the service must not keep its
 * own copy, or the two can disagree about whether anything is held.
 */
class OcclusionHold<T : Any>(
    private val holdLimitMs: Long = DEFAULT_HOLD_LIMIT_MS,
) {

    private var held: T? = null

    /**
     * The packages whose presence justifies this hold — every budgeted app that was on screen when the
     * read was taken, plus the browser whose URL was blocked.
     *
     * ⚠️ **A SET, not the single package the event stream last named — and that was a measured defect,
     * not a refinement.** It used to be one `String?`, filled from `DisplayHolds.lastPackage`, so the
     * moved-on test really asked *"has the last-named foreground package changed since we armed?"* In
     * split-screen that is the wrong question and it answers itself: both panes emit window-state events
     * continuously, so whichever one the hold armed on, the **other** one's next event reads as *"the
     * user left"*, the hold releases, and the overlay drops until the next pass puts it back.
     *
     * Measured on hardware 2026-08-30 (S25 FE, One UI 8, Instagram + TikTok in split-screen): the block
     * screen dropped for **~120 ms every 2–3 s, indefinitely, and was visible to the naked eye** — a ~5 %
     * duty cycle against C-1's 0.33 %, and C-1 is on this repo's record as a bypass rather than a
     * nuisance.
     *
     * 💡 **The split-screen P0 fix is what made it pathological.** Before `AppTargets.foregroundTargets`,
     * the second budgeted app was not a target at all, so it never got far enough to fight over the hold.
     * Closing one hole is what made this one bite — which is the argument for shipping them together.
     */
    private var heldPackages: Set<String> = emptySet()
    private var heldSinceMs = 0L

    /**
     * Whether the event stream has ever been observed to report a foreground change — see the class
     * doc. Deliberately a latch for the life of the service and **not** cleared by [release]: it is a
     * fact about whether this channel works on this device, not about any one hold.
     *
     * Deliberately not a "silent for N seconds" test either, which is the shape this first took. A user
     * sitting still on a blocked page produces no window-state events at all, so silence there is
     * indistinguishable from a dead stream — and treating it as dead reproduces the exact once-a-minute
     * flash this change removes.
     */
    private var foregroundEventSeen = false

    /** Whether anything is currently held — for the caller's "seed me once" check and diagnostics. */
    val isArmed: Boolean get() = held != null

    /** True while the timeout backstop is still load-bearing, i.e. before [noteForegroundEvent]. */
    val backstopArmed: Boolean get() = !foregroundEventSeen

    /**
     * The event stream just named a foreground package, so the moved-on release condition demonstrably
     * works here and the timeout backstop stands down for good.
     *
     * Call this for the events the caller is willing to act on in [sustain] — our own overlay and toast
     * churn is filtered out upstream and must stay filtered, or the hold would be proven live by its own
     * side effects.
     */
    fun noteForegroundEvent() {
        foregroundEventSeen = true
    }

    /**
     * A real read got through the pruning: (re)arm on it and restart the [holdLimitMs] countdown.
     *
     * [justifiedBy] is every package whose presence on screen justifies keeping this block up — the
     * budgeted apps the read found, plus the blocked browser. It may legitimately be **empty**, and an
     * empty set deliberately means *"no basis to hold against a named package"*: the first named
     * foreground releases. That is the safe direction — releasing when we should not have costs a frame
     * of flicker and the next read puts the overlay straight back, whereas holding when we should not
     * have is a block screen that follows the user out of the app with no exit but the 60 s backstop,
     * and the backstop is stood down for good once the event channel has proven itself.
     */
    fun arm(value: T, justifiedBy: Set<String>, nowMs: Long) {
        held = value
        heldPackages = justifiedBy
        heldSinceMs = nowMs
    }

    /**
     * Arm only if nothing is held yet.
     *
     * Deliberately not a plain [arm]: the caller's value is the *effective* read, which may itself be
     * the held one. Re-arming on that would refresh the countdown off held data and the timeout would
     * never fire. So the clock always measures time since the last read that genuinely got through.
     */
    fun seed(value: T, justifiedBy: Set<String>, nowMs: Long) {
        if (held == null) arm(value, justifiedBy, nowMs)
    }

    /** Drop the hold — the overlay came down, or the engine allowed the target. */
    fun release() {
        held = null
        heldPackages = emptySet()
        heldSinceMs = 0L
    }

    /**
     * The value to keep blocking on, or null when the hold has no claim left and the caller should
     * believe the (pruned) read instead. Releases on the way out so the next pass starts clean.
     */
    fun sustain(foregroundPackage: String?, nowMs: Long): T? {
        val current = held ?: return null
        // Order matters for the reasoning even though both branches release: the moved-on test is the
        // real exit and is asked first, so the backstop is only ever consulted about a case it alone
        // can answer.
        //
        // "Not one of the packages this block is about" — NOT "different from the one we armed on".
        // With one budgeted app on screen the two are identical, which is why the singleton version
        // survived every test and only split-screen exposed it. See [heldPackages].
        if (foregroundPackage != null && foregroundPackage !in heldPackages) {
            release()
            return null
        }
        if (backstopArmed && nowMs - heldSinceMs >= holdLimitMs) {
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
