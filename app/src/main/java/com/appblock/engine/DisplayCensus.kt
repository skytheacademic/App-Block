package com.appblock.engine

/**
 * Everything about *which displays existed this pass and what the pass looked like*: the scan order,
 * the backfill invariant, event attribution, the cross-display target merge, and both diagnostic
 * renderings.
 *
 * Imports nothing from Android on purpose — [DEFAULT_DISPLAY] is restated rather than borrowed — so the
 * whole of it is a JVM unit test away from the service that has none.
 *
 * ## Why the instrument is in the engine
 *
 * Because this repo has already shipped a diagnostic that lied. `AppBlockerAccessibilityService.diagnose`
 * carries its own note about it: the `HELD` field *"showed HELD zero times while the hold was in fact
 * doing its job — an instrument that lies about the thing it exists to measure."* The multi-display
 * change ships **unverified on DeX hardware**, and [line] carries the one field that can invalidate its
 * entire detection half ([untracked]). An instrument in that position must not itself be untested.
 *
 * ## Ordering is load-bearing, not tidiness
 *
 * [mergeTargets] feeds `refreshForeground` → `BudgetCoordinator.onForegroundTargets`, which calls
 * `bankTime()` and re-picks `currentTarget` every time the target *list* changes. A merge whose order
 * flapped between passes on unchanged content would re-bank every pass and split one sitting between two
 * budgets, so neither reaches its cap — **a loosening failure produced by nothing but a `HashMap`'s
 * iteration order.**
 *
 * Hence: default display first, then ascending id, first occurrence winning, deterministic for equal
 * input. And hence deliberately **not** active-display-first, which would reorder the merged list
 * whenever the user touched the other screen — the flap above, driven by a finger.
 */
object DisplayCensus {

    /** `android.view.Display.DEFAULT_DISPLAY`, restated so this file imports nothing from Android. */
    const val DEFAULT_DISPLAY: Int = 0

    /** Our block overlay's state on one display. [FAILED] is `addView` having thrown. */
    enum class Overlay { NONE, UP, FAILED }

    /**
     * One display's evidence for this pass — what the census prints and what coverage is decided from.
     *
     * [windowCount] is nullable and **null and zero are different answers.** Zero means accessibility
     * gave us a list for this display and it was empty, which is what a *mirrored* monitor looks like.
     * Null means `DisplayManager` listed the display and accessibility gave **no list at all**, i.e. we
     * are blind to it. Collapsing the two into "no evidence" would hide the single most important
     * unknown in the multi-display change behind a value that looks routine.
     */
    data class Display(
        val id: Int,
        /** `DisplayManager.getDisplays()` listed it for this uid. */
        val enumerated: Boolean,
        /** Windows in this display's OWN accessibility list; **null = no list at all**. */
        val windowCount: Int?,
        /** Topmost package's last segment. Log only — never a full package name. */
        val topPackage: String? = null,
        /** The target resolved from THIS display's own read. Log only. */
        val target: String? = null,
        /** A target or web block resolved from THIS display's own read, this pass. */
        val carriesCause: Boolean = false,
        /** An occlusion hold is sustaining a cause for this display. */
        val held: Boolean = false,
        val overlay: Overlay = Overlay.NONE,
    ) {
        val tracked: Boolean get() = windowCount != null
        val blockable: Boolean get() = carriesCause || held
    }

    /**
     * Deterministic scan order: the phone first, then ascending id. See the class doc.
     *
     * ⚠️ **Honest note on the explicit promotion:** for real display ids, which are non-negative, this
     * is *indistinguishable from a plain ascending sort* — 0 sorts first anyway. Mutating the promotion
     * out fails no test, and pretending otherwise would be exactly the kind of overclaimed "proven to
     * bite" label this project rejects. It is kept for two reasons that are not behavioural: it states
     * the intent at the point where a reader would otherwise have to re-derive it, and it is correct if
     * a negative id (`INVALID_DISPLAY` is −1) ever reaches here despite [attribute].
     *
     * The ordering guarantee that **does** bite is against `values.flatten()` — see [mergeTargets] and
     * its tests. Determinism is the property that matters; the promotion is how it is spelled.
     */
    fun order(displayIds: Collection<Int>): List<Int> {
        val sorted = displayIds.toSortedSet()
        if (!sorted.remove(DEFAULT_DISPLAY)) return sorted.toList()
        return ArrayList<Int>(sorted.size + 1).apply {
            add(DEFAULT_DISPLAY)
            addAll(sorted)
        }
    }

