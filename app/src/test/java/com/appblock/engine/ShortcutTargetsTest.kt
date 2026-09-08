package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading and the rewriting of `accessibility_button_targets`, which is the setting that decides
 * whether the floating accessibility button is on screen pointing at App-Block.
 *
 * Two behaviours are worth stating up front, because both were defects rather than choices:
 *
 *  1. **The short flattening.** `pkg/.service.Foo` and `pkg/com.example.service.Foo` are the same
 *     component written two ways, and Samsung writes both. The read this replaces compared against
 *     `ComponentName.getClassName()` with `contains`, which sees the long one and misses the short
 *     one — a false negative on the row whose only job is to say the button is there.
 *  2. **Other people's entries.** The clear is a removal of one claim, not a reset of the setting. A
 *     real accessibility tool sharing the button has to survive it, or hardening this blocker costs
 *     somebody their magnifier.
 */
class ShortcutTargetsTest {

    private val pkg = "com.appblock"
    private val cls = "com.appblock.service.AppBlockerAccessibilityService"
    private val longForm = "$pkg/$cls"
    private val shortForm = "$pkg/.service.AppBlockerAccessibilityService"
    private val other = "com.samsung.accessibility/.selectmenu.SelectMenuService"

    @Test
    fun `the long flattening is our claim`() {
        assertTrue(ShortcutTargets.claims(longForm, pkg, cls))
    }

    @Test
    fun `the short flattening is our claim too`() {
        assertTrue(ShortcutTargets.claims(shortForm, pkg, cls))
    }

    @Test
    fun `case is not part of the comparison`() {
        assertTrue(ShortcutTargets.claims(longForm.uppercase(), pkg, cls))
    }

    @Test
    fun `another accessibility service is not us`() {
        assertFalse(ShortcutTargets.claims(other, pkg, cls))
    }

    @Test
    fun `an absent setting claims nothing`() {
        assertFalse(ShortcutTargets.claims(null, pkg, cls))
        assertFalse(ShortcutTargets.claims("", pkg, cls))
    }

    /**
     * The debug and debugFast variants install as `com.appblock.debug` / `.fast` while every class in
     * them keeps the `com.appblock.*` name, so the package half of the entry and the package half of
     * the class name genuinely differ. A matcher that assumed the class lived under the applicationId
     * would clear nothing on exactly the builds a device session uses.
     */
    @Test
    fun `a suffixed applicationId still matches its own service`() {
        val debugPkg = "com.appblock.debug"
        assertTrue(ShortcutTargets.claims("$debugPkg/$cls", debugPkg, cls))
    }

    @Test
    fun `clearing our only entry empties the setting`() {
        assertEquals("", ShortcutTargets.withoutSelf(longForm, pkg, cls))
    }

    @Test
    fun `clearing keeps every other target`() {
        assertEquals(other, ShortcutTargets.withoutSelf("$other:$longForm", pkg, cls))
        assertEquals(other, ShortcutTargets.withoutSelf("$longForm:$other", pkg, cls))
    }

    @Test
    fun `both flattenings of us are removed at once`() {
        assertEquals(
            other,
            ShortcutTargets.withoutSelf("$shortForm:$other:$longForm", pkg, cls),
        )
    }

    /** Null means "no write needed", which is what stops the guard touching Secure settings at all. */
    @Test
    fun `a setting without us asks for no write`() {
        assertNull(ShortcutTargets.withoutSelf(other, pkg, cls))
        assertNull(ShortcutTargets.withoutSelf(null, pkg, cls))
        assertNull(ShortcutTargets.withoutSelf("", pkg, cls))
    }

    /**
     * The framework's own targets are bare class names with no slash in them. They are not components,
     * they are not ours, and a parse that got confused by them would be rewriting the shortcut list of
     * a magnifier somebody depends on.
     */
    @Test
    fun `the framework's own magnification target is left alone`() {
        val magnification = "com.android.server.accessibility.MagnificationController"
        assertFalse(ShortcutTargets.claims(magnification, pkg, cls))
        assertEquals(
            magnification,
            ShortcutTargets.withoutSelf("$magnification:$longForm", pkg, cls),
        )
    }

    @Test
    fun `empty segments and stray whitespace survive the round trip`() {
        assertEquals(other, ShortcutTargets.withoutSelf(":  $longForm : $other :", pkg, cls))
    }

