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
        assertFalse(SettingsWatch.isWatched(null))
    }

    // ---- the installer tier: uninstall was 3 taps and completely unguarded (audit finding B-2) ----

    private val installer = "com.google.android.packageinstaller"
    private val uninstallDialog =
        listOf<CharSequence?>("Do you want to uninstall this app?", "App-Block", "Cancel", "OK")
    private val installDialog =
        listOf<CharSequence?>("Do you want to install an update to this app?", "App-Block", "Cancel", "Install")

    @Test fun `bounces the uninstall confirmation dialog`() {
        assertTrue(bounce(pkg = installer, texts = uninstallDialog))
        assertTrue(bounce(pkg = "com.android.packageinstaller", texts = uninstallDialog))
        assertTrue(bounce(pkg = "com.samsung.android.packageinstaller", texts = uninstallDialog))
    }

    /**
     * The trap this tier exists to avoid. The installer shows the label while *installing* too, and
     * bouncing that would make it impossible to sideload a newer App-Block from the phone — the
     * self-defense would block the app's own updates, exactly like the overlay-permission page.
     */
    @Test fun `lets the installer update the app`() {
        assertFalse(bounce(pkg = installer, texts = installDialog))
    }

    @Test fun `ignores an uninstall dialog for some other app`() {
        assertFalse(
            bounce(pkg = installer, texts = listOf("Do you want to uninstall this app?", "Reddit", "OK")),
        )
    }

    /**
     * The launcher stays unwatched, and this is the reason. Its window shows "App-Block" under the
     * icon permanently, so a bare label match bounces you off your own home screen forever — and
     * label-plus-control still misfires, because long-pressing *any* other icon puts "Uninstall" in
     * a window that already contains App-Block's label. Every uninstall route ends at the installer
     * dialog above, so catching it there covers them all without touching the launcher.
     */
    @Test fun `never bounces the launcher, even mid-uninstall of another app`() {
        val launcher = "com.sec.android.app.launcher"
        assertFalse(SettingsWatch.isWatched(launcher))
        assertFalse(bounce(pkg = launcher, texts = listOf("App-Block", "Chrome", "Phone")))
        assertFalse(bounce(pkg = launcher, texts = listOf("App-Block", "Chrome", "Uninstall", "App info")))
    }

    /**
     * The notification shade is not watched either: App-Block's own watchdog notification is ongoing
     * and non-dismissable, so a bounce there would cost access to quick settings — airplane mode,
     * mobile data, the flashlight — for as long as the notification stood.
     */
    @Test fun `never bounces the notification shade`() {
        assertFalse(SettingsWatch.isWatched("com.android.systemui"))
        assertFalse(
            bounce(
                pkg = "com.android.systemui",
                texts = listOf("App-Block is not protecting you", "The Accessibility service is switched off"),
            ),
        )
    }
}
