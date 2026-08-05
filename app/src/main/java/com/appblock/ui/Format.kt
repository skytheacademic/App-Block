package com.appblock.ui

import com.appblock.engine.DayBoundary
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The number formats every screen shares. One copy, because the redesign puts the same duration in
 * several places at once — the same remaining time is the Today hero, a table cell and a block-screen
 * fact row — and two of them disagreeing by a rounding rule would read as a bug in the engine.
 *
 * Everything here is rendered with tabular figures at the call site (see `LampType`), so none of it
 * changes width as it ticks.
 */

/** `m:ss` under an hour, `h:mm:ss` over it. The table, the hero and every countdown. */
fun formatHms(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0L)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** The same, from milliseconds — what the unlock and exception countdowns carry. */
fun formatHmsFromMs(ms: Long): String = formatHms(ms / 1000)

/**
 * A window length: "30 min", "1 h", "1 h 30 min". This is the format the exception window
 * ([com.appblock.engine.DurableSettings.DEFAULT_EXCEPTION_WINDOW_MINUTES], 60) is stated in
 * everywhere, so "1 h" is never hard-coded — it falls out of the setting.
 */
fun formatWindow(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}

/** A minute-of-day as a 24-hour clock: `18:00`. */
fun formatHm(minuteOfDay: Int): String {
    val m = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
    return "%02d:%02d".format(m / 60, m % 60)
}

/** A wall time as a 24-hour clock. */
fun formatClock(time: LocalDateTime): String = "%02d:%02d".format(time.hour, time.minute)

/** The clock reading [ms] milliseconds after [now] — "starts after … 20:12". */
fun formatClockIn(now: LocalDateTime, ms: Long): String = formatClock(now.plusNanos(ms * 1_000_000L))

/** The logical day, as the header states it: `Wed 29 Jul`. */
fun formatLogicalDay(day: LocalDate): String = DAY_FORMAT.format(day)

/** When today's budgets come back: the 4am reset, as a clock reading. */
fun formatResetHour(): String = formatHm(DayBoundary.DEFAULT_RESET_HOUR * 60)

/**
 * A coarse "how long until" for the block screen: `8 h 48 m`, `48 m`.
 *
 * The seconds this renders come from the engine ([com.appblock.engine.DayBoundary.secondsUntilReset],
 * [com.appblock.engine.Schedule.nextOpening]) — this file used to carry its own copy of the reset
 * countdown, which is how a screen ends up counting down to a boundary the engine doesn't use.
 */
fun formatCoarse(totalSeconds: Long): String {
    val minutes = (totalSeconds.coerceAtLeast(0L) + 59) / 60
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "$h h $m m" else "$m m"
}

const val MINUTES_PER_DAY = 24 * 60

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
