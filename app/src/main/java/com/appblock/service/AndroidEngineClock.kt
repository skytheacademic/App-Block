package com.appblock.service

import android.os.SystemClock
import com.appblock.engine.EngineClock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The real device clock. [elapsedRealtimeMs] is monotonic uptime (survives doze, immune to the user
 * changing the clock — used for the exception wait); [nowLocal] / [wallClockMs] are wall-clock, used
 * for the 4am day boundary and weekday/weekend. See engine/Clock.kt.
 */
class AndroidEngineClock : EngineClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun nowLocal(): LocalDateTime = LocalDateTime.now()
    override fun wallClockMs(): Long = System.currentTimeMillis()

    /**
     * Read from the default zone's rules rather than cached, because changing the timezone is exactly
     * the event this exists to catch — `ZoneId.systemDefault()` re-resolves per call. Covers DST
     * transitions too, which move the offset without any user action.
     */
    override fun zoneOffsetSeconds(): Int =
        ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds
}
