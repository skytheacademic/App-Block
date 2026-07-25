package com.appblock.engine

import java.time.DayOfWeek
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DurableChangeGate.classify] and [DurableChangeGate.looseningReasons] must agree: the save is
 * gated exactly when there is something to report. If they can disagree, the user is told their edit
 * loosens their limits and then shown an empty list of reasons — which is what happened on the phone
 * 2026-07-25, and is worse than the original opaque message.
 *
 * Fuzzed rather than enumerated because the two implementations are meant to be the same case
 * analysis written twice, and eyeballing them for equivalence is exactly what already failed.
 */
class GateAgreementTest {

    private val pool = listOf(
        Target.TIKTOK,
        Target.INSTAGRAM_REELS_EXPLORE,
        Target.X,
        Target.forPackage("com.reddit.frontpage"),
    )

    private fun randomSchedule(rnd: Random): Schedule? = when (rnd.nextInt(4)) {
        0 -> null
        else -> {
            val days = DayOfWeek.entries.filter { rnd.nextBoolean() }.toSet()
            val start = rnd.nextInt(48) * 30
            val end = rnd.nextInt(48) * 30
            ScheduleEditorModel.toSchedule(listOf(WindowRule(days, start, end)))
        }
    }

    private fun randomSettings(rnd: Random) = DurableSettings(
        version = 1,
        targets = pool.filter { rnd.nextBoolean() }.associateWith {
            TargetSettings(
                enabled = rnd.nextBoolean(),
                weekdayMinutes = rnd.nextInt(5) * 15,
                weekendMinutes = rnd.nextInt(5) * 15,
                exceptionMaxMinutes = rnd.nextInt(5) * 15,
                schedule = randomSchedule(rnd),
            )
        },
        exceptionWindowMinutes = 30 + rnd.nextInt(4) * 30,
    )

    @Test fun `a save is gated exactly when there is a reason to report`() {
        val rnd = Random(20260725L)
        repeat(20_000) {
            val old = randomSettings(rnd)
            val new = randomSettings(rnd)
            val gated = DurableChangeGate.classify(old, new) == ChangeDirection.LOOSEN
            val reasons = DurableChangeGate.looseningReasons(old, new)
            assertEquals(
                "disagreement:\n  old = $old\n  new = $new\n  reasons = $reasons",
                gated,
                reasons.isNotEmpty(),
            )
        }
    }
}
