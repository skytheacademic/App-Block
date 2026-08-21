package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * The core of the commitment lock: loosening enforcement needs an unlock, tightening never does.
 * "Looser" = more access (higher cap / longer window / higher ceiling / target turned off).
 */
class DurableChangeGateTest {

    private fun settings(
        window: Int = 60,
        enabled: Boolean = true,
        wd: Int = 30,
        we: Int = 30,
        max: Int = 60,
    ) = DurableSettings(
        version = 1,
        targets = mapOf(Target.TIKTOK to TargetSettings(enabled, wd, we, max)),
        exceptionWindowMinutes = window,
    )

    @Test fun `raising a daily cap is loosening`() {
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(settings(wd = 30), settings(wd = 45)))
    }

    @Test fun `lowering a daily cap is tightening`() {
        assertEquals(ChangeDirection.TIGHTEN, DurableChangeGate.classify(settings(wd = 30), settings(wd = 15)))
    }

    @Test fun `identical settings are neutral`() {
        assertEquals(ChangeDirection.NEUTRAL, DurableChangeGate.classify(settings(), settings()))
    }

    @Test fun `turning a block off is loosening, turning it on is tightening`() {
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(settings(enabled = true), settings(enabled = false)))
        assertEquals(ChangeDirection.TIGHTEN, DurableChangeGate.classify(settings(enabled = false), settings(enabled = true)))
    }

    // ---- G-1 (audit 2026-08-21): a target's numbers are guarded whatever its switch is doing ----

    /**
     * The test this replaces asserted the opposite — `caps changing while a target stays off has no
     * enforcement effect` — and was right about enforcement and wrong about the lock. A dormant
     * target's caps are what it will run at when its free turn-on lands, so editing them is editing
     * the rules, in whichever direction.
     */
    @Test fun `raising a cap on a switched-off target is still a loosening`() {
        assertEquals(
            ChangeDirection.LOOSEN,
            DurableChangeGate.classify(settings(enabled = false, wd = 5), settings(enabled = false, wd = 500)),
        )
        assertTrue(
            DurableChangeGate.applyChange(
                settings(enabled = false, wd = 5),
                settings(enabled = false, wd = 500),
                unlocked = false,
            ) is ChangeResult.Blocked,
        )
    }

    @Test fun `lowering a cap on a switched-off target is free, like every tightening`() {
        assertEquals(
            ChangeDirection.TIGHTEN,
            DurableChangeGate.classify(settings(enabled = false, wd = 500), settings(enabled = false, wd = 5)),
        )
    }

    /**
     * The keyless route the finding describes, leg by leg, against the state each leg would have
     * persisted. TikTok seeds off; before this, leg 1 was "no enforcement effect" and leg 2 was a
     * bare tightening, so a 30-minute seed became a 24-hour cap without a window ever opening.
     */
    @Test fun `edit-while-off then turn-on is not a free route to a higher cap`() {
        val seeded = settings(enabled = false, wd = 30, we = 30)
        val inflated = settings(enabled = false, wd = 1440, we = 30)
        val leg1 = DurableChangeGate.applyChange(seeded, inflated, unlocked = false)
        assertTrue("the dormant edit must be gated", leg1 is ChangeResult.Blocked)

        // And had it somehow landed, the turn-on alone is still free — the numbers were the change.
        val armed = settings(enabled = true, wd = 1440, we = 30)
        assertTrue(DurableChangeGate.applyChange(inflated, armed, unlocked = false) is ChangeResult.Applied)
    }

    /** Turning on and raising the caps in one save: the turn-on is free, the raise is not. */
    @Test fun `a cap raised in the same save as a turn-on is gated`() {
        val off = settings(enabled = false, wd = 30)
        val onAndRaised = settings(enabled = true, wd = 1440)
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(off, onAndRaised))
        assertTrue(DurableChangeGate.applyChange(off, onAndRaised, unlocked = false) is ChangeResult.Blocked)
        // The plain turn-on, by contrast, stays the one-tap tightening the seeded-off row relies on.
        assertTrue(DurableChangeGate.applyChange(off, settings(enabled = true, wd = 30), unlocked = false) is ChangeResult.Applied)
    }

    /**
     * The rider: inside an open window, turn a target off *and* raise its caps. Before this the two
     * collapsed into one "turned off", so a single window bought both, and the raised caps sat
     * waiting for the free turn-on. Now they are two loosenings, which one window does not cover.
     */
    @Test fun `turning off with a raised cap riding along is two loosenings`() {
        val on = settings(enabled = true, wd = 30)
        val offAndRaised = settings(enabled = false, wd = 1440)
        val reasons = DurableChangeGate.looseningReasons(on, offAndRaised)
        assertEquals(2, reasons.size)
        assertTrue(DurableChangeGate.applyChange(on, offAndRaised, unlocked = true) is ChangeResult.TooManyLoosenings)
        // Turning off alone is still exactly one.
        assertTrue(DurableChangeGate.applyChange(on, settings(enabled = false, wd = 30), unlocked = true) is ChangeResult.Applied)
    }

    /** A dormant target's schedule is guarded the same way as its caps. */
    @Test fun `widening a switched-off target's hours is a loosening`() {
        val narrow = DurableSettings(1, mapOf(Target.TIKTOK to TargetSettings(false, 30, 30, 60, sched(TimeWindow(18 * 60, 20 * 60)))), 60)
        val wide = DurableSettings(1, mapOf(Target.TIKTOK to TargetSettings(false, 30, 30, 60, sched(TimeWindow(12 * 60, 20 * 60)))), 60)
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(narrow, wide))
    }

    /**
     * Membership is still judged on enforcement. Adding an app is free whatever caps it arrives with
     * — anything is tighter than unblocked, and there is nothing to compare them against — and
     * removing or adding one that is *off* is neutral: its numbers were no more reachable than an
     * absent target's, and the turn-on that would make them matter is classified on its own.
     */
    @Test fun `membership of a switched-off target is neutral, of a running one is not`() {
        val none = DurableSettings(1, emptyMap(), 60)
        val off = settings(enabled = false, wd = 1440)
        val on = settings(enabled = true, wd = 1440)
        assertEquals(ChangeDirection.NEUTRAL, DurableChangeGate.classify(none, off))
        assertEquals(ChangeDirection.NEUTRAL, DurableChangeGate.classify(off, none))
        assertEquals(ChangeDirection.TIGHTEN, DurableChangeGate.classify(none, on))
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(on, none))
    }

    /**
     * The mode flip is reported as itself, not as its side effects: schedule-only stores zero caps
     * that nothing reads, and diffing those across the flip would invent a "weekday cap 30 → 0 min"
     * tightening beside the real loosening.
     */
    @Test fun `dropping to schedule-only is one loosening, not a phantom cap change`() {
        val budget = DurableSettings(1, mapOf(Target.TIKTOK to TargetSettings(true, 30, 30, 60, sched(TimeWindow.ALL_DAY))), 60)
        val hoursOnly = DurableSettings(1, mapOf(Target.TIKTOK to TargetSettings(true, 0, 0, 0, sched(TimeWindow.ALL_DAY), scheduleOnly = true)), 60)
        val changes = DurableChangeGate.changes(budget, hoursOnly)
        assertEquals(1, changes.size)
        assertEquals(ChangeDirection.LOOSEN, changes[0].direction)
        assertEquals(ChangeDirection.TIGHTEN, DurableChangeGate.classify(hoursOnly, budget))
    }

    @Test fun `a longer exception window is loosening, shorter is tightening`() {
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(settings(window = 60), settings(window = 120)))
        assertEquals(ChangeDirection.TIGHTEN, DurableChangeGate.classify(settings(window = 60), settings(window = 30)))
    }

    @Test fun `raising the exception ceiling is loosening`() {
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(settings(max = 60), settings(max = 90)))
    }

    @Test fun `any loosening field makes the whole edit loosen`() {
        // Tightens weekday but loosens weekend — the loosening must win so it can't be smuggled through.
        val old = settings(wd = 30, we = 30)
        val new = settings(wd = 15, we = 45)
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(old, new))
    }

    @Test fun `loosening is blocked without an unlock and applied with one`() {
        val current = settings(wd = 30)
        val proposed = settings(wd = 45)

        val blocked = DurableChangeGate.applyChange(current, proposed, unlocked = false)
        assertTrue(blocked is ChangeResult.Blocked)
        assertEquals(ChangeDirection.LOOSEN, (blocked as ChangeResult.Blocked).direction)

        val applied = DurableChangeGate.applyChange(current, proposed, unlocked = true)
        assertTrue(applied is ChangeResult.Applied)
        assertEquals(proposed, (applied as ChangeResult.Applied).settings)
    }

    @Test fun `tightening is applied even while locked`() {
        val result = DurableChangeGate.applyChange(settings(wd = 30), settings(wd = 15), unlocked = false)
        assertTrue(result is ChangeResult.Applied)
    }

    // ---- schedule direction ----

    private fun withSchedule(schedule: Schedule?) = DurableSettings(
        version = 1,
        targets = mapOf(Target.TIKTOK to TargetSettings(true, 30, 30, 60, schedule)),
        exceptionWindowMinutes = 60,
    )

    private fun sched(vararg windows: TimeWindow) =
        Schedule(mapOf(DayOfWeek.MONDAY to windows.toList()))

    @Test fun `adding a schedule tightens and removing it loosens`() {
        val none = withSchedule(null)
        val scheduled = withSchedule(sched(TimeWindow(18 * 60, 20 * 60)))
        assertEquals(ChangeDirection.TIGHTEN, DurableChangeGate.classify(none, scheduled))
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(scheduled, none))
    }

    @Test fun `widening a window loosens and narrowing tightens`() {
        val narrow = withSchedule(sched(TimeWindow(18 * 60, 20 * 60)))
        val wide = withSchedule(sched(TimeWindow(17 * 60, 20 * 60)))
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(narrow, wide))
        assertEquals(ChangeDirection.TIGHTEN, DurableChangeGate.classify(wide, narrow))
    }

    @Test fun `adding an allowed day loosens`() {
        val mon = withSchedule(Schedule(mapOf(DayOfWeek.MONDAY to listOf(TimeWindow.ALL_DAY))))
        val monTue = withSchedule(
            Schedule(
                mapOf(
                    DayOfWeek.MONDAY to listOf(TimeWindow.ALL_DAY),
                    DayOfWeek.TUESDAY to listOf(TimeWindow.ALL_DAY),
                ),
            ),
        )
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(mon, monTue))
    }

    @Test fun `loosening a schedule needs an unlock`() {
        val narrow = withSchedule(sched(TimeWindow(18 * 60, 20 * 60)))
        val wide = withSchedule(sched(TimeWindow(17 * 60, 20 * 60)))
        assertTrue(DurableChangeGate.applyChange(narrow, wide, unlocked = false) is ChangeResult.Blocked)
        assertTrue(DurableChangeGate.applyChange(narrow, wide, unlocked = true) is ChangeResult.Applied)
    }

    // ---- one window buys one change, not one save (audit finding B-4) ----

    private val reddit = Target.forPackage("com.reddit.frontpage")

    /**
     * Only the *weekday* cap is a parameter. Moving weekday and weekend together would be two changes
     * on its own — see `raising both caps on one app is two changes` — which would quietly wreck every
     * count below.
     */
    private fun twoApps(
        tiktokWd: Int = 30,
        redditWd: Int = 30,
        redditOn: Boolean = true,
        window: Int = 60,
    ) = DurableSettings(
        version = 1,
        targets = mapOf(
            Target.TIKTOK to TargetSettings(true, tiktokWd, 30, 60),
            reddit to TargetSettings(redditOn, redditWd, 30, 60),
        ),
        exceptionWindowMinutes = window,
    )

    /**
     * The finding, as its own repro. Before this, two hours of waiting bought one press of Save, and
     * Save could carry an unbounded edit — raise every cap, widen the window, drop an app, all at
     * once. The wait rationed the wrong thing.
     */
    @Test fun `an open window will not swallow a mass loosening`() {
        val result = DurableChangeGate.applyChange(
            twoApps(),
            twoApps(tiktokWd = 1440, redditWd = 1440, redditOn = false, window = 240),
            unlocked = true,
        )
        assertTrue(result is ChangeResult.TooManyLoosenings)
    }

    /** And it hands back every loosening it counted, so the screen can say which to keep. */
    @Test fun `the refusal names each loosening`() {
        val result = DurableChangeGate.applyChange(
            twoApps(),
            twoApps(tiktokWd = 60, redditWd = 60),
            unlocked = true,
        ) as ChangeResult.TooManyLoosenings
        assertEquals(2, result.loosenings.size)
        assertTrue(result.loosenings.all { it.direction == ChangeDirection.LOOSEN })
    }

    @Test fun `exactly one loosening is what a window is for`() {
        val result = DurableChangeGate.applyChange(twoApps(), twoApps(tiktokWd = 45), unlocked = true)
        assertTrue(result is ChangeResult.Applied)
    }

    /**
     * The sharp edge, locked in deliberately: weekday and weekend caps are two fields, so raising both
     * is two changes and two cycles. Counting per field is what makes the friction scale with how much
     * access is being handed back.
     */
    @Test fun `raising both caps on one app is two changes`() {
        val old = settings(wd = 30, we = 30)
        val new = settings(wd = 45, we = 45)
        assertTrue(DurableChangeGate.applyChange(old, new, unlocked = true) is ChangeResult.TooManyLoosenings)
    }

    /** Tightening is always free, so bundling some alongside the one loosening mustn't cost the window. */
    @Test fun `tightenings do not count against the one change`() {
        val result = DurableChangeGate.applyChange(
            twoApps(tiktokWd = 30, redditWd = 30),
            twoApps(tiktokWd = 45, redditWd = 5),
            unlocked = true,
        )
        assertTrue(result is ChangeResult.Applied)
    }

    /**
     * Order of the two refusals: with no window at all, the answer is still "start a window", not
     * "you have too many". Being told to trim an edit you can't save either way would be nonsense.
     */
    @Test fun `no window at all still reads as locked, however big the edit`() {
        val result = DurableChangeGate.applyChange(
            twoApps(),
            twoApps(tiktokWd = 1440, redditWd = 1440),
            unlocked = false,
        )
        assertTrue(result is ChangeResult.Blocked)
    }

    @Test fun `a pure tightening never needs a window however many fields it touches`() {
        val result = DurableChangeGate.applyChange(
            twoApps(tiktokWd = 60, redditWd = 60, window = 120),
            twoApps(tiktokWd = 5, redditWd = 5, window = 10),
            unlocked = false,
        )
        assertTrue(result is ChangeResult.Applied)
    }
}
