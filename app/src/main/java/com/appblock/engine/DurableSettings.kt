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
     * (always allowed). Order follows [Target.entries] so the UI is stable.
     */
    fun toRules(): List<Rule> =
        Target.entries.mapNotNull { target ->
            targets[target]?.takeIf { it.enabled }?.let { s ->
                Rule(
                    target,
                    RuleMode.DailyBudget(
                        weekdayMinutes = s.weekdayMinutes,
                        weekendMinutes = s.weekendMinutes,
                        exceptionMaxMinutes = s.exceptionMaxMinutes,
                    ),
                )
            }
        }

    companion object {
        /**
         * Bump this in source (with a defaults edit) to force the persisting store to re-seed on the
         * next launch — the "change durable rules from the computer" path (CONSTRAINTS.md §6).
         */
        const val RULES_VERSION: Int = 1

        /** Default temporary-exception window (minutes) — a durable pre-set, editable behind the gate. */
        const val DEFAULT_EXCEPTION_WINDOW_MINUTES: Int = 60

        /** Seed a settings object from a [DefaultRules]-shaped rule list (all seeded targets enabled). */
        fun from(
            rules: List<Rule>,
            exceptionWindowMinutes: Int = DEFAULT_EXCEPTION_WINDOW_MINUTES,
            version: Int = RULES_VERSION,
        ): DurableSettings {
            val targets = rules.mapNotNull { rule ->
                (rule.mode as? RuleMode.DailyBudget)?.let { mode ->
                    rule.target to TargetSettings(
                        enabled = true,
                        weekdayMinutes = mode.weekdayMinutes,
                        weekendMinutes = mode.weekendMinutes,
                        exceptionMaxMinutes = mode.exceptionMaxMinutes,
                    )
                }
            }.toMap()
            return DurableSettings(version, targets, exceptionWindowMinutes)
        }
    }
}
