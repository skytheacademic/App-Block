package com.appblock.engine

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The read-only "what are my limits right now" view of a [TargetSettings], for the settings card.
 *
 * Pure and JVM-tested, because the risky part isn't drawing it — it's that the two things a card
 * must state are keyed on *different partitions of the week*. Budget caps split weekday/weekend and
 * that split is fixed in the engine; schedule windows carry arbitrary day sets. A card that printed
 * one shared day header would silently misreport the moment those diverge, so every line here
 * carries its own day set and they are never merged.
 */

/** "This many minutes, on these days." */
data class LimitLine(val days: Set<DayOfWeek>, val minutes: Int)

/** When the app may be opened at all, on a given set of days. */
sealed interface Availability {
    val days: Set<DayOfWeek>

    /** No schedule in play — the budget is the only limit. */
    data class AnyTime(override val days: Set<DayOfWeek>) : Availability

    /** Openable only inside [startMin]..[endMin] (minutes of day; end ≤ start = overnight). */
    data class Window(override val days: Set<DayOfWeek>, val startMin: Int, val endMin: Int) : Availability

    /**
     * Blocked for the whole day. Reached when a schedule exists but covers none of these days — the
     * sharpest edge in the schedule model: set M–F hours, forget the weekend, and the weekend is
     * *fully* blocked rather than merely budget-limited.
     */
    data class BlockedAllDay(override val days: Set<DayOfWeek>) : Availability
}

data class TargetSummary(
    val limits: List<LimitLine>,
    val availability: List<Availability>,
    val exceptionCeilingMinutes: Int,
)

/** Today's progress against today's cap. [capMinutes] 0 means the app is blocked outright today. */
data class TodayUsage(val minutesUsed: Int, val capMinutes: Int) {
    /** 0f..1f for the progress bar; a zero cap reads as full, since nothing is available. */
    val fraction: Float
        get() = if (capMinutes <= 0) 1f else (minutesUsed.toFloat() / capMinutes).coerceIn(0f, 1f)
}

object TargetSummaries {

    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    private val ALL_DAYS = DayOfWeek.entries.toSet()
    private val WEEKDAYS = ALL_DAYS - WEEKEND

    fun of(settings: TargetSettings): TargetSummary = TargetSummary(
        limits = limitsOf(settings),
        availability = availabilityOf(settings.schedule),
        exceptionCeilingMinutes = settings.exceptionMaxMinutes,
    )

    /** One line when both caps agree, two when they don't — no point printing 30/30 twice. */
    private fun limitsOf(settings: TargetSettings): List<LimitLine> =
        if (settings.weekdayMinutes == settings.weekendMinutes) {
            listOf(LimitLine(ALL_DAYS, settings.weekdayMinutes))
        } else {
            listOf(
                LimitLine(WEEKDAYS, settings.weekdayMinutes),
                LimitLine(WEEKEND, settings.weekendMinutes),
            )
        }

    private fun availabilityOf(schedule: Schedule?): List<Availability> {
        if (schedule == null) return listOf(Availability.AnyTime(ALL_DAYS))

        val windows = ScheduleEditorModel.decompose(schedule)
            .filter { it.days.isNotEmpty() }
            .map { Availability.Window(it.days, it.startMin, it.endMin) }

        val covered = windows.flatMap { it.days }.toSet()
        val uncovered = ALL_DAYS - covered

        // A schedule that compiles to nothing at all blocks every day — say so rather than
        // rendering an empty section that reads as "no restriction".
        return if (uncovered.isEmpty()) windows else windows + Availability.BlockedAllDay(uncovered)
    }

    /**
     * Today's usage against today's cap. Stored usage from an earlier logical day counts as zero —
     * the engine resets at the 4am boundary, and a stale row must never read as time already spent.
     */
    fun todayUsage(settings: TargetSettings, usage: BudgetUsage?, today: LocalDate): TodayUsage {
        val cap = when (DayBoundary.dayType(today)) {
            DayType.WEEKDAY -> settings.weekdayMinutes
            DayType.WEEKEND -> settings.weekendMinutes
        }
        val used = if (usage != null && usage.dayKey == today) (usage.secondsUsed / 60).toInt() else 0
        return TodayUsage(minutesUsed = used, capMinutes = cap)
    }
}

/** Short day names. "Tu"/"Th" and "Sa"/"Su" because a lone "T" or "S" names two different days. */
object DayLabels {

    fun short(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "M"
        DayOfWeek.TUESDAY -> "Tu"
        DayOfWeek.WEDNESDAY -> "W"
        DayOfWeek.THURSDAY -> "Th"
        DayOfWeek.FRIDAY -> "F"
        DayOfWeek.SATURDAY -> "Sa"
        DayOfWeek.SUNDAY -> "Su"
    }

    /** Always in week order, whatever order the set iterates in. */
    fun of(days: Set<DayOfWeek>): String = when {
        days.isEmpty() -> "no days"
        days.size == DayOfWeek.entries.size -> "every day"
        else -> DayOfWeek.entries.filter { it in days }.joinToString(" ") { short(it) }
    }
}
