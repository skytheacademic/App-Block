package com.appblock.data

import android.content.Context
import com.appblock.engine.DurableSettings
import com.appblock.engine.EngineCodec
import com.appblock.engine.RuleStore

/**
 * SharedPreferences-backed [RuleStore] for the durable config. Owns three things the pure engine can't:
 *
 *  - **Seeding:** a genuinely empty store (first launch) writes [seed] — the CONSTRAINTS.md v1.1
 *    defaults for this build variant.
 *  - **The computer re-seed path (CONSTRAINTS.md §6):** if the stored config's version differs from
 *    the source [seed]'s [DurableSettings.RULES_VERSION], the source wins and re-seeds. So editing the
 *    defaults in source + bumping the constant + rebuilding is the (inherently authorized) way to push
 *    a durable change from the desk — while a matching version preserves on-device (QR-authorized) edits.
 *  - **Corruption (audit finding C-3):** a stored value that no longer decodes is quarantined, not
 *    overwritten, and reported.
 *
 * ## Why corruption needed its own path
 *
 * It used to share the first-launch branch — anything that didn't decode was treated as "nothing
 * stored yet", silently replaced with build defaults, and the unreadable original destroyed. Three
 * things wrong with that, in increasing order of seriousness:
 *
 *  1. Every app added from the picker vanished, because those exist *only* in this blob. Nothing said
 *     so; the settings screen simply came back shorter than the user left it.
 *  2. It failed **open**. Build defaults know about TikTok, Instagram and X and nothing else, so a
 *     config that had been tightened over weeks collapsed to something looser — a loosening with no
 *     key, no wait and no window, which is the one thing the whole design exists to prevent.
 *  3. It destroyed the evidence. Overwriting the only copy means the corruption can never be diagnosed
 *     afterwards, and a decoding bug shipped in an update would erase every install's config on first
 *     launch with no way back.
 *
 * Now: the raw text is kept under [KEY_CORRUPT] (first one only — a later failure must not overwrite
 * the original evidence), enforcement continues from [seed] so the app is never left enforcing
 * nothing, and [corruptBlob] drives a warning so the user learns their rules need rebuilding instead
 * of discovering it by successfully opening something.
 *
 * Re-seeding is still what *enforces* here, and it is still the looser side. That is deliberate: the
 * stricter alternatives are inventing limits the user never chose, or blocking everything until they
 * intervene, and neither is a defensible response to what may be the app's own bug. The warning is the
 * safety mechanism, not the fallback config.
 */
class PrefsRuleStore(
    context: Context,
    private val seed: DurableSettings,
) : RuleStore {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): DurableSettings {
        val raw = prefs.getString(KEY_RULES, null)
            ?: return seed.also { save(it) }             // nothing stored yet — a real first launch

        val stored = EngineCodec.decodeDurable(raw)
        if (stored == null) {
            quarantine(raw)
            return seed.also { save(it) }
        }
        if (stored.version != seed.version) {
            // Authorized re-seed from source — for the built-ins. A `pkg:` target can never be in
            // the seed (it was added from the picker on the phone), so the seed has nothing to say
            // about it and replacing the whole blob just deleted it: two config reconstructions
            // before this carried them across. They come through with their settings intact.
            val picked = stored.targets.filterKeys { it.userPackage != null && it !in seed.targets }
            return seed.copy(targets = seed.targets + picked).also { save(it) }
        }
        return stored
    }

    override fun save(settings: DurableSettings) {
        prefs.edit().putString(KEY_RULES, EngineCodec.encodeDurable(settings)).apply()
    }

    /**
     * The preserved unreadable config, or null if there has never been one. Non-null means the rules
     * on screen are build defaults rather than what the user configured.
     */
    override fun corruptBlob(): String? = prefs.getString(KEY_CORRUPT, null)

    /**
     * Drop the quarantined copy once the user has rebuilt their config. Explicit rather than folded
     * into [save], because [load] itself calls [save] on the corrupt path — clearing there would wipe
     * the flag in the same breath that set it.
     */
    override fun acknowledgeCorrupt() {
        prefs.edit().remove(KEY_CORRUPT).apply()
    }

    /** Keep the *first* failure only; a second one must not overwrite the original evidence. */
    private fun quarantine(raw: String) {
        if (!prefs.contains(KEY_CORRUPT)) {
            prefs.edit().putString(KEY_CORRUPT, raw).apply()
        }
    }

    private companion object {
        const val PREFS = "appblock_rules"
        const val KEY_RULES = "durable_settings"
        const val KEY_CORRUPT = "durable_settings_unreadable"
    }
}
