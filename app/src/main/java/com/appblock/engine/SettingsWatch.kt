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

    val watchedPackages: Set<String> = settingsPackages + installerPackages

    fun isWatched(packageName: String?): Boolean = packageName in watchedPackages

    /**
     * True when the screen described by ([packageName], [visibleTexts]) is about App-Block and the
     * watch is armed — i.e. the service should bounce Home right now.
     */
    fun shouldBounce(
        packageName: String?,
        visibleTexts: Iterable<CharSequence?>,
        selfLabel: String,
        standDown: Boolean,
    ): Boolean {
        if (standDown) return false
        if (selfLabel.isBlank()) return false
        val pkg = packageName ?: return false
        if (!isWatched(pkg)) return false

        val texts = visibleTexts.filterNotNull()
        val namesUs = texts.any { it.contains(selfLabel, ignoreCase = true) }
        if (!namesUs) return false
        if (pkg in settingsPackages) return true
        return texts.any { text -> killControls.any { text.contains(it, ignoreCase = true) } }
    }
}
