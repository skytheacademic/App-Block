package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** The coordinator reads rules from its [RuleSource] every pass, so a gated edit takes effect live. */
class RuleStoreCoordinatorTest {

    private val tiktok = "com.zhiliaoapp.musically"
    private val minute = 60_000L

    private fun seed(wd: Int, enabled: Boolean = true) = DurableSettings(
        version = 1,
        targets = mapOf(Target.TIKTOK to TargetSettings(enabled, wd, wd, wd + 30)),
        exceptionWindowMinutes = 60,
    )

    @Test fun `raising the cap in the store re-opens a blocked app on the next tick`() {
        val clock = FakeClock()
        val store = InMemoryRuleStore(seed(wd = 1))
        val c = BudgetCoordinator(clock, InMemoryEngineStore(), FakeIntegrity(), RuleSource { store.load().toRules() })

        c.onForeground(tiktok)
        clock.advance(2 * minute)          // past the 1-min cap
        assertEquals(Access.BLOCK, c.tick().access)

        // Raise the cap live. Usage froze at ~2 min while blocked, so 30 min > used → allowed again.
        store.save(seed(wd = 30))
        assertEquals(Access.ALLOW, c.tick().access)
    }

    @Test fun `a disabled target is not enforced`() {
        val clock = FakeClock()
        val store = InMemoryRuleStore(seed(wd = 1, enabled = false))
        val c = BudgetCoordinator(clock, InMemoryEngineStore(), FakeIntegrity(), RuleSource { store.load().toRules() })

        c.onForeground(tiktok)
        clock.advance(10 * minute)
        val d = c.tick()
        assertEquals(Access.ALLOW, d.access)  // omitted from rules → always allowed
        assertEquals(emptyList<Target>(), c.snapshot().map { it.target }) // and hidden from the UI list
    }
}
