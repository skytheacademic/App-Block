package com.appblock.engine

/**
 * The colon-separated Secure settings that point an accessibility *shortcut* at a service, and the
 * rules for reading one and for taking ourselves back out of it.
 *
 * ## Why this exists
 *
 * `flagRequestAccessibilityButton` (N-1) is what stops any accessibility shortcut from toggling the
 * service off, and it is not optional: without it the volume-key chord is a two-second, keyless off
 * switch. The cost of holding it is that One UI adds App-Block to `accessibility_button_targets`
 * **unasked**, and with gesture navigation draws the floating button for it, which One UI parks as a
 * tab on the edge of the screen. Verified on the S25 2026-08-29; see
 * `res/xml/accessibility_service_config.xml` for the whole chain.
 *
 * Until 2026-09-08 the app's answer was a sentence on the Lock tab naming the claim and one adb
 * command to clear it. **That command does not hold.** Reported from the phone: the button was gone
 * after clearing it, and back after the next restart. The reason is that clearing the setting does
 * not change anything Android decides it *from*: the service still requests the accessibility button
 * and is still enabled, so the next time the framework reads its accessibility configuration, which
 * it does on every boot, it puts the target back exactly as it did at install. A one-shot write
 * loses to something that re-derives its answer, however many times the write is repeated.
 *
 * So the write has to happen every time the target reappears, which makes it the app's job rather
 * than the cable's. [com.appblock.service.ShortcutTargetGuard] is that job; this file is the part of
 * it with no Android in it: which entries are ours, what the value should read after ours are gone,
 * and how many times we are willing to answer before we stop and let the Lock tab say so.
 */
object ShortcutTargets {

    /**
     * The floating button / gesture target. `@hide` in the framework, so the key is written out; the
     * *value* is the stable part and this is the one Android fills in for us, unasked.
     */
    const val BUTTON_TARGETS = "accessibility_button_targets"

    /**
     * The volume-key chord's target. Read, never written: nothing puts a service here without someone
     * choosing it on the shortcut screen, and quietly undoing a choice a person made is a different
     * act from removing one they were never asked about. It also cannot hurt us, because N-1 means the
     * chord can only ever turn the service *on*. Same reasoning covers `accessibility_qs_targets`
     * (API 34+, a tile the user drags into Quick Settings), which is why it is not here either.
     */
    const val CHORD_TARGET = "accessibility_shortcut_target_service"

    /** Both keys, for the read that feeds the Lock tab's protection row. */
    val READ_KEYS: List<String> = listOf(BUTTON_TARGETS, CHORD_TARGET)

    /**
     * True while any entry in [value] names this component.
     *
     * Two shapes have to match, because Samsung writes both and the difference is invisible on the
     * phone: the long form `com.appblock/com.appblock.service.AppBlockerAccessibilityService` and the
     * short form `com.appblock/.service.AppBlockerAccessibilityService`, where the class is relative
     * to the package. A plain `contains(className)` catches the first and misses the second, which is
     * how the read this replaces could have said "no shortcut" while the button was on screen.
     */
    fun claims(value: String?, packageName: String, className: String): Boolean =
        entries(value).any { namesUs(it, packageName, className) }

    /**
     * The value [BUTTON_TARGETS] should hold once our entries are gone, or null when there is nothing
     * of ours in it and therefore nothing to write.
     *
     * Other services' entries survive: this removes one claim, it does not clear the shortcut. A real
     * accessibility tool sharing the button keeps it, and the button keeps pointing at that tool. The
     * empty string is the answer when we were the only entry, and that is what makes the button go
     * away.
     */
    fun withoutSelf(value: String?, packageName: String, className: String): String? {
        val entries = entries(value)
        val kept = entries.filterNot { namesUs(it, packageName, className) }
        if (kept.size == entries.size) return null
        return kept.joinToString(":")
    }

