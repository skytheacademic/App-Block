package com.appblock.engine

/**
 * Self-defense (CONSTRAINTS.md lever A): decides when the accessibility service should bounce the
 * user to Home because a system Settings screen *about App-Block itself* is on screen — the
 * Accessibility toggle, the "Turn off?" dialog, the App info page (force-stop / uninstall), the
 * overlay-permission page, Device care's sleeping-apps list. Without this, switching the service
 * off in Settings is a zero-friction bypass.
 *
 * Matching is deliberately broad: any visible text in a watched settings package that mentions the
 * app's label. Both labels the OS shows ("App-Block" and "App-Block detection") contain the app
 * label, so one case-insensitive substring check covers the toggle list, the detail page, and the
 * confirmation dialog.
 *
 * The sanctioned way past it is the same gate as every other loosening (CONSTRAINTS.md §6): open the
 * durable-change window (stashed key → wait → 15-min window) and the watch stands down — turning the
 * service off becomes a gated loosening instead of a free escape. The caller passes that (plus
 * "setup not finished yet", so first-time permission granting isn't bounced) as [standDown].
 *
 * Pure Kotlin so the decision is JVM-testable; the service supplies the visible texts.
 */
object SettingsWatch {

    /**
     * Settings-family packages: system Settings (toggle / App info / overlay / battery), Samsung's
     * split-out accessibility settings, and Device care (its sleeping-apps list can put the service
     * to sleep). These never show the app's label except on a screen that is *about* the app, so the
     * bare label match is right here and is the version proven on hardware at Gate A.
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
     * the label before an installer screen bounces. Two reasons this tier can't use the bare label
     * match that the Settings tier uses:
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
     */
    val bypassExclusions: List<String> = listOf("usb debugging")

    val watchedPackages: Set<String> = settingsPackages + installerPackages

    fun isWatched(packageName: String?): Boolean = packageName in watchedPackages

    /** True when these texts are the wireless-debugging screen and not the Developer options list. */
    private fun isBypassScreen(texts: List<CharSequence>): Boolean =
        texts.any { text -> bypassMarkers.any { text.contains(it, ignoreCase = true) } } &&
            texts.none { text -> bypassExclusions.any { text.contains(it, ignoreCase = true) } }

    /**
     * True when the screen described by ([packageName], [visibleTexts]) is about App-Block and the
     * watch is armed — i.e. the service should bounce Home right now.
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
     * it cannot grant itself. Without it the self-defense guards the app against its own repair: the
     * "Appear on top" page names App-Block, so losing that permission means every attempt to restore
     * it — including the one MainActivity's own button opens — gets bounced, and the blocker is stuck
     * degraded until adb.
     *
     * The trade is real and deliberate: while either of those two stands, turning the service off in
     * Settings is unguarded. It's the right side to fail on — the app can still not be *removed*, the
     * watchdog is nagging throughout a repair, and a blocker you can't repair is one that eventually
     * gets deleted.
     */
    fun shouldBounce(
        packageName: String?,
        visibleTexts: Iterable<CharSequence?>,
        selfLabel: String,
        setupIncomplete: Boolean,
        windowOpen: Boolean = false,
        repairMode: Boolean = false,
    ): Boolean {
        if (setupIncomplete) return false
        val pkg = packageName ?: return false
        if (!isWatched(pkg)) return false

        val texts = visibleTexts.filterNotNull()
        // A blank label would make the substring test match every screen, so it disables the label
        // rule — but not the label-independent bypass rule, which never consults it.
        val namesUs = selfLabel.isNotBlank() &&
            texts.any { it.contains(selfLabel, ignoreCase = true) }

        if (pkg in settingsPackages) {
            if (windowOpen || repairMode) return false
            return namesUs || isBypassScreen(texts)
        }
        // Installer tier — armed through an open window, which is what makes uninstall cost more than
        // the shortest wait in the system.
        if (!namesUs) return false
        return texts.any { text -> killControls.any { text.contains(it, ignoreCase = true) } }
    }
}
