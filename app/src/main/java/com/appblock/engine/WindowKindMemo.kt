package com.appblock.engine

/**
 * Remembers which packages have been seen owning an **application** window and which have only ever
 * been seen owning system chrome, so a foreground event can be judged even when its own window type
 * cannot be read.
 *
 * ## Why this exists (measured 2026-08-30, S25 FE / One UI 8)
 *
 * `OcclusionHold`'s moved-on test releases the block when an event names a package that is not one of
 * the blocked ones. The volume panel breaks that: pressing volume fires a `TYPE_WINDOW_STATE_CHANGED`
 * from `com.android.systemui`, the hold reads it as *"the user left"*, and the block screen drops for
 * 40–80 ms on **every press** — a flicker with a trigger the user controls, which is C-1's shape.
 *
 * The obvious fix — ignore events whose window is not `TYPE_APPLICATION` — was built, shipped to the
 * phone, and **broke the exit**: 0/5 Home presses released the block. The reason is the same blindness
 * `OcclusionHold` exists to work around. **While our overlay is up the window list is pruned, so the
 * launcher's window is not in it either:**
 *
 * ```
 * overlay DOWN:  launcher type=1 x7, null x4   systemui type=1 x0, null x2, TYPE_SYSTEM x1
 * overlay UP:    launcher type=null, every time, with no resolvable follow-up
 * ```
 *
 * The condition under which the test matters is exactly the condition under which the type is
 * unreadable. So the type has to be learned when it *is* readable — which is most of the time, because
 * the overlay is down whenever nothing is blocked.
 *
 * ## The safety property, which is the whole design
 *
 * [isSystemOnly] can only ever be true for a package **positively observed** owning a non-application
 * window and **never** observed owning an application one. Everything else — unknown packages, packages
 * seen only while pruned, a fresh install that has learned nothing — answers false, i.e. *"treat it as a
 * real foreground change"*, which is today's behaviour.
 *
 * So the worst case is the flicker it was written to remove, never a block screen that will not lift.
 * A memo that has learned nothing is exactly as safe as no memo at all. That asymmetry is deliberate:
 * releasing wrongly costs a frame, holding wrongly costs the user their phone.
 *
 * ⚠️ **Application evidence is permanent and outranks system evidence, in both orders.** A package seen
 * as an application is never added to the system-only set afterwards, and one already in that set is
 * removed the moment it is seen as an application. Without the second half, a launcher that happened to
 * be sampled as system chrome once would be muted forever — turning a nuisance into the lockout this
 * class is built to avoid.
 *
 * ## Why it is saved (0.10.2)
 *
 * In memory only, the memo started empty after every install, update and reboot, and until the volume
 * panel had been seen once *with nothing blocked*, a press over a block dropped the overlay. Measured
 * 2026-10-01 on the S25: **3 of 16 presses cold, 0 of 24 once learned.** The block screen is most likely
 * to be up right after a reboot, which is exactly when the memo used to know nothing.
 *
 * So the service saves [snapshot] whenever [note] learns something and [restore]s it on connect. Saving
 * cannot loosen anything, because [restore] keeps the safety property above: it **merges** with what is
 * already known and re-applies "application outranks system" across both, so a saved system sighting
 * can never mute a package seen as an application in this lifetime, or vice versa.
 */
class WindowKindMemo {

    /** What is worth keeping across a restart: both sets, because the application set is what vetoes. */
    data class Snapshot(val application: Set<String>, val systemOnly: Set<String>)

    private val application = HashSet<String>()
    private val systemOnly = HashSet<String>()

    /**
     * Record that [packageName] was seen owning a window of [windowType], or nothing at all when the
     * type could not be read — an unreadable type teaches nothing and must not be guessed at.
     *
     * [applicationType] is `AccessibilityWindowInfo.TYPE_APPLICATION`, passed in so this class stays
     * free of Android imports and testable on the JVM.
     *
     * Returns true when either set changed — the caller's cue to save. Almost every event teaches
     * nothing new, so this keeps the save off the per-event path.
     */
    fun note(packageName: String, windowType: Int?, applicationType: Int): Boolean {
        if (windowType == null) return false
        return if (windowType == applicationType) {
            val added = application.add(packageName)
            systemOnly.remove(packageName) || added
        } else if (packageName !in application) {
            systemOnly.add(packageName)
        } else {
            false
        }
    }

    /** Both sets, copied — what the service saves. */
    fun snapshot(): Snapshot = Snapshot(application.toSet(), systemOnly.toSet())

    /**
     * Merge a saved [Snapshot] into what this lifetime has already learned.
     *
     * A merge, not a replace, because the service may have noted events before the saved copy was
     * read. Application evidence from **either** side clears system evidence from **either** side,
     * which is the same rule [note] applies one sighting at a time.
     */
    fun restore(saved: Snapshot) {
        application.addAll(saved.application)
        systemOnly.addAll(saved.systemOnly)
        systemOnly.removeAll(application)
    }

    /**
     * True only when [packageName] has been seen owning a non-application window and never an
     * application one — the one case where an event from it must not be read as the user leaving.
     */
    fun isSystemOnly(packageName: String): Boolean = packageName in systemOnly

    /** `sysOnly=[com.android.systemui] app=3` — diagnostics. */
    fun describe(): String = "sysOnly=${systemOnly.sorted()} app=${application.size}"
}
