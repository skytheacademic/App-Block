package com.appblock

import android.app.Application
import android.os.SystemClock
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
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

    /**
     * Scroll the settings list to a node. Only for content *inside* the LazyColumn — Save/Revert now
     * live in a pinned bottom bar outside the scrollable container, so they need no scrolling (and
     * `performScrollToNode` can't find them).
     */
    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    /**
     * Open TikTok's edit sheet. The caps and the schedule editor moved off the card into a bottom
     * sheet, so every test that touches a limit goes through here first. TikTok is the first card,
     * hence index 0 of the "Edit limits" buttons.
     */
    private fun openTikTokEditor() {
        scrollTo("TikTok")
        compose.onAllNodesWithText("Edit limits")[0].performClick()
    }

    /**
     * Click a node that lives inside the edit sheet.
     *
     * `performClick` injects touch at a coordinate, and [ModalBottomSheet] renders in its own
     * composition root whose content isn't where the harness dispatches — so a normal click finds the
     * node, does nothing, and the test fails as if the app were broken. Invoking the semantics action
     * bypasses hit-testing entirely. Verified: with `performClick` the schedule toggle changed
     * nothing; with this it authors the default window.
     */
    private fun SemanticsNodeInteraction.tapInSheet(): SemanticsNodeInteraction =
        performSemanticsAction(SemanticsActions.OnClick)

    /** A stepper button by its row's label — see `stepperTag`; global indices proved too brittle. */
    private fun stepper(side: String, label: String) =
        compose.onNodeWithTag(stepperTag(side, label))

    /** The schedule switch, found by its own label rather than by position among all toggles. */
    private fun scheduleToggle() =
        compose.onNode(isToggleable() and hasAnySibling(hasText("Limit to certain hours")))

    @Test
    fun `tightening saves without the change window`() {
        show()
        openTikTokEditor()
        stepper("minus", "Weekday cap").tapInSheet()   // TikTok weekday cap 30 → 25: stricter
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(25, ruleStore.load().targets[Target.TIKTOK]!!.weekdayMinutes)
        compose.onNodeWithText("Saved.").assertExists()
    }

    @Test
    fun `loosening is blocked while locked`() {
        show()
        openTikTokEditor()
        stepper("plus", "Weekday cap").tapInSheet()    // TikTok weekday cap 30 → 35: looser
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
        openTikTokEditor()
        stepper("plus", "Weekday cap").tapInSheet()
        compose.onNodeWithText("Accept one change").assertIsEnabled().performClick()
        assertEquals(35, ruleStore.load().targets[Target.TIKTOK]!!.weekdayMinutes)
        compose.onNodeWithText("Saved. That was your one change — it's locked again.").assertExists()
    }

    @Test
    fun `schedule toggle authors a default window and counts as tightening`() {
        show()
        openTikTokEditor()
        scheduleToggle().tapInSheet()
        compose.onNodeWithText("From").assertExists()
        compose.onNodeWithText("18:00").assertExists()
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun `stepping To past From authors an overnight window`() {
        show()
        openTikTokEditor()
        scheduleToggle().tapInSheet()
        // To: 20:00 → 19:30 → 19:00 → 18:30 → (skips 18:00 = From) → 17:30, i.e. wraps past midnight.
        repeat(4) { stepper("minus", "To").tapInSheet() }
        compose.onNodeWithText("Runs past midnight", substring = true).assertExists()
    }
}
