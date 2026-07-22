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

    @Test fun `toRules follows Target order regardless of map order`() {
        val settings = DurableSettings(
            version = 1,
            targets = linkedMapOf(
                Target.X to TargetSettings(true, 15, 20, 40),
                Target.TIKTOK to TargetSettings(true, 30, 30, 60),
            ),
            exceptionWindowMinutes = 60,
        )
        // Target.entries order is TIKTOK, INSTAGRAM_REELS_EXPLORE, X → TikTok comes first.
        assertEquals(listOf(Target.TIKTOK, Target.X), settings.toRules().map { it.target })
    }
}
