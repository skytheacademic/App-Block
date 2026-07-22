package com.appblock.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsWatchTest {

    private val label = "App-Block"

    // Realistic One UI screens, as visible-text lists.
    private val accessibilityDetail = listOf<CharSequence?>("App-Block detection", "Off", "Settings")
    private val turnOffDialog = listOf<CharSequence?>("Turn off App-Block detection?", "Cancel", "Turn off")
    private val appInfo = listOf<CharSequence?>("App info", "App-Block", "Force stop", "Uninstall")
    private val unrelatedScreen = listOf<CharSequence?>("Display", "Brightness", "Dark mode")

    private fun bounce(
        pkg: String? = "com.android.settings",
        texts: List<CharSequence?>,
        standDown: Boolean = false,
    ) = SettingsWatch.shouldBounce(pkg, texts, label, standDown)

    @Test fun `bounces the accessibility toggle detail page`() {
        assertTrue(bounce(texts = accessibilityDetail))
    }

    @Test fun `bounces the turn-off confirmation dialog`() {
        assertTrue(bounce(texts = turnOffDialog))
    }

    @Test fun `bounces the app info page`() {
        assertTrue(bounce(texts = appInfo))
    }

    @Test fun `matches case-insensitively`() {
        assertTrue(bounce(texts = listOf("turn off APP-BLOCK detection?")))
    }

    @Test fun `ignores settings screens that are not about the app`() {
        assertFalse(bounce(texts = unrelatedScreen))
    }

    @Test fun `ignores other apps entirely, even ones showing the label`() {
        assertFalse(bounce(pkg = "com.zhiliaoapp.musically", texts = appInfo))
        assertFalse(bounce(pkg = null, texts = appInfo))
    }

    @Test fun `stands down when told to - setup or open change window`() {
        assertFalse(bounce(texts = turnOffDialog, standDown = true))
    }

    @Test fun `handles null and empty texts`() {
        assertFalse(bounce(texts = listOf(null, "", "  ")))
        assertFalse(bounce(texts = emptyList()))
    }

    @Test fun `a blank label never matches`() {
        assertFalse(SettingsWatch.shouldBounce("com.android.settings", listOf("anything"), "", false))
    }

    @Test fun `watches settings, samsung accessibility and device care`() {
        assertTrue(SettingsWatch.isWatched("com.android.settings"))
        assertTrue(SettingsWatch.isWatched("com.samsung.accessibility"))
        assertTrue(SettingsWatch.isWatched("com.samsung.android.lool"))
        assertFalse(SettingsWatch.isWatched("com.sec.android.app.launcher"))
        assertFalse(SettingsWatch.isWatched(null))
    }
}
