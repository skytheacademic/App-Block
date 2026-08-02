package com.appblock.engine

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigExportTest {

    private val weekdays = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    private fun entry(
        enabled: Boolean = true,
        weekday: Int = 30,
        weekend: Int = 30,
        ceiling: Int = 60,
        schedule: Schedule? = null,
    ) = TargetSettings(enabled, weekday, weekend, ceiling, schedule)

    private fun settings(vararg targets: Pair<Target, TargetSettings>) =
        DurableSettings(version = 2, targets = targets.toMap(), exceptionWindowMinutes = 60)

    private fun render(
        settings: DurableSettings,
        domains: List<String> = emptyList(),
        scopeNote: (Target) -> String? = { null },
        today: LocalDate? = null,
    ) = ConfigExport.render(
        settings = settings,
        blockedDomains = domains,
        label = { if (it == Target.TIKTOK) "TikTok" else it.key },
        scopeNote = scopeNote,
        today = today,
    )

    @Test fun `names the app and stamps the date when given one`() {
        val text = render(settings(Target.TIKTOK to entry()), today = LocalDate.of(2026, 7, 26))
        assertTrue(text.contains("App-Block"))
        assertTrue(text.contains("2026-07-26"))
    }

    @Test fun `omits the date rather than inventing one`() {
        val text = render(settings(Target.TIKTOK to entry()))
        assertFalse(text.contains("("))
    }

    @Test fun `equal caps read as one every-day line`() {
        val text = render(settings(Target.TIKTOK to entry(weekday = 30, weekend = 30)))
        assertTrue(text.contains("Limit: 30 min every day"))
    }

    @Test fun `split caps keep their own day sets`() {
        val text = render(settings(Target.TIKTOK to entry(weekday = 15, weekend = 45)))
        assertTrue(text.contains("15 min M Tu W Th F"))
        assertTrue(text.contains("45 min Sa Su"))
    }

    /** The overflow the UI already fixed once: a 24-hour cap must not print as "1440 min". */
    @Test fun `whole hours read as hours`() {
        val text = render(settings(Target.TIKTOK to entry(weekday = 24 * 60, weekend = 24 * 60, ceiling = 90)))
        assertTrue(text.contains("24 h every day"))
        assertTrue(text.contains("Exception ceiling: 1 h 30 min"))
        assertFalse(text.contains("1440"))
    }

    @Test fun `a schedule prints its window in 24-hour time with its days`() {
        val schedule = ScheduleEditorModel.toSchedule(listOf(WindowRule(weekdays, 18 * 60, 21 * 60)))
        val text = render(settings(Target.TIKTOK to entry(schedule = schedule)))
        assertTrue(text.contains("Available: 18:00–21:00 M Tu W Th F"))
    }

    /**
     * The sharpest edge in the schedule model, and the one most worth having in a written record:
     * set weekday hours, forget the weekend, and the weekend is *fully* blocked rather than merely
     * budget-limited. Rebuilding from a note that omitted this would silently loosen the weekend.
     */
    @Test fun `days no window covers are called out as blocked all day`() {
        val schedule = ScheduleEditorModel.toSchedule(listOf(WindowRule(weekdays, 18 * 60, 21 * 60)))
        val text = render(settings(Target.TIKTOK to entry(schedule = schedule)))
        assertTrue(text.contains("Blocked all day: Sa Su"))
    }

    /**
     * An overnight window is stored as two engine windows and comes back with its end at or before
     * its start. Printed bare, "22:00–02:00" reads as a four-hour gap someone might "correct" while
     * re-entering it; the marker is what stops the record being rebuilt wrong.
     */
    @Test fun `an overnight window says so`() {
        val schedule = ScheduleEditorModel.toSchedule(
            listOf(WindowRule(DayOfWeek.entries.toSet(), 22 * 60, 2 * 60)),
        )
        val text = render(settings(Target.TIKTOK to entry(schedule = schedule)))
        assertTrue(text.contains("22:00–02:00 (overnight)"))
    }

    @Test fun `no schedule reads as available any time`() {
        val text = render(settings(Target.TIKTOK to entry()))
        assertTrue(text.contains("Available: any time"))
    }

    /**
     * A disabled target stays listed. Dropping it would make the note read as "this app was never
     * blocked" when it actually means "deliberately switched off" — and the difference matters when
     * the note is the only thing being rebuilt from.
     */
    @Test fun `a disabled target is recorded as deliberately not blocked`() {
        val text = render(settings(Target.TIKTOK to entry(enabled = false)))
        assertTrue(text.contains("TikTok"))
        assertTrue(text.contains("Not blocked"))
        assertFalse(text.contains("Limit:"))
    }

    @Test fun `the scope note travels with the target`() {
        val text = render(
            settings(Target.INSTAGRAM_REELS_EXPLORE to entry()),
            scopeNote = { "Counts Reels and Explore only." },
        )
        assertTrue(text.contains("Counts Reels and Explore only."))
    }

    @Test fun `blocked domains are listed, and an empty list says so`() {
        val with = render(settings(Target.TIKTOK to entry()), domains = listOf("instagram.com", "reddit.com"))
        assertTrue(with.contains("instagram.com"))
        assertTrue(with.contains("reddit.com"))
        assertTrue(render(settings(Target.TIKTOK to entry())).contains("none"))
    }

    @Test fun `an empty config still renders something honest`() {
        val text = render(settings())
        assertTrue(text.contains("none configured"))
    }

    /**
     * The structural guarantee, not just a promise in the copy: the export must not be a format the
     * app could read back. If this ever decodes, someone has made import one line of code away —
     * which on a fresh install is free, because an absent target reads as fully open and importing
     * anything is pure tightening.
     */
    @Test fun `the export is not a decodable config blob`() {
        val text = render(
            settings(Target.TIKTOK to entry(), Target.X to entry()),
            domains = listOf("reddit.com"),
        )
        assertNull(EngineCodec.decodeDurable(text))
        assertTrue(text.contains("can't read this back in"))
    }
}
