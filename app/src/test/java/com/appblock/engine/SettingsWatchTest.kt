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
        setupIncomplete: Boolean = false,
        windowOpen: Boolean = false,
        repairMode: Boolean = false,
    ) = SettingsWatch.shouldBounce(pkg, texts, label, setupIncomplete, windowOpen, repairMode)

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

    @Test fun `stands down while setup is still incomplete`() {
        assertFalse(bounce(texts = turnOffDialog, setupIncomplete = true))
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

    // ---- repair mode: don't guard the app out of its own repair (audit finding C-2) ----

    private val overlayPage =
        listOf<CharSequence?>("Appear on top", "App-Block", "Allow permission")

    /**
     * The trap. Losing "Appear on top" leaves the block screen undrawable, and the page that restores
     * it names App-Block — so the self-defense bounces every attempt to fix it, including the button
     * inside App-Block that opens exactly that page. Without repair mode the only way back is adb.
     */
    @Test fun `lets the user reach the page that restores the overlay permission`() {
        assertTrue(bounce(texts = overlayPage))
        assertFalse(bounce(texts = overlayPage, repairMode = true))
    }

    @Test fun `repair mode stands the whole settings tier down`() {
        assertFalse(bounce(texts = accessibilityDetail, repairMode = true))
        assertFalse(bounce(texts = turnOffDialog, repairMode = true))
        assertFalse(bounce(pkg = "com.samsung.accessibility", texts = turnOffDialog, repairMode = true))
    }

    /**
     * ...but not the installer tier. Repair mode is entered whenever a permission is missing, which is
     * a state the user can reach by accident; it must not turn into a free uninstall.
     */
    @Test fun `repair mode still guards uninstall`() {
        assertTrue(bounce(pkg = installer, texts = uninstallDialog, repairMode = true))
    }

    @Test fun `repair mode does not resurrect the screens that were never watched`() {
        assertFalse(bounce(texts = unrelatedScreen, repairMode = true))
        assertFalse(bounce(pkg = installer, texts = installDialog, repairMode = true))
    }

    // ---- B-8: an open window buys Settings, not removal ----

    /**
     * Turning the service off stays a *gated* loosening (CONSTRAINTS §6) rather than an impossibility,
     * so the Settings tier still stands down. Any category does: a websites window sat through the
     * longer 72-hour wait, so reaching Settings with one is never a shortcut past the 2-hour apps gate.
     */
    @Test fun `an open window stands the settings tier down`() {
        assertTrue(bounce(texts = turnOffDialog))
        assertFalse(bounce(texts = turnOffDialog, windowOpen = true))
        assertFalse(bounce(texts = accessibilityDetail, windowOpen = true))
    }

    /**
     * The finding itself. An open window used to stand down *both* tiers, so a 2-hour apps window
     * bought a full uninstall — every rule, the blocklist and the stashed key gone for the shortest
     * wait in the system, which inverts the rule the whole device runs on. Removal now costs the
     * computer or safe mode, neither of which is an impulse.
     */
    @Test fun `an open window does not buy an uninstall`() {
        assertTrue(bounce(pkg = installer, texts = uninstallDialog, windowOpen = true))
        assertTrue(bounce(pkg = installer, texts = uninstallDialog, windowOpen = true, repairMode = true))
    }

    /**
     * Setup is the one state that disarms everything, and it has to: there is no key yet, so guarding
     * uninstall would be a trap with no sanctioned way out of it.
     */
    @Test fun `setup incomplete stands down both tiers`() {
        assertFalse(bounce(texts = turnOffDialog, setupIncomplete = true))
        assertFalse(bounce(pkg = installer, texts = uninstallDialog, setupIncomplete = true))
    }

    // ---- B-10: the wireless-debugging screen, which never names us ----

    private val wirelessDebugging = listOf<CharSequence?>(
        "Wireless debugging", "On", "Pair device with QR code", "Pair device with pairing code",
        "Device name", "IP address & Port",
    )
    private val developerOptions = listOf<CharSequence?>(
        "Developer options", "USB debugging", "Wireless debugging", "Stay awake", "Bug report shortcut",
    )

    /**
     * Pairing adb to the phone *from* the phone is what lets Shizuku hand adb-level power to an
     * ordinary app — no computer needed, and reusable forever once paired. Nothing on the screen
     * mentions App-Block, so the label match can never see it; this rule doesn't consult the label.
     */
    @Test fun `bounces the wireless debugging screen even though it never names the app`() {
        assertTrue(bounce(texts = wirelessDebugging))
        assertTrue(bounce(texts = listOf("Pair device with QR code", "Cancel")))
    }

    /**
     * And must not take Developer options with it. USB debugging lives there, and adb from the
     * computer is one of only two ways back in if the app ever needs repairing — walling it off would
     * be the overlay-permission trap again, one level up. The list carries a "Wireless debugging" row,
     * so "USB debugging" is what tells the two screens apart.
     */
    @Test fun `does not bounce the developer options list`() {
        assertFalse(bounce(texts = developerOptions))
    }

    @Test fun `the wireless debugging rule does not depend on the label being readable`() {
        assertTrue(SettingsWatch.shouldBounce("com.android.settings", wirelessDebugging, "", false))
    }

    @Test fun `the wireless debugging rule is settings-tier, so a window and repair mode stand it down`() {
        assertFalse(bounce(texts = wirelessDebugging, windowOpen = true))
        assertFalse(bounce(texts = wirelessDebugging, repairMode = true))
        assertFalse(bounce(texts = wirelessDebugging, setupIncomplete = true))
    }

    @Test fun `the installer tier ignores bypass markers`() {
        assertFalse(bounce(pkg = installer, texts = wirelessDebugging))
    }
}
