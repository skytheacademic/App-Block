package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Instagram surface rule (Phase 2b). Each case's id set mirrors a real on-device uiautomator dump
 * from Gate A (see Dropbox `ig-dumps/MAPPING.md`): the budgeted reel player carries
 * `clips_viewer_view_pager`; the one free exception is a reel shared into a DM, which additionally
 * carries `sender_username_or_fullname`.
 */
class InstagramSurfaceTest {

    private val pager = InstagramSurface.REEL_PAGER
    private val sender = InstagramSurface.DM_SENDER
    private fun ids(vararg id: String) = id.toSet()

    // --- Budgeted: the reel firehose, from every entry point ---

    @Test fun `reels tab player is budgeted`() {
        // ig_reel_tab.xml: pager + clips_* , no DM sender.
        assertEquals(Target.INSTAGRAM_REELS_EXPLORE, InstagramSurface.targetFor(ids(pager, clip("clips_video_container"))))
    }

    @Test fun `reel opened from explore is budgeted`() {
        // ig_reel_from_explore.xml: pager present, no bottom nav, no sender.
        assertEquals(Target.INSTAGRAM_REELS_EXPLORE, InstagramSurface.targetFor(ids(pager, clip("clips_viewer_action_bar_title"))))
    }

    @Test fun `suggested reel after a DM swipe is budgeted`() {
        // ig_dm_reel2.xml: pager + suggested_title, DM sender GONE.
        assertEquals(Target.INSTAGRAM_REELS_EXPLORE, InstagramSurface.targetFor(ids(pager, clip("suggested_title"))))
    }

    @Test fun `watch full reel out of a story is budgeted`() {
        // ig_story_fullreel.xml: left reel_viewer_root, landed in the pager, no sender.
        assertEquals(Target.INSTAGRAM_REELS_EXPLORE, InstagramSurface.targetFor(ids(pager, clip("clips_viewer_cta_button"))))
    }

    // --- Free: the one shared reel, and every non-player surface ---

    @Test fun `single reel shared into a DM is free`() {
        // ig_dm_reel1.xml: pager AND sender_username_or_fullname — the reel a real person sent.
        assertNull(InstagramSurface.targetFor(ids(pager, sender, clip("clips_viewer_view_pager"))))
    }

    @Test fun `explore grid is free`() {
        // ig_explore_grid.xml: explore_action_bar, preview_clip_play_count — but NO pager.
        assertNull(InstagramSurface.targetFor(ids(clip("explore_action_bar"), clip("preview_clip_play_count"))))
    }

    @Test fun `feed inline video is free`() {
        // ig_feed_inline.xml: row_feed_* + feed_preview_keep_watching_button, no pager.
        assertNull(InstagramSurface.targetFor(ids(clip("row_feed_photo_imageview"), clip("feed_preview_keep_watching_button"))))
    }

    @Test fun `story with a reel in it is free`() {
        // ig_story_watchfull.xml: reel_viewer_root + "Watch full reel" attribution, no pager.
        assertNull(InstagramSurface.targetFor(ids(clip("reel_viewer_root"), clip("reel_app_attribution_action_text"))))
    }

    @Test fun `empty surface is free`() {
        assertNull(InstagramSurface.targetFor(emptySet()))
    }

    // --- Multi-window: Instagram is not one window (regression, 2026-08-29) ---
    //
    // Captured on the S25 FE while a reel's long-press menu was open. TWO windows, both owned by
    // com.instagram.android:
    //   id=6515  "Popup Window"  layer=1  focused/active   28 nodes, ids all context_menu*
    //   id=6488  "Instagram"     layer=0                   the reel player, carries the pager
    // getWindows() offers them topmost-first, and the service took the FIRST Instagram window it was
    // given — the menu — so the pager was never read and the reel was never budgeted.
    // 🔴 The user confirmed independently that this defeated a real block while over the reels cap.
    //
    // The popup's own set is empty here because the scan only ever collects SIGNAL_IDS, and the menu
    // carries neither. That is the point: an empty window used to be allowed to speak for the app.

    @Test fun `firehose reel is budgeted with its long-press menu on top`() {
        // The real ordering: popup first, player second.
        assertEquals(
            Target.INSTAGRAM_REELS_EXPLORE,
            InstagramSurface.targetForWindows(listOf(emptySet(), ids(pager, clip("clips_video_container")))),
        )
    }

    @Test fun `window order does not decide the verdict`() {
        assertEquals(
            Target.INSTAGRAM_REELS_EXPLORE,
            InstagramSurface.targetForWindows(listOf(ids(pager), emptySet())),
        )
    }

    @Test fun `DM reel keeps its exemption while a menu is open over it`() {
        assertNull(InstagramSurface.targetForWindows(listOf(emptySet(), ids(pager, sender))))
    }

    @Test fun `a sender field in another window cannot exempt a firehose reel`() {
        // THE reason this is per-window and not a union of every window's ids. A share sheet or reply
        // bar carrying sender_username_or_fullname must not buy a free pass for an algorithmic reel
        // playing in a different window — that would be a new loosening path opened by the fix for a
        // loosening bug. Union would return null here; per-window blocks.
        assertEquals(
            Target.INSTAGRAM_REELS_EXPLORE,
            InstagramSurface.targetForWindows(listOf(ids(sender), ids(pager))),
        )
    }

    @Test fun `no instagram window is free`() {
        assertNull(InstagramSurface.targetForWindows(emptyList()))
    }

    @Test fun `every window free stays free`() {
        assertNull(InstagramSurface.targetForWindows(listOf(emptySet(), ids(clip("explore_action_bar")))))
    }

