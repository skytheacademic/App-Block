package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The window-scan → targets mapping ([AppTargets.foregroundTargets]).
 *
 * This exists because the mapping used to live inside `AppBlockerAccessibilityService`, which has no
 * tests and never will, and it was wrong there: a single `packageTarget` var took the *first* match in
 * the window loop and dropped every other budgeted app on screen. Split-screen and Samsung App Pairs
 * make that a two-tap bypass — park X under any other budgeted app and X stops being gated.
 *
 * So the question every test below is really asking: does the whole foreground reach the engine, and
 * does a single app still behave exactly as it did before?
 */
class ForegroundTargetsTest {

    private val tiktok = "com.zhiliaoapp.musically"
    private val tiktokTrill = "com.ss.android.ugc.trill"
    private val x = "com.twitter.android"
    private val instagram = InstagramSurface.PACKAGE
    private val launcher = "com.sec.android.app.launcher"
    private val reddit = "com.reddit.frontpage"

    private fun resolve(
        vararg packages: String,
        active: Set<Target> = emptySet(),
        surface: Target? = null,
    ) = AppTargets.foregroundTargets(packages.toList(), active, surface)

    // ---- the defect ----

    /**
     * 🔴 The bypass this was written for. Two budgeted apps on screen, and the engine used to hear
     * about one of them. `BudgetCoordinator.decideCurrent` has always looped every gate target and
     * blocked if any of them blocks; the loss was entirely in the adapter that fed it.
     */
    @Test fun `every budgeted app on screen is gated, not just the topmost`() {
        assertEquals(listOf(Target.TIKTOK, Target.X), resolve(tiktok, x))
    }

    /** The App Pair that needs no contrivance: Instagram is itself a package target since RULES_VERSION 5. */
    @Test fun `an instagram and tiktok pair is two package targets`() {
        assertEquals(listOf(Target.INSTAGRAM_APP, Target.TIKTOK), resolve(instagram, tiktok))
    }

    /**
     * Order is not cosmetic. `onForegroundTargets` gates every target in the list but accrues to the
     * first non-schedule-only one, so topmost-first is what decides whose budget pays for the minute.
     * Reversing the panes must reverse the list, or the app underneath starts paying for the app on top.
     */
    @Test fun `topmost stays first, because the first target is the one that accrues`() {
        assertEquals(listOf(Target.X, Target.TIKTOK), resolve(x, tiktok))
        assertEquals(listOf(Target.TIKTOK, Target.X), resolve(tiktok, x))
    }

    // ---- unchanged single-app behaviour (guards, not the fix) ----

    @Test fun `one budgeted app on screen resolves exactly as before`() {
        assertEquals(listOf(Target.TIKTOK), resolve(tiktok))
    }

    @Test fun `nothing budgeted on screen is no targets`() {
        assertEquals(emptyList<Target>(), resolve(launcher))
        assertEquals(emptyList<Target>(), resolve())
    }

    @Test fun `the surface target is appended after the packages`() {
        assertEquals(
            listOf(Target.INSTAGRAM_APP, Target.INSTAGRAM_REELS_EXPLORE),
            resolve(instagram, surface = Target.INSTAGRAM_REELS_EXPLORE),
        )
    }

    /**
     * The composition that made the surface target reachable in the first place: a package match must
     * not swallow it. Instagram always matches a package now (its closing hours), so if the package
     * won outright the reels budget would be dead code again.
     */
    @Test fun `a package match never swallows the surface target`() {
        val targets = resolve(instagram, tiktok, surface = Target.INSTAGRAM_REELS_EXPLORE)
        assertEquals(listOf(Target.INSTAGRAM_APP, Target.TIKTOK, Target.INSTAGRAM_REELS_EXPLORE), targets)
    }

    @Test fun `a surface target with no instagram package still stands alone`() {
        assertEquals(
            listOf(Target.INSTAGRAM_REELS_EXPLORE),
            resolve(surface = Target.INSTAGRAM_REELS_EXPLORE),
        )
    }

    // ---- duplicates ----

    /** Two windows of one app, and the two TikTok package names, are still one target. */
    @Test fun `the same target twice is one target`() {
        assertEquals(listOf(Target.TIKTOK), resolve(tiktok, tiktok))
        assertEquals(listOf(Target.TIKTOK), resolve(tiktok, tiktokTrill))
    }

    /** Instagram's two targets must survive de-duplication — they are different targets, one package. */
    @Test fun `deduplication does not collapse instagram's two targets`() {
        assertEquals(
            listOf(Target.INSTAGRAM_APP, Target.INSTAGRAM_REELS_EXPLORE),
            resolve(instagram, instagram, surface = Target.INSTAGRAM_REELS_EXPLORE),
        )
    }

    // ---- the active-rule gate, unchanged from targetFor ----

    @Test fun `a user-added package counts only while its rule is live`() {
        val redditTarget = Target.forPackage(reddit)
        assertEquals(listOf(redditTarget), resolve(reddit, active = setOf(redditTarget)))
        assertEquals(emptyList<Target>(), resolve(reddit))
    }

    /**
     * Built-ins resolve without consulting the active rules, which is deliberate ("built-ins win the
     * lookup") — and worth pinning, because it means a *seeded-off* TikTok still appears in this list.
     * That is harmless now: `decideCurrent` skips a target with no rule, and since the list is plural
     * an unruled app can no longer shadow a ruled one. Under the old single-slot code it could.
     */
    @Test fun `a built-in resolves even with no active rule, and no longer shadows anything`() {
        assertEquals(listOf(Target.TIKTOK, Target.X), resolve(tiktok, x, active = emptySet()))
    }

    @Test fun `an always-blocked package resolves with no rule at all`() {
        val shizuku = "moe.shizuku.privileged.api"
        assertEquals(AppTargets.alwaysBlockedTargets.toList(), resolve(shizuku))
    }
}
