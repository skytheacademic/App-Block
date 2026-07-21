package com.appblock.engine

/** The decision for the app currently on screen. [target] is null when the foreground isn't budgeted. */
data class Decision(val target: Target?, val access: Access)

/** A UI-facing snapshot of one target's standing today. All times already resolved to plain numbers. */
data class TargetStatus(
    val target: Target,
    val normalCapMinutes: Int,
    val effectiveCapMinutes: Int,
    /** The hard ceiling an exception can raise this target's cap to (CONSTRAINTS.md §5). */
    val exceptionMaxMinutes: Int,
    val usedSeconds: Long,
    val remainingSeconds: Long,
    val access: Access,
    val exception: ExceptionState,
    /** If an exception is Pending: ms left on the 1-hour wait. Else null. */
    val exceptionActivatesInMs: Long?,
    /** If an exception is Active: ms left in the raised window. Else null. */
    val exceptionEndsInMs: Long?,
)

/**
 * Runtime orchestrator that turns the pure engine into a live blocker. Holds only transient runtime
 * position (which target is foreground, and the accrual baseline); all durable state lives in the
 * injected [EngineStore], so the accessibility service (which drives accrual + decisions) and the UI
 * (which reads status and requests exceptions) can each hold their own coordinator over the same store
 * and stay consistent — they share one process.
 *
 * Timekeeping split (see Clock.kt): foreground time accrues off the **monotonic** clock so rewinding
 * the phone clock can't erase used minutes, while the day boundary and weekday/weekend read the
 * **wall** clock. A [ClockTamperMonitor] flags forward wall jumps.
 */
