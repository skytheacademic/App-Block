package com.appblock.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screens below are the Gate F capture (One UI 8 / Android 16, SM-S731B, 2026-07-26), read out of
 * the accessibility tree rather than transcribed from screenshots. Keeping them verbatim is the point:
 * the guess this rule was originally written from — that the service is labelled "App-Block" — was
 * wrong on the single most important screen, where it is "App-Block detection".
 */
class SettingsWatchTest {

    private val label = "App-Block"

    private fun screen(vararg texts: CharSequence) = SettingsWatch.Screen(texts = texts.toList())

    private fun titled(title: CharSequence, vararg texts: CharSequence) =
        SettingsWatch.Screen(titles = listOf(title), texts = texts.toList())

    private fun bounce(
        pkg: String? = "com.android.settings",
        screen: SettingsWatch.Screen,
        setupIncomplete: Boolean = false,
        windowOpen: Boolean = false,
        repairMode: Boolean = false,
    ) = SettingsWatch.shouldBounce(pkg, screen, label, setupIncomplete, windowOpen, repairMode)

    // ---- screens that ARE about App-Block ----

    /** ① The Accessibility toggle page — the screen the whole lever exists for. */
    private val accessibilityDetail = titled(
        "App-Block detection",
        "App-Block detection", "App-Block detection", "On", "App-Block detection shortcut", "App info",
        "Detects which apps are on screen (including split-screen panes) so App-Block can show the " +
            "block screen when a budgeted app is visible.",
    )

    /** ④ App info: the page that can force-stop or uninstall. Its title is generic — the controls aren't. */
    private val appInfo = titled(
        "App info",
        "App info", "App-Block", "Installed", "Privacy", "Notifications", "Permissions", "Screen time",
        "Usage", "Mobile data", "Battery", "Storage", "Open", "Uninstall", "Force stop",
    )

    private val turnOffDialog = screen("Turn off App-Block detection?", "Cancel", "Turn off")

    @Test fun `bounces the accessibility toggle page on its title`() {
        assertTrue(bounce(screen = accessibilityDetail))
    }

    /**
     * And still bounces it with no title at all. Belt and braces on the one screen that matters most:
     * a dialog has no window title, a collapsed toolbar may expose no heading, and if the title rule
     * ever came back empty here the blocker would silently stop defending its own off switch. The
     * `App info` link on the page is the second route in.
     */
    @Test fun `bounces the accessibility toggle page even with no readable title`() {
        assertTrue(bounce(screen = SettingsWatch.Screen(texts = accessibilityDetail.texts)))
    }

    @Test fun `bounces app info on its controls, not its title`() {
        assertTrue(bounce(screen = appInfo))
        // The title is "App info" — generic. Uninstall / Force stop are what identify the page.
        assertFalse(appInfo.titles.any { it.contains(label, ignoreCase = true) })
    }

    @Test fun `bounces the turn-off confirmation dialog`() {
        assertTrue(bounce(screen = turnOffDialog))
    }

    @Test fun `matches case-insensitively`() {
        assertTrue(bounce(screen = screen("turn off APP-BLOCK detection?", "Cancel", "Turn off")))
    }

    // ---- C-4: screens that merely CONTAIN the label, because we sort third alphabetically ----

    /**
     * ② "Appear on top" — the screen that proves a word list cannot do this job. It contains the
     * string "App-Block" for no reason but the alphabet, and it is also the page that *restores* the
     * overlay permission, so bouncing it guarded the app out of its own repair (C-2) and cost an adb
     * session. Compare [appInfo], which contains the same string and is genuinely about us.
     */
    private val appearOnTopList = titled(
        "Appear on top",
        "Appear on top",
        "This permission allows an app to show things on top of other apps you're using.",
        "This may interfere with your use of other apps.",
        "AlwaysOnDisplay", "Android Auto", "App-Block", "Authentication Framework",
        "Autofill with Samsung Pass", "Bixby", "Bixby Vision", "Call", "Camera",
    )

