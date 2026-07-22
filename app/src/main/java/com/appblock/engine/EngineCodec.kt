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

    // ---- DurableSettings ----
    //
    // Format (tagged, pipe-delimited): `durable1|<version>|<window>|<t>|<t>...`
    // where each `<t>` is `key,enabled(0|1),weekday,weekend,exceptionMax`. Any malformed value decodes
    // to null so the store can re-seed from source (strict — a lost config falls back to defaults, not
    // to "no rules"). Unknown target keys are skipped.

    fun encodeDurable(settings: DurableSettings): String {
        val head = "durable1|${settings.version}|${settings.exceptionWindowMinutes}"
        val targets = Target.entries.mapNotNull { target ->
            settings.targets[target]?.let { s ->
                "${target.key},${if (s.enabled) 1 else 0},${s.weekdayMinutes},${s.weekendMinutes},${s.exceptionMaxMinutes}"
            }
        }
        return (listOf(head) + targets).joinToString("|")
    }

    fun decodeDurable(raw: String?): DurableSettings? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('|')
        if (parts.size < 3 || parts[0] != "durable1") return null
        val version = parts[1].toIntOrNull() ?: return null
        val window = parts[2].toIntOrNull() ?: return null
        val targets = mutableMapOf<Target, TargetSettings>()
        for (i in 3 until parts.size) {
            val f = parts[i].split(',')
            if (f.size != 5) return null
            val target = targetForKey(f[0]) ?: continue           // unknown key: skip, don't fail
            val enabled = when (f[1]) { "1" -> true; "0" -> false; else -> return null }
            val wd = f[2].toIntOrNull() ?: return null
            val we = f[3].toIntOrNull() ?: return null
            val max = f[4].toIntOrNull() ?: return null
            targets[target] = TargetSettings(enabled, wd, we, max)
        }
        return DurableSettings(version, targets, window)
    }

    // ---- DurableUnlockState (the 2-hour, single-use change window) ----
    //
    // Formats: `locked` · `pending|activeAt|windowEnd|bootCount` · `open|windowEnd|bootCount`.
    // Anything malformed decodes to Locked — a lost unlock state must fail *closed* (no change window).

    fun encodeUnlock(state: DurableUnlockState): String = when (state) {
        is DurableUnlockState.Locked -> "locked"
        is DurableUnlockState.Pending ->
            "pending|${state.activeAtElapsedMs}|${state.windowEndElapsedMs}|${state.bootCount}"
        is DurableUnlockState.Open ->
            "open|${state.windowEndElapsedMs}|${state.bootCount}"
    }

    fun decodeUnlock(raw: String?): DurableUnlockState {
        if (raw.isNullOrBlank()) return DurableUnlockState.Locked
        val parts = raw.split('|')
        return when (parts[0]) {
            "pending" -> {
                if (parts.size != 4) return DurableUnlockState.Locked
                val activeAt = parts[1].toLongOrNull() ?: return DurableUnlockState.Locked
                val windowEnd = parts[2].toLongOrNull() ?: return DurableUnlockState.Locked
                val boot = parts[3].toIntOrNull() ?: return DurableUnlockState.Locked
                DurableUnlockState.Pending(activeAt, windowEnd, boot)
            }
            "open" -> {
                if (parts.size != 3) return DurableUnlockState.Locked
                val windowEnd = parts[1].toLongOrNull() ?: return DurableUnlockState.Locked
                val boot = parts[2].toIntOrNull() ?: return DurableUnlockState.Locked
                DurableUnlockState.Open(windowEnd, boot)
            }
            else -> DurableUnlockState.Locked
        }
    }

    // ---- KeyHash (the durable-change unlock verifier) ----

    fun encodeKeyHash(keyHash: KeyHash): String = "${keyHash.salt}|${keyHash.hash}"

    fun decodeKeyHash(raw: String?): KeyHash? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('|')
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return KeyHash(parts[0], parts[1])
    }

    private fun targetForKey(key: String): Target? = Target.entries.firstOrNull { it.key == key }
}
