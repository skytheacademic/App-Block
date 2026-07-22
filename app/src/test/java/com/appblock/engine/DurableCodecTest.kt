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
        val decoded = EngineCodec.decodeDurable("durable1|1|60|tiktok,1,30,30,60|ghost,1,5,5,5")
        assertEquals(setOf(Target.TIKTOK), decoded!!.targets.keys)
    }

    @Test fun `key hash round-trips and rejects malformed`() {
        val kh = KeyHash(salt = "abc123", hash = "deadbeef")
        assertEquals(kh, EngineCodec.decodeKeyHash(EngineCodec.encodeKeyHash(kh)))
        assertNull(EngineCodec.decodeKeyHash(null))
        assertNull(EngineCodec.decodeKeyHash("onlyonepart"))
        assertNull(EngineCodec.decodeKeyHash("|"))
    }
}
