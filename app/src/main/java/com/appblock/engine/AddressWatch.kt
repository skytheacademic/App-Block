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
 * Both are per-browser, which is why the watch resets whenever [observe] is handed a different package:
 * "we have read Chrome's omnibox" is no evidence at all about Brave's, and carrying the flag across
 * would let a working browser vouch for a broken one. The reset also restarts the grace, since a
 * newly-foregrounded browser deserves the same settle time as the first one did.
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

    /** Which browser the current watch belongs to — a different one starts a new watch. */
    private var watchedPkg: String? = null

    /** Whether a committed URL has ever been read since [watchedPkg] took the watch. */
    private var seenForPkg = false

    /** When the watch for [watchedPkg] started, for the [graceMs] countdown. */
    private var watchedSinceMs = 0L

    /**
     * Interpret one raw omnibox read for [pkg].
     *
     * [read] is the address bar as found in the tree, or null when the node wasn't there at all — the
     * distinction the four-state result is built on. A found node is passed straight through: it is a
     * real answer, whether or not it holds a committed URL, and only [BrowserTargets.Omnibox.Url]
     * counts as proof the omnibox is readable here (the caller can be handed
     * [BrowserTargets.Omnibox.Editing] forever from a focused, empty field).
     *
     * Only an absence can end up blocked, and only once it is both past the grace and unvouched-for.
     */
    fun observe(read: BrowserTargets.Omnibox?, pkg: String, nowMs: Long): BrowserTargets.Omnibox {
        if (pkg != watchedPkg) {
            watchedPkg = pkg
            seenForPkg = false
            watchedSinceMs = nowMs
        }
        if (read is BrowserTargets.Omnibox.Url) seenForPkg = true
        return when {
            read != null -> read
            seenForPkg -> BrowserTargets.Omnibox.Unknown
            nowMs - watchedSinceMs < graceMs -> BrowserTargets.Omnibox.Unknown
            else -> BrowserTargets.Omnibox.Unreadable
        }
    }

    companion object {
        /** How long an allowlisted browser may show no address bar before that counts as unreadable
         *  rather than as still settling. Long enough to cover a cold browser launch; short enough that
         *  a broken read isn't quietly allowed for a whole browsing session. */
        const val DEFAULT_GRACE_MS = 5_000L
    }
}
