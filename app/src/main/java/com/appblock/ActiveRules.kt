package com.appblock

import android.content.Context
import com.appblock.data.PrefsRuleStore
import com.appblock.engine.DefaultRules
import com.appblock.engine.DurableSettings
import com.appblock.engine.ExceptionManager
import com.appblock.engine.RuleSource
import com.appblock.engine.RuleStore

/**
 * The durable rule set the running app enforces. Reads [BuildConfig] (kept out of the Android-free
 * engine): the throwaway `debugFast` variant seeds 1-minute caps for quick on-device verification,
 * every other build seeds the real CONSTRAINTS.md v1.1 caps.
 *
 * Rules are now editable + persisted (behind the durable-change lock), so the app reads them through a
 * [RuleStore] rather than a constant. [seed] is the source-of-truth defaults used on first launch and
 * whenever [DurableSettings.RULES_VERSION] is bumped (the computer re-seed path).
 */
object ActiveRules {

    /** The defaults for this build variant — what the store seeds from. */
    val seed: DurableSettings =
        DurableSettings.from(if (BuildConfig.FAST_CAPS) DefaultRules.fastRules else DefaultRules.rules)

    /** Exception wait: 1 hour for real builds; `debugFast` shrinks it to 1 min so activation is testable. */
    val exceptionWaitMs: Long =
        if (BuildConfig.FAST_CAPS) 60_000L else ExceptionManager.WAIT_MS

    fun ruleStore(context: Context): RuleStore = PrefsRuleStore(context, seed)

    /** A live view of the persisted rules for [com.appblock.engine.BudgetCoordinator] (re-read each pass). */
    fun ruleSource(context: Context): RuleSource {
        val store = ruleStore(context)
        return RuleSource { store.load().toRules() }
    }
}
