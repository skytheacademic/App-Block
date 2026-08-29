package com.appblock.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblock.engine.BudgetUsage
import com.appblock.engine.ClockAnchor
import com.appblock.engine.DayUsage
import com.appblock.engine.ExceptionState
import com.appblock.engine.FakeClock
import com.appblock.engine.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The SharedPreferences engine store had no tests of its own until the 2026-08-21 audit — every
 * fail-safe it promises (the reboot tripwire, the corrupt-usage flag, the anchor's nullable fields)
 * was exercised only through the in-memory store, which has none of them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PrefsEngineStoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(elapsed = 100_000L)
    private val day = LocalDate.of(2026, 7, 24)
    private val reddit = Target.forPackage("com.reddit.frontpage")

    private fun prefs() = app.getSharedPreferences("appblock_engine", Context.MODE_PRIVATE)
    private fun store() = PrefsEngineStore(app, clock)

    @Before fun clear() {
        prefs().edit().clear().commit()
    }

    // ---- usage ----

    @Test fun `usage round-trips`() {
        store().saveUsage(Target.TIKTOK, BudgetUsage(1234L, day))
        assertEquals(BudgetUsage(1234L, day), store().loadUsage(Target.TIKTOK))
        assertFalse(store().usageCorrupt(Target.TIKTOK))
    }

    @Test fun `absent usage is null and not corrupt`() {
        assertNull(store().loadUsage(Target.TIKTOK))
        assertFalse(store().usageCorrupt(Target.TIKTOK))
    }

    @Test fun `unreadable usage is reported corrupt, not read as zero`() {
        prefs().edit().putString("usage_tiktok", "garbage").commit()
        assertNull(store().loadUsage(Target.TIKTOK))
        assertTrue(store().usageCorrupt(Target.TIKTOK))
    }

    /** A count below zero can only have been edited in; it must burn the day like any other damage. */
    @Test fun `a negative usage count is corrupt`() {
        prefs().edit().putString("usage_tiktok", "-600|2026-07-24").commit()
        assertNull(store().loadUsage(Target.TIKTOK))
        assertTrue(store().usageCorrupt(Target.TIKTOK))
    }

    @Test fun `saving over corrupt usage clears the flag`() {
        prefs().edit().putString("usage_tiktok", "garbage").commit()
        store().saveUsage(Target.TIKTOK, BudgetUsage(3600L, day))
        assertFalse(store().usageCorrupt(Target.TIKTOK))
    }

    // ---- exceptions ----

    @Test fun `an exception round-trips within a boot`() {
        val pending = ExceptionState.Pending(Target.TIKTOK, 30, 120, 200_000L, day)
        store().saveException(Target.TIKTOK, pending)
        clock.elapsed = 150_000L
        assertEquals(pending, store().loadException(Target.TIKTOK))
    }

    /**
     * The secondary reboot tripwire: the monotonic reading at write time is stored beside the
     * exception, and a current reading *below* it means the device rebooted since — the anchors
     * are from another boot and the exception is dropped, not honoured.
     */
    @Test fun `an exception written before a reboot is dropped, not honoured`() {
        store().saveException(Target.TIKTOK, ExceptionState.Active(Target.TIKTOK, 30, 900_000L, day))
        clock.elapsed = 5_000L                                  // elapsedRealtime restarted
        assertEquals(ExceptionState.None, store().loadException(Target.TIKTOK))
        assertFalse("the stale blob is cleared, not just hidden", prefs().contains("exc_tiktok"))
    }

    @Test fun `saving None clears the exception and its tripwire`() {
        store().saveException(Target.TIKTOK, ExceptionState.Pending(Target.TIKTOK, 30, 120, 200_000L, day))
        store().saveException(Target.TIKTOK, ExceptionState.None)
        assertFalse(prefs().contains("exc_tiktok"))
        assertFalse(prefs().contains("exc_elapsed_tiktok"))
    }

    @Test fun `clearExceptions drops every target's exception and nothing else`() {
        val s = store()
        s.saveException(Target.TIKTOK, ExceptionState.Pending(Target.TIKTOK, 30, 120, 200_000L, day))
        s.saveException(reddit, ExceptionState.Active(reddit, 15, 900_000L, day))
        s.saveUsage(Target.TIKTOK, BudgetUsage(42L, day))
        s.saveTamper("latched")
        s.clearExceptions()
        assertEquals(ExceptionState.None, s.loadException(Target.TIKTOK))
        assertEquals(ExceptionState.None, s.loadException(reddit))
        assertTrue(prefs().all.keys.none { it.startsWith("exc_") })
        assertEquals(BudgetUsage(42L, day), s.loadUsage(Target.TIKTOK))
        assertEquals("latched", s.loadTamper())
    }

    // ---- the clock anchor ----

    @Test fun `the anchor round-trips with every field`() {
        val anchor = ClockAnchor(
            wallMs = 1_700_000_000_000L,
            elapsedMs = 123_456L,
            bootCount = 7,
            zoneOffsetSeconds = -4 * 3600,
            dayKey = day,
            dayEndsElapsedMs = 65_000_000L,
        )
        store().saveClockAnchor(anchor)
        assertEquals(anchor, store().loadClockAnchor())
    }

    @Test fun `no anchor reads as null`() {
        assertNull(store().loadClockAnchor())
    }

    /** What an install from before the zone guard and the day model has on disk. */
    @Test fun `a legacy anchor reads its missing fields as null, never as invented values`() {
        prefs().edit()
            .putLong("anchor_wall", 10L)
            .putLong("anchor_elapsed", 20L)
            .putInt("anchor_boot", 3)
            .commit()
        val anchor = store().loadClockAnchor()!!
        assertEquals(3, anchor.bootCount)
        assertNull(anchor.zoneOffsetSeconds)
        assertNull(anchor.dayKey)
        assertNull(anchor.dayEndsElapsedMs)
    }

    @Test fun `a day without its end, or an unparseable day, is no model at all`() {
        store().saveClockAnchor(ClockAnchor(10L, 20L, 3, 0))
        prefs().edit().putString("anchor_day", "2026-07-24").commit()      // no anchor_day_ends
        assertNull(store().loadClockAnchor()!!.dayKey)
        assertNull(store().loadClockAnchor()!!.dayEndsElapsedMs)

        prefs().edit().putString("anchor_day", "not-a-date").putLong("anchor_day_ends_elapsed", 5L).commit()
        assertNull(store().loadClockAnchor()!!.dayKey)
        assertNull(store().loadClockAnchor()!!.dayEndsElapsedMs)
    }

    @Test fun `saving an anchor without a model removes a stale one`() {
        store().saveClockAnchor(ClockAnchor(10L, 20L, 3, 0, day, 99L))
        store().saveClockAnchor(ClockAnchor(10L, 20L, 3, 0))
        assertNull(store().loadClockAnchor()!!.dayKey)
        assertFalse(prefs().contains("anchor_day_ends_elapsed"))
    }

    // ---- the tamper latch ----

    @Test fun `the latch is clear by default, holds a reason, and clears again`() {
        assertNull(store().loadTamper())
        store().saveTamper("Date or time changed while the clock was not fully automatic")
        assertEquals("Date or time changed while the clock was not fully automatic", store().loadTamper())
        store().saveTamper(null)
        assertNull(store().loadTamper())
        assertFalse(prefs().contains("tamper_reason"))
    }

    // ---- history ----

    @Test fun `history round-trips and re-archiving a day replaces it`() {
        val s = store()
        s.recordHistory(Target.TIKTOK, DayUsage(day.minusDays(1), 600L))
        s.recordHistory(Target.TIKTOK, DayUsage(day, 1200L))
        s.recordHistory(Target.TIKTOK, DayUsage(day, 1500L))
        assertEquals(listOf(DayUsage(day.minusDays(1), 600L), DayUsage(day, 1500L)), s.loadHistory(Target.TIKTOK))
    }

    @Test fun `unreadable history reads as empty, never as a reason to block`() {
        prefs().edit().putString("history_tiktok", "nonsense").commit()
        assertEquals(emptyList<DayUsage>(), store().loadHistory(Target.TIKTOK))
    }
}
