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
        // Instagram proper maps to the *schedule-only* whole-app target, and to nothing else. Being
        // in this package still says nothing about the budget — feed / DMs / stories remain free of
        // the reels cap per CONSTRAINTS §1 — but it does say everything about the closing hours,
        // which are app-wide by intent (user's call 2026-08-06: mornings are protected, DMs
        // included). The reels budget continues to arrive from surface detection, and both targets
        // apply at once; see foregroundTargets.
        "com.instagram.android" to Target.INSTAGRAM_APP,
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

    /**
     * Every target the **whole foreground** answers to, strictest-wins downstream (the engine blocks if
     * *any* of them does — `BudgetCoordinator.decideCurrent`).
     *
     * [packagesTopmostFirst] is every distinct package owning a visible window, in the order
     * `getWindows()` offered them, which is topmost first. [surfaceTarget] is the one target no package
     * can imply — Instagram's reel surface, contributed by [InstagramSurface] after reading the tree.
     *
     * ## Why a list, part one: one package, two limits
     *
     * `com.instagram.android` carries both an app-wide schedule ([Target.INSTAGRAM_APP]) and a
     * surface-scoped budget ([Target.INSTAGRAM_REELS_EXPLORE]). While the resolution returned a single
     * target the package match won unconditionally and **the surface target became dead code** — still
     * listed on the Apps tab, never firing. That turned adding an app, a *tightening* gesture, into a
     * net loosening of the reels cap.
     *
     * ## Why a list, part two: one screen, two apps (fixed 2026-08-30)
     *
     * 🔴 The service used to keep a single `packageTarget` var and fill it with the **first** match in
     * the window loop — `if (packageTarget == null) …`. So exactly one package target ever reached the
     * engine, however many budgeted apps were on screen, and the second was neither gated nor metered.
     * Split-screen and Samsung App Pairs make that trivially reachable: put X in one pane and any other
     * budgeted app in the other, tap the other pane to raise it, and X is free. Since
     * `RULES_VERSION 5` Instagram is *itself* a package target, so an Instagram + TikTok pair is two
     * package targets by construction rather than by contrivance.
     *
     * The engine was never the problem — `onForegroundTargets` and `decideCurrent` have been plural and
     * correct throughout, and the class KDoc on the service claimed this already worked ("a budgeted app
     * counts as foreground if it occupies ANY visible window"). The whole loss was in the adapter, which
     * is exactly why this now lives here, where it can be tested: the service has no tests.
     *
     * ## Order is load-bearing
     *
     * Topmost first, and the surface target last. `BudgetCoordinator.onForegroundTargets` gates every
     * target in this list but accrues to the **first** non-schedule-only one, so the order decides which
     * app's budget the minutes come out of when two are visible at once. Topmost is the app the user
     * raised, and metering both would charge one minute of wall-clock to two budgets.
     *
     * Built-ins still win over a picker-added whole-app target for the same package (a curated target
     * keeps its special handling rather than degrading), and a user-added package contributes at most
     * itself.
     */
    fun foregroundTargets(
        packagesTopmostFirst: List<String>,
        activeTargets: Set<Target>,
        surfaceTarget: Target? = null,
    ): List<Target> {
        val targets = LinkedHashSet<Target>(4)
        for (packageName in packagesTopmostFirst) {
            targetFor(packageName, activeTargets)?.let { targets.add(it) }
        }
        surfaceTarget?.let { targets.add(it) }
        return targets.toList()
    }

    /**
     * Packages the picker must never offer, because something already enforces them.
     *
     * Keyed on *being enforced*, which is the property that actually matters, rather than on
     * membership in [packages] — those two came apart for exactly one package and it was the
     * expensive one. Instagram is enforced by surface detection, so it was never in [packages], so
     * the "don't let the picker shadow a built-in" rule did not cover it, and adding Instagram from
     * the picker would have created a whole-app target that shadowed the reels budget. That was
     * patched by naming `InstagramSurface.PACKAGE` in the caller — correct, but a special case that
     * only holds while someone remembers it. Deriving the set here means the next surface-enforced
     * target is covered on the day it is written.
     *
     * User-added packages are not included: the caller adds those from the live draft, which this
     * object cannot see.
     */
    val unofferablePackages: Set<String> = packages.keys + alwaysBlocked.keys + InstagramSurface.PACKAGE

    /** True when [target] is actually being enforced today — package match, surface, or user-added. */
    fun isEnforced(target: Target): Boolean =
        packages.containsValue(target) ||
            target in surfaceEnforced ||
            target in alwaysBlockedTargets ||
            target.userPackage != null
}
