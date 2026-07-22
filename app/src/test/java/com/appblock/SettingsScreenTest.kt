package com.appblock

import android.app.Application
import android.os.SystemClock
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.appblock.engine.DefaultRules
import com.appblock.engine.DurableSettings
import com.appblock.engine.DurableUnlockState
import com.appblock.engine.InMemoryRuleStore
import com.appblock.engine.Target
import com.appblock.security.DurableUnlockStore
import com.appblock.security.LockStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The gated settings screen on the JVM (Robolectric + Compose): the §6 asymmetry — tightening saves
 * freely, loosening needs the open window — plus the schedule editor's authoring behaviors.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w480dp-h2000dp")
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var ruleStore: InMemoryRuleStore

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        ruleStore = InMemoryRuleStore(DurableSettings.from(DefaultRules.rules))
    }

    private fun show() {
        compose.setContent {
            SettingsScreen(ruleStore = ruleStore, lockStore = LockStore(app), onBack = {})
        }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    @Test
    fun `tightening saves without the change window`() {
        show()
        scrollTo("TikTok")
        compose.onAllNodesWithText("−")[0].performClick()   // TikTok weekday cap 30 → 25: stricter
        scrollTo("Save")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(25, ruleStore.load().targets[Target.TIKTOK]!!.weekdayMinutes)
        compose.onNodeWithText("Saved.").assertExists()
    }

    @Test
    fun `loosening is blocked while locked`() {
        show()
        scrollTo("TikTok")
        compose.onAllNodesWithText("+")[0].performClick()   // TikTok weekday cap 30 → 35: looser
        scrollTo("Accept one change")
        compose.onNodeWithText("Accept one change").assertIsNotEnabled()
        compose.onNodeWithText("start the change window", substring = true).assertExists()
        assertEquals(30, ruleStore.load().targets[Target.TIKTOK]!!.weekdayMinutes)
    }

    @Test
    fun `open window lets one loosening through then relocks`() {
        DurableUnlockStore(app).save(
            DurableUnlockState.Open(
                windowEndElapsedMs = SystemClock.elapsedRealtime() + 5 * 60_000L,
                bootCount = 0,   // Robolectric's Settings.Global.BOOT_COUNT defaults to 0
            ),
        )
        show()
        scrollTo("TikTok")
        compose.onAllNodesWithText("+")[0].performClick()
        scrollTo("Accept one change")
        compose.onNodeWithText("Accept one change").assertIsEnabled().performClick()
        assertEquals(35, ruleStore.load().targets[Target.TIKTOK]!!.weekdayMinutes)
        compose.onNodeWithText("Saved. That was your one change — it's locked again.").assertExists()
    }

    @Test
    fun `schedule toggle authors a default window and counts as tightening`() {
        show()
        scrollTo("Limit to certain hours")
        compose.onAllNodes(isToggleable())[1].performClick()   // [0] = TikTok on/off, [1] = its schedule
        scrollTo("From")
        compose.onNodeWithText("From").assertExists()
        compose.onNodeWithText("18:00").assertExists()
        scrollTo("Save")
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun `stepping To past From authors an overnight window`() {
        show()
        scrollTo("Limit to certain hours")
        compose.onAllNodes(isToggleable())[1].performClick()
        scrollTo("To")
        // To: 20:00 → 19:30 → 19:00 → 18:30 → (skips 18:00 = From) → 17:30, i.e. wraps past midnight.
        repeat(4) { compose.onAllNodesWithText("−")[4].performClick() }
        compose.onNodeWithText("Runs past midnight", substring = true).assertExists()
    }
}
