package com.appblock.engine

/**
 * The editable, persisted durable configuration — the "rules" a commitment device must protect. Every
 * field here is a *durable change* in CONSTRAINTS.md §6 terms: editing it on-device requires the
 * stashed QR (or the computer). See [DurableChangeGate] for the loosen-gated / tighten-free lock.
 *
 * Kept in the pure engine (Android-free, JVM-testable). The Android layer seeds it from
 * [DefaultRules] and persists it; the running blocker reads [toRules] each pass via a [RuleSource].
 */
data class TargetSettings(
    /** Off = this app isn't enforced at all (a durable "turn the block off"). */
    val enabled: Boolean,
    val weekdayMinutes: Int,
    val weekendMinutes: Int,
    /** Hard ceiling a temporary exception can raise this app's cap to (CONSTRAINTS.md §5). */
    val exceptionMaxMinutes: Int,
    /** Optional time-of-day allow-schedule. Null = allowed any time (budget-only). Composes with the caps. */
    val schedule: Schedule? = null,
    /**
     * True when this target carries **only** closing hours — [RuleMode.ScheduleOnly]. The three cap
     * fields are then meaningless and stored as 0; nothing reads them, and the UI must not offer
     * steppers for them.
     *
     * A boolean rather than a persisted mode name because that is the whole of the variation: every
     * other target is a [RuleMode.DailyBudget], and [RuleMode.HardBlock] is never persisted (it is
     * injected from [AppTargets.alwaysBlockedRules], deliberately out of reach of the settings).
     */
    val scheduleOnly: Boolean = false,
)

/**
 * The whole durable config: per-target budgets + the temporary-exception window length, which is a
 * durable pre-set (not chosen in the moment — revised 2026-07-21, CONSTRAINTS.md §5).
 *
 * [version] is the computer re-seed knob: the persisting store re-seeds from source whenever the
 * stored version differs from [RULES_VERSION], so changing the defaults in source + bumping the
 * constant + rebuilding is the (inherently authorized) computer path to a durable change.
 */
data class DurableSettings(
    val version: Int,
    val targets: Map<Target, TargetSettings>,
    /** How long a temporary exception's raised cap lasts once it activates (minutes). */
    val exceptionWindowMinutes: Int,
) {
    /**
     * The engine rule list. Disabled targets are omitted → the coordinator treats them as untargeted
     * (always allowed). Order follows the map's own insertion order — built-ins first as seeded by
     * [DefaultRules], then user-added apps in the order they were added. The target set is open now,
     * so there is no enum order left to follow.
     */
    fun toRules(): List<Rule> =
        targets.entries.mapNotNull { (target, entry) ->
            entry.takeIf { it.enabled }?.let { s ->
                val mode = if (s.scheduleOnly) {
                    RuleMode.ScheduleOnly
                } else {
                    RuleMode.DailyBudget(
                        weekdayMinutes = s.weekdayMinutes,
                        weekendMinutes = s.weekendMinutes,
                        exceptionMaxMinutes = s.exceptionMaxMinutes,
                    )
                }
                Rule(target, mode, s.schedule)
            }
        }

    companion object {
        /**
         * Bump this in source (with a defaults edit) to force the persisting store to re-seed on the
         * next launch — the "change durable rules from the computer" path (CONSTRAINTS.md §6).
         * (v2: target entries gained an optional schedule.)
         * (v3: TikTok seeds disabled — see [DefaultRules.seededOff]. 2026-08-05, user's call.)
         * (v4: no defaults change — a re-seed *is* the change. 2026-08-06: a throwaway 18:00–20:00
         * schedule was put on JBL Portable to make the block screen render its [BlockFacts] rows for
         * the first time on hardware. It did, correctly. Removing that target is a **loosening**, and
         * with no key stored `LockStore.verify()` is false ⇒ no change window can ever open ⇒ the
         * phone cannot undo it at any price. This bump is the documented computer path (§6): the
         * store re-seeds from [DefaultRules], which has never contained JBL, so the target and its
         * schedule are gone on next launch. Every other rule is byte-identical to what was stored,
         * and usage counters live in a different store, so nothing else moves — verified against the
         * phone before bumping, precisely so the re-seed could not silently *loosen* a hand-tightened
         * cap.)
         * (v5: 2026-08-06 — [Target.INSTAGRAM_APP] added, a schedule-only whole-app target seeded
         * closed 06:00–11:00 weekdays / 06:00–10:00 weekends. A new built-in cannot arrive without a
         * re-seed, and this is the cheap moment to take one: the stored config is exactly the v4
         * defaults, so nothing hand-made is lost. Two fixes ride along, both of which would have made
         * the re-seed itself *loosen* things: [from] was dropping `rule.schedule`, and a package match
         * used to shadow the surface target instead of composing with it.)
         */
        const val RULES_VERSION: Int = 5

        /** Default temporary-exception window (minutes) — a durable pre-set, editable behind the gate. */
        const val DEFAULT_EXCEPTION_WINDOW_MINUTES: Int = 60

        /**
         * Seed a settings object from a [DefaultRules]-shaped rule list. Targets in [disabled] are
         * seeded **present but off**: kept in [targets] with their caps intact, omitted from
         * [toRules], and therefore not enforced.
         *
         * **Why "off" and not simply absent**, since dropping the rule would also stop enforcement:
         * an absent built-in cannot be restored from the phone. `AppPickerSheet` excludes
         * `AppTargets.packages`, so TikTok's packages are unofferable by design (offering one would
         * create a second, weaker whole-app target beside the real one). Seeded-off keeps the row on
         * the Apps tab, and switching it back on is `!o.enabled && n.enabled` — a **TIGHTEN** in
         * [DurableChangeGate.changes] — so it saves free and instantly, with no key and no window.
         * Absent would make re-enabling a laptop rebuild; off makes it one tap.
         *
         * It also keeps the decision in the record: [ConfigExport] prints "Not blocked" for a disabled
         * target precisely so a config rebuilt from an export can't read "deliberately unenforced" as
         * "forgotten".
         */
        fun from(
            rules: List<Rule>,
            exceptionWindowMinutes: Int = DEFAULT_EXCEPTION_WINDOW_MINUTES,
            version: Int = RULES_VERSION,
            disabled: Set<Target> = emptySet(),
        ): DurableSettings {
            val targets = rules.mapNotNull { rule ->
                // `schedule = rule.schedule` is not cosmetic: this dropped the seed's schedule on the
                // floor until 2026-08-06, and it never showed because no default rule had one. The
                // Instagram closing hours are the first, so a re-seed that lost them would have
                // silently reopened the mornings — a loosening produced by the re-seed path itself.
                when (val mode = rule.mode) {
                    is RuleMode.DailyBudget -> rule.target to TargetSettings(
                        enabled = rule.target !in disabled,
                        weekdayMinutes = mode.weekdayMinutes,
                        weekendMinutes = mode.weekendMinutes,
                        exceptionMaxMinutes = mode.exceptionMaxMinutes,
                        schedule = rule.schedule,
                    )
                    is RuleMode.ScheduleOnly -> rule.target to TargetSettings(
                        enabled = rule.target !in disabled,
                        weekdayMinutes = 0,
                        weekendMinutes = 0,
                        exceptionMaxMinutes = 0,
                        schedule = rule.schedule,
                        scheduleOnly = true,
                    )
                    // Never persisted — see TargetSettings.scheduleOnly.
                    is RuleMode.HardBlock -> null
                }
            }.toMap()
            return DurableSettings(version, targets, exceptionWindowMinutes)
        }
    }
}
