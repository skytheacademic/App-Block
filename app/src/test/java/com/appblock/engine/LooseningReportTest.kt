package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why a save is blocked, field by field.
 *
 * Grew out of an on-device report (2026-07-25): a newly added app was tightened from 30 min to 0 and
 * the screen still said the edit loosened the limits. The classifier was right — [classify] collapses
 * the whole settings object, so an unrelated loosening elsewhere in the same unsaved draft gates
 * everything — but the message named nothing, leaving no way to find it.
 */
class LooseningReportTest {

    private val reddit = Target.forPackage("com.reddit.frontpage")
    private val newApp = TargetSettings(true, 30, 30, 60)

    private val saved = DurableSettings(
        version = 1,
        targets = linkedMapOf(
            Target.TIKTOK to TargetSettings(true, 30, 30, 60),
            Target.X to TargetSettings(true, 0, 20, 40),
        ),
        exceptionWindowMinutes = 60,
    )

    // ---- the reported flow really is a tightening ----

    @Test fun `adding an app then dropping its cap to zero is a tightening`() {
        val draft = saved.copy(targets = saved.targets + (reddit to newApp.copy(weekdayMinutes = 0)))
        assertEquals(ChangeDirection.TIGHTEN, DurableChangeGate.classify(saved, draft))
        assertTrue(DurableChangeGate.looseningReasons(saved, draft).isEmpty())
    }

    @Test fun `the same holds once the add has been saved`() {
        val afterAdd = saved.copy(targets = saved.targets + (reddit to newApp))
        val draft = afterAdd.copy(targets = afterAdd.targets + (reddit to newApp.copy(weekdayMinutes = 0)))
        assertEquals(ChangeDirection.TIGHTEN, DurableChangeGate.classify(afterAdd, draft))
    }

    // ---- so the report must have carried a second, unrelated change ----

    @Test fun `one unrelated loosening gates the whole edit, and is named`() {
        val draft = saved.copy(
            targets = saved.targets +
                (reddit to newApp.copy(weekdayMinutes = 0)) +      // tighter
                (Target.X to TargetSettings(true, 15, 20, 40)),    // X weekday 0 → 15: looser
        )
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(saved, draft))

        val reasons = DurableChangeGate.looseningReasons(saved, draft)
        assertEquals(1, reasons.size)
        assertEquals(Target.X, reasons[0].target)
        assertEquals("weekday cap 0 → 15 min", reasons[0].detail)
    }

    // ---- each loosening shape reports itself ----

    @Test fun `turning a target off reports as turned off`() {
        val draft = saved.copy(targets = saved.targets + (Target.TIKTOK to TargetSettings(false, 30, 30, 60)))
        assertEquals(listOf(Loosening(Target.TIKTOK, "turned off")), DurableChangeGate.looseningReasons(saved, draft))
    }

    @Test fun `dropping a target entirely reports as removed`() {
        val draft = saved.copy(targets = saved.targets - Target.TIKTOK)
        assertEquals(
            listOf(Loosening(Target.TIKTOK, "removed from the blocked list")),
            DurableChangeGate.looseningReasons(saved, draft),
        )
    }

    @Test fun `a raised exception ceiling reports itself`() {
        val draft = saved.copy(targets = saved.targets + (Target.TIKTOK to TargetSettings(true, 30, 30, 90)))
        assertEquals(
            listOf(Loosening(Target.TIKTOK, "exception ceiling 60 → 90 min")),
            DurableChangeGate.looseningReasons(saved, draft),
        )
    }

    @Test fun `a widened schedule reports itself`() {
        val narrow = ScheduleEditorModel.toSchedule(listOf(WindowRule(setOf(java.time.DayOfWeek.MONDAY), 600, 660)))
        val wide = ScheduleEditorModel.toSchedule(listOf(WindowRule(setOf(java.time.DayOfWeek.MONDAY), 600, 900)))
        val from = saved.copy(targets = saved.targets + (Target.TIKTOK to TargetSettings(true, 30, 30, 60, narrow)))
        val to = from.copy(targets = from.targets + (Target.TIKTOK to TargetSettings(true, 30, 30, 60, wide)))
        assertEquals(listOf(Loosening(Target.TIKTOK, "allowed hours widened")), DurableChangeGate.looseningReasons(from, to))
    }

    @Test fun `a lengthened exception window reports with no target`() {
        val draft = saved.copy(exceptionWindowMinutes = 90)
        assertEquals(listOf(Loosening(null, "exception window 60 → 90 min")), DurableChangeGate.looseningReasons(saved, draft))
    }

    @Test fun `several loosenings are all reported`() {
        val draft = saved.copy(
            exceptionWindowMinutes = 90,
            targets = saved.targets + (Target.TIKTOK to TargetSettings(true, 45, 30, 60)),
        )
        val reasons = DurableChangeGate.looseningReasons(saved, draft)
        assertEquals(2, reasons.size)
        assertTrue(reasons.any { it.target == null })
        assertTrue(reasons.any { it.target == Target.TIKTOK && it.detail.startsWith("weekday cap") })
    }

    @Test fun `reasons are empty whenever the gate would allow the save`() {
        val tighter = saved.copy(targets = saved.targets + (Target.TIKTOK to TargetSettings(true, 10, 10, 60)))
        assertEquals(ChangeDirection.TIGHTEN, DurableChangeGate.classify(saved, tighter))
        assertTrue(DurableChangeGate.looseningReasons(saved, tighter).isEmpty())
    }
}
