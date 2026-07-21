package com.appblock.engine

import java.time.LocalDateTime

/** A hand-driven [EngineClock] for tests: set the fields, call the coordinator, assert. */
class FakeClock(
    var elapsed: Long = 0L,
    var local: LocalDateTime = LocalDateTime.of(2026, 7, 24, 10, 0), // Fri (weekday), after 4am
    var wall: Long = 0L,
) : EngineClock {
    override fun elapsedRealtimeMs(): Long = elapsed
    override fun nowLocal(): LocalDateTime = local
    override fun wallClockMs(): Long = wall

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
}

/** A hand-driven [ClockIntegrity]: flip [autoTime] / bump [boot] to simulate Settings changes. */
class FakeIntegrity(
    var autoTime: Boolean = true,
    var boot: Int = 1,
) : ClockIntegrity {
    override fun autoTimeEnabled(): Boolean = autoTime
    override fun bootCount(): Int = boot
}