    /** ①a Accessibility → Installed apps. A list of services; ours is simply the first row. */
    private val installedServices = titled(
        "Installed apps",
        "Installed apps", "App-Block detection", "On", "Link to Windows", "Off",
        "Live Transcribe", "Convert speech to text in real time",
        "Sound Notifications", "Get notified about important sounds",
        "Voice Access", "Control device with voice commands",
    )

    private val appsList = titled(
        "Apps",
        "Apps", "Search", "AlwaysOnDisplay", "Android Auto", "App-Block", "Calculator", "Calendar",
        "Camera", "Chrome",
    )

    private val permissionManagerCamera = titled(
        "Camera",
        "Camera", "Allowed", "Instagram", "Samsung Camera", "Not allowed", "App-Block", "Chrome",
    )

    private val appNotifications = titled(
        "App notifications",
        "App notifications", "Most recent", "AlwaysOnDisplay", "App-Block", "Chrome", "Messages",
    )

    @Test fun `a list of every app is not a screen about us`() {
        assertFalse(bounce(screen = appearOnTopList))
        assertFalse(bounce(screen = appsList))
        assertFalse(bounce(screen = permissionManagerCamera))
        assertFalse(bounce(screen = appNotifications))
    }

    /**
     * The route to the off switch is not the off switch. Not bouncing the list costs nothing: the
     * toggle page one tap further in is [accessibilityDetail], and that still bounces.
     */
    @Test fun `the installed-services list is not the toggle page`() {
        assertFalse(bounce(screen = installedServices))
        assertTrue(bounce(screen = accessibilityDetail))
    }

    /**
     * The two screens side by side, which is the whole finding: same string present, opposite verdicts.
     */
    @Test fun `containing the label is not the same as being about the app`() {
        assertTrue(appearOnTopList.texts.any { it.contains(label) })
        assertTrue(appInfo.texts.any { it.contains(label) })
        assertFalse(bounce(screen = appearOnTopList))
        assertTrue(bounce(screen = appInfo))
    }

    /**
     * ⚠️ **A deliberate gap, recorded so it is not mistaken for an oversight.** Device care's
     * sleeping-app lists are lists of every app, so they stop bouncing along with the rest — and
     * putting App-Block to sleep would stop the watchdog. The old guard here was worth less than it
     * looked (it fired only when our row happened to be scrolled into view), and bouncing these pages
     * also walls the user out of the "Never auto sleeping apps" hardening step, which is on the
     * setup checklist. Closing it properly needs a per-app screen captured from the phone.
     */
    @Test fun `the device care sleeping lists no longer bounce`() {
        val sleeping = titled(
            "Never auto sleeping apps",
            "Never auto sleeping apps", "Add apps", "App-Block", "Clock", "Wearable manager",
        )
        assertFalse(bounce(pkg = "com.samsung.android.lool", screen = sleeping))
    }

    @Test fun `ignores settings screens that are not about the app`() {
        assertFalse(bounce(screen = titled("Display", "Display", "Brightness", "Dark mode")))
    }

    // ---- rule 3: the accessibility-button picker (2026-08-29) ----

    /**
     * ✅ **Captured on the S25 2026-08-29**, and it is the screen that made rule 3 necessary: long-press
     * the floating accessibility pill → Edit → untick "App-Block detection", and
     * `enabled_accessibility_services` is empty. Four taps, no key, no computer, confirmed twice, and
     * nothing notices — the watchdog runs inside the service that has just been switched off.
     *
     * The rows are verbatim and in order. Every one is a `CheckBox` carrying its label as **both** text
     * and contentDescription, followed by a `TextView` repeating it; that duplication is what the
     * service's own walk produces, so it is reproduced here rather than tidied away.
     */
    private val shortcutPickerRows = listOf(
        "Extra dim", "Speak keyboard input aloud", "Hearing aid support", "Amplify ambient sound",
        "Mute all sounds", "Sound Notifications", "Live Transcribe", "Universal switch",
        "Assistant menu", "Dwell action", "Voice Access", "Link to Windows", "App-Block detection",
    )

