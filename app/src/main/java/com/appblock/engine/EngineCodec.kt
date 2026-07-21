package com.appblock.engine

import java.time.LocalDate

/**
 * Pure string (de)serialization for the persisted state, so [com.appblock.data.PrefsEngineStore] can
 * stash each value as one SharedPreferences string and this logic stays unit-testable off-device.
 *
 * Formats (pipe-delimited, versionless — the field count disambiguates):
 *  - usage:     `secondsUsed|dayKey(ISO-8601)`
 *  - exception: `none`
 *               `pending|target|extraMinutes|windowMinutes|activeAtElapsedMs|dayKey`
 *               `active|target|extraMinutes|windowEndElapsedMs|dayKey`
 *
 * Malformed exceptions decode to [ExceptionState.None] (strict — a lost exception just reverts to the
 * normal cap). Malformed *usage* decodes to null here, but the store reports it via
 * [EngineStore.usageCorrupt] and the coordinator burns that target's day — decode failure must never
 * turn into a fresh budget.
 */
object EngineCodec {

    // ---- BudgetUsage ----

    fun encodeUsage(usage: BudgetUsage): String =
        "${usage.secondsUsed}|${usage.dayKey}"

    fun decodeUsage(raw: String?): BudgetUsage? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('|')
        if (parts.size != 2) return null
        val seconds = parts[0].toLongOrNull() ?: return null
        val day = runCatching { LocalDate.parse(parts[1]) }.getOrNull() ?: return null
        return BudgetUsage(secondsUsed = seconds.coerceAtLeast(0L), dayKey = day)
    }

    // ---- ExceptionState ----

    fun encodeException(state: ExceptionState): String =
        when (state) {
            is ExceptionState.None -> "none"
            is ExceptionState.Pending ->
                "pending|${state.target.key}|${state.extraMinutes}|${state.windowMinutes}|" +
                    "${state.activeAtElapsedMs}|${state.dayKey}"
            is ExceptionState.Active ->
                "active|${state.target.key}|${state.extraMinutes}|${state.windowEndElapsedMs}|${state.dayKey}"
        }

    fun decodeException(raw: String?): ExceptionState {
        if (raw.isNullOrBlank()) return ExceptionState.None
        val parts = raw.split('|')
        return when (parts[0]) {
            "pending" -> {
                if (parts.size != 6) return ExceptionState.None
                val target = targetForKey(parts[1]) ?: return ExceptionState.None
                val extra = parts[2].toIntOrNull() ?: return ExceptionState.None
                val window = parts[3].toIntOrNull() ?: return ExceptionState.None
                val activeAt = parts[4].toLongOrNull() ?: return ExceptionState.None
                val day = runCatching { LocalDate.parse(parts[5]) }.getOrNull() ?: return ExceptionState.None
                ExceptionState.Pending(target, extra, window, activeAt, day)
            }
            "active" -> {
                if (parts.size != 5) return ExceptionState.None
                val target = targetForKey(parts[1]) ?: return ExceptionState.None
                val extra = parts[2].toIntOrNull() ?: return ExceptionState.None
                val windowEnd = parts[3].toLongOrNull() ?: return ExceptionState.None
                val day = runCatching { LocalDate.parse(parts[4]) }.getOrNull() ?: return ExceptionState.None
                ExceptionState.Active(target, extra, windowEnd, day)
            }
            else -> ExceptionState.None
        }
    }

    private fun targetForKey(key: String): Target? = Target.entries.firstOrNull { it.key == key }
}
