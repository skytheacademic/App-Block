package com.appblock.engine

/**
 * Instagram is the one target the engine can't identify by package alone. Its feed, DMs, stories and
 * the Explore *grid* are all free (CONSTRAINTS.md §1); only the full-screen Reels **player** — reached
 * from any entry point — counts against the shared Reels+Explore budget. This maps the set of
 * accessibility resource-ids visible in the Instagram window to the budgeted [Target], or null when the
 * current surface is one of the free ones.
 *
 * The rule (verified on-device 2026-07-22 — 9 uiautomator dumps, see Dropbox `ig-dumps/MAPPING.md`):
 *  - The reel firehose player is one component, [`clips_viewer_view_pager`][REEL_PAGER], shared by every
 *    entry: the Reels tab, a reel opened from Explore, the "Suggested" reels after a DM swipe, and the
 *    "Watch full reel" jump out of a story. None of the free surfaces (Explore grid `explore_action_bar`,
 *    feed inline video `row_feed_*`, Stories `reel_viewer_root`, search) ever carry the pager.
 *  - A reel someone shared into a DM additionally carries [`sender_username_or_fullname`][DM_SENDER]
 *    (plus its "Reply to …" bar). That single shared reel is free — the one a real person sent you. The
 *    instant you swipe past it the sender field is gone and Instagram shows `suggested_title`, i.e. it's
 *    the algorithmic firehose again.
 *
 * Net rule: **budget the pager UNLESS a DM sender field is present.** That one line covers "one reel a
 * friend sent me is fine, the rabbit-hole is not" uniformly across every entry point — no swipe
 * counting, no per-entry special-casing.
 */
object InstagramSurface {

    /** The real Instagram package. Feed/DMs/stories here are free; only the reel player is budgeted. */
    const val PACKAGE = "com.instagram.android"

    private const val PREFIX = "$PACKAGE:id/"

    /** The reel-player view pager — present in every firehose entry, absent on every free surface. */
    const val REEL_PAGER = "${PREFIX}clips_viewer_view_pager"

    /** Marks a reel that arrived via DM from a real person — that single shared reel stays free. */
    const val DM_SENDER = "${PREFIX}sender_username_or_fullname"

    /**
     * The only resource-ids the on-device scan needs to look for — so the accessibility tree walk can
     * stop as soon as it has seen both, instead of collecting the whole (large) Instagram tree.
     */
    val SIGNAL_IDS: Set<String> = setOf(REEL_PAGER, DM_SENDER)

    /**
     * The budgeted [Target] for the Instagram surface described by [resourceIds] (the set of
     * `viewIdResourceName`s visible in the Instagram window), or null when the surface is free.
     */
    fun targetFor(resourceIds: Set<String>): Target? =
        if (REEL_PAGER in resourceIds && DM_SENDER !in resourceIds) Target.INSTAGRAM_REELS_EXPLORE
        else null
}
