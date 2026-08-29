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

    private fun clip(name: String) = "${InstagramSurface.PACKAGE}:id/$name"
}
