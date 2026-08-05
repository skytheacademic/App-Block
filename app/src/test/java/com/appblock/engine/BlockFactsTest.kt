package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * What the block screen's two fact rows say, per cause.
 *
 * The rows were four fixed strings until 2026-08-05, so the value of this class is mostly in the
 * cases that are **not** the budget one: those are the ones the old copy got wrong, and two of them
 * were wrong in the loosening direction — quoting an hour where the real price is three days or is
 * unpayable. Each of those has a test that names what the old rows said.
 */
class BlockFactsTest {

    // 2026-07-20 is a Monday.
    private val monMorning = LocalDateTime.of(2026, 7, 20, 9, 0)
    private val monEvening = LocalDateTime.of(2026, 7, 20, 19, 12)
    private val hourMs = 60L * 60L * 1000L

    private fun target(
        reason: BlockReason?,
        schedule: Schedule? = null,
        alwaysBlocked: Boolean = false,
        now: LocalDateTime = monEvening,
        waitMs: Long = hourMs,
    ) = BlockFacts.forTarget(reason, schedule, alwaysBlocked, now, waitMs)

    @Test fun `a spent budget counts down to the 4am reset and prices an exception`() {
        val facts = target(BlockReason.BUDGET)
        assertEquals(
            BlockFacts.Returns.AtDayReset(4 * 60, (8 * 60 + 48) * 60L),
            facts.returns,
        )
        assertEquals(BlockFacts.Route.ExceptionWait(hourMs), facts.route)
    }

    /**
     * The wait is passed in rather than read from [ExceptionManager.WAIT_MS], so the throwaway
     * `debugFast` build's minute is what its block screen quotes. A build that opens in a minute while
     * promising an hour teaches you to disbelieve the number on the screen you are meant to believe.
     */
    @Test fun `the quoted exception wait is this build's, not the constant`() {
        assertEquals(
            BlockFacts.Route.ExceptionWait(60_000L),
            target(BlockReason.BUDGET, waitMs = 60_000L).route,
        )
    }

    /** A null reason falls through to the budget wording, because the overlay's message does too. */
    @Test fun `an unstated reason reads as the budget case, matching the message`() {
        assertEquals(target(BlockReason.BUDGET), target(null))
    }

    /**
     * 🔴 The correction that motivated all of this, half one: a schedule block is not waiting on 04:00.
     * The old row said "day resets · 04:00" under an app that reopens at 18:00 tomorrow.
     */
    @Test fun `a schedule block counts down to its next window, not to 4am`() {
        val schedule = Schedule(mapOf(DayOfWeek.TUESDAY to listOf(TimeWindow(9 * 60, 11 * 60))))
        val facts = target(BlockReason.SCHEDULE, schedule)
        // Monday 19:12 → Tuesday 09:00 is 13 h 48 m.
        assertEquals(
            BlockFacts.Returns.AtWindow(9 * 60, (13 * 60 + 48) * 60L),
            facts.returns,
        )
    }

    /**
     * 🔴 Half two, and the one that actually cost something: the old row offered "more time · 1-hour
     * wait" on a schedule block, where an exception raises a cap that is not what stopped you —
     * [BudgetCoordinator] returns [BlockReason.SCHEDULE] before the cap arithmetic runs at all. It sold
     * an hour that buys nothing.
     */
    @Test fun `a schedule block offers the hours, never a wait`() {
        val schedule = Schedule(mapOf(DayOfWeek.MONDAY to listOf(TimeWindow(6 * 60, 8 * 60))))
        assertEquals(BlockFacts.Route.EditTheHours, target(BlockReason.SCHEDULE, schedule).route)
    }

    /** Every day cleared: reachable, because clearing is a tightening and saves for free. */
    @Test fun `a schedule with no hours at all says so instead of counting down`() {
        assertEquals(
            BlockFacts.Returns.NoAllowedHours,
            target(BlockReason.SCHEDULE, Schedule(emptyMap())).returns,
        )
    }

