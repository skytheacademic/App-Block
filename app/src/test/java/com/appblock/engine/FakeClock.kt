package com.appblock.engine

import java.time.LocalDateTime

/** A hand-driven [EngineClock] for tests: set the fields, call the coordinator, assert. */
class FakeClock(
    var elapsed: Long = 0L,
    var local: LocalDateTime = LocalDateTime.of(2026, 7, 24, 10, 0), // Fri (weekday), after 4am
    var wall: Long = 0L,
    var zoneOffset: Int = -4 * 3600,   // a real, non-zero offset — 0 would hide sign/default bugs
) : EngineClock {
    override fun elapsedRealtimeMs(): Long = elapsed
    override fun nowLocal(): LocalDateTime = local
    override fun wallClockMs(): Long = wall
    override fun zoneOffsetSeconds(): Int = zoneOffset

    /**
     * Advance both monotonic + wall clocks together by [ms] (the untampered case). Note [local] does
     * NOT move — tests near the 4am boundary set it explicitly.
     */
    fun advance(ms: Long) {
        elapsed += ms
        wall += ms
    }

    /**
     * Wall + local jump by [ms] (may be negative) without monotonic time passing — the "user set the
     * date/time by hand" case the tamper guard must catch.
     */
    fun jumpWall(ms: Long) {
        wall += ms
        local = local.plusNanos(ms * 1_000_000L)
    }

    /** Monotonic-only advance (wall frozen) — the other direction of clock decoupling. */
    fun advanceElapsedOnly(ms: Long) {
        elapsed += ms
    }

    /**
     * Pick a different timezone: local time moves by the offset delta, the UTC epoch does **not**.
     * That asymmetry is the whole bypass — a helper that moved [wall] too would quietly test the
     * date-change case again and prove nothing about zones.
     */
    fun changeZone(newOffsetSeconds: Int) {
        val deltaSeconds = newOffsetSeconds - zoneOffset
        zoneOffset = newOffsetSeconds
        local = local.plusSeconds(deltaSeconds.toLong())
    }
}

/** A hand-driven [ClockIntegrity]: flip the toggles / bump [boot] to simulate Settings changes. */
class FakeIntegrity(
    var autoTime: Boolean = true,
    var boot: Int = 1,
    var autoTimeZone: Boolean = true,
) : ClockIntegrity {
    override fun autoTimeEnabled(): Boolean = autoTime
    override fun autoTimeZoneEnabled(): Boolean = autoTimeZone
    override fun bootCount(): Int = boot
}
