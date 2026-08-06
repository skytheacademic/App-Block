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

    // ---- history ----

    /**
     * The rolling history: `epochDay:seconds` entries, comma-separated, oldest first. Epoch days
     * rather than ISO dates because this string is rewritten on every rollover and stays short.
     */
    fun encodeHistory(history: List<DayUsage>): String =
        history.joinToString(",") { "${it.day.toEpochDay()}:${it.secondsUsed}" }

    /**
     * Decodes leniently — the one place in this file where leniency is right. History is
     * display-only, so a damaged entry should cost its own bar rather than the whole week, and a
     * decode failure here must never be able to look like a reason to block.
     */
    fun decodeHistory(raw: String?): List<DayUsage> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',').mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size != 2) return@mapNotNull null
            val epochDay = parts[0].toLongOrNull() ?: return@mapNotNull null
            val seconds = parts[1].toLongOrNull() ?: return@mapNotNull null
            val day = runCatching { LocalDate.ofEpochDay(epochDay) }.getOrNull() ?: return@mapNotNull null
            DayUsage(day, seconds.coerceAtLeast(0L))
        }
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

    /**
     * [expected] is the target this blob was stored under; a decoded state naming any other target is
     * discarded as None.
     *
     * That check used to be implicit: the target key space was closed, so a corrupt or foreign key
     * simply failed to resolve. Opening the key space (Batch 4) removed that accident — every string
     * now names *some* target — and an exception is the one piece of stored state that grants **more**
     * access, so silently trusting a mismatched key would fail open. The store's contract is the
     * opposite: untrustworthy state must resolve toward more blocking. Hence the explicit check.
     *
     * Pass null only where no particular target is expected (round-trip tests).
     */
    fun decodeException(raw: String?, expected: Target? = null): ExceptionState {
        val state = decodeExceptionUnchecked(raw)
        val target = when (state) {
            is ExceptionState.None -> return ExceptionState.None
            is ExceptionState.Pending -> state.target
            is ExceptionState.Active -> state.target
        }
        return if (expected == null || target == expected) state else ExceptionState.None
    }

    private fun decodeExceptionUnchecked(raw: String?): ExceptionState {
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
    // where each `<t>` is `key,enabled(0|1),weekday,weekend,exceptionMax,schedule`. The schedule field
    // (added v2) is empty for no schedule; otherwise `@<day>~<day>...` with each day
    // `<dow(1..7)>:<win>/<win>` and each win `<startMin>-<endMin>` — no commas/pipes, so it rides
    // safely inside the comma/pipe delimiters. A 7th field (added v5) is `scheduleOnly` as 0|1 — the
    // target carries closing hours and no cap, so its three cap columns are 0 and unread. Shorter
    // entries are still accepted: 6 fields = no scheduleOnly column, 5 = no schedule column either.
    // Any malformed value decodes to null so the store re-seeds from source (strict — a lost config
    // falls back to defaults, not to "no rules"). Since Batch 4 the target set is open, so any
    // non-blank key names a real target — a stored `pkg:` entry is a user-added app, not an unknown.

    fun encodeDurable(settings: DurableSettings): String {
        val head = "durable1|${settings.version}|${settings.exceptionWindowMinutes}"
        // Map order, not a fixed enum order. A key that couldn't survive the delimiters is dropped
        // rather than corrupting the record around it.
        val targets = settings.targets.entries.mapNotNull { (target, s) ->
            if (!Target.isEncodableKey(target.key)) return@mapNotNull null
            "${target.key},${if (s.enabled) 1 else 0},${s.weekdayMinutes},${s.weekendMinutes}," +
                "${s.exceptionMaxMinutes},${encodeSchedule(s.schedule)},${if (s.scheduleOnly) 1 else 0}"
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
            if (f.size !in 5..7) return null
            val target = targetForKey(f[0]) ?: continue           // unknown key: skip, don't fail
            val enabled = when (f[1]) { "1" -> true; "0" -> false; else -> return null }
            val wd = f[2].toIntOrNull() ?: return null
            val we = f[3].toIntOrNull() ?: return null
            val max = f[4].toIntOrNull() ?: return null
            val schedule = if (f.size >= 6) {
                val res = decodeScheduleField(f[5])
                if (res.isFailure) return null
                res.getOrNull()
            } else {
                null
            }
            // Absent (a pre-v5 string) = false = a budgeted target, which is what every entry
            // written before this field existed was. Fails toward *more* limiting: a schedule-only
            // entry misread as budgeted would keep its schedule and gain caps of 0 — blocked, not open.
            val scheduleOnly = when (f.getOrNull(6)) {
                null, "0" -> false
                "1" -> true
                else -> return null
            }
            targets[target] = TargetSettings(enabled, wd, we, max, schedule, scheduleOnly)
        }
        return DurableSettings(version, targets, window)
    }

    private fun encodeSchedule(schedule: Schedule?): String {
        if (schedule == null) return ""
        // Leading "@" keeps a non-null but fully-blocked schedule distinct from the empty (null) field.
        val body = schedule.allowedByDay.entries
            .filter { it.value.isNotEmpty() }
            .sortedBy { it.key.value }
            .joinToString("~") { (day, windows) ->
                "${day.value}:" + windows.joinToString("/") { "${it.startMinuteOfDay}-${it.endMinuteOfDay}" }
            }
        return "@$body"
    }

    private fun decodeScheduleField(field: String): Result<Schedule?> = runCatching {
        if (field.isEmpty()) return@runCatching null
        require(field.startsWith("@"))
        val body = field.substring(1)
        if (body.isEmpty()) return@runCatching Schedule(emptyMap())
        val map = body.split("~").associate { dayPart ->
            val colon = dayPart.indexOf(':')
            require(colon > 0)
            val day = java.time.DayOfWeek.of(dayPart.substring(0, colon).toInt())
            val windows = dayPart.substring(colon + 1).split("/").map { win ->
                val dash = win.indexOf('-')
                require(dash > 0)
                TimeWindow(win.substring(0, dash).toInt(), win.substring(dash + 1).toInt())
            }
            day to windows
        }
        Schedule(map)
    }

    // ---- DurableUnlockState (the delayed, single-use change window) ----
    //
    // Formats: `locked` · `pending|activeAt|windowEnd|bootCount|category` ·
    // `open|windowEnd|bootCount|category`. The category key is the trailing field (added with the
    // per-category waits); the older, shorter forms without it still decode — as APPS, the only
    // category that existed when they were written. Anything malformed (including an unknown
    // category) decodes to Locked — a lost unlock state must fail *closed* (no change window).

    fun encodeUnlock(state: DurableUnlockState): String = when (state) {
        is DurableUnlockState.Locked -> "locked"
        is DurableUnlockState.Pending ->
            "pending|${state.activeAtElapsedMs}|${state.windowEndElapsedMs}|${state.bootCount}|${state.category.key}"
        is DurableUnlockState.Open ->
            "open|${state.windowEndElapsedMs}|${state.bootCount}|${state.category.key}"
    }

    fun decodeUnlock(raw: String?): DurableUnlockState {
        if (raw.isNullOrBlank()) return DurableUnlockState.Locked
        val parts = raw.split('|')
        return when (parts[0]) {
            "pending" -> {
                if (parts.size != 4 && parts.size != 5) return DurableUnlockState.Locked
                val activeAt = parts[1].toLongOrNull() ?: return DurableUnlockState.Locked
                val windowEnd = parts[2].toLongOrNull() ?: return DurableUnlockState.Locked
                val boot = parts[3].toIntOrNull() ?: return DurableUnlockState.Locked
                val category = unlockCategory(parts.getOrNull(4)) ?: return DurableUnlockState.Locked
                DurableUnlockState.Pending(activeAt, windowEnd, boot, category)
            }
            "open" -> {
                if (parts.size != 3 && parts.size != 4) return DurableUnlockState.Locked
                val windowEnd = parts[1].toLongOrNull() ?: return DurableUnlockState.Locked
                val boot = parts[2].toIntOrNull() ?: return DurableUnlockState.Locked
                val category = unlockCategory(parts.getOrNull(3)) ?: return DurableUnlockState.Locked
                DurableUnlockState.Open(windowEnd, boot, category)
            }
            else -> DurableUnlockState.Locked
        }
    }

    /** Absent field = a pre-category string = APPS; a present-but-unknown key = null (fail closed). */
    private fun unlockCategory(field: String?): UnlockCategory? =
        if (field == null) UnlockCategory.APPS else UnlockCategory.forKey(field)

    // ---- KeyHash (the durable-change unlock verifier) ----

    fun encodeKeyHash(keyHash: KeyHash): String = "${keyHash.salt}|${keyHash.hash}"

    fun decodeKeyHash(raw: String?): KeyHash? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('|')
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return KeyHash(parts[0], parts[1])
    }

    /** Any non-blank key names a target now the set is open; blank keys are malformed and drop out. */
    private fun targetForKey(key: String): Target? = if (key.isBlank()) null else Target(key)
}
