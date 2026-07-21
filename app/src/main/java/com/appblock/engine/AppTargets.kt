package com.appblock.engine

/**
 * Maps a real Android package name to the [Target] it counts against.
 *
 * Whole-app targets (TikTok, X) map directly here. [Target.INSTAGRAM_REELS_EXPLORE] is deliberately
 * NOT mapped: Instagram's feed / DMs / stories are always free (CONSTRAINTS.md §1), so only the
 * Reels + Explore *surfaces* should count — and telling those apart from the rest of Instagram needs
 * in-app screen detection (Phase 2b, requires the phone). Until then Instagram is left unenforced
 * rather than blocking the whole app, which would be wrong.
 */
object AppTargets {

    /** package name → target. Multiple packages can map to one target (regional/renamed builds). */
    val packages: Map<String, Target> = mapOf(
        // TikTok — global + the older/regional "trill" package.
        "com.zhiliaoapp.musically" to Target.TIKTOK,
        "com.ss.android.ugc.trill" to Target.TIKTOK,
        // X / Twitter — legacy package still ships on most devices; keep the new one too.
        "com.twitter.android" to Target.X,
        "com.x.android" to Target.X,
        // Instagram (com.instagram.android): NOT mapped — see the class doc (Phase 2b).
    )

    fun targetFor(packageName: String): Target? = packages[packageName]

    /** True once at least one package maps to [target] — i.e. it's actually being enforced today. */
    fun isEnforced(target: Target): Boolean = packages.containsValue(target)
}