    /** Non-empty entries, whitespace trimmed. An absent setting reads as no entries, not as a fault. */
    fun entries(value: String?): List<String> =
        value.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Whether one entry names this component, by either flattening, or contains its fully-qualified
     * class name outright.
     *
     * The last clause is the old substring rule kept as a floor. It can only ever fire on a shape the
     * parse did not recognise, and it is safe to *remove* on as well as report on, because no other
     * package on the phone contains `com.appblock.service.AppBlockerAccessibilityService`. Entries
     * that are not components at all pass straight through: the framework's own magnification target
     * is a bare class name with no slash in it, and it is none of our business.
     */
    private fun namesUs(entry: String, packageName: String, className: String): Boolean {
        if (entry.contains(className, ignoreCase = true)) return true
        val slash = entry.indexOf('/')
        if (slash <= 0 || slash == entry.length - 1) return false
        val pkg = entry.substring(0, slash)
        val rawClass = entry.substring(slash + 1)
        val cls = if (rawClass.startsWith(".")) pkg + rawClass else rawClass
        return pkg.equals(packageName, ignoreCase = true) && cls.equals(className, ignoreCase = true)
    }
}

/**
 * How many times the app will take itself back out of [ShortcutTargets.BUTTON_TARGETS] before it
 * stops and leaves the claim standing.
 *
 * There is a version of this loop that never ends. We remove the entry; the framework notices the
 * setting changed, re-reads its accessibility configuration, decides an enabled service that requests
 * the accessibility button belongs in the list, and writes it back; our observer fires. Whether One
 * UI actually closes that circle is **not verified from here** and cannot be without the phone, so
 * this is written as though it does. A budget is the difference between "the fix did not take" and a
 * pair of processes writing a Secure setting at each other for as long as the phone is on.
 *
 * Five per hour, on the monotonic clock rather than the wall clock, because the wall clock is the one
 * this app already assumes is being lied to (see [com.appblock.engine.DayCorroboration]). One clear
 * per boot is the expected cost; the other four cover a re-add from the picker in the same hour. Past
 * that the app holds until the window rolls, the Lock tab keeps naming the claim, and the watchdog's
 * own sweep still gets one attempt every fifteen minutes, so a budget spent by a fight is never a
 * door left open quietly.
 */
class ShortcutTargetSweep(
    private val budget: Int = MAX_CLEARS_PER_WINDOW,
    private val windowMs: Long = WINDOW_MS,
) {

    private var windowStartMs = 0L
    private var spent = 0
    private var started = false

    /** What the caller should do about [value] right now. */
    sealed interface Decision {
        /** Write this back to [ShortcutTargets.BUTTON_TARGETS]; ours are gone, everyone else's stay. */
        data class Clear(val value: String) : Decision

        /** Nothing of ours in the setting. The common case, and the one that ends the loop. */
        data object AlreadyClear : Decision

        /** The budget for this window is gone. Hold, and let the Lock tab carry the claim. */
        data object Spent : Decision
    }

    /**
     * Decides, and spends a clear from the budget when it returns [Decision.Clear] — so a caller that
     * then fails to write has still spent the attempt. That is deliberate: a write that keeps failing
     * is exactly the case the budget is for.
     */
    fun decide(
        elapsedMs: Long,
        value: String?,
        packageName: String,
        className: String,
    ): Decision {
        val cleared = ShortcutTargets.withoutSelf(value, packageName, className)
            ?: return Decision.AlreadyClear
        if (!started || elapsedMs - windowStartMs >= windowMs || elapsedMs < windowStartMs) {
            // The last clause covers the one way elapsedRealtime() moves backwards: a fresh boot,
            // where the counter restarts at zero and the previous window is not a window at all.
            started = true
            windowStartMs = elapsedMs
            spent = 0
        }
        if (spent >= budget) return Decision.Spent
        spent++
        return Decision.Clear(cleared)
    }

    companion object {
        const val MAX_CLEARS_PER_WINDOW = 5
        const val WINDOW_MS = 60L * 60L * 1000L
    }
}
