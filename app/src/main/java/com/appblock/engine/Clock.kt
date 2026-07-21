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

    /** Boots since factory reset (Settings.Global.BOOT_COUNT) — strictly increases on every reboot. */
    fun bootCount(): Int
}
