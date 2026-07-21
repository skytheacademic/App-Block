package com.appblock.engine

/**
 * Persistence for the runtime state the engine can't recompute: per-target daily usage and the
 * per-target exception state. The single source of truth shared by the accessibility service (writer)
 * and the UI (reader + exception requester) — both run in the same process, so a plain
 * SharedPreferences-backed impl is enough (see [com.appblock.data.PrefsEngineStore]).
 *
 * Fail-safe direction: if an exception can't be trusted (e.g. after a reboot, when the monotonic clock
 * it was anchored to has reset), the store returns [ExceptionState.None] — the *stricter* state. A
 * commitment device should always err toward more blocking, never less.
 */
interface EngineStore {

    fun loadUsage(target: Target): BudgetUsage?

    fun saveUsage(target: Target, usage: BudgetUsage)

    fun loadException(target: Target): ExceptionState

    fun saveException(target: Target, state: ExceptionState)
}

/** In-memory store for tests and defaults. No reboot/tamper concerns — lives only for the process. */
class InMemoryEngineStore : EngineStore {

    private val usage = mutableMapOf<Target, BudgetUsage>()
    private val exceptions = mutableMapOf<Target, ExceptionState>()

    override fun loadUsage(target: Target): BudgetUsage? = usage[target]

    override fun saveUsage(target: Target, usage: BudgetUsage) {
        this.usage[target] = usage
    }

    override fun loadException(target: Target): ExceptionState =
        exceptions[target] ?: ExceptionState.None

    override fun saveException(target: Target, state: ExceptionState) {
        exceptions[target] = state
    }
}
