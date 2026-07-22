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
     * Packages whose screens can kill the blocker and therefore get scanned:
     * system Settings (toggle / App info / overlay / battery), Samsung's split-out accessibility
     * settings, and Device care (its sleeping-apps list can put the service to sleep).
     */
    val watchedPackages: Set<String> = setOf(
        "com.android.settings",
        "com.samsung.accessibility",
        "com.samsung.android.lool",
    )

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
        if (!isWatched(packageName)) return false
        if (selfLabel.isBlank()) return false
        return visibleTexts.any { it?.contains(selfLabel, ignoreCase = true) == true }
    }
}