    /** No titles: the dump caught it scrolled, with nothing in the tree naming the screen. */
    private val shortcutPicker = SettingsWatch.Screen(
        texts = shortcutPickerRows.flatMap { listOf(it, it, it) },
        checkables = shortcutPickerRows.flatMap { listOf(it, it) },
    )

    @Test fun `bounces the accessibility-button picker`() {
        assertTrue(bounce(screen = shortcutPicker))
    }

    /**
     * Why it needed a third rule at all: the picker walks straight through the other two. Its title is
     * the shortcut's and not ours (rule 1), and not one [SettingsWatch.settingsControls] word appears
     * anywhere on it (rule 2) — it is twenty-two feature names and nothing else. By vocabulary alone it
     * is indistinguishable from a list, which is exactly what C-4 says must not bounce.
     */
    @Test fun `the picker defeats the title and control rules`() {
        assertFalse(shortcutPicker.titles.any { it.contains(label, ignoreCase = true) })
        assertFalse(
            shortcutPicker.texts.any { text ->
                SettingsWatch.settingsControls.any { text.contains(it, ignoreCase = true) }
            },
        )
        assertTrue(shortcutPicker.texts.any { it.contains(label, ignoreCase = true) })
    }

    /**
     * The comparison that makes rule 3 a discriminator rather than another bare label match. The
     * device-admin list was captured minutes from the picker on the same phone: six admins, six
     * switches, and every one of those switches **bare** — `text=""`, `contentDescription=""` — with our
     * name on a separate, non-checkable `TextView` beside it. See [deviceAdminList] for the capture.
     *
     * So the two screens differ in structure exactly where they agree in vocabulary. A list associates
     * a control with a name by *position*, which a text walk cannot see and must not guess at; the
     * picker puts the name **on** the control. That, and not the words, is what rule 3 reads.
     */
    @Test fun `a bare switch beside our name is still a list`() {
        assertFalse(bounce(screen = deviceAdminList))
        assertTrue(deviceAdminList.texts.any { it.contains(label, ignoreCase = true) })
        assertTrue(deviceAdminList.checkables.isNotEmpty())
    }

    /**
     * The other list that names us next to a state, and the nearest miss of all: Accessibility →
     * Installed apps renders "App-Block detection" beside the *word* "On". Same string as the picker's
     * row, no checkbox — so it keeps not bouncing, and the toggle page one tap further in keeps
     * bouncing on its title.
     */
    @Test fun `the installed-services list has the same words and no checkbox`() {
        assertTrue(installedServices.checkables.isEmpty())
        assertFalse(bounce(screen = installedServices))
    }

    /**
     * Rule 3 lives inside the Settings tier, so it stands down with the rest of it. Switching the
     * service off through the picker is a loosening like any other: pay for a window and it is yours.
     */
    @Test fun `the picker stands down with the rest of the settings tier`() {
        assertFalse(bounce(screen = shortcutPicker, windowOpen = true))
        assertFalse(bounce(screen = shortcutPicker, repairMode = true))
        assertFalse(bounce(screen = shortcutPicker, setupIncomplete = true))
    }

    /**
     * And it reaches no further than the Settings tier. A checkable control naming us in the *installer*
     * is not an off switch — and treating it as one would be the update trap again.
     */
    @Test fun `a checkable label does not arm the installer tier`() {
        assertFalse(
            bounce(
                pkg = "com.google.android.packageinstaller",
                screen = SettingsWatch.Screen(
                    texts = listOf("Do you want to install an update to this app?", "App-Block"),
                    checkables = listOf("App-Block"),
                ),
            ),
        )
    }

    @Test fun `a blank label never matches a checkable control either`() {
        assertFalse(
            SettingsWatch.shouldBounce("com.android.settings", shortcutPicker, "", false),
        )
    }

    @Test fun `ignores other apps entirely, even ones showing the label`() {
        assertFalse(bounce(pkg = "com.zhiliaoapp.musically", screen = appInfo))
        assertFalse(bounce(pkg = null, screen = appInfo))
    }

