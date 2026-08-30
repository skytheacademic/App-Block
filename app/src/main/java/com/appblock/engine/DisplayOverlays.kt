package com.appblock.engine

/**
 * N block overlays, one per covered display. **Executes a [DisplayCoverage.Plan]; decides nothing.**
 *
 * All the bookkeeping the untested service must not own: which display has a view attached, which window
 * manager added it (and may therefore remove it), each view's own message key and fact-row cache, the
 * remove-then-add-then-refresh order, and the honest report of what is actually attached.
 *
 * ## Why generic + lambdas rather than an Android class
 *
 * The service has no tests and never will, and the bug class here is *bookkeeping* — which window manager
 * removes which view, whether an unplugged display's view is ever detached, whether the second display's
 * fact cache is its own. Bookkeeping is exactly what a fake can exercise, so the state machine lives here
 * as plain JVM code and the Android calls stay in one-line lambdas the service supplies.
 *
 * Robolectric was considered and rejected: its multi-display shadowing is thin, so a green test there
 * would be evidence about Robolectric rather than about One UI.
 *
 * ## The four rules that are structural rather than remembered
 *
 *  1. **The window manager that removes is the one that added**, because it lives in the attachment
 *     record. Getting this wrong leaks an overlay that cannot be taken down — a permanent cover with no
 *     exit, which is worse than the bug being fixed.
 *  2. **No context or window manager is cached across attachments.** DeX display ids are reported
 *     regenerated per session; a cached handle for a recycled id would `addView` onto a dead display,
 *     throw `InvalidDisplayException`, be swallowed by the caller's `runCatching`, and leave a **silent**
 *     no-overlay. Creating it at attach time removes the bug class instead of managing it.
 *  3. **Each attachment caches its own key and facts.** Left as the service-wide single fields they were,
 *     the first display's write populates the cache, the second display's view compares equal,
 *     `applyFacts` returns early, and the DeX block screen renders the layout's **placeholder** fact rows
 *     forever — rows whose own comment records that they quoted a wrong price, twice in the loosening
 *     direction. A block screen that lies about the price is a documented past P1 here.
 *  4. **Remove before add**, so a display id that is simultaneously removed and re-added (a DeX mode
 *     switch) cannot end up holding two views.
 *
 * A failed add is **remembered, not forgotten**: the id stays out of [covered], so
 * [DisplayCoverage.satisfied] is false, the census prints `ov=FAIL`, and the next tick puts the id back
 * into `plan.add` and retries. Retry is automatic and needs no extra state.
 *
 * @param W the platform window-manager type (`android.view.WindowManager` in the service).
 * @param V the platform view type (`android.view.View`).
 * @param F the rendered fact-row type.
 */
class DisplayOverlays<W : Any, V : Any, F : Any>(
    /** May a NEW overlay be added right now? (`Settings.canDrawOverlays`) */
    private val mayDraw: () -> Boolean,
    /** Display 0's window manager — the service's own lazy one, unchanged. */
    private val defaultWindowManager: () -> W?,
    /** Any other display's — `createDisplayContext(display).getSystemService(WINDOW_SERVICE)`. */
    private val secondaryWindowManager: (displayId: Int) -> W?,
    /** Inflate + bind + wire Close for [displayId]; null if it could not be built. */
    private val inflate: (displayId: Int, content: Content<F>) -> V?,
    /** `addView`, already wrapped in `runCatching` by the caller; false means it threw. */
    private val add: (W, V) -> Boolean,
    /** `removeView`, already wrapped in `runCatching` by the caller. */
    private val remove: (W, V) -> Unit,
    private val bindMessage: (V, CharSequence) -> Unit,
    private val bindFacts: (V, F) -> Unit,
) {

    /** What one display's block screen shows, and where its Close button should steer. */
    data class Content<F : Any>(
        val message: CharSequence,
        /** Identifies the CAUSE; the message is rewritten only when this changes. */
        val key: String,
        /** The rendered rows. Their numbers change without the cause changing, so they are not keyed. */
        val facts: F,
        /** The browser to steer away from a blocked page on THIS display, or null → Home. */
        val exitBrowserPkg: String? = null,
    )

    private class Attached<W : Any, V : Any, F : Any>(
        /** The window manager that added [view], and therefore the only one that may remove it. */
        val windowManager: W,
        val view: V,
        var key: String,
        var facts: F,
        var content: Content<F>,
    )

    private val attached = LinkedHashMap<Int, Attached<W, V, F>>()
    private val failedIds = LinkedHashSet<Int>()

    fun covered(): Set<Int> = attached.keys.toSet()

    fun failed(): Set<Int> = failedIds.toSet()

    /** What [displayId]'s overlay is currently showing — the Close button's steering comes from here. */
    fun contentOn(displayId: Int): Content<F>? = attached[displayId]?.content

    /**
     * Reconcile to exactly [want]: remove the stale, refresh the kept, add the missing.
     *
     * Returns what is **now** covered, which is what [DisplayCoverage.satisfied] and
     * [DisplayCoverage.homeFallback] are then asked about — never an assumption that the adds worked.
     *
     * [mayDraw] is consulted only before a NEW attach, exactly where `Settings.canDrawOverlays` sits
     * today: a revoked permission stops a new overlay and leaves existing ones up, which is what lets the
     * user reach the re-grant page at all.
     */
    fun reconcile(want: Map<Int, Content<F>>): Set<Int> {
        val plan = DisplayCoverage.plan(want.keys, attached.keys.toSet())
        plan.remove.forEach(::hideOn)
        for (id in plan.keep) {
            val a = attached[id] ?: continue
            val content = want[id] ?: continue
            if (a.key != content.key) {
                bindMessage(a.view, content.message)
                a.key = content.key
            }
            if (a.facts != content.facts) {
                bindFacts(a.view, content.facts)
                a.facts = content.facts
            }
            a.content = content
        }
        if (plan.add.isNotEmpty() && mayDraw()) {
            for (id in plan.add) want[id]?.let { attach(id, it) }
        }
        return covered()
    }

    fun hideOn(displayId: Int) {
        attached.remove(displayId)?.let { remove(it.windowManager, it.view) }
        failedIds.remove(displayId)
    }

    fun removeAll() {
        attached.keys.toList().forEach(::hideOn)
        failedIds.clear()
    }

    private fun attach(displayId: Int, content: Content<F>) {
        failedIds.remove(displayId)
        val windowManager =
            if (displayId == DisplayCensus.DEFAULT_DISPLAY) defaultWindowManager()
            else secondaryWindowManager(displayId)
        if (windowManager == null) {
            failedIds.add(displayId)
            return
        }
        val view = inflate(displayId, content)
        if (view == null) {
            failedIds.add(displayId)
            return
        }
        if (add(windowManager, view)) {
            attached[displayId] = Attached(windowManager, view, content.key, content.facts, content)
        } else {
            failedIds.add(displayId)
        }
    }
}
