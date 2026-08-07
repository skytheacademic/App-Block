package com.appblock.engine

import java.time.LocalDate
import kotlin.math.abs

/**
 * The decision for the app currently on screen. [target] is null when the foreground isn't budgeted;
 * [reason] is non-null exactly when [access] is BLOCK, so the overlay can say *why*.
 */
data class Decision(val target: Target?, val access: Access, val reason: BlockReason? = null)

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
    /** True when the block is because the current time is outside this target's allowed schedule. */
    val blockedBySchedule: Boolean = false,
    /**
     * True for a [RuleMode.ScheduleOnly] target: closing hours and nothing else. Every cap and usage
     * field above is 0 and means nothing — the UI must render hours, not minutes-remaining, and must
     * not offer an exception (no exception can lift a schedule).
     */
    val scheduleOnly: Boolean = false,
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
 * **wall** clock. The wall clock is only trusted while the OS syncs it ([ClockIntegrity]); with
 * automatic time off, any divergence between the clocks latches the tamper flag and every budgeted
 * target hard-blocks until automatic time is back on ([guardClocks]).
 *
 * Public methods are @Synchronized: today everything runs on the main looper, but nothing should
 * silently lose accrual writes if a caller ever moves off it. (Two coordinator *instances* over the
 * same store still rely on the shared main looper — keep new callers on it.)
 */
