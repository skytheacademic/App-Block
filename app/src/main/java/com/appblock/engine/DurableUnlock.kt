package com.appblock.engine

/**
 * Which kind of enforcement a durable-unlock cycle loosens. The wait scales with the category
 * (CONSTRAINTS.md §2/§6): app rules keep the 2-hour wait; website rules (blocklist removals) get a
 * 72-hour one. A window only ever authorizes changes of its own category — requesting the short apps
 * wait can never open the door to a website loosening.
 */
enum class UnlockCategory(val key: String, val defaultWaitMs: Long) {
    APPS("apps", 2L * 60L * 60L * 1000L),
    WEBSITES("websites", 72L * 60L * 60L * 1000L);

    companion object {
        fun forKey(key: String): UnlockCategory? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The delayed, single-use authorization for a durable *loosening* change (CONSTRAINTS.md §6, revised
 * 2026-07-22). Flow: prove possession of the stashed key → a **per-category wait** (apps 2 h ·
 * websites 72 h) → a **15-minute window** opens (announced by a notification) → make one change of
 * that category → it relocks. Missing the window, or a reboot during the wait, drops back to Locked
 * and the whole cycle must be repeated.
 *
 * Timing is monotonic (elapsedRealtime) so a clock change can't shorten the wait, and every state
 * carries the boot count: elapsedRealtime resets to ~0 on reboot, so a boot-count change means the
 * stored deadlines are from a previous boot and can't be trusted → restart (the user's chosen
 * behavior, and the fail-safe one). Tightening never uses this path — it stays free.
 */
sealed interface DurableUnlockState {

    /** No change window — loosening is blocked. */
    data object Locked : DurableUnlockState

    /** The wait is running; the window opens at [activeAtElapsedMs]. */
    data class Pending(
        val activeAtElapsedMs: Long,
        val windowEndElapsedMs: Long,
        val bootCount: Int,
        val category: UnlockCategory = UnlockCategory.APPS,
    ) : DurableUnlockState

    /** The 15-minute window is open until [windowEndElapsedMs] — one [category] change, then relock. */
    data class Open(
        val windowEndElapsedMs: Long,
        val bootCount: Int,
        val category: UnlockCategory = UnlockCategory.APPS,
    ) : DurableUnlockState
}

object DurableUnlockManager {

    /** How long the change window stays open once it does (real build) — same for every category. */
    const val DEFAULT_WINDOW_MS: Long = 15L * 60L * 1000L

    /**
     * Begin the wait for one [category] change. The real wait comes from the category (apps 2 h ·
     * websites 72 h); [waitMs]/[windowMs] are injectable so the fast build can use short values.
     */
    fun request(
        nowElapsedMs: Long,
        bootCount: Int,
        category: UnlockCategory = UnlockCategory.APPS,
        waitMs: Long = category.defaultWaitMs,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): DurableUnlockState.Pending {
        val activeAt = nowElapsedMs + waitMs
        return DurableUnlockState.Pending(
            activeAtElapsedMs = activeAt,
            windowEndElapsedMs = activeAt + windowMs,
            bootCount = bootCount,
            category = category,
        )
    }

    /**
     * Advance the state machine. A boot-count change restarts (→ Locked); otherwise Pending→Open at the
     * wait's end and Open→Locked at the window's end. A late first observation can find the window
     * already over (→ Locked) — it can't be parked and cashed in later.
     */
    fun tick(state: DurableUnlockState, nowElapsedMs: Long, bootCount: Int): DurableUnlockState =
        when (state) {
            is DurableUnlockState.Locked -> state
            is DurableUnlockState.Pending -> when {
                state.bootCount != bootCount -> DurableUnlockState.Locked
                nowElapsedMs >= state.windowEndElapsedMs -> DurableUnlockState.Locked
                nowElapsedMs >= state.activeAtElapsedMs ->
                    DurableUnlockState.Open(state.windowEndElapsedMs, state.bootCount, state.category)
                else -> state
            }
            is DurableUnlockState.Open -> when {
                state.bootCount != bootCount -> DurableUnlockState.Locked
                nowElapsedMs >= state.windowEndElapsedMs -> DurableUnlockState.Locked
                else -> state
            }
        }

    /**
     * Whether *any* window is open, regardless of category — for "is an unlock in progress at all"
     * concerns like the settings-watch stand-down. Authorizing an actual change goes through
     * [isOpenFor].
     */
    fun isOpen(state: DurableUnlockState): Boolean = state is DurableUnlockState.Open

    /** Whether a change of [category] may be made right now — the window must match. */
    fun isOpenFor(state: DurableUnlockState, category: UnlockCategory): Boolean =
        state is DurableUnlockState.Open && state.category == category

    /** The single-use close: after one accepted change, relock. */
    fun consume(): DurableUnlockState = DurableUnlockState.Locked

    /** Ms left on the wait (0 unless Pending). */
    fun msUntilOpen(state: DurableUnlockState, nowElapsedMs: Long): Long =
        if (state is DurableUnlockState.Pending) (state.activeAtElapsedMs - nowElapsedMs).coerceAtLeast(0L) else 0L

    /** Ms left in the open window (0 unless Open). */
    fun msUntilClose(state: DurableUnlockState, nowElapsedMs: Long): Long =
        if (state is DurableUnlockState.Open) (state.windowEndElapsedMs - nowElapsedMs).coerceAtLeast(0L) else 0L
}