class BudgetCoordinator(
    private val clock: EngineClock,
    private val store: EngineStore,
    private val rules: List<Rule> = DefaultRules.rules,
) {
    private var currentTarget: Target? = null
    private var lastAccrualElapsedMs: Long = clock.elapsedRealtimeMs()
    private val tamperMonitor = ClockTamperMonitor()

    /** The target the last decision blocked, if any — accrual freezes while its overlay is up. */
    private var blockedTarget: Target? = null

    /** True if the last [tick] saw a suspicious forward wall-clock jump. Informational for now. */
    var lastTamperDetected: Boolean = false
        private set

    /** Call when the foreground package changes. Flushes the outgoing target's time, then switches. */
    fun onForeground(packageName: String?) {
        val newTarget = packageName?.let { AppTargets.targetFor(it) }
        bankTime()                      // bank the app we're leaving
        currentTarget = newTarget
        blockedTarget = null            // a fresh foreground isn't blocked until the next decision
        lastAccrualElapsedMs = clock.elapsedRealtimeMs()
    }

    /** Periodic heartbeat while an app is foreground: advance exceptions, bank time, decide. */
    fun tick(): Decision {
        lastTamperDetected = tamperMonitor.check(clock.wallClockMs(), clock.elapsedRealtimeMs())
        advanceExceptions()
        bankTime()
        val decision = decideCurrent()
        blockedTarget = if (decision.access == Access.BLOCK) decision.target else null
        return decision
    }

    /** Start the 1-hour wait for a bounded raise on [target]. Persisted so the service picks it up. */
    fun requestException(target: Target, extraMinutes: Int, windowMinutes: Int) {
        store.saveException(
            target,
            ExceptionManager.request(target, extraMinutes, windowMinutes, clock.elapsedRealtimeMs()),
        )
    }

    /** Drop any exception on [target] (revert to the normal cap immediately). */
    fun cancelException(target: Target) {
        store.saveException(target, ExceptionState.None)
    }

    /** Per-target standing for the UI. Advances exceptions + banks current time so numbers are fresh. */
    fun snapshot(): List<TargetStatus> {
        advanceExceptions()
        bankTime()
        val today = DayBoundary.logicalDay(clock.nowLocal())
        val dayType = DayBoundary.dayType(today)
        val now = clock.elapsedRealtimeMs()
        return rules.map { rule ->
            val exc = store.loadException(rule.target)
            val used = UsageTracker.secondsUsedOn(store.loadUsage(rule.target), today)
            when (val mode = rule.mode) {
                is RuleMode.HardBlock ->
                    TargetStatus(rule.target, 0, 0, 0, used, 0, Access.BLOCK, exc, null, null)

                is RuleMode.DailyBudget -> {
                    val extra = ExceptionManager.activeExtraMinutes(exc, rule.target)
                    val normalCap = mode.normalMinutes(dayType)
                    val effCap = PolicyEngine.effectiveCapMinutes(mode, dayType, extra)
                    val capSeconds = effCap * 60L
                    val remaining = (capSeconds - used).coerceAtLeast(0L)
                    val access = if (used >= capSeconds) Access.BLOCK else Access.ALLOW
                    val activatesIn = (exc as? ExceptionState.Pending)
                        ?.let { (it.activeAtElapsedMs - now).coerceAtLeast(0L) }
                    val endsIn = (exc as? ExceptionState.Active)
                        ?.let { (it.windowEndElapsedMs - now).coerceAtLeast(0L) }
                    TargetStatus(
                        target = rule.target,
                        normalCapMinutes = normalCap,
                        effectiveCapMinutes = effCap,
                        exceptionMaxMinutes = mode.exceptionMaxMinutes,
                        usedSeconds = used,
                        remainingSeconds = remaining,
                        access = access,
                        exception = exc,
                        exceptionActivatesInMs = activatesIn,
                        exceptionEndsInMs = endsIn,
                    )
                }
            }
        }
    }

    // ---- internals ----

    /**
     * Bank time, but freeze accrual while the current target is the one we're actively blocking — the
     * overlay is covering it, so the user isn't really using it, and counting that time would make a
     * later exception pointless (usage would already be far past the raised cap).
     */
    private fun bankTime() {
        if (currentTarget != null && currentTarget == blockedTarget) {
            lastAccrualElapsedMs = clock.elapsedRealtimeMs()   // consume the gap without counting it
            return
        }
        accrueCurrent()
    }

    private fun advanceExceptions() {
        val now = clock.elapsedRealtimeMs()
        for (rule in rules) {
            val st = store.loadException(rule.target)
            val next = ExceptionManager.tick(st, now)
            if (next != st) store.saveException(rule.target, next)
        }
    }

    /**
     * Bank foreground seconds for [currentTarget] since the last accrual. Uses whole seconds and
     * advances the baseline only by the seconds consumed, so sub-second remainders carry forward
     * instead of being truncated away each tick.
     */
    private fun accrueCurrent() {
        val nowElapsed = clock.elapsedRealtimeMs()
        val t = currentTarget
        if (t == null) {
            lastAccrualElapsedMs = nowElapsed
            return
        }
        val totalDeltaMs = nowElapsed - lastAccrualElapsedMs
        if (totalDeltaMs <= 0L) {
            lastAccrualElapsedMs = nowElapsed     // clock stall / rewind: don't accrue, resync
            return
        }
        val deltaSec = totalDeltaMs / 1000L
        if (deltaSec <= 0L) return                // keep baseline; let the remainder accumulate
        lastAccrualElapsedMs += deltaSec * 1000L
        val today = DayBoundary.logicalDay(clock.nowLocal())
        store.saveUsage(t, UsageTracker.accrue(store.loadUsage(t), deltaSec, today))
    }

    private fun decideCurrent(): Decision {
        val t = currentTarget ?: return Decision(null, Access.ALLOW)
        val rule = rules.firstOrNull { it.target == t } ?: return Decision(t, Access.ALLOW)
        val today = DayBoundary.logicalDay(clock.nowLocal())
        val access = PolicyEngine.decide(rule, store.loadUsage(t), store.loadException(t), today)
        return Decision(t, access)
    }
}
