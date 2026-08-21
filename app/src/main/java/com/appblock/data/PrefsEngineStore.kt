package com.appblock.data

import android.content.Context
import com.appblock.engine.BudgetUsage
import com.appblock.engine.ClockAnchor
import com.appblock.engine.DayUsage
import com.appblock.engine.EngineClock
import com.appblock.engine.EngineCodec
import com.appblock.engine.EngineStore
import com.appblock.engine.ExceptionState
import com.appblock.engine.Target
import com.appblock.engine.UsageTracker
import java.time.LocalDate

/**
 * SharedPreferences-backed [EngineStore]. The service and the UI both run in this app's single
 * process, so a plain MODE_PRIVATE prefs file is a shared, thread-safe source of truth for both.
 *
 * Usage survives anything (it's keyed by wall date — a reboot mid-day keeps the right count). A
 * stored usage value that no longer decodes is reported via [usageCorrupt] so the coordinator can
 * burn that target's day instead of silently granting a fresh budget.
 *
 * Exceptions are anchored to the **monotonic** clock, which resets to ~0 on reboot. The primary
 * reboot detector is the coordinator's boot-count check (see BudgetCoordinator.guardClocks); this
 * class keeps a second, cruder tripwire — each saved exception records the monotonic reading at write
 * time, and if the current reading is *less* than that, the device rebooted since, the anchors are
 * meaningless, and the exception is dropped. Dropping reverts to the normal (stricter) cap, the
 * correct fail-safe for a commitment device. See CONSTRAINTS.md §5.
 */
class PrefsEngineStore(
    context: Context,
    private val clock: EngineClock,
) : EngineStore {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun loadUsage(target: Target): BudgetUsage? =
        EngineCodec.decodeUsage(prefs.getString(usageKey(target), null))

    override fun saveUsage(target: Target, usage: BudgetUsage) {
        prefs.edit().putString(usageKey(target), EngineCodec.encodeUsage(usage)).apply()
    }

    override fun usageCorrupt(target: Target): Boolean {
        val raw = prefs.getString(usageKey(target), null) ?: return false
        return EngineCodec.decodeUsage(raw) == null
    }

    override fun loadException(target: Target): ExceptionState {
        val raw = prefs.getString(excKey(target), null) ?: return ExceptionState.None
        val writtenAtElapsed = prefs.getLong(excElapsedKey(target), Long.MAX_VALUE)
        if (clock.elapsedRealtimeMs() < writtenAtElapsed) {
            // Monotonic clock went backwards ⇒ reboot since the write ⇒ anchors are stale. Fail safe.
            clearException(target)
            return ExceptionState.None
        }
        // Pass the target we asked for: a blob naming a different one is not this target's exception,
        // and an exception only ever grants extra minutes, so a mismatch must fail closed.
        return EngineCodec.decodeException(raw, expected = target)
    }

    override fun saveException(target: Target, state: ExceptionState) {
        if (state is ExceptionState.None) {
            clearException(target)
            return
        }
        prefs.edit()
            .putString(excKey(target), EngineCodec.encodeException(state))
            .putLong(excElapsedKey(target), clock.elapsedRealtimeMs())
            .apply()
    }

    override fun clearExceptions() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(EXC_PREFIX) || it.startsWith(EXC_ELAPSED_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    override fun loadClockAnchor(): ClockAnchor? {
        if (!prefs.contains(KEY_ANCHOR_BOOT)) return null
        // The day model is read as a pair: a day without its end (or an unparseable day) is no model
        // at all, and the coordinator then starts one from the wall clock — the same rule as the
        // zone below, for the same reason: a half-invented value would be acted on.
        val day = prefs.getString(KEY_ANCHOR_DAY, null)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.takeIf { prefs.contains(KEY_ANCHOR_DAY_ENDS) }
        return ClockAnchor(
            wallMs = prefs.getLong(KEY_ANCHOR_WALL, 0L),
            elapsedMs = prefs.getLong(KEY_ANCHOR_ELAPSED, 0L),
            bootCount = prefs.getInt(KEY_ANCHOR_BOOT, 0),
            // Absent on anchors written before the timezone guard shipped. Read as null, never 0 —
            // a fabricated 0 would look like a whole-offset jump and latch the tamper flag on the
            // first pass after the update for anyone not on UTC.
            zoneOffsetSeconds = if (prefs.contains(KEY_ANCHOR_ZONE)) prefs.getInt(KEY_ANCHOR_ZONE, 0) else null,
            dayKey = day,
            dayEndsElapsedMs = if (day != null) prefs.getLong(KEY_ANCHOR_DAY_ENDS, 0L) else null,
        )
    }

    override fun saveClockAnchor(anchor: ClockAnchor) {
        prefs.edit()
            .putLong(KEY_ANCHOR_WALL, anchor.wallMs)
            .putLong(KEY_ANCHOR_ELAPSED, anchor.elapsedMs)
            .putInt(KEY_ANCHOR_BOOT, anchor.bootCount)
            .apply {
                val zone = anchor.zoneOffsetSeconds
                if (zone == null) remove(KEY_ANCHOR_ZONE) else putInt(KEY_ANCHOR_ZONE, zone)
                val day = anchor.dayKey
                val ends = anchor.dayEndsElapsedMs
                if (day == null || ends == null) {
                    remove(KEY_ANCHOR_DAY)
                    remove(KEY_ANCHOR_DAY_ENDS)
                } else {
                    putString(KEY_ANCHOR_DAY, day.toString())
                    putLong(KEY_ANCHOR_DAY_ENDS, ends)
                }
            }
            .apply()
    }

    override fun loadTamper(): String? = prefs.getString(KEY_TAMPER, null)

    override fun saveTamper(reason: String?) {
        if (reason == loadTamper()) return        // avoid a write per tick in the steady state
        prefs.edit().apply {
            if (reason == null) remove(KEY_TAMPER) else putString(KEY_TAMPER, reason)
        }.apply()
    }

    override fun loadHistory(target: Target): List<DayUsage> =
        EngineCodec.decodeHistory(prefs.getString(historyKey(target), null))

    override fun recordHistory(target: Target, completed: DayUsage) {
        val next = UsageTracker.archive(loadHistory(target), completed)
        prefs.edit().putString(historyKey(target), EngineCodec.encodeHistory(next)).apply()
    }

    private fun clearException(target: Target) {
        prefs.edit()
            .remove(excKey(target))
            .remove(excElapsedKey(target))
            .apply()
    }

    private fun usageKey(target: Target) = "usage_${target.key}"
    private fun excKey(target: Target) = "$EXC_PREFIX${target.key}"
    private fun excElapsedKey(target: Target) = "$EXC_ELAPSED_PREFIX${target.key}"
    private fun historyKey(target: Target) = "history_${target.key}"

    companion object {
        private const val PREFS = "appblock_engine"
        private const val KEY_ANCHOR_WALL = "anchor_wall"
        private const val KEY_ANCHOR_ELAPSED = "anchor_elapsed"
        private const val KEY_ANCHOR_BOOT = "anchor_boot"
        private const val KEY_ANCHOR_ZONE = "anchor_zone_offset"
        private const val KEY_ANCHOR_DAY = "anchor_day"
        private const val KEY_ANCHOR_DAY_ENDS = "anchor_day_ends_elapsed"
        private const val KEY_TAMPER = "tamper_reason"
        // `exc_elapsed_` starts with `exc_` too, so the first prefix alone would already catch both
        // on clear; both are named so a future key can't be caught by accident.
        private const val EXC_PREFIX = "exc_"
        private const val EXC_ELAPSED_PREFIX = "exc_elapsed_"
    }
}