    @Test fun `handles empty and blank text`() {
        assertFalse(bounce(screen = screen("", "  ")))
        assertFalse(bounce(screen = SettingsWatch.Screen()))
    }

    @Test fun `a blank label never matches`() {
        assertFalse(
            SettingsWatch.shouldBounce("com.android.settings", accessibilityDetail, "", false),
        )
    }

    @Test fun `watches settings, samsung accessibility and device care`() {
        assertTrue(SettingsWatch.isWatched("com.android.settings"))
        assertTrue(SettingsWatch.isWatched("com.samsung.accessibility"))
        assertTrue(SettingsWatch.isWatched("com.samsung.android.lool"))
        assertFalse(SettingsWatch.isWatched(null))
    }

    // ---- the installer tier: uninstall was 3 taps and completely unguarded (audit finding B-2) ----

    private val installer = "com.google.android.packageinstaller"
    private val uninstallDialog = screen("Do you want to uninstall this app?", "App-Block", "Cancel", "OK")
    private val installDialog =
        screen("Do you want to install an update to this app?", "App-Block", "Cancel", "Install")

    @Test fun `bounces the uninstall confirmation dialog`() {
        assertTrue(bounce(pkg = installer, screen = uninstallDialog))
        assertTrue(bounce(pkg = "com.android.packageinstaller", screen = uninstallDialog))
        assertTrue(bounce(pkg = "com.samsung.android.packageinstaller", screen = uninstallDialog))
    }

    /**
     * The trap this tier exists to avoid. The installer shows the label while *installing* too, and
     * bouncing that would make it impossible to sideload a newer App-Block from the phone — the
     * self-defense would block the app's own updates, exactly like the overlay-permission page.
     */
    @Test fun `lets the installer update the app`() {
        assertFalse(bounce(pkg = installer, screen = installDialog))
    }

    /**
     * The installer tier keeps the body match on purpose. The uninstall and update dialogs are the
     * same screen with one word changed, so a title tells them apart no better than nothing would —
     * and the label there is a bare row either way.
     */
    @Test fun `the installer tier does not care about titles`() {
        val titledUninstall = SettingsWatch.Screen(listOf("App-Block"), uninstallDialog.texts)
        val titledInstall = SettingsWatch.Screen(listOf("App-Block"), installDialog.texts)
        assertTrue(bounce(pkg = installer, screen = titledUninstall))
        assertFalse(bounce(pkg = installer, screen = titledInstall))
    }

