package com.appblock.ui

import com.appblock.engine.ChangeResult
import com.appblock.engine.DurableSettings
import com.appblock.engine.RuleStore
import com.appblock.engine.Target
import com.appblock.engine.TargetSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order of the two writes a loosening makes. The window used to be spent *after* the rules were
 * saved; a process death between the two left the loosening on disk with the window still open —
 * a second free change. Spending first means a death there costs the user the window instead.
 */
class RulesDraftCommitTest {

    private val seed = DurableSettings(
        version = 1,
        targets = mapOf(Target.TIKTOK to TargetSettings(enabled = true, weekdayMinutes = 30, weekendMinutes = 30, exceptionMaxMinutes = 60)),
        exceptionWindowMinutes = 60,
    )

    /** Records the order of everything that happens to it. */
    private class RecordingStore(seed: DurableSettings, private val log: MutableList<String>) : RuleStore {
        private var settings = seed
        override fun load(): DurableSettings = settings
        override fun save(settings: DurableSettings) {
            log += "save"
            this.settings = settings
        }
    }

    private fun raisedCap(settings: DurableSettings): DurableSettings =
        settings.copy(targets = mapOf(Target.TIKTOK to settings.targets[Target.TIKTOK]!!.copy(weekdayMinutes = 60)))

    @Test fun `a loosening spends the window before the rules are written`() {
        val log = mutableListOf<String>()
        val draft = RulesDraft(RecordingStore(seed, log))
        draft.draft = raisedCap(draft.saved)
        assertTrue(draft.loosens)

        val result = draft.commit(unlocked = true, onLooseningAccepted = { log += "consume" })
        assertTrue(result is ChangeResult.Applied)
        assertEquals(listOf("consume", "save"), log)
        assertEquals(60, draft.saved.targets[Target.TIKTOK]!!.weekdayMinutes)
    }

    @Test fun `a tightening never touches the window`() {
        val log = mutableListOf<String>()
        val draft = RulesDraft(RecordingStore(seed, log))
        draft.draft = seed.copy(targets = mapOf(Target.TIKTOK to seed.targets[Target.TIKTOK]!!.copy(weekdayMinutes = 10)))

        val result = draft.commit(unlocked = false, onLooseningAccepted = { log += "consume" })
        assertTrue(result is ChangeResult.Applied)
        assertEquals(listOf("save"), log)
    }

    @Test fun `a refused loosening spends nothing and writes nothing`() {
        val log = mutableListOf<String>()
        val draft = RulesDraft(RecordingStore(seed, log))
        draft.draft = raisedCap(draft.saved)

        val result = draft.commit(unlocked = false, onLooseningAccepted = { log += "consume" })
        assertTrue(result is ChangeResult.Blocked)
        assertEquals(emptyList<String>(), log)
        assertEquals(30, draft.saved.targets[Target.TIKTOK]!!.weekdayMinutes)
    }
}
