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

    /** Advance both monotonic + wall clocks together by [ms] (the untampered case). */
    fun advance(ms: Long) {
        elapsed += ms
        wall += ms
    }
}
