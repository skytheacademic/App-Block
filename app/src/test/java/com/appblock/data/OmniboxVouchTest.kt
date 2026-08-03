package com.appblock.data

import com.appblock.engine.SignalCanary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which canary verdicts still let an absent address bar be treated as innocent.
 *
 * This mapping is where the B-7 redesign's whole safety argument lives, so it is pinned rather than
 * left implicit in a `!=`. Only one verdict may withdraw the benefit of the doubt; every other value
 * blocking would be the false positive coming back one notch further along.
 */
class OmniboxVouchTest {

    @Test fun `a confirmed id vouches`() {
        assertTrue(OmniboxWitnessStore.vouches(SignalCanary.Health.CONFIRMED))
    }

    /**
     * The browser updated recently and hasn't been re-read yet. Blocking here is exactly the defect
     * this change removes, moved to the first launch after a Chrome update: a restored, already-scrolled
     * tab has no readable omnibox and would block on sight.
     */
    @Test fun `a recent browser update still vouches during the grace`() {
        assertTrue(OmniboxWitnessStore.vouches(SignalCanary.Health.PENDING))
    }

    /**
     * No readable version means the canary has nothing to say — the reasoning [SignalCanary] already
     * states for Instagram. No reading is not a bad reading, and guessing produces noise.
     */
    @Test fun `an unreadable version abstains rather than accusing`() {
        assertTrue(OmniboxWitnessStore.vouches(SignalCanary.Health.NO_APP))
    }

    /**
     * The one that withdraws it, and the reason fail-closed survives this change at all: a browser that
     * updated a week ago and has *still* never produced a readable address bar is evidence the id moved,
     * not evidence of a quiet week — re-vouching needs no deliberate act, just any page load with the
     * toolbar showing.
     */
    @Test fun `a stale id does not vouch`() {
        assertFalse(OmniboxWitnessStore.vouches(SignalCanary.Health.STALE))
    }

    /**
     * Shorter than the reel canary's fourteen days, on purpose — confirming a reel sighting needs the
     * user to open Reels, confirming an omnibox needs them only to browse.
     */
    @Test fun `the browser grace is a week`() {
        assertEquals(7L * 24 * 60 * 60 * 1_000, OmniboxWitnessStore.GRACE_MS)
        assertTrue(OmniboxWitnessStore.GRACE_MS < SignalCanary.DEFAULT_GRACE_MS)
    }
}
