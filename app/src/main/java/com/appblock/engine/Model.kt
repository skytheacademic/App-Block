package com.appblock.engine

/**
 * A blockable target — a whole app or a specific in-app surface. [key] is a stable id the engine and
 * storage use; the mapping to a real package / on-screen detection signal lives in the Android layer
 * (Phase 2b). See CONSTRAINTS.md §1.
 *
 * **Open, not an enum** (Batch 4): it was closed while the blocked set was fixed in source, but apps
 * are now addable on-device. The curated built-ins survive as companion constants, so `Target.TIKTOK`
 * still reads the same; user-added apps are keyed on their package via [forPackage].
 *
 * [key] must contain no `,` or `|` — those delimit the encoded settings string
 * ([EngineCodec.encodeDurable]). Android package names can contain neither, so package keys are safe;
 * anything building a Target from another source must preserve that invariant ([isEncodableKey]).
 */
data class Target(val key: String) {

    /** The package this target enforces, for user-added apps; null for the curated built-ins. */
    val userPackage: String?
        get() = if (key.startsWith(USER_PREFIX)) key.removePrefix(USER_PREFIX) else null

    companion object {
        private const val USER_PREFIX = "pkg:"

        val TIKTOK = Target("tiktok")
        val INSTAGRAM_REELS_EXPLORE = Target("ig_reels_explore")

        /**
         * Instagram as a whole app, carrying a *schedule only* — no cap, no accrual, no exception.
         *
         * It exists **beside** [INSTAGRAM_REELS_EXPLORE] rather than replacing it, because the two
         * limits want different scopes: closing hours are app-wide (they cover DMs), while the budget
         * is surface-scoped (reels only). Both resolve on `com.instagram.android` and the strictest
         * answer wins — see [AppTargets.foregroundTargets] and `BudgetCoordinator.decideCurrent`.
         *
         * Making it a second target is what keeps the cap honest. Folding the schedule onto the
         * existing target would have meant one rule carrying both, and a whole-app *cap* is the thing
         * CONSTRAINTS §1 forbids: it would price feed/DMs/stories against the reels budget.
         */
        val INSTAGRAM_APP = Target("ig_app")
        val X = Target("x")

        /** The curated targets, in the order the UI lists them. */
        val BUILT_INS: List<Target> = listOf(TIKTOK, INSTAGRAM_APP, INSTAGRAM_REELS_EXPLORE, X)

        /** A whole-app target for a user-picked package. */
        fun forPackage(packageName: String): Target = Target(USER_PREFIX + packageName)

        /** True if [key] can round-trip through the encoded settings string. */
        fun isEncodableKey(key: String): Boolean =
            key.isNotBlank() && !key.contains(',') && !key.contains('|')
    }
}

/** Which kind of day a logical day is — X's cap differs on weekends. */
enum class DayType { WEEKDAY, WEEKEND }

/** The allow/block outcome for whatever surface is on screen. */
enum class Access { ALLOW, BLOCK }

/** Why a BLOCK decision blocked — drives which message the overlay shows. */
enum class BlockReason { BUDGET, SCHEDULE, TAMPER, HARD_BLOCK }

/** How a target is limited. */
sealed interface RuleMode {

    /** Always blocked (no budget). */
    data object HardBlock : RuleMode

    /**
     * Blocked outside the rule's [Rule.schedule], unrestricted inside it. No cap, no accrual, no
     * exception — the closing-hours half of a limit, on its own.
     *
     * **Why this is not just a [DailyBudget] with a 24-hour cap.** A fake cap would be a lie the UI
     * then repeats: the Apps row would advertise "24 h every day", the limits sheet would offer
     * steppers that change nothing, and the block screen would grow a "request more time" affordance
     * for a block that no exception can lift (the schedule gate runs *before* the budget, so an
     * exception cannot reach it). This project has already been bitten once by rows quoting a price
     * that wasn't real — see [BlockFacts]. A mode that has no cap should not have a cap field.
     *
     * A rule in this mode with a null schedule allows everything, which is the correct degenerate
     * reading: no closing hours means never closed.
     */
    data object ScheduleOnly : RuleMode

    /**
     * Allowed up to a daily cap that differs weekday/weekend, and can be temporarily raised by an
     * exception up to [exceptionMaxMinutes] — the hard ceiling even an exception can't exceed.
     */
    data class DailyBudget(
        val weekdayMinutes: Int,
        val weekendMinutes: Int,
        val exceptionMaxMinutes: Int,
    ) : RuleMode {
        fun normalMinutes(dayType: DayType): Int =
            if (dayType == DayType.WEEKEND) weekendMinutes else weekdayMinutes
    }
}

/**
 * A target, how it's limited ([mode]), and optionally *when* it's allowed ([schedule]). A null
 * schedule means no time-of-day restriction (budget-only). Budget and schedule compose: the target is
 * open only if it's inside an allowed window AND under its cap.
 */
data class Rule(val target: Target, val mode: RuleMode, val schedule: Schedule? = null)
