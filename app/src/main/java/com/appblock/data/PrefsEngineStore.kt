package com.appblock.data

import android.content.Context
import com.appblock.engine.BudgetUsage
import com.appblock.engine.ClockAnchor
import com.appblock.engine.EngineClock
import com.appblock.engine.EngineCodec
import com.appblock.engine.EngineStore
import com.appblock.engine.ExceptionState
import com.appblock.engine.Target

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
        return EngineCodec.decodeException(raw)
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

    override fun loadClockAnchor(): ClockAnchor? {
        if (!prefs.contains(KEY_ANCHOR_BOOT)) return null
        return ClockAnchor(
            wallMs = prefs.getLong(KEY_ANCHOR_WALL, 0L),
            elapsedMs = prefs.getLong(KEY_ANCHOR_ELAPSED, 0L),
            bootCount = prefs.getInt(KEY_ANCHOR_BOOT, 0),
        )
    }

    override fun saveClockAnchor(anchor: ClockAnchor) {
        prefs.edit()
            .putLong(KEY_ANCHOR_WALL, anchor.wallMs)
            .putLong(KEY_ANCHOR_ELAPSED, anchor.elapsedMs)
            .putInt(KEY_ANCHOR_BOOT, anchor.bootCount)
            .apply()
    }

    override fun loadTamper(): String? = prefs.getString(KEY_TAMPER, null)

    override fun saveTamper(reason: String?) {
        if (reason == loadTamper()) return        // avoid a write per tick in the steady state
        prefs.edit().apply {
            if (reason == null) remove(KEY_TAMPER) else putString(KEY_TAMPER, reason)
        }.apply()
    }

    private fun clearException(target: Target) {
        prefs.edit()
            .remove(excKey(target))
            .remove(excElapsedKey(target))
            .apply()
    }

    private fun usageKey(target: Target) = "usage_${target.key}"
    private fun excKey(target: Target) = "exc_${target.key}"
    private fun excElapsedKey(target: Target) = "exc_elapsed_${target.key}"

    companion object {
        private const val PREFS = "appblock_engine"
        private const val KEY_ANCHOR_WALL = "anchor_wall"
        private const val KEY_ANCHOR_ELAPSED = "anchor_elapsed"
        private const val KEY_ANCHOR_BOOT = "anchor_boot"
        private const val KEY_TAMPER = "tamper_reason"
    }
}