    /** The chord is read, never swept. Stated here so the day it changes, this test says so. */
    @Test
    fun `only the button target is a written key`() {
        assertEquals(
            listOf("accessibility_button_targets", "accessibility_shortcut_target_service"),
            ShortcutTargets.READ_KEYS,
        )
        assertEquals("accessibility_button_targets", ShortcutTargets.BUTTON_TARGETS)
    }
}

/**
 * The budget, which exists for a loop nobody has yet watched close.
 *
 * We remove ourselves from the setting; the framework may notice the setting changed, re-read its
 * accessibility configuration, decide an enabled service requesting the accessibility button belongs
 * in the list, and write it back. If One UI does that, an unbounded guard and the system write a
 * Secure setting at each other for as long as the phone is on. Whether it does is unverified and
 * unverifiable without the phone, so the budget is written as though it does.
 */
class ShortcutTargetSweepTest {

    private val pkg = "com.appblock"
    private val cls = "com.appblock.service.AppBlockerAccessibilityService"
    private val claimed = "$pkg/$cls"

    private fun sweep() = ShortcutTargetSweep()

    private fun decide(s: ShortcutTargetSweep, at: Long, value: String? = claimed) =
        s.decide(at, value, pkg, cls)

    @Test
    fun `a claim is cleared and the cleared value is what to write`() {
        val decision = decide(sweep(), 0L)
        assertEquals(ShortcutTargetSweep.Decision.Clear(""), decision)
    }

    @Test
    fun `nothing of ours spends nothing`() {
        val s = sweep()
        repeat(50) { assertEquals(ShortcutTargetSweep.Decision.AlreadyClear, decide(s, 0L, "")) }
        // The budget is untouched, so a real claim arriving after the quiet spell is still answered.
        assertEquals(ShortcutTargetSweep.Decision.Clear(""), decide(s, 0L))
    }

    @Test
    fun `five clears an hour, and then the claim stands`() {
        val s = sweep()
        repeat(ShortcutTargetSweep.MAX_CLEARS_PER_WINDOW) {
            assertEquals(ShortcutTargetSweep.Decision.Clear(""), decide(s, it * 1_000L))
        }
        assertEquals(ShortcutTargetSweep.Decision.Spent, decide(s, 6_000L))
    }

    @Test
    fun `the budget comes back with the next window`() {
        val s = sweep()
        repeat(ShortcutTargetSweep.MAX_CLEARS_PER_WINDOW) { decide(s, 0L) }
        assertEquals(ShortcutTargetSweep.Decision.Spent, decide(s, ShortcutTargetSweep.WINDOW_MS - 1))
        assertEquals(
            ShortcutTargetSweep.Decision.Clear(""),
            decide(s, ShortcutTargetSweep.WINDOW_MS),
        )
    }

    /**
     * `elapsedRealtime()` restarts at zero on a boot, and a boot is precisely when the target comes
     * back. A window opened at 9,000,000 with the counter now reading 400 is not a window at all, and
     * treating it as one would hold the guard down for an hour on the one pass that matters most.
     */
    @Test
    fun `a clock that restarts at boot does not hold the budget down`() {
        val s = sweep()
        repeat(ShortcutTargetSweep.MAX_CLEARS_PER_WINDOW) { decide(s, 9_000_000L) }
        assertEquals(ShortcutTargetSweep.Decision.Spent, decide(s, 9_000_001L))
        assertEquals(ShortcutTargetSweep.Decision.Clear(""), decide(s, 400L))
    }

    @Test
    fun `a spent budget is spent per window, not per lifetime`() {
        val s = sweep()
        repeat(3) { window ->
            val base = window * ShortcutTargetSweep.WINDOW_MS
            repeat(ShortcutTargetSweep.MAX_CLEARS_PER_WINDOW) {
                assertEquals(ShortcutTargetSweep.Decision.Clear(""), decide(s, base + it))
            }
            assertEquals(ShortcutTargetSweep.Decision.Spent, decide(s, base + 100))
        }
    }

    /** Other targets survive the clear on the way through the budget, too. */
    @Test
    fun `the value it hands back is the one with everybody else still in it`() {
        val other = "com.samsung.accessibility/.selectmenu.SelectMenuService"
        assertEquals(
            ShortcutTargetSweep.Decision.Clear(other),
            decide(sweep(), 0L, "$claimed:$other"),
        )
    }
}
