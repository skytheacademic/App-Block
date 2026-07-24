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

    private fun clip(name: String) = "${InstagramSurface.PACKAGE}:id/$name"
}