    @Test fun `ignores an uninstall dialog for some other app`() {
        assertFalse(
            bounce(pkg = installer, screen = screen("Do you want to uninstall this app?", "Reddit", "OK")),
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
        assertFalse(bounce(pkg = launcher, screen = screen("App-Block", "Chrome", "Phone")))
        assertFalse(bounce(pkg = launcher, screen = screen("App-Block", "Chrome", "Uninstall", "App info")))
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
                screen = screen("App-Block is not protecting you", "The Accessibility service is switched off"),
            ),
        )
    }

    // ---- repair mode: don't guard the app out of its own repair (audit finding C-2) ----

    /**
     * The per-app permission page, one tap in from [appearOnTopList]. ✅ **Captured on the S25
     * 2026-08-06** (`am start -a android.settings.MANAGE_APP_OVERLAY_PERMISSION -d package:com.appblock`,
     * then a dump to read it) — these four strings are verbatim, including the title.
     *
     * ⚠️ **The capture disproved the assumption C-4 left this page resting on.** C-4 expected the app
     * header to read as a *title* candidate, so that rule 1 would catch it. It does not: the title is
     * `Appear on top`, exactly the list's, and "App-Block" is a body row on both. The page went
     * unguarded on hardware for a day — verified by walking it: no bounce, with a live
     * `Allow permission` toggle. Rule 2 catches it now.
     */
    private val overlayPerAppPage =
        titled("Appear on top", "Appear on top", "App-Block", "Version 0.1.0", "Allow permission")

    @Test fun `bounces the per-app permission page whose identity is us`() {
        assertTrue(bounce(screen = SettingsWatch.Screen(listOf("App-Block"), overlayPerAppPage.texts)))
    }

    /**
     * The real page, with the real title — the case that was silently open. It must bounce on the
     * control alone, with no title naming us anywhere.
     */
    @Test fun `bounces the per-app permission page on its control when no title names us`() {
        assertTrue(bounce(screen = overlayPerAppPage))
        assertFalse(overlayPerAppPage.titles.any { it.contains(label, ignoreCase = true) })
    }

    /**
     * And the discriminator has to keep working in the direction C-4 bought: the list shares that
     * title and also names us, so the *only* thing separating them is the control. `Allow permission`
     * is absent from all 21 strings the list actually renders.
     */
    @Test fun `the list one tap out still does not bounce`() {
        assertFalse(bounce(screen = appearOnTopList))
        assertFalse(appearOnTopList.texts.any { it.contains("Allow permission", ignoreCase = true) })
    }

    /**
     * The neighbouring per-app page that must NOT be swept in: Permission manager → Camera → App-Block
     * reads `Allowed` / `Not allowed`, not `Allow permission`. Guarding it would wall off an ordinary
     * permission screen for no gain.
     */
    @Test fun `does not fire on allowed-style permission wording`() {
        assertFalse(bounce(screen = permissionManagerCamera))
    }

    /**
     * The trap. Losing "Appear on top" leaves the block screen undrawable, and the page that restores
     * it names App-Block — so the self-defense bounced every attempt to fix it, including the button
     * inside App-Block that opens exactly that page. Narrowing the match takes most of the weight off
     * repair mode (the list is no longer a screen about us at all), but repair mode still has to stand
     * the per-app page down.
     */
    @Test fun `lets the user reach the page that restores the overlay permission`() {
        assertFalse(bounce(screen = appearOnTopList))
        val perApp = SettingsWatch.Screen(listOf("App-Block"), overlayPerAppPage.texts)
        assertTrue(bounce(screen = perApp))
        assertFalse(bounce(screen = perApp, repairMode = true))
    }

    @Test fun `repair mode stands the whole settings tier down`() {
        assertFalse(bounce(screen = accessibilityDetail, repairMode = true))
        assertFalse(bounce(screen = turnOffDialog, repairMode = true))
        assertFalse(bounce(pkg = "com.samsung.accessibility", screen = turnOffDialog, repairMode = true))
    }

    /**
     * ...but not the installer tier. Repair mode is entered whenever a permission is missing, which is
     * a state the user can reach by accident; it must not turn into a free uninstall.
     */
    @Test fun `repair mode still guards uninstall`() {
        assertTrue(bounce(pkg = installer, screen = uninstallDialog, repairMode = true))
    }

    @Test fun `repair mode does not resurrect the screens that were never watched`() {
        assertFalse(bounce(screen = appsList, repairMode = true))
        assertFalse(bounce(pkg = installer, screen = installDialog, repairMode = true))
    }

    // ---- B-8: an open window buys Settings, not removal ----

    /**
     * Turning the service off stays a *gated* loosening (CONSTRAINTS §6) rather than an impossibility,
     * so the Settings tier still stands down. Any category does: a websites window sat through the
     * longer 72-hour wait, so reaching Settings with one is never a shortcut past the 2-hour apps gate.
     */
    @Test fun `an open window stands the settings tier down`() {
        assertTrue(bounce(screen = turnOffDialog))
        assertFalse(bounce(screen = turnOffDialog, windowOpen = true))
        assertFalse(bounce(screen = accessibilityDetail, windowOpen = true))
    }

    /**
     * The finding itself. An open window used to stand down *both* tiers, so a 2-hour apps window
     * bought a full uninstall — every rule, the blocklist and the stashed key gone for the shortest
     * wait in the system, which inverts the rule the whole device runs on. Removal now costs the
     * computer or safe mode, neither of which is an impulse.
     */
    @Test fun `an open window does not buy an uninstall`() {
        assertTrue(bounce(pkg = installer, screen = uninstallDialog, windowOpen = true))
        assertTrue(bounce(pkg = installer, screen = uninstallDialog, windowOpen = true, repairMode = true))
    }

    /**
     * Setup is the one state that disarms everything, and it has to: there is no key yet, so guarding
     * uninstall would be a trap with no sanctioned way out of it.
     */
    @Test fun `setup incomplete stands down both tiers`() {
        assertFalse(bounce(screen = turnOffDialog, setupIncomplete = true))
        assertFalse(bounce(pkg = installer, screen = uninstallDialog, setupIncomplete = true))
    }

    // ---- N-2 (audit 2026-08-21): the device-admin page, the cheapest full bypass on the phone ----

    /**
     * ⚠️ Written from AOSP's `DeviceAdminAdd` strings, not a capture. **The capture arrived
     * 2026-08-29 and is `s25DeviceAdminDialog` below** — the wording guess was close enough that
     * "Deactivate" and the label both landed, but the *activity* was not AOSP's at all, and that is
     * what N-2 actually tripped on. Kept as-is: AOSP wording is still what a non-Samsung build would
     * show, and both must bounce. Security and privacy → Other security settings → Device admin apps → App-Block
     * protection. The title is the framework's, not ours, so rule 1 never saw it; "Deactivate" is
     * the only control on it, and it was not a control word. Ten taps, no key, and One UI Modes
     * could suspend the blocker again.
     */
    private val deviceAdminDetail = titled(
        "Device admin app",
        "Device admin app", "App-Block protection",
        "Requests no powers over your phone. Being listed here is what stops Android from " +
            "suspending App-Block, which is how Modes and other apps can otherwise switch the blocking off.",
        "This admin app is active and allows the app App-Block to perform the following operations:",
        "Deactivate",
    )

    /**
     * The list one tap out: one row per admin, each with a switch. Nothing on it deactivates.
     *
     * ✅ **Captured on the S25 2026-08-29** (replacing a guess that gave each row an "On"/"Off" state
     * word, which One UI does not render). Two things the real tree says that the guess did not:
     *
     *  - the six `Switch` nodes are **bare** — `text=""`, `contentDescription=""` — which is what makes
     *    rule 3 safe here and is asserted by `a bare switch beside our name is still a list`;
     *  - the blurb contains "disable", live proof of why that word is in [SettingsWatch.killControls]
     *    and deliberately not in [SettingsWatch.settingsControls].
     */
    private val deviceAdminList = SettingsWatch.Screen(
        titles = listOf("Device admin apps"),
        texts = listOf(
            "Device admin apps",
            "Device admin apps can enforce password requirements and other security policies on your " +
                "phone. They can disable the camera and other features, and they can lock or reset " +
                "your phone remotely.",
            "App-Block protection", "Find Hub", "Find Hub", "Link to Windows",
            "Outlook Device Policy", "Secure Folder",
        ),
        checkables = List(6) { "" },
    )

    /** Our own `onDisableRequested` text, shown by the framework before it deactivates. */
    private val deactivateDialog = screen(
        "Deactivate device admin app?",
        "Turning this off lets Modes and other apps suspend App-Block, which switches blocking off completely.",
        "Cancel", "Deactivate",
    )

    @Test fun `bounces the device-admin page on its control, not its title`() {
        assertTrue(bounce(screen = deviceAdminDetail))
        assertFalse(deviceAdminDetail.titles.any { it.contains(label, ignoreCase = true) })
    }

    @Test fun `bounces the deactivation confirmation`() {
        assertTrue(bounce(screen = deactivateDialog))
    }

    /** A list of every admin is a list, and lists are what rule 2 exists to leave alone. */
    @Test fun `the device-admin list one tap out does not bounce`() {
        assertFalse(bounce(screen = deviceAdminList))
        assertFalse(deviceAdminList.texts.any { it.contains("deactivate", ignoreCase = true) })
    }

    /**
     * Deactivating the admin is a loosening like turning the service off, and it takes the same
     * sanctioned route: the Settings tier stands down for an open window. The watchdog now reports
     * the admin as inactive and the Lock tab can re-activate it, so a window spent this way is at
     * least visible and reversible from the phone.
     */
    @Test fun `an open window stands the device-admin guard down like the rest of the settings tier`() {
        assertFalse(bounce(screen = deviceAdminDetail, windowOpen = true))
        assertFalse(bounce(screen = deviceAdminDetail, repairMode = true))
    }

    /** The word needs our label beside it — "deactivate" on a page about something else is nothing. */
    @Test fun `deactivate alone, without the label, is not a screen about us`() {
        assertFalse(bounce(screen = titled("SIM manager", "SIM manager", "Deactivate eSIM", "Add eSIM")))
    }

    // ---- B-10: the wireless-debugging screen, which never names us ----

    private val wirelessDebugging = titled(
        "Wireless debugging",
        "Wireless debugging", "Wireless debugging", "Off",
        "To see and use available devices, turn on wireless debugging",
    )

    /** Note the non-breaking hyphen in "Wi‑Fi" — verbatim, and a reason not to key on that row. */
    private val developerOptions = titled(
        "Developer options",
        "Developer options", "Debugging",
        "USB debugging", "Debug mode when USB is connected",
        "Revoke USB debugging authorizations",
        "Wireless debugging", "Debug mode when Wi‑Fi is connected",
    )

    /**
     * Pairing adb to the phone *from* the phone is what lets Shizuku hand adb-level power to an
     * ordinary app — no computer needed, and reusable forever once paired. Nothing on the screen
     * mentions App-Block, so the label match can never see it; this rule doesn't consult the label.
     */
    @Test fun `bounces the wireless debugging screen even though it never names the app`() {
        assertTrue(bounce(screen = wirelessDebugging))
        assertTrue(bounce(screen = screen("Pair device with QR code", "Cancel")))
    }

    /**
     * And must not take Developer options with it. USB debugging lives there, and adb from the
     * computer is one of only two ways back in if the app ever needs repairing — walling it off would
     * be the overlay-permission trap again, one level up.
     */
    @Test fun `does not bounce the developer options list`() {
        assertFalse(bounce(screen = developerOptions))
    }

    /**
     * 🐛 **The B-10 defect, and the regression test for it.** The exclusion used to be the USB-debugging
     * *row*, which made the guard a co-visibility bet: scroll until `Wireless debugging` is on screen
     * and every row containing "usb debugging" has left the top, and Developer options bounced (2/2 on
     * hardware). The screen's own name can't scroll away, so it anchors the exclusion instead.
     */
    @Test fun `developer options survives being scrolled past every usb-debugging row`() {
        val scrolled = titled(
            "Developer options",
            "Developer options",
            "Wireless debugging", "Debug mode when Wi‑Fi is connected",
            "Stay awake", "Bug report shortcut", "Verify apps over USB",
        )
        assertFalse(bounce(screen = scrolled))
        // The old exclusion is gone from this screen entirely — that is what used to fire the bug.
        assertFalse(scrolled.texts.any { it.contains("usb debugging", ignoreCase = true) })
    }

    @Test fun `the wireless debugging rule does not depend on the label being readable`() {
        assertTrue(SettingsWatch.shouldBounce("com.android.settings", wirelessDebugging, "", false))
    }

    @Test fun `the wireless debugging rule is settings-tier, so a window and repair mode stand it down`() {
        assertFalse(bounce(screen = wirelessDebugging, windowOpen = true))
        assertFalse(bounce(screen = wirelessDebugging, repairMode = true))
        assertFalse(bounce(screen = wirelessDebugging, setupIncomplete = true))
    }

    @Test fun `the installer tier ignores bypass markers`() {
        assertFalse(bounce(pkg = installer, screen = wirelessDebugging))
    }

    // ---- N-2 on hardware (cable session 2026-08-29) ----

    /**
     * Both screens verbatim off the S25 (One UI 8, `uiautomator dump`, 2026-08-29). Samsung's page is
     * `SecDeviceAdminAdd`, a **floating transparent** activity stacked in the Settings task — not
     * AOSP's full-screen `DeviceAdminAdd` these strings were originally guessed from.
     *
     * The guess was right. `Deactivate` is the button, and `App-Block protection` carries the label.
     * What the tests below pin down is that **the rules were never the problem**: the pair bounces the
     * moment it is handed over, and the list alone — which is all the service actually collected on
     * hardware, because it only ever read `getWindows()` — correctly does not.
     */
    private val s25DeviceAdminDialog = screen(
        "Device admin app",
        "App-Block protection",
        "This admin app is active and allows App-Block to perform the following actions:",
        "Cancel",
        "Deactivate",
    )

    /** One tap out. Rows with switches, no per-app control — a list, and it must stay free. */
    private val s25DeviceAdminList = screen(
        "Device admin apps",
        "App-Block protection", "Find Hub", "Link to Windows", "Outlook Device Policy", "Secure Folder",
    )

    /** Title-less on purpose: rule 1 must not be what saves this: the dialog has no window title. */
    @Test fun `the S25 device-admin dialog bounces on label plus deactivate`() {
        assertTrue(bounce(screen = s25DeviceAdminDialog))
        assertFalse(s25DeviceAdminDialog.titles.any { it.contains(label, ignoreCase = true) })
    }

    /**
     * 🐛 **N-2's hardware failure, as a test.** The dialog is transparent, so the list stayed visible
     * behind it — and the list is what the watch judged, because the dialog's window never made it
     * through `getWindows()`. The list names us (the row is literally "App-Block protection") but
     * offers no per-app control, so rule 2 correctly declined. Correct verdict, wrong screen.
     */
    @Test fun `the S25 device-admin list alone does not bounce, which is why N-2 failed open`() {
        assertFalse(bounce(screen = s25DeviceAdminList))
        // It is not that the label was missing — that is the part that makes this a collection bug.
        assertTrue(s25DeviceAdminList.texts.any { it.contains(label, ignoreCase = true) })
        assertFalse(s25DeviceAdminList.texts.any { it.contains("deactivate", ignoreCase = true) })
    }

    /**
     * What the second channel buys. The dialog is transparent, so on hardware both windows really are
     * on screen at once — this is the screen the watch should have been judging all along.
     */
    @Test fun `the list plus the rescued dialog bounces`() {
        val both = SettingsWatch.Screen(texts = s25DeviceAdminList.texts + s25DeviceAdminDialog.texts)
        assertTrue(bounce(screen = both))
    }

    @Test fun `the S25 device-admin dialog is settings-tier, so the stand-downs reach it`() {
        assertFalse(bounce(screen = s25DeviceAdminDialog, windowOpen = true))
        assertFalse(bounce(screen = s25DeviceAdminDialog, repairMode = true))
        assertFalse(bounce(screen = s25DeviceAdminDialog, setupIncomplete = true))
    }

    // ---- the active-window second channel ----

    @Test fun `an active watched window that getWindows missed is walked`() {
        assertTrue(SettingsWatch.shouldWalkActiveWindow(emptySet(), 42, "com.android.settings"))
    }

    @Test fun `a window already walked is not walked twice`() {
        assertFalse(SettingsWatch.shouldWalkActiveWindow(setOf(42), 42, "com.android.settings"))
    }

    /** Widens *how* the watch sees a screen, never *which* screens it may judge. */
    @Test fun `an unwatched active window is never walked`() {
        assertFalse(SettingsWatch.shouldWalkActiveWindow(emptySet(), 42, "com.instagram.android"))
        assertFalse(SettingsWatch.shouldWalkActiveWindow(emptySet(), 42, null))
    }

    /** The installer tier is watched too, and an install dialog is exactly the floating-window shape. */
    @Test fun `the installer package also qualifies for the second channel`() {
        assertTrue(SettingsWatch.shouldWalkActiveWindow(emptySet(), 7, installer))
    }
}
