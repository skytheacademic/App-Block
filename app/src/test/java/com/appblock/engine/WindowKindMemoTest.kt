package com.appblock.engine

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
}
