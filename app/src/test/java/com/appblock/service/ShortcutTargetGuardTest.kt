package com.appblock.service

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblock.engine.ShortcutTargetSweep
import com.appblock.engine.ShortcutTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The guard that keeps the floating accessibility button off the screen.
 *
 * The bug: `adb shell settings put secure accessibility_button_targets ""` cleared the button, and
 * the next restart brought it back as a tab on the edge of the screen. Android derives that setting
 * from "service enabled + requests the accessibility button" and rewrites it on every accessibility
 * config read, boot included, so a one-shot write can only ever hold until the next one.
 *
 * What is asserted here is therefore not "the setting can be cleared" but "clearing it is repeatable,
 * bounded, and costs nobody else their shortcut". The reboot itself is the one thing these tests
 * cannot reach — see the PR's phone checklist for that half.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShortcutTargetGuardTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val service = ComponentName(app, AppBlockerAccessibilityService::class.java)
    private val ours = service.flattenToString()
    private val other = "com.samsung.accessibility/.selectmenu.SelectMenuService"

    private var elapsed = 0L

    private fun guard() = ShortcutTargetGuard(app) { elapsed }

    private fun buttonTargets(): String? =
        Settings.Secure.getString(app.contentResolver, ShortcutTargets.BUTTON_TARGETS)

    private fun setButtonTargets(value: String?) {
        Settings.Secure.putString(app.contentResolver, ShortcutTargets.BUTTON_TARGETS, value)
    }

    private fun gestureTargets(): String? =
        Settings.Secure.getString(app.contentResolver, ShortcutTargets.GESTURE_TARGETS)

    private fun setGestureTargets(value: String?) {
        Settings.Secure.putString(app.contentResolver, ShortcutTargets.GESTURE_TARGETS, value)
    }

    private fun grant() =
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)

    private fun revoke() =
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)

    @Before
    fun setUp() {
        elapsed = 0L
        revoke()
        setButtonTargets(null)
        setGestureTargets(null)
    }

    /**
     * Without this line in the manifest, `pm grant` answers "has not requested permission" and the
     * whole fix is a no-op that looks installed. Declared config whose absence fails at the phone and
     * nowhere else, which is the same class of defect as the accessibility flags.
     */
    @Test
    fun `the permission is declared, or pm grant has nothing to grant`() {
        val requested = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toList()
        assertTrue(
            "WRITE_SECURE_SETTINGS missing from the manifest: `adb shell pm grant` will refuse it " +
                "and the floating button comes back at every restart with nothing able to stop it",
            requested.contains(Manifest.permission.WRITE_SECURE_SETTINGS),
        )
    }

    @Test
    fun `the claim is cleared when the grant is held`() {
        grant()
        setButtonTargets(ours)
        assertEquals(ShortcutTargetGuard.Outcome.CLEARED, guard().sweepNow())
        assertEquals("", buttonTargets())
    }

    @Test
    fun `without the grant nothing is written`() {
        setButtonTargets(ours)
        assertEquals(ShortcutTargetGuard.Outcome.NO_PERMISSION, guard().sweepNow())
        assertEquals(ours, buttonTargets())
    }

    /**
     * The missing grant is reported only where it would change something. A phone with no claim on it
     * is in the end state, and saying "no permission" about it would put a row on the Lock tab telling
     * the user to go and fix a button that is not there.
     */
    @Test
    fun `an unclaimed setting is already clear, grant or no grant`() {
        assertEquals(ShortcutTargetGuard.Outcome.ALREADY_CLEAR, guard().sweepNow())
        setButtonTargets(other)
        assertEquals(ShortcutTargetGuard.Outcome.ALREADY_CLEAR, guard().sweepNow())
        assertEquals(other, buttonTargets())
    }

    @Test
    fun `another accessibility tool keeps the button`() {
        grant()
        setButtonTargets("$other:$ours")
        assertEquals(ShortcutTargetGuard.Outcome.CLEARED, guard().sweepNow())
        assertEquals(other, buttonTargets())
    }

    /**
     * The restart, as near as a JVM test gets to it: Android writes the entry back, and the app takes
     * it out again. This is the whole fix — not that the setting can be cleared once, but that
     * clearing it is something that happens every time it is undone.
     */
    @Test
    fun `a target Android writes back is cleared again`() {
        grant()
        val guard = guard()
        repeat(3) {
            setButtonTargets(ours)
            assertEquals(ShortcutTargetGuard.Outcome.CLEARED, guard.sweepNow())
            assertEquals("", buttonTargets())
        }
    }

    /**
     * Our own write re-fires the ContentObserver, so every clear costs a second pass. That pass must
     * be free: it reads the value we just wrote, finds nothing of ours, and stops. If it were not,
     * one clear would spend two of the budget and the guard would give up in half the time.
     */
    @Test
    fun `the sweep our own write triggers is free`() {
        grant()
        val guard = guard()
        setButtonTargets(ours)
        assertEquals(ShortcutTargetGuard.Outcome.CLEARED, guard.sweepNow())
        repeat(20) { assertEquals(ShortcutTargetGuard.Outcome.ALREADY_CLEAR, guard.sweepNow()) }
        // All five clears still there: the re-entrant passes spent none of them.
        repeat(ShortcutTargetSweep.MAX_CLEARS_PER_WINDOW - 1) {
            setButtonTargets(ours)
            assertEquals(ShortcutTargetGuard.Outcome.CLEARED, guard.sweepNow())
        }
        setButtonTargets(ours)
        assertEquals(ShortcutTargetGuard.Outcome.BUDGET_SPENT, guard.sweepNow())
    }

    /**
     * The pathological case: something writes the target back as fast as we remove it. The guard
     * stands down rather than trading Secure writes forever, and the claim it leaves standing is what
     * the Lock tab then names.
     */
    @Test
    fun `a write-for-write fight stops at the budget and recovers an hour later`() {
        grant()
        val guard = guard()
        repeat(ShortcutTargetSweep.MAX_CLEARS_PER_WINDOW) {
            setButtonTargets(ours)
            assertEquals(ShortcutTargetGuard.Outcome.CLEARED, guard.sweepNow())
        }
        setButtonTargets(ours)
        assertEquals(ShortcutTargetGuard.Outcome.BUDGET_SPENT, guard.sweepNow())
        assertEquals(ours, buttonTargets())

        elapsed += ShortcutTargetSweep.WINDOW_MS
        assertEquals(ShortcutTargetGuard.Outcome.CLEARED, guard.sweepNow())
        assertEquals("", buttonTargets())
    }

    /**
     * The watchdog's and the Lock tab's channel. Its own budget resets every call, which is safe only
     * because what calls it is already rate-limited: a fifteen-minute worker and a screen somebody
     * opened. It is also the slow retry that keeps working after the service's guard has stood down.
     */
    @Test
    fun `the one-shot sweep clears without keeping any state`() {
        grant()
        setButtonTargets(ours)
        assertEquals(ShortcutTargetGuard.Outcome.CLEARED, ShortcutTargetGuard.sweepOnce(app))
        assertEquals("", buttonTargets())
        repeat(ShortcutTargetSweep.MAX_CLEARS_PER_WINDOW * 3) {
            setButtonTargets(ours)
            assertEquals(ShortcutTargetGuard.Outcome.CLEARED, ShortcutTargetGuard.sweepOnce(app))
        }
    }

    /** start() sweeps, so the boot path clears the target without waiting for an observer callback. */
    @Test
    fun `start clears what is already there`() {
        grant()
        setButtonTargets(ours)
        val guard = guard()
        guard.start()
        assertEquals("", buttonTargets())
        guard.stop()
    }

    /**
     * The gesture's list, which the S25 rewrote at both restarts on 2026-09-10 and filled with us
     * after the second. Invisible in button mode 1, one Settings change from live, and its Edit list
     * is the picker that used to switch detection off.
     */
    @Test
    fun `the gesture target is cleared too`() {
        grant()
        setGestureTargets(ours)
        assertEquals(ShortcutTargetGuard.Outcome.CLEARED, guard().sweepNow())
        assertEquals("", gestureTargets())
    }

    /** What the phone looked like after the second restart before the guard ran: both lists name us. */
    @Test
    fun `both claims go in one sweep`() {
        grant()
        setButtonTargets(ours)
        setGestureTargets(ours)
        assertEquals(ShortcutTargetGuard.Outcome.CLEARED, guard().sweepNow())
        assertEquals("", buttonTargets())
        assertEquals("", gestureTargets())
    }

    @Test
    fun `without the grant neither list is written`() {
        setButtonTargets(ours)
        setGestureTargets(ours)
        assertEquals(ShortcutTargetGuard.Outcome.NO_PERMISSION, guard().sweepNow())
        assertEquals(ours, buttonTargets())
        assertEquals(ours, gestureTargets())
    }

    @Test
    fun `another accessibility tool keeps the gesture`() {
        grant()
        setGestureTargets("$other:$ours")
        assertEquals(ShortcutTargetGuard.Outcome.CLEARED, guard().sweepNow())
        assertEquals(other, gestureTargets())
    }

    /**
     * One budget per list. If they shared one, something fighting over the button would spend the
     * clears the gesture needs, and the gesture claim would stand for the rest of the hour for no
     * reason of its own.
     */
    @Test
    fun `a fight over one list does not spend the other's budget`() {
        grant()
        val guard = guard()
        repeat(ShortcutTargetSweep.MAX_CLEARS_PER_WINDOW) {
            setButtonTargets(ours)
            guard.sweepNow()
        }
        setGestureTargets(ours)
        guard.sweepNow()
        assertEquals("", gestureTargets())
    }

    /**
     * The worse of the two outcomes is the one reported. A claim still standing on the button must
     * not be hidden by a clean clear of the gesture in the same pass.
     */
    @Test
    fun `a claim left standing is reported over a clear on the other list`() {
        grant()
        val guard = guard()
        repeat(ShortcutTargetSweep.MAX_CLEARS_PER_WINDOW) {
            setButtonTargets(ours)
            guard.sweepNow()
        }
        setButtonTargets(ours)
        setGestureTargets(ours)
        assertEquals(ShortcutTargetGuard.Outcome.BUDGET_SPENT, guard.sweepNow())
        assertEquals(ours, buttonTargets())
        assertEquals("", gestureTargets())
    }

    /**
     * The observer is the channel that catches a re-add after the sweep at connect, and it has to be
     * listening on both lists. A key the guard sweeps but does not watch would only be cleared by the
     * next connect or the next fifteen-minute pass.
     */
    @Test
    fun `start watches both lists`() {
        val guard = guard()
        guard.start()
        val resolver = shadowOf(app.contentResolver)
        for (key in ShortcutTargets.WRITE_KEYS) {
            assertTrue(
                "no observer on $key",
                resolver.getContentObservers(Settings.Secure.getUriFor(key)).isNotEmpty(),
            )
        }
        guard.stop()
        for (key in ShortcutTargets.WRITE_KEYS) {
            assertTrue(
                "observer left behind on $key",
                resolver.getContentObservers(Settings.Secure.getUriFor(key)).isEmpty(),
            )
        }
    }

    /** stop() twice, start() on a phone with nothing set: neither may throw out of the service. */
    @Test
    fun `the lifecycle calls are safe to repeat`() {
        val guard = guard()
        guard.start()
        guard.stop()
        guard.stop()
        assertNull(buttonTargets())
    }
}
