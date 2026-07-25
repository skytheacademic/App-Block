package com.appblock.engine

/**
 * Maps a real Android package name to the [Target] it counts against.
 *
 * Whole-app targets (TikTok, X) map directly here. [Target.INSTAGRAM_REELS_EXPLORE] is deliberately
 * NOT in [packages]: Instagram's feed / DMs / stories are always free (CONSTRAINTS.md §1), so being in
 * the Instagram package tells us nothing on its own. It is instead enforced by *surface* detection —
 * the accessibility layer reads the on-screen resource-ids and asks [InstagramSurface.targetFor] (Phase
 * 2b, verified on-device 2026-07-22). So it counts as enforced ([surfaceEnforced]) even though no
 * package maps to it.
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
        // Instagram (com.instagram.android): NOT mapped — enforced by surface detection, see below.
    )

    /** Targets enforced by in-app surface detection rather than a whole-package match. */
    val surfaceEnforced: Set<Target> = setOf(Target.INSTAGRAM_REELS_EXPLORE)

    fun targetFor(packageName: String): Target? = packages[packageName]

    /**
     * Package → target, including apps the user added on-device (Batch 4).
     *
     * A user-added app is enforced purely by having an active rule: the rule list *is* the registry,
     * so there is no second list to keep in sync — and dropping that rule is exactly what
     * [DurableChangeGate] already classifies as a loosening, which is what makes removal gated.
     *
     * Built-ins win the lookup: a curated package (TikTok's two names, X's two) keeps its curated
     * target and its special handling rather than degrading to a plain whole-app block.
     */
    fun targetFor(packageName: String, activeTargets: Set<Target>): Target? =
        packages[packageName] ?: Target.forPackage(packageName).takeIf { it in activeTargets }

    /** True when [target] is actually being enforced today — package match, surface, or user-added. */
    fun isEnforced(target: Target): Boolean =
        packages.containsValue(target) || target in surfaceEnforced || target.userPackage != null
}
