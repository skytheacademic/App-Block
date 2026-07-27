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

    /**
     * package name → target. Multiple packages can map to one target (regional/renamed builds).
     *
     * The Lite/alternate clients are listed for a reason the audit made concrete: a blocked service
     * ships more than one app, and installing the other one was a ~40-second, permanent escape. They
     * cannot be covered from the phone either — the in-app picker only lists apps that are already
     * installed, so an app you have not installed yet can only be pre-blocked from here.
     */
    val packages: Map<String, Target> = mapOf(
        // TikTok — global, the older/regional "trill" package, and the Lite client.
        "com.zhiliaoapp.musically" to Target.TIKTOK,
        "com.ss.android.ugc.trill" to Target.TIKTOK,
        "com.zhiliaoapp.musically.go" to Target.TIKTOK,
        // X / Twitter — legacy package still ships on most devices; keep the new one and Lite too.
        "com.twitter.android" to Target.X,
        "com.x.android" to Target.X,
        "com.twitter.android.lite" to Target.X,
        // Instagram proper (com.instagram.android) is NOT mapped — it's enforced by surface
        // detection, because being in the package says nothing (feed / DMs / stories are free per
        // CONSTRAINTS §1).
        //
        // Instagram *Lite* is mapped, and deliberately as a whole-app target: InstagramSurface keys
        // on com.instagram.android and on resource ids read from that app's tree, so Lite gets no
        // surface detection at all and would otherwise be an unlimited free reel firehose. Being in
        // `packages` means the entire Lite app counts against the Reels/Explore budget — stricter
        // than Instagram proper, and an accepted asymmetry (user's call 2026-07-25): Lite is
        // essentially a feed/reels client, it isn't installed, and any cap on an app you don't use
        // is a tightening that costs nothing. If it ever becomes a real DM client, it needs its own
        // surface rule rather than this line.
        "com.instagram.lite" to Target.INSTAGRAM_REELS_EXPLORE,
    )

    /** Targets enforced by in-app surface detection rather than a whole-package match. */
    val surfaceEnforced: Set<Target> = setOf(Target.INSTAGRAM_REELS_EXPLORE)

    /**
     * Packages blocked outright, forever, with no rule the user can reach (B-10).
     *
     * Shizuku hands adb-level power to ordinary apps once it has been paired over the phone's own
     * wireless debugging — an escape that needs no computer and, once set up, is reusable silently
     * forever. It exists on this device for exactly one purpose.
     *
     * Deliberately **not** a [DurableSettings] entry, and that is the point rather than a shortcut:
     * a settings row could be switched off through a 2-hour window, while this can only be undone by
     * editing source and rebuilding. It also sidesteps a config migration — adding a built-in would
     * mean bumping [DurableSettings.RULES_VERSION], which re-seeds and would wipe every rule and
     * picker-added app on the next launch.
     */
    val alwaysBlocked: Map<String, Target> = mapOf(
        "moe.shizuku.privileged.api" to Target("shizuku"),
    )

    val alwaysBlockedTargets: Set<Target> = alwaysBlocked.values.toSet()

    /**
     * The hard-block rules injected alongside the user's own (see `ActiveRules.ruleSource`). Kept out
     * of the persisted settings so they can't be listed, edited, gated, or removed.
     */
    val alwaysBlockedRules: List<Rule> = alwaysBlockedTargets.map { Rule(it, RuleMode.HardBlock) }

    fun targetFor(packageName: String): Target? = packages[packageName] ?: alwaysBlocked[packageName]

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
        packages[packageName]
            ?: alwaysBlocked[packageName]
            ?: Target.forPackage(packageName).takeIf { it in activeTargets }

    /** True when [target] is actually being enforced today — package match, surface, or user-added. */
    fun isEnforced(target: Target): Boolean =
        packages.containsValue(target) ||
            target in surfaceEnforced ||
            target in alwaysBlockedTargets ||
            target.userPackage != null
}
