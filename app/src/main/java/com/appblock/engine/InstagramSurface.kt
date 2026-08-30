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
 *
 * ## The second rule: the Explore press-and-hold preview (added 2026-08-29, fifth cable)
 *
 * The pager rule above is not sufficient, because **there is a way to watch a reel with no pager on
 * screen at all.** Press and hold a thumbnail in the Explore grid and Instagram plays it near-full-width
 * in a *preview card* with a Like / Share / Repost menu under it. Reproduced over the cap on 0.6.0:
 * **three minutes of playback, finger off the glass, no block** (two screenshots three minutes apart are
 * different frames of the same reel).
 *
 * The window arrangement is the opposite of the one [targetForWindows] was written for:
 *  - the `PopupWindow` (`Rect(56, 1513 - 653, 2298)`, 28 nodes) holds **only** [`context_menu`][CONTEXT_MENU]
 *    and its items — the menu, nothing else;
 *  - the **video plays inside the Explore grid's own window** (`MainTabActivity`, 99 nodes).
 *
 * So both Instagram windows get walked, correctly, and **neither carries the pager**. Nothing was broken:
 * the multi-window fix taught the scan to read a player *underneath* a popup, and here **there is no
 * player**.
 *
 * The signal is therefore the pair — [`context_menu`][CONTEXT_MENU] **and**
 * [`explore_action_bar`][EXPLORE_ACTION_BAR] — because measurement on hardware showed neither half is
 * sufficient alone:
 *  - **Home feed**, press and hold → **no popup at all** (it just double-tap-likes, `like_heart`). Free,
 *    and free without needing a rule.
 *  - **Profile grid**, press and hold → the **identical** `context_menu`. Profile browsing is free
 *    (CONSTRAINTS §1), so *the menu on its own would block a free surface*. `explore_action_bar` is what
 *    scopes the rule to Explore; the profile's action bar is a different id.
 *  - **A plain tap** on an Explore reel opens the real player and is already blocked by the pager rule
 *    (measured 3/3 under 1.6 s). Only the *hold* needed a new rule.
 *
 * ⚖️ **A photo preview is budgeted too, deliberately.** The context menu is byte-identical for a reel and
 * a photo (only the labels differ, by adding "Report"), so no rule keyed on it can tell them apart —
 * and the owner's call on 2026-08-29 was that an Explore preview counts either way. CONSTRAINTS §1
 * keeps the Explore **grid** free; a full-card preview is not the grid.
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
     * The press-and-hold media preview's menu, which lives in its own `PopupWindow`.
     *
     * Present for a reel **and** for a photo, and present on the profile grid too — so it says "a
     * preview card is open", never "a reel is playing" and never "this surface is budgeted". It only
     * decides anything paired with [EXPLORE_ACTION_BAR].
     */
    const val CONTEXT_MENU = "${PREFIX}context_menu"

    /**
     * The Explore grid's own action bar — what the preview above is anchored over.
     *
     * A **free** surface's id being load-bearing looks odd, so: it is not evidence of anything budgeted.
     * It is the scope limiter that keeps [CONTEXT_MENU] from firing on the profile grid, whose action
     * bar carries a different id. Verified still present on Instagram 444.0.0.46.85 (2026-08-29), which
     * re-confirms the 2026-07-22 mapping.
     */
    const val EXPLORE_ACTION_BAR = "${PREFIX}explore_action_bar"

    /**
     * The only resource-ids the on-device scan needs to look for, instead of collecting the whole
     * (large) Instagram tree.
     *
     * ⚠️ The walk's "stop once every signal is seen" short-circuit is now nearly dead — no single window
     * carries all four — so in practice the walk is bounded by its node budget rather than by this set.
     * That costs nothing measured: the two windows in the case this exists for are **99 + 28 nodes**
     * against a 1,200 budget, and a full reel player is ~692.
     */
    val SIGNAL_IDS: Set<String> = setOf(REEL_PAGER, DM_SENDER, CONTEXT_MENU, EXPLORE_ACTION_BAR)

    /**
     * The ids whose continued existence [SignalCanary] tracks — a strict subset of [SIGNAL_IDS], and
     * the two left out are left out for **opposite** reasons.
     *
     * The canary's design (read its KDoc first) turns on one point: a *missing* sighting is evidence
     * only when a sighting was near-certain to happen anyway. Witness an id nobody exercises and the
     * prompt fires hardest at the user the blocker is working for, which is the exact inversion the
     * whole class exists to avoid.
     *
     *  - [REEL_PAGER] — **witnessed.** On screen the instant any reel plays, which is the budgeted
     *    behaviour itself. A fortnight of Instagram use with no sighting is real evidence it moved.
     *  - [EXPLORE_ACTION_BAR] — **witnessed** (added 2026-08-29 with the rule that reads it). On screen
     *    whenever the Explore *grid* is, i.e. during ordinary free browsing, so it is exercised at least
     *    as often as the pager. Its drift kills the Explore-preview rule, which fails **open**.
     *  - [CONTEXT_MENU] — **deliberately not witnessed, and this is the uncomfortable one.** Its drift
     *    also fails open, but the only thing that ever puts it on screen is the press-and-hold *bypass*.
     *    Witnessing it would prompt loudest at the user who stopped doing the thing being blocked. So
     *    the pair rule is covered through one of its two halves, and the gap is written down here
     *    rather than left to be discovered.
     *  - [DM_SENDER] — not witnessed, for the opposite reason: it is the only *exempting* id in this
     *    file, so its drift fails **closed** (a reel a real person sent starts being blocked). That is
     *    the safe direction; an alarm there would be an alarm about being too strict.
     *
     * `InstagramSurfaceTest` asserts this partition is total, so a fifth id cannot be added without
     * someone deciding out loud which side of it that id falls on.
     */
    val WITNESSED_IDS: Set<String> = setOf(REEL_PAGER, EXPLORE_ACTION_BAR)

    /**
     * The budgeted [Target] for the Instagram surface described by [resourceIds] (the set of
     * `viewIdResourceName`s visible in the Instagram window), or null when the surface is free.
     */
    fun targetFor(resourceIds: Set<String>): Target? =
        if (REEL_PAGER in resourceIds && DM_SENDER !in resourceIds) Target.INSTAGRAM_REELS_EXPLORE
        else null

    /**
     * The strictest [Target] across **every** Instagram window on screen, one signal set per window.
     *
     * Instagram is not one window. Opening the reel long-press menu adds a second window owned by the
     * same package — an anchored `PopupWindow`, higher layer, ~28 nodes, carrying neither signal — and
     * the service used to take the *first* Instagram window it was offered and never look at another.
     * `getWindows()` hands them over topmost-first, so the popup won at every tie and the reel player
     * underneath was never read: no pager, no target, no block.
     *
     * 🔴 **Confirmed on hardware 2026-08-29, and confirmed by the user as a bypass they had already
     * used to watch reels while over the cap.** The menu covers only the bottom-left third of the
     * screen (`Rect(56, 1513 - 653, 2298)` of 1080×2340), so the reel keeps playing in plain view.
     *
     * Strictest-wins, evaluated **per window rather than on the union**, and that distinction is the
     * whole safety argument: [DM_SENDER] exempts a reel only when it sits in the *same* window as the
     * pager, which is what "one reel a real person sent you" actually looks like. Unioning the sets
     * instead would let any window that happens to carry a sender field — a share sheet, a reply bar —
     * manufacture an exemption for a firehose reel in a different window. That would be a new
     * loosening path opened by a fix for a loosening bug.
     *
     * The four cases, all covered by tests:
     *  - firehose reel alone → blocked (unchanged)
     *  - firehose reel + popup → **blocked (this is the fix)**
     *  - DM reel alone → free (unchanged)
     *  - DM reel + popup → free (the exemption survives the fix)
     *
     * ## Then [explorePreview] arrives and *does* look across windows — read this before touching it
     *
     * Everything above says: never judge on the union. The Explore-preview rule breaks that shape, and
     * the reason it is still safe is an **asymmetry, not an exception**:
     *
     * > **A cross-window signal may only ever ADD strictness. It may never create an exemption.**
     *
     * [DM_SENDER] is the only exempting signal in this file, and it is still read **strictly
     * per-window**, inside [targetFor], exactly as before — so the share-sheet attack that motivated the
     * per-window discipline is still refused (its test is unchanged and still passes). [explorePreview]
     * can only turn "free" into "budgeted", so unioning its two inputs cannot manufacture a free pass;
     * the worst a wrong union can do there is block something it shouldn't, which is the safe direction
     * and visible immediately.
     *
     * Invert that and the fix would re-open the loosening bug it was written beside. If a future signal
     * needs to *exempt* across windows, it does not belong here.
     */
    fun targetForWindows(perWindowResourceIds: List<Set<String>>): Target? =
        perWindowResourceIds.firstNotNullOfOrNull { targetFor(it) }
            ?: explorePreview(perWindowResourceIds)

    /**
     * The Explore press-and-hold preview: a media preview card open over the Explore grid.
     *
     * Both halves are required and neither is checked per-window, because on hardware they are *never*
     * in the same window — the menu is a `PopupWindow`, the grid is the activity. Asking only that each
     * appears *somewhere* is also the more robust of the two spellings: were Instagram to move the menu
     * into the activity's own window, "different windows" would silently stop firing (a loosening
     * failure), whereas "anywhere" keeps working.
     *
     * See the class KDoc for why the pair is needed: the menu alone also fires on the free profile grid.
     */
    private fun explorePreview(perWindowResourceIds: List<Set<String>>): Target? {
        val previewOpen = perWindowResourceIds.any { CONTEXT_MENU in it }
        val overExplore = perWindowResourceIds.any { EXPLORE_ACTION_BAR in it }
        return if (previewOpen && overExplore) Target.INSTAGRAM_REELS_EXPLORE else null
    }
}
