package com.appblock.engine

/**
 * Self-defense (CONSTRAINTS.md lever A): decides when the accessibility service should bounce the
 * user to Home because a system Settings screen *about App-Block itself* is on screen — the
 * Accessibility toggle, the "Turn off?" dialog, the App info page (force-stop / uninstall). Without
 * this, switching the service off in Settings is a zero-friction bypass.
 *
 * **The discriminator is what the screen *is*, not what text happens to be on it (audit finding
 * C-4).** This used to be one case-insensitive substring test over every visible string in a watched
 * package, on the theory that Settings only ever shows the app's label on a screen about the app.
 * The Gate F string capture killed that theory: "Appear on top" is *a list of every app on the
 * phone*, and App-Block appears in it for no reason but sorting third alphabetically. So are the
 * Apps list, App notifications, Permission manager → Camera, and Accessibility → Installed apps.
 * The bare match bounced the user off all of them, and — because the overlay-permission page is one
 * of those lists — guarded the app out of its own repair (C-2, which cost one adb session).
 *
 * Two screens from that capture prove no word list can separate them, because both contain the
 * string "App-Block" and only one is about App-Block:
 *
 * ```
 * Appear on top          |  App info
 * This permission …      |  App-Block · Installed
 * AlwaysOnDisplay        |  Privacy · Notifications · Permissions
 * Android Auto           |  …
 * App-Block         <--- |  Open · Uninstall · Force stop
 * Authentication Fram…   |
 * ```
 *
 * What separates them is **position**: on the left the label is one row in the body, on the right
 * the page is *about* one app and carries controls that only exist on such a page. Hence a [Screen]
 * rather than a bag of strings, and hence two ways in:
 *
 *  1. the label is in the screen's **title** — the page's identity is us; or
 *  2. the label is anywhere on the screen **and** a [settingsControls] word is present — the page
 *     offers a control that can only mean one app.
 *
 * A list of every app satisfies neither: its title is a category ("Apps", "Appear on top"), and it
 * offers no per-app control. The Accessibility toggle page satisfies both. The per-app permission
 * page satisfies **only rule 2** — measured on hardware 2026-08-06, it shares the list's title and
 * carries "Allow permission", which is the whole reason rule 2 exists.
 *
 * The sanctioned way past it is the same gate as every other loosening (CONSTRAINTS.md §6): open the
 * durable-change window (stashed key → wait → 15-min window) and the watch stands down — turning the
 * service off becomes a gated loosening instead of a free escape. The caller passes that (plus
 * "setup not finished yet", so first-time permission granting isn't bounced) as the stand-down flags
 * on [shouldBounce].
 *
 * Pure Kotlin so the decision is JVM-testable; the service supplies the screen.
 */
object SettingsWatch {

    /**
     * One rendering of a watched Settings screen: everything visible on it ([texts]), with the part
     * that says what the screen *is* pulled out separately ([titles]).
     *
     * [titles] is a small set of *candidates*, not one authoritative string, because no single source
     * is reliable on One UI: the framework's window title is often just the activity label
     * ("Settings"), a collapsing toolbar's heading disappears from the expanded position when you
     * scroll, and a dialog has neither. The service offers what it can find — window title, first
     * heading node, and (only when both are missing) the first text in reading order — and any one
     * of them naming the app is enough. Over-supplying costs a false bounce on a screen whose first
     * row happens to be us; under-supplying costs nothing, because rule 2 still stands.
     *
     * [texts] is the whole screen including the title, so a body match never has to reason about
     * which strings were promoted.
     */
    data class Screen(
        val titles: List<CharSequence> = emptyList(),
        val texts: List<CharSequence> = emptyList(),
    )

    /**
     * Settings-family packages: system Settings (toggle / App info / overlay / battery), Samsung's
     * split-out accessibility settings, and Device care (its sleeping-apps list can put the service
     * to sleep).
     */
    val settingsPackages: Set<String> = setOf(
        "com.android.settings",
        "com.samsung.accessibility",
        "com.samsung.android.lool",
    )

