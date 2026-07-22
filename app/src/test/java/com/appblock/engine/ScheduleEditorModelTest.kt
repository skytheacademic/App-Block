package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY

/** The authoring model ↔ engine schedule mapping, incl. the one-gesture overnight split. */
class ScheduleEditorModelTest {

    private val h = 60

    @Test fun `plain rule compiles to one window per selected day`() {
        val s = ScheduleEditorModel.toSchedule(listOf(WindowRule(setOf(MONDAY, WEDNESDAY), 18 * h, 20 * h)))
        assertEquals(listOf(TimeWindow(18 * h, 20 * h)), s.allowedByDay[MONDAY])
        assertEquals(listOf(TimeWindow(18 * h, 20 * h)), s.allowedByDay[WEDNESDAY])
        assertEquals(null, s.allowedByDay[TUESDAY])
    }

    @Test fun `overnight rule splits at midnight onto the next day`() {
        val s = ScheduleEditorModel.toSchedule(listOf(WindowRule(setOf(MONDAY), 22 * h, 2 * h)))
        assertEquals(listOf(TimeWindow(22 * h, 24 * h)), s.allowedByDay[MONDAY])
        assertEquals(listOf(TimeWindow(0, 2 * h)), s.allowedByDay[TUESDAY])
    }

    @Test fun `sunday overnight wraps into monday`() {
        val s = ScheduleEditorModel.toSchedule(listOf(WindowRule(setOf(SUNDAY), 23 * h, 1 * h)))
        assertEquals(listOf(TimeWindow(23 * h, 24 * h)), s.allowedByDay[SUNDAY])
        assertEquals(listOf(TimeWindow(0, 1 * h)), s.allowedByDay[MONDAY])
    }

    @Test fun `until-midnight rule spills nothing into the next day`() {
        val s = ScheduleEditorModel.toSchedule(listOf(WindowRule(setOf(MONDAY), 21 * h, 0)))
        assertEquals(listOf(TimeWindow(21 * h, 24 * h)), s.allowedByDay[MONDAY])
        assertEquals(null, s.allowedByDay[TUESDAY])
    }

    @Test fun `empty day-set contributes nothing`() {
        val s = ScheduleEditorModel.toSchedule(listOf(WindowRule(emptySet(), 18 * h, 20 * h)))
        assertTrue(s.allowedByDay.isEmpty())
    }

    @Test fun `multi-day overnight round-trips to a single rule`() {
        val rule = WindowRule(setOf(MONDAY, THURSDAY), 22 * h, 2 * h)
        assertEquals(listOf(rule), ScheduleEditorModel.decompose(ScheduleEditorModel.toSchedule(listOf(rule))))
    }

    @Test fun `identical plain ranges group back into one rule across days`() {
        val rule = WindowRule(setOf(MONDAY, TUESDAY, FRIDAY), 18 * h, 20 * h)
        assertEquals(listOf(rule), ScheduleEditorModel.decompose(ScheduleEditorModel.toSchedule(listOf(rule))))
    }

    @Test fun `distinct ranges stay separate rules`() {
        val weekday = WindowRule(setOf(MONDAY, TUESDAY), 18 * h, 20 * h)
        val weekend = WindowRule(setOf(SATURDAY, SUNDAY), 10 * h, 22 * h)
        val back = ScheduleEditorModel.decompose(ScheduleEditorModel.toSchedule(listOf(weekday, weekend)))
        assertEquals(setOf(weekday, weekend), back.toSet())
    }

    @Test fun `two windows in one day survive the round trip`() {
        val morning = WindowRule(setOf(MONDAY), 9 * h, 10 * h)
        val evening = WindowRule(setOf(MONDAY), 20 * h, 21 * h)
        val back = ScheduleEditorModel.decompose(ScheduleEditorModel.toSchedule(listOf(morning, evening)))
        assertEquals(setOf(morning, evening), back.toSet())
    }

    @Test fun `hand-built evening-plus-morning pair displays as one overnight rule`() {
        val s = Schedule(
            mapOf(
                MONDAY to listOf(TimeWindow(22 * h, 24 * h)),
                TUESDAY to listOf(TimeWindow(0, 2 * h)),
            ),
        )
        assertEquals(listOf(WindowRule(setOf(MONDAY), 22 * h, 2 * h)), ScheduleEditorModel.decompose(s))
    }

    @Test fun `hand-built until-midnight window normalizes to the wrapped zero`() {
        val s = Schedule(mapOf(FRIDAY to listOf(TimeWindow(20 * h, 24 * h))))
        val back = ScheduleEditorModel.decompose(s)
        assertEquals(listOf(WindowRule(setOf(FRIDAY), 20 * h, 0)), back)
        assertEquals(s, ScheduleEditorModel.toSchedule(back))
    }

    @Test fun `all-day rule round-trips`() {
        val rule = WindowRule(setOf(SUNDAY), 0, 0)
        val s = ScheduleEditorModel.toSchedule(listOf(rule))
        assertEquals(listOf(TimeWindow.ALL_DAY), s.allowedByDay[SUNDAY])
        assertEquals(listOf(rule), ScheduleEditorModel.decompose(s))
    }

    @Test fun `stepClock wraps at midnight in both directions`() {
        assertEquals(0, ScheduleEditorModel.stepClock(23 * h + 30, +30, skip = -1))
        assertEquals(23 * h + 30, ScheduleEditorModel.stepClock(0, -30, skip = -1))
    }

    @Test fun `stepClock skips the opposite bound so From and To never collide`() {
        assertEquals(10 * h + 30, ScheduleEditorModel.stepClock(9 * h + 30, +30, skip = 10 * h))
        assertEquals(9 * h, ScheduleEditorModel.stepClock(10 * h, -30, skip = 9 * h + 30))
    }
}
