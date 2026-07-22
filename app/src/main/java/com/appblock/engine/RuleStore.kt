package com.appblock.engine

/**
 * Where [BudgetCoordinator] reads its rules each pass. Kept as a one-method interface so the durable
 * config can change at runtime (via the gated settings UI) and the long-lived accessibility-service
 * coordinator picks the change up on its next tick — the same way it re-reads usage/exception state
 * from the [EngineStore]. Tests pass a fixed lambda; the app passes one backed by a [RuleStore].
 */
fun interface RuleSource {
    fun rules(): List<Rule>
}

/**
 * Persistence for the [DurableSettings]. The Android impl (SharedPreferences) also owns seeding and
 * the [DurableSettings.RULES_VERSION] re-seed; this interface stays Android-free for tests.
 */
interface RuleStore {
    fun load(): DurableSettings
    fun save(settings: DurableSettings)
}

/** In-memory store for tests. Seeded explicitly; no versioning/re-seed (that's the Android layer's job). */
class InMemoryRuleStore(seed: DurableSettings) : RuleStore {
    private var settings: DurableSettings = seed
    override fun load(): DurableSettings = settings
    override fun save(settings: DurableSettings) {
        this.settings = settings
    }
}
