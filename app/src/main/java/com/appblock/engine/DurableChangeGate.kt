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

    /**
     * A window is open, but the edit loosens in more than one place. A window buys one change, and
     * this is the [loosenings] it would have spent itself on — the caller lists them so the user can
     * keep one and undo the rest.
     */
    data class TooManyLoosenings(val loosenings: List<Loosening>) : ChangeResult
}

/**
 * The durable-change lock (CONSTRAINTS.md §6). Pure: classify an edit, then allow it unless it loosens
 * enforcement without an unlock. The unlock itself (verifying a scanned/typed code, opening a session)
 * is [KeyAuthority] / the Android layer; this object only decides whether a given edit is permitted.
 */
object DurableChangeGate {

    /**
     * [proposed] is allowed unless it loosens enforcement without an open window — or loosens in more
     * than one place, which one window doesn't cover.
     *
     * **One window buys one change** (CONSTRAINTS §6; user's call, 2026-07-26 — the fix for audit
     * finding B-4). This used to gate on the *direction* of the whole edit, so a window authorized one
     * `save` rather than one change: two hours of waiting bought a single press of Save that could
     * raise every cap to 24 hours, blank every schedule and remove every app at once. The wait was
     * doing almost no work, because the thing it rationed was unbounded in size.
     *
     * Counted per [FieldChange], the same units the screen already lists back to the user, and the
     * same shape as the websites path — one blocklist removal per window. Raising a weekday cap and a
     * weekend cap is therefore two changes and two cycles. That is the intended answer for a
     * commitment device: the friction has to scale with how much access is being handed back, or it
     * isn't friction.
     *
     * Tightenings alongside are free and uncounted — you may always bind yourself harder, and doing so
     * in the same edit shouldn't cost the window.
     */
    fun applyChange(current: DurableSettings, proposed: DurableSettings, unlocked: Boolean): ChangeResult {
        val loosenings = looseningReasons(current, proposed)
        return when {
            loosenings.isEmpty() -> ChangeResult.Applied(proposed)
            !unlocked -> ChangeResult.Blocked(ChangeDirection.LOOSEN)
            loosenings.size > 1 -> ChangeResult.TooManyLoosenings(loosenings)
            else -> ChangeResult.Applied(proposed)
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
     * **A target's numbers are judged whatever its switch is doing** (audit 2026-08-21, G-1). The
     * original rule let the enabled transition *dominate*: off-before-and-after contributed nothing
     * "because it is unenforced either way", off → on was a bare tightening, on → off a bare
     * loosening, and the caps were only compared when the target was on both sides. Each of those
     * was a door, and together they were a free route around the whole gate, reachable with no key
     * the day TikTok started seeding *off*:
     *
     *  1. **off → off:** set the dormant row's caps to 1440 — "no enforcement effect", saved free;
     *  2. **off → on:** turn it on — a tightening, saved free. Net: a target the seed capped at 30
     *     minutes is now enforced at 24 hours, and nothing was ever gated.
     *  3. **on → off with riders:** inside one open window, turn a target off *and* raise its caps in
     *     the same save — one loosening, one window, and the raised caps wait for the free turn-on.
     *
     * The fix is to stop pretending the switch and the numbers are one field. Every target present
     * on both sides has its caps, ceiling, schedule and mode diffed regardless of `enabled`, *and* a
     * flipped switch reports as its own change. A stored number is the number the target will run
     * at the moment its free turn-on lands, so it must be guarded as if it were running now. Lowering
     * a dormant cap stays free, as every tightening does.
     *
     * Membership is still judged on enforcement: a target *added* is a tightening whatever its caps
     * (there is nothing to compare them with, and anything is tighter than unblocked), a target
     * *removed* while on is a loosening, and adding or removing one that is off is neutral — the
     * numbers of an absent target are no more reachable than a present-but-off one's, and the
     * turn-on that would make them matter is itself classified.
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
            val o = old.targets[target]
            val n = new.targets[target]
            when {
                o == null && n == null -> Unit
                o == null -> if (n!!.enabled) {
                    out += FieldChange(target, "added to the blocked list", ChangeDirection.TIGHTEN)
                }
                n == null -> if (o.enabled) {
                    out += FieldChange(target, "removed from the blocked list", ChangeDirection.LOOSEN)
                }
                else -> {
                    if (!o.enabled && n.enabled) out += FieldChange(target, "turned on", ChangeDirection.TIGHTEN)
                    if (o.enabled && !n.enabled) out += FieldChange(target, "turned off", ChangeDirection.LOOSEN)
                    fields(out, target, o, n)
                }
            }
        }
        return out
    }

    /** Every field of a target present on both sides, compared without regard to its switch. */
    private fun fields(out: MutableList<FieldChange>, target: Target, o: TargetSettings, n: TargetSettings) {
        when {
            // The mode flip *is* the change on the caps side. Schedule-only stores its three cap
            // columns as zeros that nothing reads, so diffing them across the flip would report a
            // phantom "weekday cap 30 → 0 min" — a tightening that isn't one — beside the real
            // loosening of the caps ceasing to exist.
            o.scheduleOnly != n.scheduleOnly -> out += FieldChange(
                target,
                if (n.scheduleOnly) "daily caps removed (closing hours only)" else "daily caps added",
                if (n.scheduleOnly) ChangeDirection.LOOSEN else ChangeDirection.TIGHTEN,
            )
            !n.scheduleOnly -> {
                cap(out, target, "weekday cap", o.weekdayMinutes, n.weekdayMinutes)
                cap(out, target, "weekend cap", o.weekendMinutes, n.weekendMinutes)
                cap(out, target, "exception ceiling", o.exceptionMaxMinutes, n.exceptionMaxMinutes)
            }
        }
        val sd = scheduleDirection(o.schedule, n.schedule)
        if (sd != ChangeDirection.NEUTRAL) {
            out += FieldChange(
                target,
                if (sd == ChangeDirection.LOOSEN) "allowed hours widened" else "allowed hours narrowed",
                sd,
            )
        }
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
    // gone rather than left as a second opinion. (Its rules — both-off is neutral, off→on tightens
    // whatever the caps say — were carried into `changes()` verbatim, and turned out to be G-1.)

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

}
