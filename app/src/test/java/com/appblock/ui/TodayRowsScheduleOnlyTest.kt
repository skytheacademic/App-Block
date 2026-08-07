package com.appblock.ui

import com.appblock.engine.Access
import com.appblock.engine.DayUsage
import com.appblock.engine.DurableSettings
import com.appblock.engine.ExceptionState
import com.appblock.engine.Target
import com.appblock.engine.TargetSettings
import com.appblock.engine.TargetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * How the Today tab must treat a target that has closing hours and no budget.
 *
 * Both cases here are ways the *same* mistake shows up: a schedule-only target stores 0 in every cap
 * and usage field, and code that reads those zeros as a budget draws confident nonsense. One of them
 * shipped to hardware on 2026-08-06 and was caught by looking at the screen, which is the reason
 * this file exists — `todayRows`/`weekRows` had no unit coverage at all.
 */
class TodayRowsScheduleOnlyTest {

    private val hours = Target.INSTAGRAM_APP
    private val reels = Target.INSTAGRAM_REELS_EXPLORE
    private val today = LocalDate.of(2026, 8, 6)

    private val settings = DurableSettings(
        version = 5,
        targets = linkedMapOf(
            hours to TargetSettings(
                enabled = true,
                weekdayMinutes = 0,
                weekendMinutes = 0,
                exceptionMaxMinutes = 0,
                schedule = null,
                scheduleOnly = true,
            ),
            reels to TargetSettings(
                enabled = true,
                weekdayMinutes = 10,
                weekendMinutes = 10,
                exceptionMaxMinutes = 30,
            ),
        ),
        exceptionWindowMinutes = 60,
    )

    private fun status(target: Target, scheduleOnly: Boolean, blockedBySchedule: Boolean = false) =
        TargetStatus(
            target = target,
            normalCapMinutes = if (scheduleOnly) 0 else 10,
            effectiveCapMinutes = if (scheduleOnly) 0 else 10,
            exceptionMaxMinutes = if (scheduleOnly) 0 else 30,
            usedSeconds = 0L,
            remainingSeconds = if (scheduleOnly) 0L else 600L,
            access = if (blockedBySchedule) Access.BLOCK else Access.ALLOW,
            exception = ExceptionState.None,
            exceptionActivatesInMs = null,
            exceptionEndsInMs = null,
            blockedBySchedule = blockedBySchedule,
            scheduleOnly = scheduleOnly,
        )

    /**
     * The zero cap satisfies `remainingSeconds <= 0` at every moment of every day, so the naive read
     * labels a wide-open app "spent" — the exact opposite of true, and on the one screen the user
     * checks to see what they have left.
     */
    @Test fun `a schedule-only target inside its hours is OPEN, never SPENT`() {
        val rows = todayRows(
            settings = settings,
            statuses = listOf(status(hours, scheduleOnly = true), status(reels, scheduleOnly = false)),
            usageSecondsFor = { 0L },
            today = today,
            todayDayOfWeek = today.dayOfWeek,
        )
        val row = rows.first { it.target == hours }
        assertEquals(TargetState.OPEN, row.state)
        assertEquals(0L, row.capSeconds)
    }

    /** Outside its hours it is CLOSED — the schedule still has to win over the "no budget" reading. */
    @Test fun `a schedule-only target outside its hours is CLOSED`() {
        val rows = todayRows(
            settings = settings,
            statuses = listOf(
                status(hours, scheduleOnly = true, blockedBySchedule = true),
                status(reels, scheduleOnly = false),
            ),
            usageSecondsFor = { 0L },
            today = today,
            todayDayOfWeek = today.dayOfWeek,
        )
        assertEquals(TargetState.CLOSED, rows.first { it.target == hours }.state)
    }

    /**
     * The defect actually seen on the phone: the week strip listed the hours row, whose bars are
     * empty for ever because it never accrues. Its 74 dp label column clips at one line with no
     * ellipsis, so "Instagram hours" arrived reading "Instagram" — visually a duplicate of the reels
     * row directly below it, above a week of nothing.
     */
    @Test fun `the week strip omits schedule-only targets entirely`() {
        val rows = weekRows(
            settings = settings,
            today = today,
            historyFor = { target ->
                // Only the budgeted target has history; without any, weekRows returns empty by design.
                if (target == reels) listOf(DayUsage(today.minusDays(1), 300L)) else emptyList()
            },
            todaySecondsFor = { 0L },
        )
        assertTrue(rows.isNotEmpty())
        assertFalse(rows.any { it.target == hours })
        assertTrue(rows.any { it.target == reels })
    }
}
