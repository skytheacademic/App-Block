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

    /** Overall direction: any single field that loosens makes the whole edit LOOSEN (and thus gated). */
    fun classify(old: DurableSettings, new: DurableSettings): ChangeDirection {
        val directions = buildList {
            add(numeric(old.exceptionWindowMinutes, new.exceptionWindowMinutes))
            for (target in old.targets.keys + new.targets.keys) {
                add(targetDirection(old.targets[target], new.targets[target]))
            }
        }
        return reduce(directions)
    }

    /** Direction for one target. Absent = disabled = fully open. */
    private fun targetDirection(old: TargetSettings?, new: TargetSettings?): ChangeDirection {
        val o = old ?: DISABLED
        val n = new ?: DISABLED
        return when {
            // Both off: the app is fully open before and after — caps have no enforcement effect.
            !o.enabled && !n.enabled -> ChangeDirection.NEUTRAL
            // Off → on: went from no limit to some limit, whatever the caps → stricter.
            !o.enabled && n.enabled -> ChangeDirection.TIGHTEN
            // On → off: removed the limit entirely → looser.
            o.enabled && !n.enabled -> ChangeDirection.LOOSEN
            // Both on: combine the per-cap directions (higher cap = looser) with the schedule direction.
            else -> reduce(
                listOf(
                    numeric(o.weekdayMinutes, n.weekdayMinutes),
                    numeric(o.weekendMinutes, n.weekendMinutes),
                    numeric(o.exceptionMaxMinutes, n.exceptionMaxMinutes),
                    scheduleDirection(o.schedule, n.schedule),
                ),
            )
        }
    }

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