    /** A schedule-reasoned block with no schedule to read is the same "nothing to count down to". */
    @Test fun `a schedule block with no schedule attached does not invent a window`() {
        assertEquals(BlockFacts.Returns.NoAllowedHours, target(BlockReason.SCHEDULE, null).returns)
    }

    @Test fun `the tamper latch clears from settings and waits for nothing`() {
        val facts = target(BlockReason.TAMPER)
        assertEquals(BlockFacts.Returns.NotOnItsOwn, facts.returns)
        assertEquals(BlockFacts.Route.RestoreAutomaticTime, facts.route)
    }

    /**
     * 🔴 B-10's bypass tools. The message says "can't be unblocked from the phone" while the row under
     * it used to quote an hour — the screen contradicting itself in six lines.
     */
    @Test fun `an always-blocked bypass tool has no route at all`() {
        assertEquals(
            BlockFacts.Route.NotFromThisPhone,
            target(BlockReason.HARD_BLOCK, alwaysBlocked = true).route,
        )
    }

    /** A user-authored hard block is not a bypass tool: it comes off through the apps cycle. */
    @Test fun `a user's own hard block costs the apps change window`() {
        assertEquals(
            BlockFacts.Route.ChangeWindow(UnlockCategory.APPS),
            target(BlockReason.HARD_BLOCK, alwaysBlocked = false).route,
        )
    }

    /** 🔴 A blocked site costs 72 h, not the hour the old row quoted. */
    @Test fun `a blocked site costs the websites change window`() {
        val facts = BlockFacts.forWeb(BrowserPolicy.WebBlock.BLOCKED_SITE)
        assertEquals(BlockFacts.Returns.NotOnItsOwn, facts.returns)
        assertEquals(BlockFacts.Route.ChangeWindow(UnlockCategory.WEBSITES), facts.route)
    }

    @Test fun `a browser off the allowlist points at one on it`() {
        assertEquals(
            BlockFacts.Route.UseAnAllowedBrowser,
            BlockFacts.forWeb(BrowserPolicy.WebBlock.NON_ALLOWLISTED_BROWSER).route,
        )
        assertEquals(
            BlockFacts.Route.UseAnAllowedBrowser,
            BlockFacts.forWeb(BrowserPolicy.WebBlock.WEB_APP).route,
        )
    }

    /**
     * The only block that undoes itself for free: it is on because the address can't be read, and it
     * goes off the moment it can. Neither row may imply a price.
     */
    @Test fun `an unreadable address costs nothing and clears itself`() {
        val facts = BlockFacts.forWeb(BrowserPolicy.WebBlock.UNREADABLE_ADDRESS)
        assertEquals(BlockFacts.Returns.WhenAddressReadable, facts.returns)
        assertEquals(BlockFacts.Route.ShowTheAddressBar, facts.route)
    }

    /**
     * No cause may be answered with a wait it cannot use. Pins the property the old rows violated,
     * rather than only the individual cases above — a seventh cause added later has to pick a side.
     */
    @Test fun `only a budget block is ever priced as an exception wait`() {
        val priced = listOf(
            BlockReason.BUDGET to false,
            BlockReason.SCHEDULE to false,
            BlockReason.TAMPER to false,
            BlockReason.HARD_BLOCK to false,
            BlockReason.HARD_BLOCK to true,
        ).filter { (reason, always) ->
            target(reason, Schedule(emptyMap()), always).route is BlockFacts.Route.ExceptionWait
        }
        assertEquals(listOf(BlockReason.BUDGET to false), priced)

        BrowserPolicy.WebBlock.entries.forEach {
            assertFalse(
                "$it was priced as an exception wait, which cannot raise a website block",
                BlockFacts.forWeb(it).route is BlockFacts.Route.ExceptionWait,
            )
        }
    }
}
