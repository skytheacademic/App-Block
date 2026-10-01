package com.appblock

import android.content.Context
import com.appblock.data.PrefsRuleStore
import com.appblock.engine.AppTargets
import com.appblock.engine.DefaultRules
import com.appblock.engine.DurableSettings
import com.appblock.engine.ExceptionManager
import com.appblock.engine.RuleSource
import com.appblock.engine.RuleStore
import com.appblock.engine.SeedFile
import com.appblock.security.BlocklistStore

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

    /**
     * The defaults for this build variant — what the store seeds from.
     *
     * [DefaultRules.seededOff] applies to the real rules only: `debugFast` exists to prove a block
     * fires in ~90 s, so seeding its TikTok off would retire that test.
     */
    val seed: DurableSettings =
        if (BuildConfig.FAST_CAPS) {
            DurableSettings.from(DefaultRules.fastRules)
        } else {
            DurableSettings.from(DefaultRules.rules, disabled = DefaultRules.seededOff)
        }

    /** Exception wait: 1 hour for real builds; `debugFast` shrinks it to 1 min so activation is testable. */
    val exceptionWaitMs: Long =
        if (BuildConfig.FAST_CAPS) 60_000L else ExceptionManager.WAIT_MS

    /**
     * The gitignored seed file, as compiled into this build — sites and extra apps a fresh install
     * should come up with ([SeedFile]).
     *
     * `BuildConfig.SEED` is filled by **release** builds only and is empty everywhere else (see
     * `app/build.gradle.kts`). That keeps the unit tests — which run the debug variant — identical
     * on a laptop that has a `seed.txt` and on CI, which never does; and it keeps the `debugFast`
     * QA build what it is, the way [DefaultRules.seededOff] does. Lines that don't parse are dropped
     * here; `SeedFileTest` is what reports them.
     */
    val seedFile: SeedFile.Seed = SeedFile.parse(BuildConfig.SEED)

    fun ruleStore(context: Context): RuleStore = PrefsRuleStore(context, seed, seedFile.apps)

    /**
     * The website blocklist with this build's seed domains applied. Applying is idempotent and
     * add-once ([BlocklistStore.applySeed]), so every caller can go through here.
     */
    fun blocklistStore(context: Context): BlocklistStore =
        BlocklistStore(context).also { it.applySeed(seedFile.sites) }

    /**
     * A live view of the persisted rules for [com.appblock.engine.BudgetCoordinator] (re-read each pass).
     *
     * [AppTargets.alwaysBlockedRules] is appended rather than persisted (B-10). A bypass tool must not
     * be something the settings screen can list, the gate can weigh, or a 2-hour window can switch
     * off — and injecting it here keeps it out of [DurableSettings] entirely, so there is no version
     * bump and therefore no re-seed wiping every rule and picker-added app on the next launch.
     */
    fun ruleSource(context: Context): RuleSource {
        val store = ruleStore(context)
        return RuleSource { store.load().toRules() + AppTargets.alwaysBlockedRules }
    }
}