class BudgetCoordinator(
    private val clock: EngineClock,
    private val store: EngineStore,
    private val integrity: ClockIntegrity,
    /**
     * Read once per pass so a durable-rule edit (via the gated settings UI) is picked up by the
     * long-lived service coordinator on its next tick — the same way it re-reads usage from the store.
     * Defaults to the static [DefaultRules] so existing tests construct it with three args.
     */
    private val ruleSource: RuleSource = RuleSource { DefaultRules.rules },
    /** Wait before an exception activates — injected so `debugFast` can shrink it (ActiveRules). */
    private val exceptionWaitMs: Long = ExceptionManager.WAIT_MS,
) {
    /** The target time accrues to — at most one, and never a [RuleMode.ScheduleOnly] one. */
    private var currentTarget: Target? = null

    /**
     * Every target the foreground answers to. Usually 0 or 1; two when Instagram is on screen (its
     * app-wide closing hours plus, on a reel, the surface budget). **Strictest wins** — see
     * [decideCurrent].
     *
     * Kept apart from [currentTarget] because the two questions genuinely differ: *what am I
     * spending?* has one answer, *what could stop me?* has several. Conflating them is what let a
     * package match shadow the surface target and silently retire the reels budget.
     */
    private var gateTargets: List<Target> = emptyList()
    private var lastAccrualElapsedMs: Long = clock.elapsedRealtimeMs()

    /** Sub-second accrual remainders per target, so switching apps never truncates time away. */
    private val carryMs = mutableMapOf<Target, Long>()

    /** The target the last decision blocked, if any — accrual freezes while its overlay is up. */
    private var blockedTarget: Target? = null

    /** Non-null while the tamper latch is set (the human-readable reason). */
    fun tamperReason(): String? = store.loadTamper()

    /**
     * Call when the foreground package changes. Flushes the outgoing target's time, then switches.
     * A convenience over [onForegroundTarget] for whole-package targets (TikTok / X); Instagram is
     * surface-detected, so the service resolves its target itself and calls [onForegroundTarget].
     */
    @Synchronized
    fun onForeground(packageName: String?) =
        onForegroundTarget(packageName?.let { AppTargets.targetFor(it) })

    /**
     * Switch to an already-resolved foreground [target] (null = nothing budgeted on screen). Lets the
     * accessibility layer drive the target directly — needed for Instagram, where the *same* package is
     * budgeted or free depending on the on-screen surface ([InstagramSurface]).
     */
    @Synchronized
    fun onForegroundTarget(target: Target?) = onForegroundTargets(listOfNotNull(target))

    /**
     * Switch to the full set of targets the foreground answers to (empty = nothing limited on screen).
     *
     * Which one *accrues* is decided here rather than by the caller, from the rules themselves: a
     * [RuleMode.ScheduleOnly] target has no budget to spend, so it never accrues, and the remaining
     * candidate (if any) takes the time. That keeps the accessibility layer free of policy — it
     * reports what is on screen and the engine decides what that costs.
     */
    @Synchronized
    fun onForegroundTargets(targets: List<Target>) {
        gateTargets = targets
        val rules = ruleSource.rules()
        val target = targets.firstOrNull { t ->
            rules.firstOrNull { it.target == t }?.mode !is RuleMode.ScheduleOnly
        }
        if (store.loadTamper() != null) {
            lastAccrualElapsedMs = clock.elapsedRealtimeMs()  // latched: consume the gap, count nothing
        } else {
            bankTime()                  // bank the app we're leaving
        }
        currentTarget = target
        blockedTarget = null            // a fresh foreground isn't blocked until the next decision
        lastAccrualElapsedMs = clock.elapsedRealtimeMs()
    }

    /** Periodic heartbeat while an app is foreground: guard the clocks, advance exceptions, decide. */
    @Synchronized
    fun tick(): Decision {
        val rules = ruleSource.rules()
        guardClocks(rules)
        advanceExceptions(rules)
        if (store.loadTamper() != null) {
            // Latched: freeze accrual (the block screen is up; behind it isn't real usage) and block
            // every budgeted target until the wall clock is trustworthy again.
            lastAccrualElapsedMs = clock.elapsedRealtimeMs()
            // A schedule-only target must latch too, and it has no accrual target to be found under:
            // its gate is the wall clock, which is exactly what the latch says can't be trusted.
            val t = currentTarget ?: gateTargets.firstOrNull() ?: return Decision(null, Access.ALLOW)
            blockedTarget = t
            return Decision(t, Access.BLOCK, BlockReason.TAMPER)
        }
        bankTime()
        val decision = decideCurrent(rules)
        blockedTarget = if (decision.access == Access.BLOCK) decision.target else null
        return decision
    }

    /** Start the exception wait for a bounded raise on [target]. Persisted so the service picks it up. */
    @Synchronized
    fun requestException(target: Target, extraMinutes: Int, windowMinutes: Int) {
        store.saveException(
            target,
            ExceptionManager.request(
                target,
                extraMinutes,
                windowMinutes,
                clock.elapsedRealtimeMs(),
                DayBoundary.logicalDay(clock.nowLocal()),
                waitMs = exceptionWaitMs,
            ),
        )
    }

    /** Drop any exception on [target] (revert to the normal cap immediately). */
    @Synchronized
    fun cancelException(target: Target) {
        store.saveException(target, ExceptionState.None)
    }

    /** Per-target standing for the UI. Runs the same guards as [tick] so numbers are fresh + honest. */
    @Synchronized
    fun snapshot(): List<TargetStatus> {
        val rules = ruleSource.rules()
        guardClocks(rules)
        advanceExceptions(rules)
        val latched = store.loadTamper() != null
        if (latched) {
            lastAccrualElapsedMs = clock.elapsedRealtimeMs()
        } else {
            bankTime()
        }
        val nowLocal = clock.nowLocal()
        val today = DayBoundary.logicalDay(nowLocal)
        val dayType = DayBoundary.dayType(today)
        val now = clock.elapsedRealtimeMs()
        return rules.map { rule ->
            val exc = store.loadException(rule.target)
            val used = UsageTracker.secondsUsedOn(store.loadUsage(rule.target), today)
            val scheduleBlocks = !PolicyEngine.scheduleAllows(rule.schedule, nowLocal)
            when (val mode = rule.mode) {
                is RuleMode.HardBlock ->
                    TargetStatus(rule.target, 0, 0, 0, used, 0, Access.BLOCK, exc, null, null)

                is RuleMode.ScheduleOnly -> TargetStatus(
                    target = rule.target,
                    normalCapMinutes = 0,
                    effectiveCapMinutes = 0,
                    exceptionMaxMinutes = 0,
                    // Reported as 0 rather than from the store: this target never accrues, so any
                    // stored figure would be a leftover from before it became schedule-only, and
                    // showing it would imply a budget that isn't there.
                    usedSeconds = 0,
                    remainingSeconds = 0,
                    access = if (latched || scheduleBlocks) Access.BLOCK else Access.ALLOW,
                    exception = exc,
                    exceptionActivatesInMs = null,
                    exceptionEndsInMs = null,
                    blockedBySchedule = scheduleBlocks,
                    scheduleOnly = true,
                )

                is RuleMode.DailyBudget -> {
                    val extra = ExceptionManager.activeExtraMinutes(exc, rule.target)
                    val normalCap = mode.normalMinutes(dayType)
                    val effCap = PolicyEngine.effectiveCapMinutes(mode, dayType, extra)
                    val capSeconds = effCap * 60L
                    val remaining = (capSeconds - used).coerceAtLeast(0L)
                    val access =
                        if (latched || scheduleBlocks || used >= capSeconds) Access.BLOCK else Access.ALLOW
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
                        blockedBySchedule = scheduleBlocks,
                    )
                }
            }
        }
    }

    // ---- internals ----

    /**
     * The tamper guard. Runs before every decision/snapshot:
     *  1. Reboot (boot count changed): monotonic anchors from the old boot are meaningless → drop all
     *     in-flight exceptions (stricter cap). Rebooting with the clock untrusted also latches — a
     *     reboot is how you'd launder a manual date change past the drift check below.
     *  2. Drift (same boot, clock untrusted): between passes the wall clock must advance by about
     *     the same amount as monotonic uptime; divergence beyond [DRIFT_TOLERANCE_MS] in either
     *     direction means the date/time was changed by hand → latch.
     *  3. Zone shift (same boot, clock untrusted): the UTC offset moved. This is the case the drift
     *     check structurally cannot see — see [trustedClock].
     *  4. Clock trusted again: the OS owns both date and zone → the latch clears.
     *  5. Corrupt stored usage: burn that target's day (usage := its exception ceiling) — a decode
     *     failure must never become a fresh budget.
     *  6. Day regression (stored dayKey ahead of today): the wall date was rolled back. Re-key the
     *     stored count onto today so the earlier "future day" usage still counts, and latch if the
     *     clock is untrusted.
     * The anchor is persisted so none of this goes blind across process restarts.
     */
    private fun guardClocks(rules: List<Rule>) {
        val nowWall = clock.wallClockMs()
        val nowElapsed = clock.elapsedRealtimeMs()
        val nowZone = clock.zoneOffsetSeconds()
        val boot = integrity.bootCount()
        val auto = trustedClock()
        val anchor = store.loadClockAnchor()

        // These reasons are shown to the user verbatim, so none of them names a single toggle: the
        // latch now depends on both, and "turn automatic date & time back on" would send someone to
        // Settings to perform a fix that doesn't clear it. The reason says what happened; the overlay
        // and the warning card name both toggles to turn on.
        if (anchor != null && anchor.bootCount != boot) {
            rules.forEach { store.saveException(it.target, ExceptionState.None) }
            if (!auto) store.saveTamper("Rebooted while the clock was not fully automatic")
        } else if (anchor != null && !auto) {
            val drift = (nowWall - anchor.wallMs) - (nowElapsed - anchor.elapsedMs)
            // Order matters for the message only — either signal alone is enough to latch.
            if (anchor.zoneOffsetSeconds != null && anchor.zoneOffsetSeconds != nowZone) {
                store.saveTamper("Time zone changed while the clock was not fully automatic")
            } else if (abs(drift) > DRIFT_TOLERANCE_MS) {
                store.saveTamper("Date or time changed while the clock was not fully automatic")
            }
        }
        if (auto && store.loadTamper() != null) store.saveTamper(null)

        val today = DayBoundary.logicalDay(clock.nowLocal())
        for (rule in rules) {
            if (store.usageCorrupt(rule.target)) {
                val burnSeconds = when (val mode = rule.mode) {
                    is RuleMode.DailyBudget -> mode.exceptionMaxMinutes * 60L
                    is RuleMode.HardBlock -> 24L * 3600L
                    // No budget to burn — its gate is the clock, not a counter, and the schedule
                    // still holds whatever this row says. Burning a day here would write a figure
                    // nothing reads and the UI reports as 0 anyway.
                    is RuleMode.ScheduleOnly -> 0L
                }
                store.saveUsage(rule.target, BudgetUsage(burnSeconds, today))
                continue
            }
            val usage = store.loadUsage(rule.target) ?: continue
            if (usage.dayKey > today) {
                if (!auto) store.saveTamper("Stored usage is dated ahead of today's date")
                store.saveUsage(rule.target, BudgetUsage(usage.secondsUsed, today))
            }
        }

        store.saveClockAnchor(ClockAnchor(nowWall, nowElapsed, boot, nowZone))
    }

    /**
     * Whether the wall clock counts as OS-owned. Local time has **two** independent inputs on One UI
     * — the date and the zone — behind two separate Settings toggles, and the clock is only trusted
     * while the OS owns both.
     *
     * Watching only AUTO_TIME was the audit's top finding, and the reason it hid is worth keeping:
     * the drift check compares [EngineClock.wallClockMs] (UTC epoch) against monotonic uptime, and a
     * timezone change moves the UTC epoch by *exactly zero*. So the guard computed a drift of 0 and
     * reported everything normal, while [EngineClock.nowLocal] jumped the full offset, rolled the
     * logical day, and handed every target a fresh budget at once. The guard was watching the one
     * input that happened to prompt it, not every input that moves the quantity it protects.
     *
     * Recovery is unchanged and needs no key: turn both toggles back on and the latch clears on the
     * next tick.
     */
    private fun trustedClock(): Boolean =
        integrity.autoTimeEnabled() && integrity.autoTimeZoneEnabled()

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

    private fun advanceExceptions(rules: List<Rule>) {
        val now = clock.elapsedRealtimeMs()
        val today = DayBoundary.logicalDay(clock.nowLocal())
        for (rule in rules) {
            val st = store.loadException(rule.target)
            val next = ExceptionManager.tick(st, now, today)
            if (next != st) store.saveException(rule.target, next)
        }
    }

    /**
     * Bank foreground milliseconds for [currentTarget] since the last accrual. Whole seconds are
     * persisted; the sub-second remainder is carried per target in [carryMs], so neither heartbeat
     * ticks nor app switches ever truncate time away.
     */
    private fun accrueCurrent() {
        val nowElapsed = clock.elapsedRealtimeMs()
        val t = currentTarget
        if (t == null) {
            lastAccrualElapsedMs = nowElapsed
            return
        }
        val deltaMs = nowElapsed - lastAccrualElapsedMs
        lastAccrualElapsedMs = nowElapsed
        if (deltaMs <= 0L) return                 // clock stall: nothing to accrue
        val totalMs = deltaMs + (carryMs[t] ?: 0L)
        val seconds = totalMs / 1000L
        carryMs[t] = totalMs % 1000L
        if (seconds <= 0L) return
        val today = DayBoundary.logicalDay(clock.nowLocal())
        val previous = store.loadUsage(t)
        // The 4am rollover, caught at the one moment it is visible: the stored row is from an
        // earlier logical day and is about to be reset to zero by `accrue`. Archive it first, so the
        // week strip has a record. Display-only — nothing downstream reads it, and a target you
        // never opened simply has no entry for that day, which is the truth.
        if (previous != null && previous.dayKey < today) {
            store.recordHistory(t, DayUsage(previous.dayKey, previous.secondsUsed))
        }
        store.saveUsage(t, UsageTracker.accrue(previous, seconds, today))
    }

    /**
     * The strictest answer across every target the foreground answers to: **any block is a block.**
     *
     * Ordering is not cosmetic. A schedule block is reported ahead of a budget block because it is the
     * one the user cannot pay off — the schedule gate runs before the budget, so no exception reaches
     * it — and the block screen quotes the reported target's price. Reporting "you're out of reel
     * minutes" while the whole app is shut until 11:00 would name a smaller obstacle than the real
     * one, and this project has already shipped that bug once ([BlockFacts]).
     *
     * The decision is attributed to the target that actually blocked, so the countdown and the route
     * come from the rule doing the blocking rather than from whatever happened to be accruing.
     */
    private fun decideCurrent(rules: List<Rule>): Decision {
        val gates = gateTargets.ifEmpty { listOfNotNull(currentTarget) }
        if (gates.isEmpty()) return Decision(null, Access.ALLOW)
        val now = clock.nowLocal()
        val logicalDay = DayBoundary.logicalDay(now)
        val fallback = currentTarget ?: gates.first()

        var budgetBlock: Decision? = null
        for (t in gates) {
            val rule = rules.firstOrNull { it.target == t } ?: continue
            if (!PolicyEngine.scheduleAllows(rule.schedule, now)) {
                return Decision(t, Access.BLOCK, BlockReason.SCHEDULE)
            }
            val access = PolicyEngine.decide(rule, store.loadUsage(t), store.loadException(t), logicalDay)
            if (access == Access.BLOCK && budgetBlock == null) {
                val reason = if (rule.mode is RuleMode.HardBlock) BlockReason.HARD_BLOCK else BlockReason.BUDGET
                budgetBlock = Decision(t, Access.BLOCK, reason)
            }
        }
        return budgetBlock ?: Decision(fallback, Access.ALLOW)
    }

    private companion object {
        /** How far the wall clock may diverge from monotonic uptime between passes (doze slop etc.). */
        const val DRIFT_TOLERANCE_MS = 90_000L
    }
}
