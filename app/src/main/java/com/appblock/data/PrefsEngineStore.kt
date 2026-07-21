package com.appblock.data

import android.content.Context
import com.appblock.engine.BudgetUsage
import com.appblock.engine.EngineClock
import com.appblock.engine.EngineCodec
import com.appblock.engine.EngineStore
import com.appblock.engine.ExceptionState
import com.appblock.engine.Target

/**
 * SharedPreferences-backed [EngineStore]. The service and the UI both run in this app's single
 * process, so a plain MODE_PRIVATE prefs file is a shared, thread-safe source of truth for both.
 *
 * Usage survives anything (it's keyed by wall date — a reboot mid-day keeps the right count).
 *
 * Exceptions are anchored to the **monotonic** clock, which resets to ~0 on reboot. So each saved
 * exception also records the monotonic reading at write time; if the current reading is *less* than
 * that, the device rebooted since — the stored anchors are meaningless, so we drop the exception and
 * return [ExceptionState.None]. Dropping an in-flight exception reverts to the normal (stricter) cap,
 * which is the correct fail-safe for a commitment device. See CONSTRAINTS.md §5.
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
    }
}
