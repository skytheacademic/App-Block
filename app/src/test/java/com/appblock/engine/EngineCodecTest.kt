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
        val day = LocalDate.of(2026, 7, 24)
        val none: ExceptionState = ExceptionState.None
        val pending: ExceptionState = ExceptionState.Pending(Target.TIKTOK, 30, 120, 9_999L, day)
        val active: ExceptionState = ExceptionState.Active(Target.X, 25, 5_000L, day)
        assertEquals(none, EngineCodec.decodeException(EngineCodec.encodeException(none)))
        assertEquals(pending, EngineCodec.decodeException(EngineCodec.encodeException(pending)))
        assertEquals(active, EngineCodec.decodeException(EngineCodec.encodeException(active)))
    }

    @Test fun `malformed exception decodes to None`() {
        assertEquals(ExceptionState.None, EngineCodec.decodeException(null))
        assertEquals(ExceptionState.None, EngineCodec.decodeException(""))
        assertEquals(ExceptionState.None, EngineCodec.decodeException("pending|tiktok|30")) // too few fields
        assertEquals(ExceptionState.None, EngineCodec.decodeException("garbage"))
    }

    @Test fun `an exception naming a different target than it was stored under fails closed`() {
        // The key space is open since Batch 4, so a foreign key no longer fails to resolve by accident.
        // An exception is the one stored value that grants MORE access, so the mismatch has to be
        // caught explicitly — otherwise a corrupt blob hands out extra minutes.
        assertEquals(
            ExceptionState.None,
            EngineCodec.decodeException("active|nosuchtarget|10|5000|2026-07-24", expected = Target.TIKTOK),
        )
        assertEquals(
            ExceptionState.None,
            EngineCodec.decodeException("active|x|10|5000|2026-07-24", expected = Target.TIKTOK),
        )
    }

    @Test fun `an exception stored under its own target still decodes`() {
        val state = EngineCodec.decodeException("active|tiktok|10|5000|2026-07-24", expected = Target.TIKTOK)
        assertEquals(Target.TIKTOK, (state as ExceptionState.Active).target)
    }

    @Test fun `legacy day-less exception formats decode to None (stricter), not garbage`() {
        assertEquals(ExceptionState.None, EngineCodec.decodeException("pending|tiktok|30|120|9999"))
        assertEquals(ExceptionState.None, EngineCodec.decodeException("active|x|25|5000"))
    }

    @Test fun `history round-trips`() {
        val history = listOf(
            DayUsage(LocalDate.of(2026, 7, 22), 600L),
            DayUsage(LocalDate.of(2026, 7, 23), 1800L),
        )
        assertEquals(history, EngineCodec.decodeHistory(EngineCodec.encodeHistory(history)))
    }

    /**
     * History is display-only, so — uniquely in this codec — a damaged entry costs its own bar and
     * the rest of the week survives. Nothing it feeds can widen access, so leniency is safe here in
     * a way it deliberately is not for usage or exceptions.
     */
    @Test fun `a damaged history entry is dropped, not the whole week`() {
        val decoded = EngineCodec.decodeHistory("20291:600,rubbish,20292:1800")
        assertEquals(listOf(600L, 1800L), decoded.map { it.secondsUsed })
    }

    @Test fun `absent history decodes to nothing`() {
        assertEquals(emptyList<DayUsage>(), EngineCodec.decodeHistory(null))
        assertEquals(emptyList<DayUsage>(), EngineCodec.decodeHistory(""))
    }
}
