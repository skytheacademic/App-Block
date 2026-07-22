package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The core of the commitment lock: loosening enforcement needs an unlock, tightening never does.
 * "Looser" = more access (higher cap / longer window / higher ceiling / target turned off).
 */
class DurableChangeGateTest {

    private fun settings(
        window: Int = 60,
        enabled: Boolean = true,
        wd: Int = 30,
        we: Int = 30,
        max: Int = 60,
    ) = DurableSettings(
        version = 1,
        targets = mapOf(Target.TIKTOK to TargetSettings(enabled, wd, we, max)),
        exceptionWindowMinutes = window,
    )

    @Test fun `raising a daily cap is loosening`() {
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(settings(wd = 30), settings(wd = 45)))
    }

    @Test fun `lowering a daily cap is tightening`() {
        assertEquals(ChangeDirection.TIGHTEN, DurableChangeGate.classify(settings(wd = 30), settings(wd = 15)))
    }

    @Test fun `identical settings are neutral`() {
        assertEquals(ChangeDirection.NEUTRAL, DurableChangeGate.classify(settings(), settings()))
    }

    @Test fun `turning a block off is loosening, turning it on is tightening`() {
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(settings(enabled = true), settings(enabled = false)))
        assertEquals(ChangeDirection.TIGHTEN, DurableChangeGate.classify(settings(enabled = false), settings(enabled = true)))
    }

    @Test fun `caps changing while a target stays off has no enforcement effect`() {
        assertEquals(
            ChangeDirection.NEUTRAL,
            DurableChangeGate.classify(settings(enabled = false, wd = 5), settings(enabled = false, wd = 500)),
        )
    }

    @Test fun `a longer exception window is loosening, shorter is tightening`() {
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(settings(window = 60), settings(window = 120)))
        assertEquals(ChangeDirection.TIGHTEN, DurableChangeGate.classify(settings(window = 60), settings(window = 30)))
    }

    @Test fun `raising the exception ceiling is loosening`() {
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(settings(max = 60), settings(max = 90)))
    }

    @Test fun `any loosening field makes the whole edit loosen`() {
        // Tightens weekday but loosens weekend — the loosening must win so it can't be smuggled through.
        val old = settings(wd = 30, we = 30)
        val new = settings(wd = 15, we = 45)
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(old, new))
    }

    @Test fun `loosening is blocked without an unlock and applied with one`() {
        val current = settings(wd = 30)
        val proposed = settings(wd = 45)

        val blocked = DurableChangeGate.applyChange(current, proposed, unlocked = false)
        assertTrue(blocked is ChangeResult.Blocked)
        assertEquals(ChangeDirection.LOOSEN, (blocked as ChangeResult.Blocked).direction)

        val applied = DurableChangeGate.applyChange(current, proposed, unlocked = true)
        assertTrue(applied is ChangeResult.Applied)
        assertEquals(proposed, (applied as ChangeResult.Applied).settings)
    }

    @Test fun `tightening is applied even while locked`() {
        val result = DurableChangeGate.applyChange(settings(wd = 30), settings(wd = 15), unlocked = false)
        assertTrue(result is ChangeResult.Applied)
    }
}
