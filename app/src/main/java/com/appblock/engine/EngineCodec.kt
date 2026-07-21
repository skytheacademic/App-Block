package com.appblock.engine

import java.time.LocalDate

/**
 * Pure string (de)serialization for the persisted state, so [com.appblock.data.PrefsEngineStore] can
 * stash each value as one SharedPreferences string and this logic stays unit-testable off-device.
 *
 * Formats (pipe-delimited, versionless — the field count disambiguates):
 *  - usage:     `secondsUsed|dayKey(ISO-8601)`
 *  - exception: `none`
 *               `pending|target|extraMinutes|windowMinutes|activeAtElapsedMs`
 *               `active|target|extraMinutes|windowEndElapsedMs`
 *
 * Anything malformed decodes to null / [ExceptionState.None] (fail safe — never crash the service,
 * never invent a laxer state).
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
                "pending|${state.target.key}|${state.extraMinutes}|${state.windowMinutes}|${state.activeAtElapsedMs}"
            is ExceptionState.Active ->
                "active|${state.target.key}|${state.extraMinutes}|${state.windowEndElapsedMs}"
        }

    fun decodeException(raw: String?): ExceptionState {
        if (raw.isNullOrBlank()) return ExceptionState.None
        val parts = raw.split('|')
        return when (parts[0]) {
            "pending" -> {
                if (parts.size != 5) return ExceptionState.None
                val target = targetForKey(parts[1]) ?: return ExceptionState.None
                val extra = parts[2].toIntOrNull() ?: return ExceptionState.None
                val window = parts[3].toIntOrNull() ?: return ExceptionState.None
                val activeAt = parts[4].toLongOrNull() ?: return ExceptionState.None
                ExceptionState.Pending(target, extra, window, activeAt)
            }
            "active" -> {
                if (parts.size != 4) return ExceptionState.None
                val target = targetForKey(parts[1]) ?: return ExceptionState.None
                val extra = parts[2].toIntOrNull() ?: return ExceptionState.None
                val windowEnd = parts[3].toLongOrNull() ?: return ExceptionState.None
                ExceptionState.Active(target, extra, windowEnd)
            }
            else -> ExceptionState.None
        }
    }

    private fun targetForKey(key: String): Target? = Target.entries.firstOrNull { it.key == key }
}
