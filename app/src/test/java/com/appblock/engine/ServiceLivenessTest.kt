package com.appblock.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Liveness has two failure directions and they pull against each other:
 *  - **false negative** → the protection list says "nothing is blocked" while blocking works, and the
 *    watchdog nags forever. That is the direction most of these tests guard.
 *  - **false positive** → a genuinely dead service reads as alive and the watchdog goes quiet exactly
 *    when it is needed. Guarded by the teardown tests, which must keep passing.
 *
 * ⚠️ These tests were written in response to a hardware report on 2026-08-04 that was **withdrawn the
 * same day as a measurement artefact** — `uiautomator dump` had been destroying the service it was being
 * used to observe. The race below has therefore **never been observed on this device**; it is guarded
 * because Android's contract permits it, not because it happened. See [ServiceLiveness] for the full
 * account, and do not let these tests be read as evidence that it did.
 *
 * Instances are modelled as bare [Any] objects because identity is the only property under test.
 */
class ServiceLivenessTest {

    private val a = Any()
    private val b = Any()

    @Test
    fun `nothing has connected, so nothing is running`() {
        assertFalse(ServiceLiveness().isRunning)
    }

    @Test
    fun `a connected instance is running`() {
        val liveness = ServiceLiveness()
        liveness.connected(a)
        assertTrue(liveness.isRunning)
    }

    @Test
    fun `the live instance tearing itself down retires the claim`() {
        val liveness = ServiceLiveness()
        liveness.connected(a)
        liveness.destroyed(a)
        assertFalse("a real teardown must still read as dead", liveness.isRunning)
    }

    /**
     * **The regression test.** The interleaving Android permits but this device was never seen to
     * produce: the replacement is connected *before* the outgoing instance is destroyed, so with a
     * shared boolean the corpse's `onDestroy` would write `false` last and nothing would ever set it
     * true again.
     */
    @Test
    fun `a replacement connecting before the old instance dies stays running`() {
        val liveness = ServiceLiveness()
        liveness.connected(a)
        liveness.connected(b)
        liveness.destroyed(a)
        assertTrue(
            "B is connected and serving; A is a corpse and must not be able to retract B's claim",
            liveness.isRunning,
        )
    }

    /** The same rule stated from the other side: being superseded is enough to lose the right to retract. */
    @Test
    fun `a superseded instance cannot retire the claim even long after the handover`() {
        val liveness = ServiceLiveness()
        liveness.connected(a)
        liveness.connected(b)
        repeat(5) { liveness.destroyed(a) }
        assertTrue(liveness.isRunning)
    }

    /** And the live one still can, so the fix does not cost the real teardown it is built on. */
    @Test
    fun `after a handover the new instance is the one that can retire the claim`() {
        val liveness = ServiceLiveness()
        liveness.connected(a)
        liveness.connected(b)
        liveness.destroyed(a)
        liveness.destroyed(b)
        assertFalse(liveness.isRunning)
    }

    /** A full stop then a fresh start — the ordinary reconnect — must come back to life. */
    @Test
    fun `a fresh connect after a complete teardown is running again`() {
        val liveness = ServiceLiveness()
        liveness.connected(a)
        liveness.destroyed(a)
        liveness.connected(b)
        assertTrue(liveness.isRunning)
    }
}
