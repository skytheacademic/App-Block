package com.appblock.engine

/**
 * Which way a proposed [DurableSettings] edit moves enforcement. The commitment lock is built on one
 * asymmetry: the impulsive self only ever wants to *loosen*, so only loosening needs the QR/computer
 * unlock — tightening and no-op changes are always free (you can always bind yourself harder).
 *
 * "Looser" = more access: a higher cap, a longer exception window, a higher exception ceiling, or a
 * target turned off (off = unenforced = fully open, the loosest possible state for that app).
 */
enum class ChangeDirection { NEUTRAL, TIGHTEN, LOOSEN }

/**
 * One field-level difference between two settings, and which way it moves enforcement.
 *
 * [target] is null for the settings-wide exception window; [detail] is user-facing.
 */
data class FieldChange(val target: Target?, val detail: String, val direction: ChangeDirection)

/** A [FieldChange] that loosens — what gates a save. */
typealias Loosening = FieldChange

/** Outcome of running an edit through [DurableChangeGate]. */
sealed interface ChangeResult {
    /** The edit was tightening/neutral, or an unlock was present — [settings] is what to persist. */
    data class Applied(val settings: DurableSettings) : ChangeResult

    /** The edit loosens enforcement and no unlock is active — needs the stashed QR / computer. */
    data class Blocked(val direction: ChangeDirection) : ChangeResult
}

/**
 * The durable-change lock (CONSTRAINTS.md §6). Pure: classify an edit, then allow it unless it loosens
 * enforcement without an unlock. The unlock itself (verifying a scanned/typed code, opening a session)
 * is [KeyAuthority] / the Android layer; this object only decides whether a given edit is permitted.
 */
object DurableChangeGate {

    /** [proposed] is allowed unless it loosens enforcement while [unlocked] is false. */
    fun applyChange(current: DurableSettings, proposed: DurableSettings, unlocked: Boolean): ChangeResult {
        val direction = classify(current, proposed)
        return if (direction == ChangeDirection.LOOSEN && !unlocked) {
            ChangeResult.Blocked(direction)
        } else {
            ChangeResult.Applied(proposed)
        }
    }

    /**
     * Every field-level difference between [old] and [new] — the single source of truth this file
     * works from.
     *
     * [classify] and [looseningReasons] both derive from this rather than each walking the settings
     * themselves. They used to be the same case analysis written twice, and the phone reported the
     * failure mode that allows (2026-07-25): the screen said the edit loosened limits and then listed
     * nothing, because the two walks disagreed. Deriving both makes "gated but nothing to show"
     * unrepresentable — the warning fires precisely when this list has a LOOSEN entry in it.
     *
     * Enabled transitions dominate the caps deliberately, matching the original semantics: a target
     * that is off before and after contributes nothing whatever its numbers say (it is unenforced
     * either way), off → on is a tightening whatever the caps, and on → off is a loosening.
     */
    fun changes(old: DurableSettings, new: DurableSettings): List<FieldChange> {
        val out = mutableListOf<FieldChange>()

        numeric(old.exceptionWindowMinutes, new.exceptionWindowMinutes).let { d ->
            if (d != ChangeDirection.NEUTRAL) {
                out += FieldChange(
                    null,
                    "exception window ${old.exceptionWindowMinutes} → ${new.exceptionWindowMinutes} min",
                    d,
                )
            }
        }

        for (target in old.targets.keys + new.targets.keys) {
            val o = old.targets[target] ?: DISABLED
            val n = new.targets[target] ?: DISABLED
            when {
                !o.enabled && !n.enabled -> Unit
                !o.enabled && n.enabled -> out += FieldChange(
                    target,
                    if (target in old.targets) "turned on" else "added to the blocked list",
                    ChangeDirection.TIGHTEN,
                )
                !n.enabled -> out += FieldChange(
                    target,
                    if (target in new.targets) "turned off" else "removed from the blocked list",
                    ChangeDirection.LOOSEN,
                )
                else -> {
                    cap(out, target, "weekday cap", o.weekdayMinutes, n.weekdayMinutes)
                    cap(out, target, "weekend cap", o.weekendMinutes, n.weekendMinutes)
                    cap(out, target, "exception ceiling", o.exceptionMaxMinutes, n.exceptionMaxMinutes)
                    val sd = scheduleDirection(o.schedule, n.schedule)
                    if (sd != ChangeDirection.NEUTRAL) {
                        out += FieldChange(
                            target,
                            if (sd == ChangeDirection.LOOSEN) "allowed hours widened" else "allowed hours narrowed",
                            sd,
                        )
                    }
                }
            }
        }
        return out
    }

