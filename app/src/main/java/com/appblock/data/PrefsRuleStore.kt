package com.appblock.data

import android.content.Context
import com.appblock.engine.DurableSettings
import com.appblock.engine.EngineCodec
import com.appblock.engine.RuleStore

/**
 * SharedPreferences-backed [RuleStore] for the durable config. Owns two things the pure engine can't:
 *
 *  - **Seeding:** first launch (or an undecodable value) writes [seed] — the CONSTRAINTS.md v1.1
 *    defaults for this build variant.
 *  - **The computer re-seed path (CONSTRAINTS.md §6):** if the stored config's version differs from
 *    the source [seed]'s [DurableSettings.RULES_VERSION], the source wins and re-seeds. So editing the
 *    defaults in source + bumping the constant + rebuilding is the (inherently authorized) way to push
 *    a durable change from the desk — while a matching version preserves on-device (QR-authorized) edits.
 */
class PrefsRuleStore(
    context: Context,
    private val seed: DurableSettings,
) : RuleStore {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): DurableSettings {
        val stored = EngineCodec.decodeDurable(prefs.getString(KEY_RULES, null))
        if (stored == null || stored.version != seed.version) {
            save(seed)
            return seed
        }
        return stored
    }

    override fun save(settings: DurableSettings) {
        prefs.edit().putString(KEY_RULES, EngineCodec.encodeDurable(settings)).apply()
    }

    private companion object {
        const val PREFS = "appblock_rules"
        const val KEY_RULES = "durable_settings"
    }
}
