package com.appblock.engine

/**
 * Detects that Instagram reel blocking has quietly stopped working.
 *
 * The failure it watches for: [InstagramSurface.REEL_PAGER] is a resource-id inside someone else's
 * app, and Instagram ships weekly. The day `clips_viewer_view_pager` is renamed, the scan finds
 * nothing, no target resolves, no overlay appears, and *nothing anywhere says so* — the only signal is
 * that doom-scrolling suddenly works again. That's the one failure mode where the app's silence is
 * indistinguishable from the app working.
 *
 * The obvious canary is wrong. "Warn if the reel pager hasn't been seen lately" fires hardest at the
 * user who stopped watching reels — i.e. it treats the blocker's whole purpose as a fault. Absence of
 * a sighting carries no information on its own.
 *
 * So anchor to the thing that can actually break it: **Instagram's version**. Resource-ids only move
 * when the app is updated, so a sighting confirms the ids for that exact versionCode and stays valid
 * until Instagram updates. Never watching reels then costs at most one prompt per Instagram update,
 * and it clears itself the moment the pager is next seen — which happens on the way *into* the block,
 * before the overlay is even drawn, so confirming it costs one tap and blocks you as it does so.
 *
 * Two honest limits, neither of which the version anchor can cover:
 *  - Meta rolls layouts out server-side, so ids can in principle change with no version bump. The
 *    canary would stay [Health.CONFIRMED] through that.
 *  - The timestamps are wall-clock, so a clock change skews the grace period — forward trips it early
 *    (a spurious prompt, the harmless direction), backward delays it. Not worth hardening: the
 *    tamper guard already latches everything blocked when the clock isn't trusted.
 */
object SignalCanary {

    /**
     * How long after an Instagram update to stay quiet before prompting. Long on purpose: this is a
     * diagnostic, and a prompt the user learns to dismiss is worse than no prompt at all.
     */
    const val DEFAULT_GRACE_MS = 14L * 24 * 60 * 60 * 1_000

    enum class Health {
        /** Instagram isn't installed (or its version can't be read) — nothing to verify. */
        NO_APP,

        /** The reel pager has been seen on exactly the version that's installed now. */
        CONFIRMED,

        /** Installed version has moved on, but it's within the grace period — no prompt yet. */
        PENDING,

        /** Instagram updated a while ago and no sighting has re-confirmed the ids since. */
        STALE,
    }

    /**
     * What's known about the reel-pager ids. [confirmedVersion] is the Instagram versionCode the pager
     * was last actually seen on; [installedVersion] is what's on the phone now, first noticed at
     * [installedSeenAtMs] — the grace period runs from there, so it measures time since the *update*
     * rather than time since the last sighting.
     */
    data class Witness(
        val confirmedVersion: Long? = null,
        val confirmedAtMs: Long = 0L,
        val installedVersion: Long? = null,
        val installedSeenAtMs: Long = 0L,
    )

    fun assess(witness: Witness, nowMs: Long, graceMs: Long = DEFAULT_GRACE_MS): Health = when {
        witness.installedVersion == null -> Health.NO_APP
        witness.confirmedVersion == witness.installedVersion -> Health.CONFIRMED
        nowMs - witness.installedSeenAtMs < graceMs -> Health.PENDING
        else -> Health.STALE
    }

    /**
     * Fold in a fresh look at what's installed. Restarts the grace clock only when the version actually
     * changed — calling this every scan must not keep pushing the deadline away, or the canary could
     * never reach [Health.STALE].
     */
    fun observe(witness: Witness, installedVersion: Long?, nowMs: Long): Witness =
        if (installedVersion == witness.installedVersion) witness
        else witness.copy(installedVersion = installedVersion, installedSeenAtMs = nowMs)

    /** The reel pager was just seen: it confirms the ids for whatever version is installed right now. */
    fun confirm(witness: Witness, installedVersion: Long?, nowMs: Long): Witness =
        observe(witness, installedVersion, nowMs)
            .copy(confirmedVersion = installedVersion, confirmedAtMs = nowMs)
}
