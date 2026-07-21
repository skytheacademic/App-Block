package com.appblock.engine

/**
 * Persisted snapshot of both clocks (plus the boot count) at the last engine pass — the tamper
 * guard's baseline. Lives in the store so it survives process restarts: without that, killing the
 * service process between "set the date forward" and "open TikTok" would blind the guard.
 */
data class ClockAnchor(val wallMs: Long, val elapsedMs: Long, val bootCount: Int)

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
}

/** In-memory store for tests and defaults. No reboot/tamper concerns — lives only for the process. */
class InMemoryEngineStore : EngineStore {

    private val usage = mutableMapOf<Target, BudgetUsage>()
    private val exceptions = mutableMapOf<Target, ExceptionState>()
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
}
