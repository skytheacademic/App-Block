package com.appblock.engine

/**
 * Which displays the block screen must be on, the add/keep/remove diff to get there, whether the result
 * is honest, when to fall back to kicking Home, and where a self-defense bounce must be additionally
 * aimed. Pure set problems — no Android imports, no `SDK_INT`.
 *
 * ## Coverage follows evidence, never enumeration (invariant I3)
 *
 * A display is covered because **its own windows carried the blocked thing**, never because it exists.
 * That one rule is what keeps App-Block off screens it has no business papering over, and it needs no
 * blocklist to do it:
 *
 *  - **A mirrored monitor** — which is what One UI 8 does by default, so it is the *common* case, not the
 *    exotic one — contributes no windows of its own. `AccessibilityWindowsPopulator` filters cloned
 *    windows framework-side (`isNotClone`), so a mirror can never attract an overlay.
 *  - **A screen recorder's or a cast's private virtual display** is hidden from us three times over:
 *    uid-filtered enumeration, `isValidDisplay`, and `addView`'s own access check.
 *
 * So "do not paper over a recording" is answered structurally. ⚠️ **A hand-rolled list of
 * displays-not-to-cover must never be added here** — that is precisely the thing that fails open two OS
 * versions from now, when a display type nobody thought of appears and matches nothing on the list.
 *
 * ## Why *any* target and not only the decided one — the wide rule
 *
 * `BudgetCoordinator.decideCurrent` returns the **first** target that blocks, and returns immediately on
 * a schedule block. One decision cannot pick out coverage across two displays: with Reels blocked on the
 * phone and TikTok blocked on the monitor, the decision names Reels, and covering only Reels' display
 * leaves TikTok playing **permanently** — the phone's own overlay prunes its read, the hold sustains, the
 * next decision is identical, and nothing ever re-decides.
 *
 * So while the engine is blocking anything, every display carrying any target is covered. The cost is
 * over-blocking a limited-but-currently-allowed app on the other screen; the cost of the narrow rule is a
 * permanent bypass. Shipping wide first is the point, and narrowing it later needs
 * `BudgetCoordinator.blockedForegroundTargets()`, which does not exist yet.
 */
object DisplayCoverage {

    /** Why a display is covered — decides which message that display's overlay shows. */
    enum class Cause { WEB, TARGET }

    data class Plan(val add: List<Int>, val keep: List<Int>, val remove: List<Int>) {
        val summary: String get() = "add=$add keep=$keep rm=$remove"
    }

    /**
     * The displays to cover, in scan order, each with its reason.
     *
     * [webBlocked] wins over [targetsOn] per display — `applyDecision`'s web-first precedence, applied
     * per display instead of globally, because the phone can be blocked for one reason while the monitor
     * is blocked for the other.
     */
    fun causes(
        webBlocked: Set<Int>,
        targetsOn: Set<Int>,
        engineBlocking: Boolean,
    ): Map<Int, Cause> {
        val cover = webBlocked + if (engineBlocking) targetsOn else emptySet()
        val out = LinkedHashMap<Int, Cause>(cover.size)
        for (id in DisplayCensus.order(cover)) {
            out[id] = if (id in webBlocked) Cause.WEB else Cause.TARGET
        }
        return out
    }

    /**
     * The diff from [covered] to [cover].
     *
     * **[Plan.remove] is computed against what is COVERED, never against the live display list**
     * (invariant I4). A monitor unplugged mid-block is gone from every enumeration while we still hold
     * its `View`; only the attachment map knows it exists, so only the attachment map may decide it goes.
     */
    fun plan(cover: Set<Int>, covered: Set<Int>): Plan = Plan(
        add = DisplayCensus.order(cover - covered),
        keep = DisplayCensus.order(cover intersect covered),
        remove = DisplayCensus.order(covered - cover),
    )

    /**
     * Every wanted display actually covered. Vacuously true for an empty [cover].
     *
     * This replaces `showOverlay`'s old `return overlayView != null`, which was honest while exactly one
     * overlay could exist and becomes **a lie** with two: phone covered, the monitor's `addView` rejected
     * with `InvalidDisplayException` and swallowed by the caller's `runCatching`, and the old expression
     * still answers `true` — so the kick-to-home fallback is skipped and the monitor is left free.
     */
    fun satisfied(cover: Set<Int>, covered: Set<Int>): Boolean = covered.containsAll(cover)

    /**
     * When to kick Home because the overlay could not be drawn.
     *
     * Deliberately **not** simply `!satisfied`. A display that can never take our overlay would then
     * produce a Home kick every five seconds for as long as it stays plugged in — which does not cover
     * the offending display (`GLOBAL_ACTION_HOME` is not display-scoped) and does make the phone
     * unusable. A blocker that makes the phone unusable gets uninstalled, and that is the fail-open that
     * matters most.
     *
     * But HOME **is** an injected key event that follows **input focus**, so when the uncovered display
     * is the one the user is actually driving, the kick lands where it is needed and the strictness is
     * free. Hence three cases: nothing covered at all (today's condition verbatim — the permission was
     * revoked); the default display wanted and bare; or [activeDisplayId] wanted and bare.
     */
    fun homeFallback(cover: Set<Int>, covered: Set<Int>, activeDisplayId: Int?): Boolean =
        cover.isNotEmpty() && (
            covered.isEmpty() ||
                (DisplayCensus.DEFAULT_DISPLAY in cover && DisplayCensus.DEFAULT_DISPLAY !in covered) ||
                (activeDisplayId != null && activeDisplayId in cover && activeDisplayId !in covered)
            )

    /**
     * Which display a self-defense bounce must be **additionally** aimed at, or null when the plain
     * `performGlobalAction(GLOBAL_ACTION_HOME)` already lands right.
     *
     * In DeX dual mode the phone can hold input focus while a Settings page about App-Block sits open on
     * the monitor, so the global HOME hits the wrong screen entirely — a fail-open on the tier that
     * guards every other tier. Lowest id, for determinism.
     */
    fun bounceDisplay(watchedDisplays: Set<Int>, activeDisplayId: Int?): Int? =
        watchedDisplays.filter { it != activeDisplayId }.minOrNull()
}
