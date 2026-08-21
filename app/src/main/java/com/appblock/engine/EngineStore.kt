package com.appblock.engine

import java.time.LocalDate

/**
 * The tamper guard's persisted baseline. Lives in the store so it survives process restarts: without
 * that, killing the service process between "set the date forward" and "open TikTok" would blind
 * the guard.
 *
 * Two things live here, with different update rules (see BudgetCoordinator.guardClocks):
 *  - **The trusted baseline** — [wallMs], [elapsedMs], [zoneOffsetSeconds]: the clocks as they stood
 *    at the last pass where the OS owned them *and* nothing was latched. It is frozen while either
 *    is false, so a change made with automatic time off is still visible after automatic time is
 *    turned back on: the latch only clears once the clocks agree with this baseline again.
 *  - **The day model** — [dayKey], [dayEndsElapsedMs]: the logical day the last pass charged and the
 *    uptime reading at which it ends, so the day can never advance faster than uptime within a boot
 *    ([DayCorroboration]). Updated on every pass.
 *
 * [bootCount] decides whether any of it still applies: a different boot means fresh clocks and a
 * fresh model from the wall.
 *
 * [zoneOffsetSeconds] is **nullable on purpose**: anchors written before the timezone guard existed
 * carry no offset, and there is no safe value to invent for them. Defaulting to 0 would read as a
 * multi-hour "zone change" on the first pass after an update for anyone not sitting on UTC, latching
 * the tamper flag and blocking everything the moment the update lands. Null means "unknown — record
 * it, compare from the next pass", which costs one pass of coverage exactly once.
 */
data class ClockAnchor(
    val wallMs: Long,
    val elapsedMs: Long,
    val bootCount: Int,
    val zoneOffsetSeconds: Int? = null,
    /** The day model's day — null on anchors written before the model existed (same rule as the zone). */
    val dayKey: LocalDate? = null,
    /** Monotonic ms at which [dayKey] ends; null exactly when [dayKey] is. */
    val dayEndsElapsedMs: Long? = null,
)

/**
 * Persistence for the runtime state the engine can't recompute: per-target daily usage, per-target
 * exception state, the clock anchor, and the tamper latch. The single source of truth shared by the
 * accessibility service (writer) and the UI (reader + exception requester) — both run in the same
 * process, so a plain SharedPreferences-backed impl is enough (see [com.appblock.data.PrefsEngineStore]).
 *
 * Fail-safe direction: whenever stored state can't be trusted (reboot-stale exception anchors,
 * undecodable values), the store/coordinator must resolve toward *more* blocking, never less. A
 * commitment device should always err strict.
 */
interface EngineStore {

    fun loadUsage(target: Target): BudgetUsage?

    fun saveUsage(target: Target, usage: BudgetUsage)

    /** True if a usage value exists for [target] but can't be decoded — the coordinator fails closed. */
    fun usageCorrupt(target: Target): Boolean

    fun loadException(target: Target): ExceptionState

    fun saveException(target: Target, state: ExceptionState)

    /**
     * Drop every stored exception, whoever it belongs to — the reboot path. Per-target clearing
     * over the *enabled* rules left an exception on a since-disabled target alive across a reboot
     * with monotonic anchors from the old boot; the store knows every key it holds, the rule list
     * doesn't.
     */
    fun clearExceptions()

    fun loadClockAnchor(): ClockAnchor?

    fun saveClockAnchor(anchor: ClockAnchor)

    /** The tamper latch: null = clear; non-null = latched, with a human-readable reason. */
    fun loadTamper(): String?

    /** Latch (non-null reason) or clear (null) the tamper flag. */
    fun saveTamper(reason: String?)

    /**
     * The last [UsageTracker.HISTORY_DAYS] *completed* logical days for [target], oldest first.
     *
     * Display-only, and deliberately outside the fail-safe rule that governs everything else here:
     * no policy decision ever reads history, so a lost or unreadable entry costs one bar on a chart
     * and nothing else. It must therefore fail quietly — unreadable reads as "no history", never as
     * a reason to block.
     *
     * The one exception is the day-regression re-key in BudgetCoordinator.guardClocks, which takes
     * the archived count for the day the clock fell back onto and keeps the *larger* of it and the
     * count being re-keyed. That read can only ever raise a count, never lower one, so a missing or
     * damaged history still fails toward the stricter answer.
     */
    fun loadHistory(target: Target): List<DayUsage>

    /** Archive one completed day. Idempotent per day — see [UsageTracker.archive]. */
    fun recordHistory(target: Target, completed: DayUsage)
}

/** In-memory store for tests and defaults. No reboot/tamper concerns — lives only for the process. */
class InMemoryEngineStore : EngineStore {

    private val usage = mutableMapOf<Target, BudgetUsage>()
    private val exceptions = mutableMapOf<Target, ExceptionState>()
    private val history = mutableMapOf<Target, List<DayUsage>>()
    private var anchor: ClockAnchor? = null
    private var tamperReason: String? = null

    /** Tests set this to simulate an undecodable stored usage value. */
    val corruptUsage = mutableSetOf<Target>()

    override fun loadUsage(target: Target): BudgetUsage? = usage[target]

    override fun saveUsage(target: Target, usage: BudgetUsage) {
        this.usage[target] = usage
        corruptUsage.remove(target)
    }

    override fun usageCorrupt(target: Target): Boolean = target in corruptUsage

    override fun loadException(target: Target): ExceptionState =
        exceptions[target] ?: ExceptionState.None

    override fun saveException(target: Target, state: ExceptionState) {
        exceptions[target] = state
    }

    override fun clearExceptions() {
        exceptions.clear()
    }

    override fun loadClockAnchor(): ClockAnchor? = anchor

    override fun saveClockAnchor(anchor: ClockAnchor) {
        this.anchor = anchor
    }

    override fun loadTamper(): String? = tamperReason

    override fun saveTamper(reason: String?) {
        tamperReason = reason
    }

    override fun loadHistory(target: Target): List<DayUsage> = history[target].orEmpty()

    override fun recordHistory(target: Target, completed: DayUsage) {
        history[target] = UsageTracker.archive(loadHistory(target), completed)
    }
}
