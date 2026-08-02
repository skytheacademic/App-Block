package com.appblock.engine

/**
 * Persisted snapshot of both clocks (plus the boot count) at the last engine pass — the tamper
 * guard's baseline. Lives in the store so it survives process restarts: without that, killing the
 * service process between "set the date forward" and "open TikTok" would blind the guard.
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
