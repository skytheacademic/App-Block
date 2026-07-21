package com.appblock.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTamperMonitorTest {

    @Test fun `normal ticks are not flagged`() {
        val m = ClockTamperMonitor()
        assertFalse(m.check(wallMs = 1_000_000L, elapsedMs = 500_000L)) // first tick primes
        assertFalse(m.check(wallMs = 1_060_000L, elapsedMs = 560_000L)) // +60s both → fine
    }

    @Test fun `a forward wall-clock jump is flagged`() {
        val m = ClockTamperMonitor()
        m.check(wallMs = 1_000_000L, elapsedMs = 500_000L)
        // wall jumps +1 day while uptime barely moves → jump
        assertTrue(m.check(wallMs = 1_000_000L + 86_400_000L, elapsedMs = 500_500L))
    }
}
