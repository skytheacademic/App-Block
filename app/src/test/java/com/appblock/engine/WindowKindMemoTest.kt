package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which packages may be ignored by the moved-on release test.
 *
 * The two directions, and this project has now paid for both in one day:
 *  - **Too eager to ignore** → the block screen will not lift. Measured 2026-08-30: a `type !=
 *    TYPE_APPLICATION` test released **0 of 5** Home presses, because a pruned launcher window has no
 *    readable type while our own overlay is up.
 *  - **Too reluctant** → the volume panel drops the block for 40-80 ms on every press, a flicker with a
 *    trigger the user controls.
 *
 * Every test below is really asking the same question: can [WindowKindMemo.isSystemOnly] ever be true
 * for something the user actually moved to?
 */
class WindowKindMemoTest {

    private val APPLICATION = 1     // AccessibilityWindowInfo.TYPE_APPLICATION
    private val SYSTEM = 3          // AccessibilityWindowInfo.TYPE_SYSTEM

    private val systemui = "com.android.systemui"
    private val launcher = "com.sec.android.app.launcher"

    private fun memo() = WindowKindMemo()

    // ---- the defect it was written for ----

    /** BITE. The volume panel, exactly: seen once as system chrome, muted from then on. */
    @Test fun `a package seen only as a system window is system-only`() {
        val m = memo()
        m.note(systemui, SYSTEM, APPLICATION)
        assertTrue(m.isSystemOnly(systemui))
    }

    /** BITE. And that is what survives the unreadable events, which is the entire point. */
    @Test fun `an unreadable type after a system sighting does not un-learn it`() {
        val m = memo()
        m.note(systemui, SYSTEM, APPLICATION)
        m.note(systemui, null, APPLICATION)     // every volume press while the overlay is up
        assertTrue(m.isSystemOnly(systemui))
    }

    // ---- the safety property: never true for something the user moved to ----

    /** GUARD. The launcher is the exit. If this ever returns true the block screen cannot be left. */
    @Test fun `a package seen as an application window is never system-only`() {
        val m = memo()
        m.note(launcher, APPLICATION, APPLICATION)
        assertFalse(m.isSystemOnly(launcher))
    }

    /**
     * GUARD, and the one that matters most. Application evidence must outrank system evidence in BOTH
     * orders — a launcher sampled once as chrome and muted forever is the 0/5 lockout, rediscovered.
     */
    @Test fun `an application sighting clears an earlier system sighting`() {
        val m = memo()
        m.note(launcher, SYSTEM, APPLICATION)
        assertTrue(m.isSystemOnly(launcher))
        m.note(launcher, APPLICATION, APPLICATION)
        assertFalse(m.isSystemOnly(launcher))
    }

    /** GUARD. And it must not come back — later chrome sightings cannot re-mute a known application. */
    @Test fun `a later system sighting cannot re-mute a known application`() {
        val m = memo()
        m.note(launcher, APPLICATION, APPLICATION)
        m.note(launcher, SYSTEM, APPLICATION)
        m.note(launcher, SYSTEM, APPLICATION)
        assertFalse(m.isSystemOnly(launcher))
    }

    /** GUARD. A memo that has learned nothing must be exactly as safe as no memo at all. */
    @Test fun `an unknown package is not system-only`() {
        assertFalse(memo().isSystemOnly("com.example.never.seen"))
    }

    /** GUARD. Unreadable types teach nothing — guessing from them is how the 0/5 lockout happened. */
    @Test fun `unreadable types alone teach nothing`() {
        val m = memo()
        repeat(5) { m.note(launcher, null, APPLICATION) }
        assertFalse(m.isSystemOnly(launcher))
    }

    /** GUARD. Learning about one package says nothing about another. */
    @Test fun `system-only is per package`() {
        val m = memo()
        m.note(systemui, SYSTEM, APPLICATION)
        assertFalse(m.isSystemOnly(launcher))
    }

