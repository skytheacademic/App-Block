package com.appblock.engine

import java.time.Duration
import java.time.LocalDateTime

/**
 * The two rows under the block screen's rule, as facts about *this* block rather than constants of the
 * app.
 *
 * ## What they used to say, and why that had to change (2026-08-05)
 *
 * The rows were hard-coded in `overlay_block.xml`: `day resets · 04:00` and `more time · 1-hour wait`.
 * The layout's own comment called them "constants of the system, not of this particular block", which
 * is true of a budget block and **false of every other kind**, in the direction that matters:
 *
 *  - A **schedule** block is not waiting on 04:00 — it is waiting on the next window, which can be
 *    tonight, tomorrow, or (with every day cleared) never. And the exception wait cannot touch it at
 *    all: [PolicyEngine.scheduleAllows] gates the decision *before* the budget path
 *    ([BudgetCoordinator] returns [BlockReason.SCHEDULE] and never reaches the cap arithmetic), so an
 *    exception raises a cap that is not what is stopping you. "more time · 1-hour wait" told the user
 *    to buy an hour that would change nothing.
 *  - A **blocked site** costs the 72-hour website cycle, not a one-hour wait.
 *  - An **always-blocked bypass tool** (B-10) costs nothing that can be paid from the phone, and the
 *    block message already says so — while the row underneath quoted an hour.
 *  - A **clock-tamper latch** clears from Settings, free, and does not reset at 04:00.
 *
 * So four of the six causes read a price that was wrong, and two of those were wrong in the loosening
 * direction — quoting an hour where the real cost is three days or is unpayable. Same defect as the
 * keyless Lock copy fixed the same day: a true sentence written once and then shown everywhere.
 *
 * ## The two questions
 *
 * Every block answers both, so the layout keeps two rows and neither is ever hidden:
 *
 *  1. [Returns] — **when it lifts on its own**, having paid nothing and changed nothing.
 *  2. [Route] — **the way through**, for someone who does not want to wait for that.
 *
 * Rendering lives in the service (these carry no strings, so the block screen and any future surface
 * cannot drift apart on the numbers), and the arithmetic lives here, where it is testable without a
 * device — the same split as [OcclusionHold] and [AddressWatch].
 */
object BlockFacts {

    /** When the block lifts by itself. */
    sealed interface Returns {

        /** Today's budget comes back at the 4am roll: [minuteOfDay] local, [secondsUntil] from now. */
        data class AtDayReset(val minuteOfDay: Int, val secondsUntil: Long) : Returns

        /** The schedule's next window opens at [minuteOfDay] local, [secondsUntil] from now. */
        data class AtWindow(val minuteOfDay: Int, val secondsUntil: Long) : Returns

        /** A schedule with no windows on any day — there is nothing to count down to. */
        data object NoAllowedHours : Returns

        /** Waiting does nothing; this one only ends when something is acted on. */
        data object NotOnItsOwn : Returns

        /** Ends the moment the browser's address bar can be read again. */
        data object WhenAddressReadable : Returns
    }

    /** What it costs to get past the block before [Returns] arrives. */
    sealed interface Route {

        /** A bounded, delayed budget raise after [waitMs] — CONSTRAINTS §5, budget blocks only. */
        data class ExceptionWait(val waitMs: Long) : Route

        /** The durable-change cycle for [category]: key → wait → 15-minute window. CONSTRAINTS §6. */
        data class ChangeWindow(val category: UnlockCategory) : Route

        /**
         * Editing the schedule, which no wait substitutes for.
         *
         * Deliberately not quoted as a price. *Widening* the hours is a loosening and so is gated,
         * *narrowing* is free, and with no key stored a widening cannot be bought at any wait at all —
         * three different answers that the Lock tab already states correctly and the block screen has
         * no room to. Naming the lever without pricing it is the honest short form.
         */
        data object EditTheHours : Route

        /** The tamper latch clears by turning both automatic-time toggles back on. Costs nothing. */
        data object RestoreAutomaticTime : Route

        /** Always-blocked bypass tools (B-10): no route exists from the phone, by design. */
        data object NotFromThisPhone : Route

        /** A browser or web app whose address cannot be checked — the route is one that can be. */
        data object UseAnAllowedBrowser : Route

        /** The address bar is present, just scrolled away: showing it clears the block by itself. */
        data object ShowTheAddressBar : Route
    }

    data class Facts(val returns: Returns, val route: Route)

    /**
     * The facts for a budgeted/scheduled app block.
     *
     * [schedule] is the blocked target's own schedule and is only read for [BlockReason.SCHEDULE];
     * [alwaysBlocked] separates a bypass tool from a user-authored hard block, which is the difference
     * between "no route" and "the 2-hour apps cycle". [exceptionWaitMs] is passed in rather than read
     * from [ExceptionManager.WAIT_MS] so the `debugFast` build's short wait is what the block screen
     * shows — otherwise the throwaway build would quote an hour and open in a minute.
     */
    fun forTarget(
        reason: BlockReason?,
        schedule: Schedule?,
        alwaysBlocked: Boolean,
        now: LocalDateTime,
        exceptionWaitMs: Long,
    ): Facts = when (reason) {
        BlockReason.SCHEDULE -> {
            val opening = schedule?.nextOpening(now)
            Facts(
                returns = if (opening == null) {
                    Returns.NoAllowedHours
                } else {
                    Returns.AtWindow(
                        minuteOfDay = opening.hour * 60 + opening.minute,
                        secondsUntil = Duration.between(now, opening).seconds,
                    )
                },
                route = Route.EditTheHours,
            )
        }

        BlockReason.TAMPER -> Facts(Returns.NotOnItsOwn, Route.RestoreAutomaticTime)

        BlockReason.HARD_BLOCK -> Facts(
            returns = Returns.NotOnItsOwn,
            route = if (alwaysBlocked) Route.NotFromThisPhone
            else Route.ChangeWindow(UnlockCategory.APPS),
        )

        // The budget case, and the fallback for a null reason: the overlay's own message defaults to
        // the budget wording there too, so the rows must not say something else.
        else -> Facts(
            returns = Returns.AtDayReset(
                minuteOfDay = DayBoundary.DEFAULT_RESET_HOUR * 60,
                secondsUntil = DayBoundary.secondsUntilReset(now),
            ),
            route = Route.ExceptionWait(exceptionWaitMs),
        )
    }

    /** The facts for a website / browser block (CONSTRAINTS §2). */
    fun forWeb(webBlock: BrowserPolicy.WebBlock): Facts = when (webBlock) {
        BrowserPolicy.WebBlock.BLOCKED_SITE ->
            Facts(Returns.NotOnItsOwn, Route.ChangeWindow(UnlockCategory.WEBSITES))

        BrowserPolicy.WebBlock.NON_ALLOWLISTED_BROWSER ->
            Facts(Returns.NotOnItsOwn, Route.UseAnAllowedBrowser)

        BrowserPolicy.WebBlock.WEB_APP ->
            Facts(Returns.NotOnItsOwn, Route.UseAnAllowedBrowser)

        // The one block that undoes itself with no cost and no wait: it is on because App-Block cannot
        // see the address, and it goes off the moment it can.
        BrowserPolicy.WebBlock.UNREADABLE_ADDRESS ->
            Facts(Returns.WhenAddressReadable, Route.ShowTheAddressBar)
    }
}
