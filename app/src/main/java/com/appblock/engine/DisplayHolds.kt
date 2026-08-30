package com.appblock.engine

/**
 * One [OcclusionHold] per logical display, plus the whole per-pass fold, plus the lifecycle a *map* of
 * holds needs and a single hold does not.
 *
 * It **composes** [OcclusionHold] rather than modifying it. That class is the only mechanism in this
 * project whose behaviour was settled by measuring a phone, and its test suite stays valid byte for byte.
 *
 * ## Why the hold must be per display
 *
 * The pruning [OcclusionHold] exists to survive is computed **strictly per display**:
 * `AccessibilityWindowManager.DisplayWindowsObserver` walks that display's own unaccounted space and
 * breaks early once that screen is accounted for. So covering the DeX display prunes the DeX window list
 * exactly as measured on display 0 (Instagram 692 nodes → 1–2; fully opaque, dropped from the list
 * altogether) **while display 0's list is untouched**. One global hold has to answer one question that
 * has two different right answers, and gets one of them wrong whichever way it leans.
 *
 * ## The bypass this class exists to prevent, concretely
 *
 * 🔴 TikTok blocked on the monitor, hold armed. The user taps the launcher on the phone. A single global
 * `lastWindowPackage` becomes the launcher, the moved-on test fires, and **the monitor's** overlay comes
 * down — an unobstructed reel for as long as it takes the next pass to notice, repeatable at will with
 * one tap. That is the C-1 flash with a trigger the user controls instead of a 60-second timer, and C-1
 * is already on this repo's record as a bypass rather than a nuisance.
 *
 * ## Why the latch lives outside the holds
 *
 * [OcclusionHold.noteForegroundEvent] is a latch about *whether this channel works on this device*,
 * deliberately not cleared by `release()`. A hold created lazily **after** its display's first event would
 * start with the 60-second backstop re-armed — the measured 148–256 ms unobstructed, tappable gap once a
 * minute, returning through a side door. So [eventSeen] is kept beside the map and replayed into any hold
 * created later.
 *
 * It is per display because "the event stream reports foreground changes" is a fact about *a channel*, and
 * there is one channel per display. Retiring display 3's backstop because display 0's events arrive would
 * leave a display whose events never come with **no exit at all**. Per display, such a display keeps the
 * same 60-second backstop the phone has always had, and says so in the diagnostic (`3=HELD!`).
 *
 * @param T the read type, so the service's private `Foreground` stays private to the service.
 */
