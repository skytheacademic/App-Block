package com.appblock.engine

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSummaryTest {

    private fun settings(
        weekday: Int = 30,
        weekend: Int = 30,
        ceiling: Int = 60,
        schedule: Schedule? = null,
    ) = TargetSettings(
        enabled = true,
        weekdayMinutes = weekday,
        weekendMinutes = weekend,
        exceptionMaxMinutes = ceiling,
        schedule = schedule,
    )

    private val allDays = DayOfWeek.entries.toSet()
    private val weekdays = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )
    private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    @Test
    fun `equal caps collapse to a single every-day line`() {
        val summary = TargetSummaries.of(settings(weekday = 30, weekend = 30))
        assertEquals(listOf(LimitLine(allDays, 30)), summary.limits)
    }

    @Test
    fun `differing caps split into weekday and weekend lines`() {
        val summary = TargetSummaries.of(settings(weekday = 15, weekend = 20))
        assertEquals(listOf(LimitLine(weekdays, 15), LimitLine(weekend, 20)), summary.limits)
    }

    @Test
    fun `no schedule means available any time on every day`() {
        val summary = TargetSummaries.of(settings())
        assertEquals(listOf(Availability.AnyTime(allDays)), summary.availability)
    }

    @Test
    fun `days a schedule does not cover are reported as blocked all day`() {
        // Weekdays 18:00-20:00, nothing on the weekend — the trap the card has to surface.
        val schedule = ScheduleEditorModel.toSchedule(listOf(WindowRule(weekdays, 18 * 60, 20 * 60)))
        val summary = TargetSummaries.of(settings(schedule = schedule))

        assertTrue(summary.availability.contains(Availability.Window(weekdays, 18 * 60, 20 * 60)))
        assertTrue(summary.availability.contains(Availability.BlockedAllDay(weekend)))
    }

    @Test
    fun `a schedule covering every day reports no blocked-all-day line`() {
        val schedule = ScheduleEditorModel.toSchedule(listOf(WindowRule(allDays, 9 * 60, 17 * 60)))
        val summary = TargetSummaries.of(settings(schedule = schedule))
        assertTrue(summary.availability.none { it is Availability.BlockedAllDay })
    }

    @Test
    fun `usage from an earlier logical day counts as zero`() {
        val today = LocalDate.of(2026, 7, 25)          // a Saturday
        val stale = BudgetUsage(secondsUsed = 900, dayKey = today.minusDays(1))
        val usage = TargetSummaries.todayUsage(settings(weekday = 30, weekend = 45), stale, today)
        assertEquals(0, usage.minutesUsed)
    }

    @Test
    fun `usage is measured against today's own cap`() {
        val saturday = LocalDate.of(2026, 7, 25)
        assertEquals(DayType.WEEKEND, DayBoundary.dayType(saturday))

        val usage = TargetSummaries.todayUsage(
            settings(weekday = 30, weekend = 45),
            BudgetUsage(secondsUsed = 600, dayKey = saturday),
            saturday,
        )
        assertEquals(10, usage.minutesUsed)
        assertEquals(45, usage.capMinutes)   // the weekend cap, not the weekday one
    }

    @Test
    fun `a zero cap reads as a full bar rather than dividing by zero`() {
        val monday = LocalDate.of(2026, 7, 27)
        val usage = TargetSummaries.todayUsage(settings(weekday = 0), null, monday)
        assertEquals(1f, usage.fraction, 0.001f)
    }

    @Test
    fun `day labels are unambiguous and always in week order`() {
        assertEquals("every day", DayLabels.of(allDays))
        assertEquals("M Tu W Th F", DayLabels.of(weekdays))
        // Set order must not leak into the label.
        assertEquals("Tu Th", DayLabels.of(setOf(DayOfWeek.THURSDAY, DayOfWeek.TUESDAY)))
    }
}
