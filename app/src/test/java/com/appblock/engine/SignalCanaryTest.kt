package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The drift detector for Instagram's reel-player resource-id.
 *
 * The whole design problem is here: absence of a sighting means nothing on its own, because the user
 * who never opens Reels is the user this app is *working* for. So every test below is really asking
 * the same question — does this fire on a broken id, and stay quiet on a successful user?
 */
class SignalCanaryTest {

    private val day = 24L * 60 * 60 * 1_000
    private val grace = SignalCanary.DEFAULT_GRACE_MS

    private val v300 = 300_000_000L
    private val v301 = 301_000_000L

    private fun assess(witness: SignalCanary.Witness, atDay: Long) =
        SignalCanary.assess(witness, nowMs = atDay * day, graceMs = grace)

    @Test fun `says nothing at all when Instagram is not installed`() {
        assertEquals(SignalCanary.Health.NO_APP, assess(SignalCanary.Witness(), atDay = 0))
        // Not even after a long time — there is no rule to verify.
        assertEquals(SignalCanary.Health.NO_APP, assess(SignalCanary.Witness(), atDay = 365))
    }

    @Test fun `a sighting confirms the version it was seen on`() {
        val seen = SignalCanary.confirm(SignalCanary.Witness(), v300, nowMs = 0)
        assertEquals(v300, seen.confirmedVersion)
        assertEquals(SignalCanary.Health.CONFIRMED, assess(seen, atDay = 200))
    }

    /**
     * The point of anchoring to the version rather than to elapsed time: once confirmed, the user can
     * go a year without opening Reels and never be prompted. Never watching reels is success, not a
     * fault, and a canary that can't tell those apart is worse than none.
     */
    @Test fun `never prompts a user who simply stopped watching reels`() {
        var witness = SignalCanary.confirm(SignalCanary.Witness(), v300, nowMs = 0)
        repeat(365) { day -> witness = SignalCanary.observe(witness, v300, nowMs = day * this.day) }
        assertEquals(SignalCanary.Health.CONFIRMED, assess(witness, atDay = 365))
    }

    // ---- an update invalidates the confirmation ----

    @Test fun `an update starts the grace period, not a prompt`() {
        val confirmed = SignalCanary.confirm(SignalCanary.Witness(), v300, nowMs = 0)
        val updated = SignalCanary.observe(confirmed, v301, nowMs = 10 * day)
        assertEquals(SignalCanary.Health.PENDING, assess(updated, atDay = 10))
        assertEquals(SignalCanary.Health.PENDING, assess(updated, atDay = 23))
    }

    @Test fun `prompts once the grace period runs out with no new sighting`() {
        val confirmed = SignalCanary.confirm(SignalCanary.Witness(), v300, nowMs = 0)
        val updated = SignalCanary.observe(confirmed, v301, nowMs = 10 * day)
        assertEquals(SignalCanary.Health.STALE, assess(updated, atDay = 24))
    }

    /** Opening Reels once re-confirms and the prompt goes away — the block itself is the proof. */
    @Test fun `a fresh sighting clears it`() {
        val confirmed = SignalCanary.confirm(SignalCanary.Witness(), v300, nowMs = 0)
        val updated = SignalCanary.observe(confirmed, v301, nowMs = 10 * day)
        assertEquals(SignalCanary.Health.STALE, assess(updated, atDay = 30))

        val reconfirmed = SignalCanary.confirm(updated, v301, nowMs = 30 * day)
        assertEquals(SignalCanary.Health.CONFIRMED, assess(reconfirmed, atDay = 30))
        assertEquals(30 * day, reconfirmed.confirmedAtMs)
    }

    /**
     * observe() runs on every pass, so it must be idempotent about the deadline. If each call reset
     * the grace clock the canary could never reach STALE — it would be permanently 14 days from now.
     */
    @Test fun `repeated looks do not push the deadline away`() {
        var witness = SignalCanary.observe(SignalCanary.Witness(), v300, nowMs = 0)
        repeat(30) { day -> witness = SignalCanary.observe(witness, v300, nowMs = day * this.day) }
        assertEquals(0L, witness.installedSeenAtMs)
        assertEquals(SignalCanary.Health.STALE, assess(witness, atDay = 30))
    }

    /** A brand-new install has confirmed nothing yet, so it gets the same grace as an update. */
    @Test fun `a first install is pending, then stale`() {
        val fresh = SignalCanary.observe(SignalCanary.Witness(), v300, nowMs = 0)
        assertEquals(SignalCanary.Health.PENDING, assess(fresh, atDay = 13))
        assertEquals(SignalCanary.Health.STALE, assess(fresh, atDay = 14))
    }

    @Test fun `uninstalling Instagram silences it again`() {
        val confirmed = SignalCanary.confirm(SignalCanary.Witness(), v300, nowMs = 0)
        val gone = SignalCanary.observe(confirmed, installedVersion = null, nowMs = 5 * day)
        assertEquals(SignalCanary.Health.NO_APP, assess(gone, atDay = 100))
    }

    /** Reinstalling the same version is still confirmed — the ids didn't move. */
    @Test fun `reinstalling the confirmed version needs no re-check`() {
        val confirmed = SignalCanary.confirm(SignalCanary.Witness(), v300, nowMs = 0)
        val gone = SignalCanary.observe(confirmed, installedVersion = null, nowMs = 5 * day)
        val back = SignalCanary.observe(gone, v300, nowMs = 6 * day)
        assertEquals(SignalCanary.Health.CONFIRMED, assess(back, atDay = 100))
    }

    /** Downgrades count as a change like any other — the ids are only known good for one version. */
    @Test fun `a downgrade also needs re-confirming`() {
        val confirmed = SignalCanary.confirm(SignalCanary.Witness(), v301, nowMs = 0)
        val rolledBack = SignalCanary.observe(confirmed, v300, nowMs = day)
        assertEquals(SignalCanary.Health.STALE, assess(rolledBack, atDay = 30))
    }
}