    /** GUARD. Window types other than APPLICATION all count as chrome — dividers, IMEs, overlays. */
    @Test fun `any non-application type counts as chrome`() {
        val m = memo()
        m.note("com.example.divider", 5, APPLICATION)    // TYPE_SPLIT_SCREEN_DIVIDER
        assertTrue(m.isSystemOnly("com.example.divider"))
    }

    // ---- saved across restarts (0.10.2) ----

    /** BITE. The reason for saving: a fresh memo restored from yesterday's knows the volume panel. */
    @Test fun `a restored memo already knows the volume panel`() {
        val before = memo().apply { note(systemui, SYSTEM, APPLICATION) }
        val after = memo().apply { restore(before.snapshot()) }
        assertTrue(after.isSystemOnly(systemui))
    }

    /**
     * GUARD. A saved system sighting must not mute a package this lifetime has seen as an application —
     * restore runs after connect, and events may already have been noted.
     */
    @Test fun `a saved system sighting cannot mute a live application`() {
        val saved = memo().apply { note(launcher, SYSTEM, APPLICATION) }.snapshot()
        val m = memo().apply { note(launcher, APPLICATION, APPLICATION) }
        m.restore(saved)
        assertFalse(m.isSystemOnly(launcher))
    }

    /** GUARD. And the other way: saved application evidence vetoes a live system sighting. */
    @Test fun `a saved application sighting vetoes a live system sighting`() {
        val saved = memo().apply { note(launcher, APPLICATION, APPLICATION) }.snapshot()
        val m = memo().apply { note(launcher, SYSTEM, APPLICATION) }
        m.restore(saved)
        assertFalse(m.isSystemOnly(launcher))
        m.note(launcher, SYSTEM, APPLICATION)
        assertFalse("and it stays vetoed after the restore", m.isSystemOnly(launcher))
    }

    /** GUARD. A snapshot that breaks the invariant (hand-edited, or older code) is repaired on load. */
    @Test fun `restore repairs a snapshot that lists a package in both sets`() {
        val m = memo()
        m.restore(WindowKindMemo.Snapshot(application = setOf(launcher), systemOnly = setOf(launcher, systemui)))
        assertFalse(m.isSystemOnly(launcher))
        assertTrue(m.isSystemOnly(systemui))
    }

    /** GUARD. An empty copy (first run, cleared data, unreadable prefs) restores to exactly a new memo. */
    @Test fun `restoring nothing changes nothing`() {
        val m = memo()
        m.restore(WindowKindMemo.Snapshot(emptySet(), emptySet()))
        assertFalse(m.isSystemOnly(systemui))
        assertEquals(WindowKindMemo.Snapshot(emptySet(), emptySet()), m.snapshot())
    }

    // ---- note() reports change, so saving stays off the per-event path ----

    @Test fun `note reports only what is new`() {
        val m = memo()
        assertTrue("first chrome sighting", m.note(systemui, SYSTEM, APPLICATION))
        assertFalse("same again", m.note(systemui, SYSTEM, APPLICATION))
        assertFalse("unreadable", m.note(systemui, null, APPLICATION))
        assertTrue("first application sighting", m.note(launcher, APPLICATION, APPLICATION))
        assertFalse("same again", m.note(launcher, APPLICATION, APPLICATION))
        assertFalse("chrome after application teaches nothing", m.note(launcher, SYSTEM, APPLICATION))
    }

    /** BITE. Promotion from system-only to application is a change, or it would never be saved. */
    @Test fun `promotion from chrome to application is reported`() {
        val m = memo()
        m.note(launcher, SYSTEM, APPLICATION)
        assertTrue(m.note(launcher, APPLICATION, APPLICATION))
        assertEquals(setOf(launcher), m.snapshot().application)
        assertEquals(emptySet<String>(), m.snapshot().systemOnly)
    }
}
