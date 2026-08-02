package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-10. Shizuku pairs with the phone's own wireless debugging and then hands adb-level power to
 * ordinary apps — a bypass that needs no computer and, once set up, is silent and reusable forever.
 *
 * What these tests really pin is that it is blocked *outside* the editable config, because every
 * other way of expressing "always blocked" reintroduces the escape somewhere else.
 */
class AlwaysBlockedTest {

    private val shizuku = "moe.shizuku.privileged.api"

    @Test fun `the bypass tool resolves to a target`() {
        assertNotNull(AppTargets.targetFor(shizuku))
    }

    /**
     * And resolves with **no** active targets — i.e. without any entry in the user's settings. An
     * ordinary picker-added app only enforces because a rule exists for it; this one must not depend
     * on that, since a rule is exactly the thing a change window can switch off.
     */
    @Test fun `it resolves without any rule in the user's settings`() {
        assertNotNull(AppTargets.targetFor(shizuku, activeTargets = emptySet()))
        assertEquals(AppTargets.targetFor(shizuku), AppTargets.targetFor(shizuku, emptySet()))
    }

    @Test fun `it is enforced, and blocked outright rather than budgeted`() {
        val target = AppTargets.targetFor(shizuku)!!
        assertTrue(AppTargets.isEnforced(target))
        assertEquals(listOf(Rule(target, RuleMode.HardBlock)), AppTargets.alwaysBlockedRules)
    }

    /**
     * Not a built-in, and that is the point rather than an oversight. A built-in would mean a row in
     * the settings screen (switchable off through a 2-hour window) *and* a
     * [DurableSettings.RULES_VERSION] bump — which re-seeds, wiping every rule and every
     * picker-added app on the next launch.
     */
    @Test fun `it is not a built-in and never reaches the persisted settings`() {
        val target = AppTargets.targetFor(shizuku)!!
        assertFalse(target in Target.BUILT_INS)
        assertFalse(target in DurableSettings.from(DefaultRules.rules).targets.keys)
        assertFalse(target in DurableSettings.from(DefaultRules.fastRules).targets.keys)
    }

    /** It has no user package, so nothing treats it as a removable picker-added app. */
    @Test fun `it is not shaped like a user-added app`() {
        assertEquals(null, AppTargets.targetFor(shizuku)!!.userPackage)
    }

    /**
     * How the running app actually sees it: the persisted rules plus the injected ones, which is what
     * `ActiveRules.ruleSource` composes each pass.
     */
    @Test fun `the composed rule list blocks it alongside the user's own rules`() {
        val rules = DurableSettings.from(DefaultRules.rules).toRules() + AppTargets.alwaysBlockedRules
        val target = AppTargets.targetFor(shizuku)!!

        assertEquals(RuleMode.HardBlock, rules.first { it.target == target }.mode)
        // and the user's own rules survive the append
        assertTrue(rules.any { it.target == Target.TIKTOK })
    }

    /** A curated built-in must still win the lookup — the always-blocked map must not shadow one. */
    @Test fun `it does not shadow the curated packages`() {
        assertEquals(Target.TIKTOK, AppTargets.targetFor("com.zhiliaoapp.musically"))
        assertTrue(AppTargets.alwaysBlocked.keys.none { it in AppTargets.packages.keys })
    }
}
