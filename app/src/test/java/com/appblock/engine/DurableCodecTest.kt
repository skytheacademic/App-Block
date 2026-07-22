package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurableCodecTest {

    private val sample = DurableSettings(
        version = 3,
        targets = mapOf(
            Target.TIKTOK to TargetSettings(enabled = true, weekdayMinutes = 30, weekendMinutes = 30, exceptionMaxMinutes = 60),
            Target.INSTAGRAM_REELS_EXPLORE to TargetSettings(enabled = false, weekdayMinutes = 10, weekendMinutes = 10, exceptionMaxMinutes = 30),
            Target.X to TargetSettings(enabled = true, weekdayMinutes = 15, weekendMinutes = 20, exceptionMaxMinutes = 40),
        ),
        exceptionWindowMinutes = 90,
    )

    @Test fun `durable settings round-trip through the codec`() {
        assertEquals(sample, EngineCodec.decodeDurable(EngineCodec.encodeDurable(sample)))
    }

    @Test fun `malformed or blank durable strings decode to null so the store re-seeds`() {
        assertNull(EngineCodec.decodeDurable(null))
        assertNull(EngineCodec.decodeDurable(""))
        assertNull(EngineCodec.decodeDurable("garbage"))
        assertNull(EngineCodec.decodeDurable("durable1|notanint|60"))
        assertNull(EngineCodec.decodeDurable("durable1|1|60|tiktok,1,30,30")) // target field short
        assertNull(EngineCodec.decodeDurable("wrongtag|1|60"))
    }

    @Test fun `unknown target keys are skipped, not fatal`() {
        val decoded = EngineCodec.decodeDurable("durable1|1|60|tiktok,1,30,30,60,|ghost,1,5,5,5,")
        assertEquals(setOf(Target.TIKTOK), decoded!!.targets.keys)
    }

    @Test fun `a legacy 5-field entry decodes with no schedule`() {
        val decoded = EngineCodec.decodeDurable("durable1|2|60|tiktok,1,30,30,60")!!
        assertNull(decoded.targets[Target.TIKTOK]!!.schedule)
    }

    @Test fun `durable settings with a schedule round-trip`() {
        val scheduled = sample.copy(
            targets = sample.targets + (
                Target.TIKTOK to sample.targets[Target.TIKTOK]!!.copy(
                    schedule = Schedule(
                        mapOf(
                            java.time.DayOfWeek.MONDAY to listOf(TimeWindow(18 * 60, 20 * 60)),
                            java.time.DayOfWeek.SATURDAY to listOf(TimeWindow(0, 6 * 60), TimeWindow(20 * 60, 1440)),
                        ),
                    ),
                )
                ),
        )
        assertEquals(scheduled, EngineCodec.decodeDurable(EngineCodec.encodeDurable(scheduled)))
    }

    @Test fun `a fully-blocked schedule is distinct from null`() {
        val blocked = sample.copy(
            targets = sample.targets + (Target.X to sample.targets[Target.X]!!.copy(schedule = Schedule(emptyMap()))),
        )
        val decoded = EngineCodec.decodeDurable(EngineCodec.encodeDurable(blocked))!!
        assertEquals(Schedule(emptyMap()), decoded.targets[Target.X]!!.schedule)
    }

    @Test fun `unlock state round-trips and fails closed on garbage`() {
        val pending = DurableUnlockState.Pending(activeAtElapsedMs = 1000L, windowEndElapsedMs = 2000L, bootCount = 7)
        assertEquals(pending, EngineCodec.decodeUnlock(EngineCodec.encodeUnlock(pending)))
        val open = DurableUnlockState.Open(windowEndElapsedMs = 2000L, bootCount = 7)
        assertEquals(open, EngineCodec.decodeUnlock(EngineCodec.encodeUnlock(open)))
        assertEquals(DurableUnlockState.Locked, EngineCodec.decodeUnlock(EngineCodec.encodeUnlock(DurableUnlockState.Locked)))
        assertEquals(DurableUnlockState.Locked, EngineCodec.decodeUnlock(null))
        assertEquals(DurableUnlockState.Locked, EngineCodec.decodeUnlock("pending|1000|2000")) // too short
        assertEquals(DurableUnlockState.Locked, EngineCodec.decodeUnlock("garbage"))
    }

    @Test fun `key hash round-trips and rejects malformed`() {
        val kh = KeyHash(salt = "abc123", hash = "deadbeef")
        assertEquals(kh, EngineCodec.decodeKeyHash(EngineCodec.encodeKeyHash(kh)))
        assertNull(EngineCodec.decodeKeyHash(null))
        assertNull(EngineCodec.decodeKeyHash("onlyonepart"))
        assertNull(EngineCodec.decodeKeyHash("|"))
    }
}