    private fun cap(out: MutableList<FieldChange>, target: Target, name: String, from: Int, to: Int) {
        val d = numeric(from, to)
        if (d != ChangeDirection.NEUTRAL) out += FieldChange(target, "$name $from → $to min", d)
    }

    /** Overall direction: any single field that loosens makes the whole edit LOOSEN (and thus gated). */
    fun classify(old: DurableSettings, new: DurableSettings): ChangeDirection =
        reduce(changes(old, new).map { it.direction })

    /** The loosening subset of [changes] — exactly what gates a save, and what to show when it does. */
    fun looseningReasons(old: DurableSettings, new: DurableSettings): List<Loosening> =
        changes(old, new).filter { it.direction == ChangeDirection.LOOSEN }

    // targetDirection() lived here and duplicated the per-target case analysis that `changes()` now
    // owns. Keeping both is what let the gate and its explanation disagree, so it is deliberately
    // gone rather than left as a second opinion. The rules it encoded are preserved verbatim in
    // `changes()`: both-off is neutral, off→on tightens whatever the caps say, on→off loosens.

    /**
     * Direction for a schedule change, by allowed time-of-day: any newly-allowed minute is looser
     * (more access); strictly removing allowed minutes is tighter. Null = no schedule = all week
     * allowed, so adding a schedule tightens and dropping one loosens. Compared over the full week as
     * a minute mask — exact, and cheap since it only runs when the user saves.
     */
    private fun scheduleDirection(old: Schedule?, new: Schedule?): ChangeDirection {
        if (old == new) return ChangeDirection.NEUTRAL
        val oldMask = allowedMask(old)
        val newMask = allowedMask(new)
        var added = false
        var removed = false
        for (i in oldMask.indices) {
            if (newMask[i] && !oldMask[i]) added = true
            if (oldMask[i] && !newMask[i]) removed = true
            if (added && removed) break
        }
        return when {
            added -> ChangeDirection.LOOSEN
            removed -> ChangeDirection.TIGHTEN
            else -> ChangeDirection.NEUTRAL
        }
    }

    /** A week of allowed minutes (7×1440). Null schedule = every minute allowed. */
    private fun allowedMask(schedule: Schedule?): BooleanArray {
        val mask = BooleanArray(7 * TimeWindow.DAY_MINUTES)
        if (schedule == null) {
            mask.fill(true)
            return mask
        }
        for ((day, windows) in schedule.allowedByDay) {
            val base = (day.value - 1) * TimeWindow.DAY_MINUTES
            for (window in windows) {
                val start = window.startMinuteOfDay.coerceIn(0, TimeWindow.DAY_MINUTES)
                val end = window.endMinuteOfDay.coerceIn(0, TimeWindow.DAY_MINUTES)
                for (m in start until end) mask[base + m] = true
            }
        }
        return mask
    }

    /** Higher number = more access = looser. */
    private fun numeric(old: Int, new: Int): ChangeDirection = when {
        new > old -> ChangeDirection.LOOSEN
        new < old -> ChangeDirection.TIGHTEN
        else -> ChangeDirection.NEUTRAL
    }

    /** Any loosening wins; else any tightening; else neutral. */
    private fun reduce(directions: List<ChangeDirection>): ChangeDirection = when {
        directions.any { it == ChangeDirection.LOOSEN } -> ChangeDirection.LOOSEN
        directions.any { it == ChangeDirection.TIGHTEN } -> ChangeDirection.TIGHTEN
        else -> ChangeDirection.NEUTRAL
    }

    private val DISABLED = TargetSettings(enabled = false, weekdayMinutes = 0, weekendMinutes = 0, exceptionMaxMinutes = 0)
}
