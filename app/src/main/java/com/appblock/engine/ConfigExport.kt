package com.appblock.engine

import java.time.LocalDate

/**
 * Renders the durable config as prose you can paste into a note (C-7).
 *
 * **Export only, and the format is the reason it stays that way.** Losing this phone or this laptop
 * means reinstalling, and a reinstall takes every cap, schedule, picker-added app and blocked domain
 * with it — today the only record of all that is memory. So a record is worth having.
 *
 * An *import* is not, and gating one wouldn't fix it. On an existing install a bulk import is just
 * bulk editing, which [DurableChangeGate] already refuses past one loosening. But import is only
 * genuinely useful on a **fresh** install — and there, with no key and no stored config, an absent
 * target reads as fully open, so importing anything at all is pure tightening and free. Reinstall,
 * edit one line of the file, import, and the whole configuration comes back minus the one rule you
 * wanted gone, having never touched the gate. The useful case and the unsafe case are the same case.
 *
 * Hence prose rather than [EngineCodec]'s blob: this can only be re-entered by hand, which is both
 * the recovery path and the reason an import can't quietly be added later. Ten minutes of typing is
 * the friction, not an oversight.
 *
 * Nothing secret is emitted. The lock key never appears — it is stored only as a salted hash and is
 * not part of [DurableSettings] at all — and neither does usage, exception state, or unlock state.
 */
object ConfigExport {

    /**
     * @param label how to name a target; the caller owns it because a user-added app's name comes
     *   from the launcher, which the engine can't see.
     * @param scopeNote optional caveat per target (Instagram counts reels only, say) — the same note
     *   the settings card carries, since an export that omitted it would overstate what is blocked.
     * @param today stamped into the header so an old note in a drawer is recognisably old.
     */
    fun render(
        settings: DurableSettings,
        blockedDomains: List<String>,
        label: (Target) -> String,
        scopeNote: (Target) -> String? = { null },
        today: LocalDate? = null,
    ): String = buildString {
        appendLine(if (today == null) "App-Block — my rules" else "App-Block — my rules ($today)")
        appendLine()

        if (settings.targets.isEmpty()) {
            appendLine("Apps: none configured.")
        } else {
            settings.targets.forEach { (target, entry) ->
                appendLine(label(target))
                scopeNote(target)?.let { appendLine("  $it") }
                if (!entry.enabled) {
                    // Still listed. "This app is deliberately not blocked" is part of the record —
                    // silence would read as "forgot to add it" when it's rebuilt from this.
                    appendLine("  Not blocked")
                } else {
                    appendTarget(entry)
                }
                appendLine()
            }
        }

        appendLine("Blocked websites")
        if (blockedDomains.isEmpty()) appendLine("  none")
        else blockedDomains.forEach { appendLine("  $it") }
        appendLine()

        appendLine("Temporary exception window: ${duration(settings.exceptionWindowMinutes)}")
        appendLine()
        appendLine("--")
        appendLine("A record, not a backup — App-Block can't read this back in, so re-entering it by")
        appendLine("hand is the only way. That is deliberate. Your lock key is not in this file.")
    }

    private fun StringBuilder.appendTarget(entry: TargetSettings) {
        val summary = TargetSummaries.of(entry)

        val limits = summary.limits.joinToString(" · ") {
            "${duration(it.minutes)} ${DayLabels.of(it.days)}"
        }
        appendLine("  Limit: $limits")

        summary.availability.forEach { availability ->
            when (availability) {
                is Availability.AnyTime ->
                    appendLine("  Available: any time")
                is Availability.Window ->
                    appendLine("  Available: ${window(availability)} ${DayLabels.of(availability.days)}")
                is Availability.BlockedAllDay ->
                    appendLine("  Blocked all day: ${DayLabels.of(availability.days)}")
            }
        }

        appendLine("  Exception ceiling: ${duration(summary.exceptionCeilingMinutes)}")
    }

    /** 24-hour, matching the settings screen. An end at or before the start crosses midnight. */
    private fun window(w: Availability.Window): String {
        val span = "${clock(w.startMin)}–${clock(w.endMin)}"
        return if (w.endMin <= w.startMin) "$span (overnight)" else span
    }

    private fun clock(minuteOfDay: Int): String {
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** Same units as the UI: whole hours read as hours, so "1440 min" never reaches the page. */
    private fun duration(minutes: Int): String = when {
        minutes <= 0 -> "0 min"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}
