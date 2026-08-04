package com.appblock.engine

/**
 * "Is the accessibility service actually running?", answered so that a dying instance cannot lie about
 * a living one.
 *
 * ## ⚠️ What this is NOT — the 2026-08-04 report was a measurement artefact
 *
 * This class was written to fix an apparent hardware defect: the Lock tab's protection list read *"The
 * service isn't running, so nothing is blocked"* while the service was demonstrably blocking. **That
 * defect was not real, and this class did not fix it.** Installed and re-checked on hardware the next
 * morning, the row still read dead — and the reason was the instrument:
 *
 * **`uiautomator dump` connects Android's own UiAutomation, which unbinds every other accessibility
 * service for as long as it is attached.** Every reading was taken through a dump, so every dump
 * destroyed the service, the 1 Hz UI poll then *correctly* recorded `false`, Compose recomposed, and
 * only then was the tree captured. The window survived "an activity relaunch" and "a HOME → reopen"
 * because each of those was verified with another dump, which re-created the condition each time.
 *
 * Re-observed with `adb shell screencap`, which touches no accessibility state, the same build on the
 * same phone reads **"Blocking service — running ✓"**. The UI was telling the truth throughout; the
 * reported symptom was manufactured by the act of looking. Traced end to end in logcat: every
 * `onUnbind → onDestroy → onCreate → onServiceConnected` cycle on this device is strictly ordered and
 * settles back to `running=true`, so the interleaving below was never once observed.
 *
 * **The reusable lesson is about the instrument, not the code: a probe that shares a mechanism with the
 * thing being probed cannot be trusted to observe it.** For this project that hardens into a rule —
 * never verify App-Block's own UI with `uiautomator dump`; use `screencap` and read the image.
 *
 * ## Why the class stays anyway
 *
 * Because the race it guards is real in Android's contract even though it did not happen here. Android
 * does not guarantee the old instance is destroyed before the replacement is connected. Both orderings
 * are permitted, and one of them poisons a shared flag permanently:
 *
 * ```
 *   A.onServiceConnected()   isRunning = true
 *   B.onServiceConnected()   isRunning = true     // replacement is live and serving
 *   A.onDestroy()            isRunning = false    // the corpse writes last
 *   ...                                           // nothing sets it true again, ever:
 *                                                 // onServiceConnected has already run for the last time
 * ```
 *
 * **A liveness flag shared by two instances of one class is not a flag, it is a race.** The old doc
 * comment claimed *"true only while the system has this service running"* — a statement about the
 * **system** — while the code actually reported whichever **instance** touched it most recently.
 *
 * ## Why it is an identity check and not, say, a counter
 *
 * A counter (`+1` on connect, `-1` on destroy) also survives the interleaving above, but it drifts: any
 * `onDestroy` the framework skips (process death, a crash after connect) leaks a permanent `+1` and the
 * service reads as alive forever — failing **open**, which for a blocker is the worse direction. Holding
 * the instance cannot drift: there is exactly one live claim at a time, a replacement overwrites it, and
 * a process death takes the whole object graph with it so the next process starts from `null`.
 *
 * ## Why the guard is worth its keep
 *
 * `Watchdog.health()` reads the same answer and maps `!serviceRunning -> SERVICE_DEAD`, so a poisoned
 * flag would post *"App-Block is not protecting you"* every 15 minutes, permanently, while the app
 * worked perfectly — the exact *"a notification that can't be dismissed needs code that withdraws it"*
 * failure Batch B already fixed once, arrived at from the opposite direction. A commitment device that
 * cries wolf is one the user eventually deletes, and this one had already been deleted once. Cheap
 * insurance against a permanent false alarm is worth an extra class even with the race unobserved.
 *
 * A related truth the tracing did confirm, and it is not a defect: while any other accessibility-based
 * tool is attached, this service really is destroyed and really is not blocking. `isRunning` reports
 * that honestly. The watchdog can therefore sample a genuine `false` during such a window — rare, since
 * it needs a second a11y tool running, but it is the real reason this answer must stay truthful rather
 * than being made optimistic.
 *
 * Pure and Android-free on purpose, so the interleaving above is testable without a device — the same
 * reason [OcclusionHold] and [AddressWatch] were lifted out of the service.
 */
class ServiceLiveness {

    /**
     * The one instance currently claiming to be live, or null.
     *
     * `@Volatile` because the writers are lifecycle callbacks on the main thread while the readers are
     * the UI poll and the watchdog worker, which are not.
     */
    @Volatile
    private var current: Any? = null

    /** True while some instance holds the claim. */
    val isRunning: Boolean get() = current != null

    /** [instance] is now serving. Any previous claim is superseded — last connect wins. */
    fun connected(instance: Any) {
        current = instance
    }

    /**
     * [instance] is being torn down.
     *
     * The identity check is the whole point: an instance that has **already been replaced** is a
     * corpse, and a corpse must not be able to retract a claim it no longer owns.
     */
    fun destroyed(instance: Any) {
        if (current === instance) current = null
    }
}
