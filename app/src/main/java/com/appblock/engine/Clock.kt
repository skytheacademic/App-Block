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
 * Guards the daily reset against the "set the clock forward to force a reset" cheat. Between ticks the
 * wall clock should advance by about the same as monotonic uptime; a large positive divergence is a jump.
 */
class ClockTamperMonitor(private val toleranceMs: Long = 90_000L) {

    private var lastWallMs: Long? = null
    private var lastElapsedMs: Long? = null

    /** Feed each tick's (wall, elapsed); returns true if this looks like a forward wall-clock jump. */
    fun check(wallMs: Long, elapsedMs: Long): Boolean {
        val lw = lastWallMs
        val le = lastElapsedMs
        lastWallMs = wallMs
        lastElapsedMs = elapsedMs
        if (lw == null || le == null) return false
        val wallDelta = wallMs - lw
        val elapsedDelta = elapsedMs - le
        return wallDelta - elapsedDelta > toleranceMs
    }
}