    /**
     * The package installer — the confirmation dialog every uninstall route funnels through, whether
     * it started from the launcher's long-press menu, Settings → Apps, or the Play Store. Watching
     * the one dialog at the end covers all of them; watching the launcher instead would not, and
     * would misfire badly (see [killControls]).
     */
    val installerPackages: Set<String> = setOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
    )

    /**
     * Words that only appear on a screen that can actually *remove* the blocker, required alongside
     * the label before an installer screen bounces. Two reasons this tier can't use a bare label
     * match:
     *
     *  - **The installer also shows the label when it is installing.** Bouncing that would make
     *    sideloading a newer App-Block impossible from the phone — the self-defense would guard the
     *    app against its own updates, the same trap the overlay-permission page already fell into.
     *  - It is why the launcher stays unwatched. The home screen renders "App-Block" under the icon
     *    permanently, so a bare label match there bounces you off your own launcher forever; and a
     *    label-plus-control match still misfires, because long-pressing *any* icon shows "Uninstall"
     *    while App-Block's label sits in the same window from the icon behind it.
     *
     * "install" and "update" are deliberately absent. Matching is case-insensitive substring, so
     * "uninstall" also catches "Uninstall app" / "Do you want to uninstall this app?".
     */
    val killControls: List<String> = listOf(
        "uninstall",
        "force stop",
        "clear data",
        "clear storage",
        "disable",
    )

    /**
     * The Settings tier's equivalent: text that can only be on a page about **one** app, so finding
     * the label beside it means the page is about *this* app. Every word here was read off the S25 at
     * Gate F — `Uninstall` and `Force stop` from App info, `App info` from the Accessibility toggle
     * page (which links to it), `Turn off` from the confirmation dialog.
     *
     * `allow permission` was added 2026-08-06 after the per-app permission page was finally captured
     * on hardware and **the title theory it was resting on turned out to be false** — see the
     * [shouldBounce] repair-mode note. The two captures, verbatim:
     *
     * ```
     * per-app: "Appear on top" | "App-Block" | "Version 0.1.0" | "Allow permission" | "This permission…"
     * list:    "Appear on top" | "This permission…" | "AlwaysOnDisplay" | … | "App-Block" | "13.48 MB" | …
     * ```
     *
     * Same title on both, so rule 1 sees neither. `Allow permission` is on the per-app page and
     * **absent from all 21 strings of the list**, which is what makes it a discriminator rather than
     * another word that fires on a list of every app. It generalises the right way, too: every Samsung
     * per-app special-access page (`Install unknown apps`, `Modify system settings`, …) carries it, and
     * each of those is a page about one app. `Permission manager → Camera → App-Block` does not — its
     * controls read `Allow only while using the app` / `Don't allow`, which this needle does not match.
     *
     * Deliberately a different list from [killControls], which guards a different question. The
     * installer tier asks "is this removal?", so it must exclude installs and stays armed through an
     * open window. This tier asks "is this page about us?", so it includes navigation like `App info`
     * that removes nothing — and it can afford to, because the whole tier stands down for a window or
     * a repair.
     *
     * `disable` is *not* here even though it is in [killControls]: One UI's Apps list has a
     * "Disabled apps" filter, and a list of every app is precisely what this rule exists to ignore.
     *
     * `deactivate` was added 2026-08-21 (audit N-2) for the **device-admin page** — Security and
     * privacy → Other security settings → Device admin apps → App-Block protection — whose one button
     * deactivates the admin that keeps App-Block un-suspendable by One UI Modes. Its title is the
     * framework's ("Device admin app"), not ours, so rule 1 never saw it, and none of the words above
     * are on it; it was ten unguarded taps to the cheapest full bypass on the phone. The list one tap
     * out ("Device admin apps") is rows with switches and carries no such word, so it stays free, as
     * a list should. ⚠️ Both pages are written from AOSP's `DeviceAdminAdd` / `DeviceAdminSettings`
     * strings, not a capture — the S25 wording is on the phone checklist. The word is safe even if
     * Samsung phrases the button differently: rule 2 still needs our label beside it, and nothing
     * else in Settings says "deactivate" next to "App-Block".
     */
    val settingsControls: List<String> = listOf(
        "uninstall",
        "force stop",
        "clear data",
        "clear storage",
        "app info",
        "turn off",
        "allow permission",
        "deactivate",
    )

    /**
     * Text identifying Android's **wireless debugging** screen, which is watched with no reference to
     * the app's label at all (B-10). Pairing adb to the phone *from* the phone is what lets Shizuku
     * hand adb-level power to an ordinary app — an escape that needs no computer and, once paired, is
     * reusable forever. Nothing on that screen names App-Block, so the label match can never see it.
     *
     * This is friction on the *setup* path, not a lock: the toggle itself sits on the Developer options
     * list and can be flipped without opening this screen. Pairing cannot, and pairing is the step that
     * has to happen at least once.
     */
    val bypassMarkers: List<String> = listOf("wireless debugging", "pair device with")

    /**
     * Text that proves the screen is the Developer options **list** rather than the wireless-debugging
     * screen itself — the list carries a "Wireless debugging" row and would otherwise match.
     *
     * Developer options must stay reachable: USB debugging lives there, and adb from the computer is
     * one of only two ways back in if this app ever needs repairing (the other is safe mode). Walling
     * that off would be the overlay-permission trap again, one level up.
     *
     * **`developer options` is the load-bearing one; `usb debugging` is the one that failed.** The
     * exclusion was originally the USB-debugging *row*, which made the guard a co-visibility bet: at
     * Gate F, scrolling until `Wireless debugging` was visible and every row containing "usb
     * debugging" had left the top bounced Developer options, 2/2 (the only thing holding it open one
     * scroll earlier was the unrelated `Revoke USB debugging authorizations`). The screen's own name
     * cannot scroll away — a collapsing toolbar shrinks its title into the app bar rather than
     * dropping it — so it anchors the exclusion at every scroll position. The row is kept beside it
     * because this is the one guard where a false *negative* is cheap (one bypass screen is friction
     * on a setup step) and a false positive is expensive (it walls off an escape valve).
     */
    val bypassExclusions: List<String> = listOf("developer options", "usb debugging")

    val watchedPackages: Set<String> = settingsPackages + installerPackages

    fun isWatched(packageName: String?): Boolean = packageName in watchedPackages

    /**
     * Whether the *active* window's root is worth walking as a second channel, after the service has
     * already walked everything `getWindows()` offered.
     *
     * Added 2026-08-29 after N-2 failed on hardware. The watch had exactly one channel to the screen —
     * `getWindows()`, each entry rooted with `window.root` — and that channel **fails silently**: a
     * window that is missing from the list, or whose `root` comes back null, is skipped by a
     * `?: return@runCatching` and the screen is judged on whatever else happened to be visible. There
     * is no signal that anything was missed.
     *
     * What that cost, measured on the S25 (One UI 8): Samsung's device-admin page is
     * `SecDeviceAdminAdd`, a **floating, transparent** activity stacked inside the Settings task
     * (`floating=true`, `isTopActivityTransparent=true` in the WindowManager log) rather than AOSP's
     * full-screen `DeviceAdminAdd`. `uiautomator` read its five strings without trouble — including
     * both halves rule 2 needs, `"App-Block protection"` and `"Deactivate"`, in 25 nodes against a
     * budget of 800 — yet the page sat unbounced for 8+ s across four visits by the real route, while
     * App-Block's own App info page bounced in under 1.5 s the same minute. The words were never the
     * problem; the screen never reached [shouldBounce].
     *
     * ⚠️ **Which of the two silent failures actually happened is still unproven** — absent from
     * `getWindows()`, or present with a null root. It needs the phone and the debug build's
     * `AppBlockWatch` line to separate them. This helper does not care: the active window is by
     * definition the one the user is looking at, and reading it through a second framework path
     * (`getRootInActiveWindow()`) covers both.
     *
     * Deduplicated by window id so the ordinary case — the active window was already in
     * `getWindows()`, which is almost always true — costs one integer lookup and walks nothing twice.
     * The package test is the same one every other window gets: this widens *how* the watch sees a
     * screen, never *which* screens it may judge.
     */
    fun shouldWalkActiveWindow(
        walkedWindowIds: Set<Int>,
        activeWindowId: Int,
        activePackageName: String?,
    ): Boolean = activeWindowId !in walkedWindowIds && isWatched(activePackageName)

    private fun Iterable<CharSequence>.anyContains(needles: List<String>): Boolean =
        any { text -> needles.any { text.contains(it, ignoreCase = true) } }

    /** True when these texts are the wireless-debugging screen and not the Developer options list. */
    private fun isBypassScreen(texts: List<CharSequence>): Boolean =
        texts.anyContains(bypassMarkers) && !texts.anyContains(bypassExclusions)

    /**
     * True when the screen described by ([packageName], [screen]) is about App-Block and the watch is
     * armed — i.e. the service should bounce Home right now.
     *
     * Three stand-downs, and which tiers each one reaches is the whole design:
     *
     * [setupIncomplete] stands down **both** tiers. There is no key yet at that point, so guarding
     * uninstall would be a trap with no sanctioned way out — and first-time permission granting must
     * not be bounced either.
     *
     * [windowOpen] — an open durable-change window — stands down the **Settings tier only** (B-8).
     * It used to stand down everything, which meant the 2-hour apps window bought a full uninstall:
     * every rule, the blocklist and the stashed key gone, for the *shortest* wait in the system. That
     * inverted the rule the whole device runs on, that friction scales with how much access is handed
     * back. Turning the service off in Settings stays available as a gated loosening (CONSTRAINTS §6);
     * removing the app now needs the computer or safe mode.
     *
     * Any category of window still stands the Settings tier down, deliberately: a websites window sat
     * through the *longer* 72-hour wait, so letting it reach Settings is never a shortcut past the
     * 2-hour apps gate.
     *
     * [repairMode] also stands the Settings tier down only, while the blocker is missing a permission
     * it cannot grant itself. Narrowing the match (C-4) took most of the weight off this: the
     * "Appear on top" *list* is no longer a screen about us, so the route back is open on its own
     * merits. It stays because the per-app permission page one tap further in is still guarded, and
     * being bounced off *that* is the same lockout one step later.
     *
     * ⚠️ **Corrected 2026-08-06, on hardware.** C-4 left that page guarded on an assumption — that its
     * app header would read as a *title* on One UI 8. It does not. The page was captured over adb and
     * its title is `Appear on top`, the same as the list's; "App-Block" is a body row on both. So for
     * one day the page was **not guarded at all** and revoking the overlay permission was free from the
     * special-access route. It is now caught by rule 2 instead, on the `Allow permission` control (see
     * [settingsControls]) — which is a stronger anchor than the title would have been, because it is a
     * control that cannot appear on a list.
     *
     * The trade is real and deliberate: while either of those two stands, turning the service off in
     * Settings is unguarded. It's the right side to fail on — the app can still not be *removed*, the
     * watchdog is nagging throughout a repair, and a blocker you can't repair is one that eventually
     * gets deleted.
     */
    fun shouldBounce(
        packageName: String?,
        screen: Screen,
        selfLabel: String,
        setupIncomplete: Boolean,
        windowOpen: Boolean = false,
        repairMode: Boolean = false,
    ): Boolean {
        if (setupIncomplete) return false
        val pkg = packageName ?: return false
        if (!isWatched(pkg)) return false

        // A blank label would make the substring test match every screen, so it disables the label
        // rules — but not the label-independent bypass rule, which never consults it.
        val readable = selfLabel.isNotBlank()
        val titleNamesUs = readable && screen.titles.any { it.contains(selfLabel, ignoreCase = true) }
        val namesUs = readable && screen.texts.any { it.contains(selfLabel, ignoreCase = true) }

        if (pkg in settingsPackages) {
            if (windowOpen || repairMode) return false
            if (titleNamesUs) return true
            if (namesUs && screen.texts.anyContains(settingsControls)) return true
            return isBypassScreen(screen.texts)
        }
        // Installer tier — armed through an open window, which is what makes uninstall cost more than
        // the shortest wait in the system. Title position is no help here: the uninstall and update
        // dialogs are the same screen with one word changed, so the control words are the whole rule.
        if (!namesUs) return false
        return screen.texts.anyContains(killControls)
    }
}
