package com.appblock.engine

/**
 * Turns "the address bar isn't in the tree right now" into either *not yet* or *not ever* — the timing
 * that lets website blocking fail closed without fighting ordinary browsing.
 *
 * The raw read can only say "found" or "absent"; absent is the ambiguous one, and both readings of it
 * are expensive. Call it harmless and the entire blocklist is one renamed resource-id away from
 * enforcing nothing ([BrowserTargets.Omnibox.Unreadable] exists for exactly that). Call it hostile and
 * the browser gets blocked out from under the user for reasons they can't see. So the absence has to
 * earn the hostile reading, and there are two independent ways for it to be innocent:
 *
 *  1. **The browser only just came foreground** and its tree isn't built yet. Covered by [graceMs] —
 *     an absence younger than the grace decides nothing.
 *  2. **Chrome scrolled its toolbar away** mid-article. Covered by the seen-it-here flag: a page you
 *     scrolled down was necessarily loaded with the toolbar visible, so a successful read is already
 *     on record for this browser and no later absence can reach [BrowserTargets.Omnibox.Unreadable].
 *     A renamed resource-id has no such read behind it and reaches it every time.
 *
 * Both are evidence about *one browser's current tree*, so the state is per-package ([watches]) and is
 * dropped by [retain] the moment that browser stops being on screen. Two separate claims ride on that
 * one rule:
 *
 *  - **Chrome's working omnibox is no evidence about Brave's.** Keyed by package, so it can't leak. The
 *    two also keep separate grace clocks, which is what stops a pair of browsers side by side from
 *    re-arming each other forever — with one slot between them, neither could ever be called unreadable.
 *  - **A read only vouches for the tree it was taken from.** Leaving and coming back is where a rebuilt
 *    tree shows up, and it is the one moment a stale vouch would be caught. Without [retain] the flag
 *    outlives its evidence indefinitely: Chrome auto-updates overnight with a renamed `url_bar`, our
 *    process never died, and the vouch taken *before* the update keeps the blocklist enforcing nothing
 *    until the next reboot. Exactly the silent failure this class exists to prevent, from the inside.
 *
 * Expiring the vouch on a *timer* instead would be the obvious fix and is the wrong one — it would run
 * out mid-article precisely while the toolbar is scrolled away, which is the one moment the vouch is
 * load-bearing. Foreground-exit is the event that means "different tree"; elapsed time isn't.
 *
 * ⚠️ The grace length is a desk judgement — whether Chrome's scrolled-away toolbar actually leaves the
 * accessibility tree is unverified on hardware. If it turns out it does *and* a fresh launch can
 * restore an already-scrolled tab, that is where a false positive would come from.
 *
 * Pure and time-injected like [OcclusionHold]: the service supplies the raw read (it needs an
 * `AccessibilityNodeInfo` to take one) and the clock, and this owns the state that spans passes.
 */
class AddressWatch(
    private val graceMs: Long = DEFAULT_GRACE_MS,
) {

    /** One browser's standing: whether its omnibox has ever read as a committed URL since this watch
     *  began, and when that was — the two facts an absence has to get past. */
    private class Watch(var seen: Boolean, val sinceMs: Long)

    private val watches = HashMap<String, Watch>()

    /**
     * Interpret one raw omnibox read for [pkg].
     *
     * [read] is the address bar as found in the tree, or null when the node wasn't there at all — the
     * distinction the four-state result is built on. A found node is passed straight through: it is a
     * real answer, whether or not it holds a committed URL, and only [BrowserTargets.Omnibox.Url]
     * counts as proof the omnibox is readable here (the caller can be handed
     * [BrowserTargets.Omnibox.Editing] forever from a focused, empty field).
     *
     * A package with no watch yet starts one at [nowMs], so the grace is measured from the first time
     * this browser was looked at rather than from service start.
     *
     * Only an absence can end up blocked, and only once it is both past the grace and unvouched-for.
     */
    fun observe(read: BrowserTargets.Omnibox?, pkg: String, nowMs: Long): BrowserTargets.Omnibox {
        val watch = watches.getOrPut(pkg) { Watch(seen = false, sinceMs = nowMs) }
        if (read is BrowserTargets.Omnibox.Url) watch.seen = true
        return when {
            read != null -> read
            watch.seen -> BrowserTargets.Omnibox.Unknown
            nowMs - watch.sinceMs < graceMs -> BrowserTargets.Omnibox.Unknown
            else -> BrowserTargets.Omnibox.Unreadable
        }
    }

    /**
     * Keep watches only for the browsers still on screen; anything else has left the foreground and its
     * next sighting starts clean — full grace, no vouch.
     *
     * Called once per window scan with every allowlisted package that had a window in it, which is a
     * wider set than the one [observe] is called for: the service reads a single browser per pass, but a
     * browser sitting in the other split-screen pane is still present and must not be evicted.
     *
     * Erring: an over-eager eviction hands back a fresh grace, i.e. one [graceMs] of allowing. That is
     * reachable when our own block overlay prunes the browser out of the window list entirely (see
     * `holdThroughOcclusion`) — bounded, self-correcting on the next pass, and paid for by closing the
     * stale-vouch hole above, which has no bound at all.
     */
    fun retain(visibleBrowsers: Set<String>) {
        if (watches.isEmpty()) return
        watches.keys.retainAll(visibleBrowsers)
    }

    companion object {
        /** How long an allowlisted browser may show no address bar before that counts as unreadable
         *  rather than as still settling. Long enough to cover a cold browser launch; short enough that
         *  a broken read isn't quietly allowed for a whole browsing session. */
        const val DEFAULT_GRACE_MS = 5_000L
    }
}