    /**
     * Which display an event belongs to, or **null when it must be dropped**.
     *
     * `Display.INVALID_DISPLAY` is `-1`, not `0`, so an unpopulated id is *detectable* rather than
     * silently masquerading as the phone. That distinction is the whole point: filing an unattributable
     * event under [DEFAULT_DISPLAY] would let it **release display 0's hold**, and a wrong release is the
     * loosening direction. Dropping it is free — `OcclusionHold.sustain` already treats a null package as
     * "no basis to move on" — so the strict answer costs nothing.
     */
    fun attribute(eventDisplayId: Int?): Int? =
        if (eventDisplayId == null || eventDisplayId < 0) null else eventDisplayId

    /**
     * True when the all-displays read owes the default display a window list.
     *
     * **Invariant I2 — display 0 is never worse than it is today.** The research grades "a single-display
     * device returns one entry keyed 0" as *likely*, not confirmed on hardware. This turns that inference
     * into a rule the caller enforces: if the all-displays read came back empty, or without key 0, key 0
     * is refilled from the legacy `getWindows()` call that has always worked.
     */
    fun mustBackfillDefault(displayIds: Set<Int>): Boolean = DEFAULT_DISPLAY !in displayIds

    /**
     * Every foreground target across every display, in scan order, first occurrence winning.
     *
     * A **list**, not a set: `BudgetCoordinator.decideCurrent` walks it in order, and the block screen
     * quotes the reported target's price.
     */
    fun mergeTargets(perDisplayTargets: Map<Int, List<Target>>): List<Target> =
        order(perDisplayTargets.keys).flatMap { perDisplayTargets[it].orEmpty() }.distinct()

    /**
     * Displays `DisplayManager` enumerated that accessibility gave **no window list for** — the field.
     *
     * `untracked=[3]` on the first cable session says the detection half is dead on this hardware and no
     * amount of overlay work will fix it: `AccessibilityManagerService.isValidDisplay()` excludes some
     * display types outright, which is unverifiable from source. One glance answers it.
     */
    fun untracked(displays: List<Display>): List<Int> =
        order(displays.filter { it.enumerated && !it.tracked }.map { it.id })

    /**
     * The ` +dN{…}` suffix appended to the `AppBlockFg` line, or **the empty string when only the
     * default display exists**.
     *
     * Empty is not a nicety. It keeps that line character-for-character what it is today on a phone with
     * no monitor, so every existing log note in the repo stays valid and the line's own
     * string-equality dedup does not start churning.
     */
    fun blocks(displays: List<Display>): String =
        displays.filter { it.id != DEFAULT_DISPLAY }
            .sortedBy { it.id }
            .joinToString("") { " +d${it.id}{${fields(it)}}" }

    /**
     * The whole `AppBlockDsp` line. The caller deduplicates it on string equality, as the other two
     * diagnostics do.
     *
     * [allDisplaysApi] false on an Android 16 phone means the SDK guard is inverted and the fix never
     * ran — which is exactly the kind of silent config failure this project keeps meeting.
     */
    fun line(
        displays: List<Display>,
        allDisplaysApi: Boolean,
        cover: Set<Int>,
        covered: Set<Int>,
        holds: String,
        /** Each window's own `getDisplayId()` cross-checked against the map key; null = not checked. */
        crossCheck: String? = null,
        /** Ids Samsung's DESKTOP display category names. **Annotation only, never a mechanism.** */
        dexDisplays: List<Int>? = null,
    ): String {
        val enumerated = order(displays.filter { it.enumerated }.map { it.id })
        val head = "api=${if (allDisplaysApi) "all" else "legacy"} dm=$enumerated " +
            "untracked=${untracked(displays)} cover=${order(cover)} covered=${order(covered)} " +
            "sat=${covered.containsAll(cover)} holds=$holds" +
            (crossCheck?.let { " xdisp=$it" } ?: "") +
            (dexDisplays?.let { " dex=${order(it)}" } ?: "")
        val rows = order(displays.map { it.id }).mapNotNull { id ->
            displays.firstOrNull { it.id == id }?.let { d ->
                "${d.id}${if (d.id == DEFAULT_DISPLAY) "*" else ""} ${fields(d)}"
            }
        }
        return (listOf(head) + rows).joinToString(" | ")
    }

    /** The per-display field group, shared by [blocks] and [line] so the two can never disagree. */
    private fun fields(d: Display): String = buildString {
        append("w=").append(d.windowCount ?: "?")
        d.topPackage?.let { append(" top=").append(it) }
        d.target?.let { append(" target=").append(it) }
        if (d.carriesCause) append(" cause")
        if (d.held) append(" held")
        append(" ov=").append(
            when (d.overlay) {
                Overlay.NONE -> "-"
                Overlay.UP -> "UP"
                Overlay.FAILED -> "FAIL"
            },
        )
    }
}
