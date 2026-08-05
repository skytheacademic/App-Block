package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableSettingsTest {

    @Test fun `seeding from DefaultRules round-trips back to the same rule list`() {
        val settings = DurableSettings.from(DefaultRules.rules)
        assertEquals(DefaultRules.rules, settings.toRules())
    }

    @Test fun `disabled targets are omitted from the enforced rules`() {
        val settings = DurableSettings(
            version = 1,
            targets = mapOf(
                Target.TIKTOK to TargetSettings(enabled = true, weekdayMinutes = 30, weekendMinutes = 30, exceptionMaxMinutes = 60),
                Target.X to TargetSettings(enabled = false, weekdayMinutes = 15, weekendMinutes = 20, exceptionMaxMinutes = 40),
            ),
            exceptionWindowMinutes = 60,
        )
        val targets = settings.toRules().map { it.target }
        assertTrue(Target.TIKTOK in targets)
        assertFalse(Target.X in targets)
    }

    // ---- seeded-off targets (2026-08-05: TikTok, the user's call) ----

    @Test fun `a seeded-off target is present in the settings but not enforced`() {
        val settings = DurableSettings.from(DefaultRules.rules, disabled = setOf(Target.TIKTOK))
        // Present — so the Apps tab still lists it and ConfigExport still records the decision.
        assertTrue(Target.TIKTOK in settings.targets.keys)
        // Unenforced — no rule reaches the coordinator, so no cap and no block.
        assertFalse(Target.TIKTOK in settings.toRules().map { it.target })
        // The others are untouched.
        assertTrue(Target.INSTAGRAM_REELS_EXPLORE in settings.toRules().map { it.target })
        assertTrue(Target.X in settings.toRules().map { it.target })
    }

    @Test fun `a seeded-off target keeps its caps, so switching it on restores the real numbers`() {
        val settings = DurableSettings.from(DefaultRules.rules, disabled = setOf(Target.TIKTOK))
        val tiktok = settings.targets.getValue(Target.TIKTOK)
        assertFalse(tiktok.enabled)
        assertEquals(30, tiktok.weekdayMinutes)
        assertEquals(30, tiktok.weekendMinutes)
        assertEquals(60, tiktok.exceptionMaxMinutes)
    }

    /**
     * The reason seeded-off beats deleting the rule outright. If this ever fails, re-enabling TikTok
     * has become a gated *loosening* — and on a phone with no key that means it cannot be re-enabled
     * at all, because a window can never be opened.
     */
    @Test fun `switching a seeded-off target back on is a tightening, so it saves with no window`() {
        val seeded = DurableSettings.from(DefaultRules.rules, disabled = setOf(Target.TIKTOK))
        val on = seeded.copy(
            targets = seeded.targets +
                (Target.TIKTOK to seeded.targets.getValue(Target.TIKTOK).copy(enabled = true)),
        )
        assertEquals(
            listOf(ChangeDirection.TIGHTEN),
            DurableChangeGate.changes(seeded, on).map { it.direction },
        )
        // unlocked = false: no key, no window — the state the phone is actually in.
        assertTrue(DurableChangeGate.applyChange(seeded, on, unlocked = false) is ChangeResult.Applied)
    }

    @Test fun `seededOff leaves the fast QA rules alone, since their job is proving a block fires`() {
        val fast = DurableSettings.from(DefaultRules.fastRules)
        assertTrue(Target.TIKTOK in fast.toRules().map { it.target })
    }

    @Test fun `disabled defaults to empty, so every other from() caller is unchanged`() {
        val settings = DurableSettings.from(DefaultRules.rules)
        assertTrue(settings.targets.values.all { it.enabled })
    }

    @Test fun `toRules follows the map's insertion order`() {
        val reddit = Target.forPackage("com.reddit.frontpage")
        val settings = DurableSettings(
            version = 1,
            targets = linkedMapOf(
                Target.X to TargetSettings(true, 15, 20, 40),
                Target.TIKTOK to TargetSettings(true, 30, 30, 60),
                reddit to TargetSettings(true, 20, 20, 40),
            ),
            exceptionWindowMinutes = 60,
        )
        // The target set is open since Batch 4, so there is no enum order left to normalise to —
        // insertion order is the stable order, and user-added apps land after the seeded built-ins.
        assertEquals(listOf(Target.X, Target.TIKTOK, reddit), settings.toRules().map { it.target })
    }
}