    @Test fun `one window agrees with the single-surface rule`() {
        for (surface in listOf(ids(pager), ids(pager, sender), emptySet())) {
            assertEquals(
                InstagramSurface.targetFor(surface),
                InstagramSurface.targetForWindows(listOf(surface)),
            )
        }
    }

    // --- The Explore press-and-hold preview (2026-08-29, fifth cable) ---
    //
    // Captured on the S25 FE, over the cap, on release 0.6.0. Press and hold a thumbnail in the Explore
    // grid and the reel plays in a preview card for MINUTES with no block — verified by two screenshots
    // three minutes apart showing different frames of the same reel, finger off the glass.
    //
    //   Window #11  PopupWindow:21aa864   com.instagram.android   28 nodes, ids ALL context_menu*
    //               bounds [56,1513][653,2298]  <- the menu only; no video in this window
    //   Window #13  MainTabActivity       com.instagram.android   99 nodes, carries explore_action_bar
    //               <- the video plays HERE, in the Explore grid's own window
    //
    // Neither window carries the pager, so the rule above returns null — correctly. There is no player
    // to find. The fixtures below use exactly those two id sets.

    private val menu = InstagramSurface.CONTEXT_MENU
    private val exploreBar = InstagramSurface.EXPLORE_ACTION_BAR

    /** Window #11 verbatim: the 28-node popup carries the menu and nothing else the scan looks for. */
    private val previewMenuWindow = ids(menu)

    /** Window #13 verbatim, as the scan sees it: the Explore grid, no pager anywhere. */
    private val exploreGridWindow = ids(exploreBar, clip("preview_clip_play_count"))

    @Test fun `media held open in the explore preview is budgeted`() {
        // Covers a photo as well as a reel, and there is deliberately no second test for the photo:
        // the two produce the *same* id set on hardware (the photo's menu differs only by adding a
        // "Report" label, which is not a resource-id), so a photo case here would assert the identical
        // thing and prove nothing. That they are indistinguishable is the finding — and budgeting both
        // was the owner's call on 2026-08-29.
        assertEquals(
            Target.INSTAGRAM_REELS_EXPLORE,
            InstagramSurface.targetForWindows(listOf(previewMenuWindow, exploreGridWindow)),
        )
    }

    @Test fun `explore preview verdict does not depend on window order`() {
        assertEquals(
            Target.INSTAGRAM_REELS_EXPLORE,
            InstagramSurface.targetForWindows(listOf(exploreGridWindow, previewMenuWindow)),
        )
    }

    @Test fun `both halves in one window still count`() {
        // Robustness, not an observed layout: were Instagram to stop using a separate PopupWindow, a
        // "the two must be in different windows" spelling would silently stop firing — a loosening
        // failure. See explorePreview's KDoc.
        assertEquals(
            Target.INSTAGRAM_REELS_EXPLORE,
            InstagramSurface.targetForWindows(listOf(exploreGridWindow + previewMenuWindow)),
        )
    }

    @Test fun `the same preview menu on the profile grid is free`() {
        // 🔴 THE reason the rule is a pair. Press and hold on the profile grid produces the IDENTICAL
        // context_menu (measured on hardware the same session), and profile browsing is free under
        // CONSTRAINTS §1. Only explore_action_bar keeps this from blocking a free surface.
        assertNull(
            InstagramSurface.targetForWindows(listOf(previewMenuWindow, ids(clip("profile_tab")))),
        )
    }

    @Test fun `feed press and hold is free`() {
        // Measured: the feed produces NO popup at all — it just double-tap-likes (like_heart). So there
        // is no context_menu to pair with anything, whatever else is on screen.
        assertNull(
            InstagramSurface.targetForWindows(listOf(ids(clip("like_heart"), clip("main_feed_action_bar")))),
        )
    }

    @Test fun `explore grid with nothing held open is free`() {
        // The grid itself is free (CONSTRAINTS §1) — explore_action_bar must never budget on its own,
        // or merely opening Explore would block.
        assertNull(InstagramSurface.targetForWindows(listOf(exploreGridWindow)))
        assertNull(InstagramSurface.targetFor(exploreGridWindow))
    }

    @Test fun `a preview menu with no explore grid anywhere is free`() {
        assertNull(InstagramSurface.targetForWindows(listOf(previewMenuWindow, emptySet())))
    }

    @Test fun `the DM exemption is still read per-window, not across the union`() {
        // The asymmetry that makes the cross-window rule safe: adding it must not let a DM sender field
        // in one window exempt a firehose reel in another. The new ids are on screen here and change
        // nothing — the pager window has no sender, so it is still budgeted.
        assertEquals(
            Target.INSTAGRAM_REELS_EXPLORE,
            InstagramSurface.targetForWindows(listOf(ids(sender), ids(pager), previewMenuWindow)),
        )
    }

    @Test fun `a DM reel keeps its exemption with a preview menu on screen`() {
        // …and the exemption itself still works when the new signals are present but unpaired.
        assertNull(InstagramSurface.targetForWindows(listOf(previewMenuWindow, ids(pager, sender))))
    }

    @Test fun `the new ids never budget a single surface on their own`() {
        // targetFor is the per-window rule and must stay pager-only: the Explore rule is cross-window
        // by construction, so no single window may resolve on it.
        for (surface in listOf(ids(menu), ids(exploreBar), ids(menu, exploreBar))) {
            assertNull(InstagramSurface.targetFor(surface))
        }
    }

    @Test fun `every signal the scan collects is one the rules actually read`() {
        assertEquals(
            setOf(pager, sender, menu, exploreBar),
            InstagramSurface.SIGNAL_IDS,
        )
    }

    private fun clip(name: String) = "${InstagramSurface.PACKAGE}:id/$name"
}