class DisplayHolds<T : Any>(
    private val holdLimitMs: Long = OcclusionHold.DEFAULT_HOLD_LIMIT_MS,
) {

    private val holds = LinkedHashMap<Int, OcclusionHold<T>>()
    private val lastPackage = HashMap<Int, String>()
    private val eventSeen = HashSet<Int>()

    /** Displays currently holding a read — diagnostics, and the caller's "seed me once" check. */
    val armedDisplays: Set<Int>
        get() = holds.filterValues { it.isArmed }.keys.toSet()

    fun isArmed(displayId: Int): Boolean = holds[displayId]?.isArmed == true

    /** True while [displayId]'s timeout backstop is still load-bearing. Diagnostics. */
    fun backstopArmed(displayId: Int): Boolean = holds[displayId]?.backstopArmed ?: (displayId !in eventSeen)

    /** The package this display's event stream last named, or null. Diagnostics. */
    fun packageOn(displayId: Int): String? = lastPackage[displayId]

    /**
     * This display's event stream just named a foreground package.
     *
     * Never creates a hold: an event is not evidence that anything is being blocked, and creating holds
     * from the event stream would populate the map with every display the user merely touches.
     */
    fun noteForegroundEvent(displayId: Int, packageName: String) {
        lastPackage[displayId] = packageName
        eventSeen.add(displayId)
        holds[displayId]?.noteForegroundEvent()
    }

    /**
     * The whole pass, every display, no exceptions — **this signature is invariant I1.**
     *
     * > *An event says WHEN to look, never WHERE.*
     *
     * Accessibility event coalescing is keyed by `eventType` **alone**, with no display dimension, and
     * this service declares `notificationTimeout="100"`. Two window-state changes 100 ms apart on two
     * displays collapse into one delivered event and the older is recycled and dropped — which is
     * *routine* the moment a monitor is plugged in, because both launchers settle together. So there is
     * no parameter here naming the display an event came from. The rule is unstateable wrongly.
     *
     * Folds `reads.keys ∪ holds.keys`, ascending with the default display first. The union half is
     * load-bearing: **a fully covered display is dropped from its own window list**, so iterating reads
     * alone would take the overlay down on the very display being blocked.
     *
     * Returns `held ?: raw` per display rather than only the blockable entries, matching today's
     * `sustain(...) ?: read`. The caller sets `surfaceAppVisible` / `browserVisible` from these and uses
     * them to keep the 5-second tick alive; dropping non-blockable reads would stop the heartbeat for an
     * under-cap Instagram feed — a single-display fail-open introduced by a multi-display change.
     */
    fun effective(
        reads: Map<Int, T>,
        blockable: (T) -> Boolean,
        justifiedBy: (T) -> Set<String>,
        covered: Set<Int>,
        nowMs: Long,
    ): Map<Int, T> {
        val ids = DisplayCensus.order(reads.keys + holds.keys)
        val out = LinkedHashMap<Int, T>(ids.size)
        for (id in ids) {
            val raw = reads[id]
            if (id !in covered) {
                // Today's `if (overlayView == null) { occlusionHold.release(); return read }`.
                holds[id]?.release()
                if (raw != null) out[id] = raw
                continue
            }
            if (raw != null && blockable(raw)) {
                // A real read got through the pruning: re-arm on it and restart the countdown.
                // Armed on what the READ says is on screen, never on `lastPackage` — the event stream's
                // most recent name is one of possibly several live apps, and picking it made the hold
                // fight the other split-screen pane. See OcclusionHold.heldPackages.
                holdFor(id).arm(raw, justifiedBy(raw), nowMs)
                out[id] = raw
                continue
            }
            (holds[id]?.sustain(lastPackage[id], nowMs) ?: raw)?.let { out[id] = it }
        }
        return out
    }

    /** Arm only if nothing is held for [displayId] — see [OcclusionHold.seed] for why not `arm`. */
    fun seed(displayId: Int, value: T, justifiedBy: Set<String>, nowMs: Long) {
        holdFor(displayId).seed(value, justifiedBy, nowMs)
    }

    fun release(displayId: Int) {
        holds[displayId]?.release()
    }

    fun releaseAll() {
        holds.values.forEach { it.release() }
    }

    /**
     * Drop everything belonging to a display that no longer exists.
     *
     * ⚠️ **Guarded twice, and both guards are load-bearing in opposite directions.** DeX display ids are
     * reported regenerated per session, so a held read must never outlive its display — but an **empty**
     * live set means the read failed, not that every display vanished, and [DisplayCensus.DEFAULT_DISPLAY]
     * must survive a momentarily empty map.
     *
     * Over-eager eviction drops the phone's live block (fail-open). Never evicting keeps a dead id in the
     * cover set forever, so `DisplayCoverage.satisfied` is permanently false and the service is pinned in
     * a kick-to-home loop (fail-closed, and unusable).
     */
    fun retain(liveDisplayIds: Set<Int>) {
        if (liveDisplayIds.isEmpty()) return
        val keep = liveDisplayIds + DisplayCensus.DEFAULT_DISPLAY
        holds.keys.retainAll(keep)
        lastPackage.keys.retainAll(keep)
        // A returning id is NOT evidence that the old display's event channel worked, so the latch goes
        // with it — a re-plugged monitor starts with its backstop armed again, the strict direction.
        eventSeen.retainAll(keep)
    }

    /** `0=- 3=HELD!` — `HELD` means armed, and the `!` means that display's backstop is still armed. */
    fun describe(): String = DisplayCensus.order(holds.keys).joinToString(" ") { id ->
        val hold = holds[id]
        val state = if (hold?.isArmed == true) "HELD" else "-"
        val backstop = if (hold?.backstopArmed == true) "!" else ""
        "$id=$state$backstop"
    }

    private fun holdFor(displayId: Int): OcclusionHold<T> = holds.getOrPut(displayId) {
        // The latch is a fact about the device, not about any one hold: a hold created after its
        // display's first event must NOT start with the 60 s backstop re-armed. (C-1, side door.)
        OcclusionHold<T>(holdLimitMs).also { if (displayId in eventSeen) it.noteForegroundEvent() }
    }
}
