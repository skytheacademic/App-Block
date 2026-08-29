package com.appblock.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.appblock.engine.ChangeDirection
import com.appblock.engine.ChangeResult
import com.appblock.engine.DurableChangeGate
import com.appblock.engine.DurableSettings
import com.appblock.engine.FieldChange
import com.appblock.engine.RuleStore
import com.appblock.engine.Schedule
import com.appblock.engine.Target
import com.appblock.engine.TargetSettings

/**
 * The edit-in-progress, hoisted above the tabs.
 *
 * Apps is where rules are *edited* and Lock is where they are *committed*, so the draft cannot live
 * inside either — the old screen could keep it local only because editing and saving shared one
 * scroll. Splitting them by subject is the whole point of the redesign; hoisting the draft is the
 * price, and it makes the invariant explicit: there is exactly one draft, and exactly one place it
 * lands.
 *
 * [DurableChangeGate] still judges the whole [DurableSettings] at once, so one loosening anywhere
 * gates the entire save. That is deliberate and unchanged — which is why Lock shows the *full* diff
 * rather than only the offending line.
 */
@Stable
class RulesDraft(private val store: RuleStore) {

    /** What is actually in force. Reloaded after every save. */
    var saved by mutableStateOf(store.load())
        private set

    /** What the sheets are editing. Nothing here is enforced until it lands via [commit]. */
    var draft by mutableStateOf(saved)

    val dirty: Boolean get() = draft != saved

    /**
     * Every field-level difference, in the gate's own words — the Lock tab's diff.
     *
     * Loosenings first (stable, so the rest keep the gate's order). Two readers depend on it: the
     * Lock tab lists at most six changes, and the save receipt quotes the *first* one as what the
     * window was spent on. Since the gate began reporting a flipped switch and its riders separately
     * (G-1), a draft can carry a free "turned on" beside the loosening that actually gated it, and
     * the loosening is the line both readers must not lose.
     */
    val changes: List<FieldChange>
        get() = DurableChangeGate.changes(saved, draft)
            .sortedByDescending { it.direction == ChangeDirection.LOOSEN }

    val direction: ChangeDirection get() = DurableChangeGate.classify(saved, draft)

    val loosens: Boolean get() = direction == ChangeDirection.LOOSEN

    /**
     * How many separate loosenings this draft contains. A window buys **one** change (CONSTRAINTS §6),
     * so this is counted here as well as inside the gate: the rule has to be visible *before* Save,
     * not only as a rejection afterwards. A greyed Save with its reason on screen beats one that looks
     * live and then refuses.
     */
    val looseningCount: Int get() = DurableChangeGate.looseningReasons(saved, draft).size

    /** Set when the saved config couldn't be read and build defaults were substituted. */
    fun corruptBlob(): String? = store.corruptBlob()

    fun update(target: Target, block: (TargetSettings) -> TargetSettings) {
        val current = draft.targets[target] ?: return
        draft = draft.copy(targets = draft.targets + (target to block(current)))
    }

    fun setEnabled(target: Target, enabled: Boolean) = update(target) { it.copy(enabled = enabled) }

    fun setSchedule(target: Target, schedule: Schedule?) = update(target) { it.copy(schedule = schedule) }

    /**
     * Add a target the settings didn't have. A tightening — the gate reads an absent target as fully
     * open — so it saves freely, which is the asymmetry that lets an on-device "block another app"
     * exist at all without becoming a bypass.
     */
    fun add(target: Target, settings: TargetSettings) {
        draft = draft.copy(targets = draft.targets + (target to settings))
    }

    /**
     * Drop a user-added target. Only edits the draft: the gate classifies a vanished target as a
     * loosening on its own, so removal costs the same 2-hour window as turning one off.
     */
    fun remove(target: Target) {
        draft = draft.copy(targets = draft.targets - target)
    }

    fun discard() {
        draft = saved
    }

    /** Pick up an external change (a re-seed, or the settings-watch bounce) without losing the draft. */
    fun reload() {
        val fresh = store.load()
        if (fresh != saved) {
            val wasClean = !dirty
            saved = fresh
            if (wasClean) draft = fresh
        }
    }

    /**
     * The one commit point. Returns [ChangeResult.Blocked] when the edit loosens and no window is
     * open — the caller says why and routes to Lock; it never quietly writes a partial set.
     *
     * [onLooseningAccepted] runs when the gate has accepted a loosening, **before** the rules are
     * written: it is where the caller spends the window. The order is the fail-safe one. A process
     * death between the two steps then costs the user the window (a fresh wait for the same edit),
     * never the gate a second free loosening with the first one already on disk — which is what
     * consuming *after* the save left open.
     */
    fun commit(unlocked: Boolean, onLooseningAccepted: () -> Unit = {}): ChangeResult {
        val result = DurableChangeGate.applyChange(saved, draft, unlocked)
        if (result is ChangeResult.Applied) {
            if (loosens) onLooseningAccepted()
            store.save(result.settings)
            saved = result.settings
            draft = result.settings
            // Whatever they just saved is now the config, so a quarantined unreadable one has served
            // its purpose and the warning about it can go.
            store.acknowledgeCorrupt()
        }
        return result
    }
}
