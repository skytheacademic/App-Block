package com.appblock.engine

import java.time.LocalDateTime

/**
 * Time source for the engine. [elapsedRealtimeMs] is monotonic uptime (used for the exception wait —
 * clock-proof); [nowLocal] / [wallClockMs] are wall-clock (needed for the 4am day boundary). The
 * Android impl wraps SystemClock.elapsedRealtime() + LocalDateTime.now() and is added at integration.
 */
interface EngineClock {
    fun elapsedRealtimeMs(): Long
    fun nowLocal(): LocalDateTime
    fun wallClockMs(): Long

    /**
     * The current UTC offset in seconds — the missing third clock signal.
     *
     * [wallClockMs] is UTC epoch millis, so it does **not** move when the timezone changes, while
     * [nowLocal] moves by the full offset. That split is the whole timezone bypass: the drift check
     * compares wall against monotonic and sees a difference of zero, while the day boundary reads
     * local time, rolls to a new logical day, and hands every target a fresh budget. Exposing the
     * offset lets the guard watch the signal that actually moved.
     */
    fun zoneOffsetSeconds(): Int
}

/**
 * Platform signals the tamper guard needs beyond the raw clocks (see BudgetCoordinator.guardClocks):
 * whether the wall clock is OS-synced (trusted) or user-set (the "change the date to reset the
 * budget" attack surface), and how many times the device has booted (detects reboots even though
 * elapsedRealtime restarts at 0). The Android impl reads Settings.Global; tests use a fake.
 */
interface ClockIntegrity {
    /** True when the OS syncs date & time automatically (Settings.Global.AUTO_TIME == 1). */
    fun autoTimeEnabled(): Boolean

    /**
     * True when the OS picks the timezone automatically (Settings.Global.AUTO_TIME_ZONE == 1).
     *
     * One UI splits "Set time automatically" and "Set time zone automatically" into two independent
     * toggles, and the guard originally knew only the first. Leaving the second unwatched meant the
     * whole of local time could be moved ~20 hours with the tamper guard still reporting a trusted
     * clock. Both must be on for the wall clock to count as OS-owned.
     */
    fun autoTimeZoneEnabled(): Boolean

    /** Boots since factory reset (Settings.Global.BOOT_COUNT) — strictly increases on every reboot. */
    fun bootCount(): Int
}
