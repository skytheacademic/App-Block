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
