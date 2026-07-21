package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class EngineCodecTest {

    @Test fun `usage round-trips`() {
        val usage = BudgetUsage(secondsUsed = 1234L, dayKey = LocalDate.of(2026, 7, 24))
        assertEquals(usage, EngineCodec.decodeUsage(EngineCodec.encodeUsage(usage)))
    }

    @Test fun `malformed usage decodes to null`() {
        assertNull(EngineCodec.decodeUsage(null))
        assertNull(EngineCodec.decodeUsage(""))
        assertNull(EngineCodec.decodeUsage("nope"))
        assertNull(EngineCodec.decodeUsage("12|not-a-date"))
        assertNull(EngineCodec.decodeUsage("x|2026-07-24"))
    }

    @Test fun `exception states round-trip`() {
        val none: ExceptionState = ExceptionState.None
        val pending: ExceptionState = ExceptionState.Pending(Target.TIKTOK, 30, 120, 9_999L)
        val active: ExceptionState = ExceptionState.Active(Target.X, 25, 5_000L)
        assertEquals(none, EngineCodec.decodeException(EngineCodec.encodeException(none)))
        assertEquals(pending, EngineCodec.decodeException(EngineCodec.encodeException(pending)))
        assertEquals(active, EngineCodec.decodeException(EngineCodec.encodeException(active)))
    }

    @Test fun `malformed exception decodes to None`() {
        assertEquals(ExceptionState.None, EngineCodec.decodeException(null))
        assertEquals(ExceptionState.None, EngineCodec.decodeException(""))
        assertEquals(ExceptionState.None, EngineCodec.decodeException("pending|tiktok|30")) // too few fields
        assertEquals(ExceptionState.None, EngineCodec.decodeException("active|nosuchtarget|1|2"))
        assertEquals(ExceptionState.None, EngineCodec.decodeException("garbage"))
    }
}
