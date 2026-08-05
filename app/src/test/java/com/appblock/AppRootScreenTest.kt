package com.appblock

import android.app.Application
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.appblock.engine.DurableUnlockState
import com.appblock.engine.RuleStore
import com.appblock.engine.Target
import com.appblock.security.DurableUnlockStore
import com.appblock.ui.AppRoot
import com.appblock.ui.theme.AppBlockTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The §6 asymmetry through the redesigned UI — tightening saves freely, loosening needs the open
 * window — plus the schedule editor's authoring behaviours.
 *
 * Ported from `SettingsScreenTest` when the one settings screen split into four tabs. Every scenario
 * is the one it tested; what changed is where the controls are. That is the point of keeping it: the
 * gate is unit-tested in `DurableChangeGateTest`, and this is the only thing asserting that the
 * *screens* honour it — now across a tab boundary, with the edit made on Apps and committed on Lock.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w480dp-h2000dp")
class AppRootScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var ruleStore: RuleStore

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        // The real store, cleared, rather than an in-memory double: the screen builds its own from
        // ActiveRules, so asserting against the same prefs file is what proves the save landed.
        app.getSharedPreferences("appblock_rules", 0).edit().clear().commit()
        app.getSharedPreferences("appblock_lock", 0).edit().clear().commit()
        ruleStore = ActiveRules.ruleStore(app)
        ruleStore.load()   // seed
    }

    private fun show() {
        compose.setContent {
            AppBlockTheme {
                AppRoot(
                    accessibilityEnabled = true,
                    overlayGranted = true,
                    onOpenAccessibility = {},
                    onOpenOverlay = {},
                    onOpenDateSettings = {},
                )
            }
        }
    }

    /** Tabs are found by their icon's description — "Apps" is also the screen's own title. */
    private fun goTo(tab: String) = compose.onNodeWithContentDescription(tab).performClick()

    /**
     * Open X's limits: the card's name area is the tap target, not a separate Edit button.
     *
     * **Why X and not TikTok**, which every scenario below used until 2026-08-05: TikTok now seeds
     * *disabled* ([com.appblock.engine.DefaultRules.seededOff]), and `DurableChangeGate` treats a
     * target that is off before and after as contributing nothing whatever its numbers say. Stepping
     * its caps became a no-op, so five gate tests failed while the gate itself was fine — the fixture
     * had gone inert, not the behaviour. X keeps the shape these tests need: enforced, with distinct
     * weekday (15) and weekend (20) caps, so the two-loosenings case still has two fields to move.
     */
    private fun openXLimits() {
        goTo("Apps")
        compose.onNodeWithText("X (Twitter)").performClick()
    }

    /**
     * Click a node that lives inside the limits sheet.
     *
     * `performClick` injects touch at a coordinate, and `ModalBottomSheet` renders in its own
     * composition root whose content isn't where the harness dispatches — so a normal click finds
     * the node, does nothing, and the test fails as if the app were broken. Invoking the semantics
     * action bypasses hit-testing entirely.
     */
    private fun SemanticsNodeInteraction.tapInSheet(): SemanticsNodeInteraction =
        performSemanticsAction(SemanticsActions.OnClick)

    private fun closeSheet() = compose.onNodeWithText("Done").tapInSheet()

    /** A stepper button by its row's label — see `stepperTag`; global indices proved too brittle. */
    private fun stepper(side: String, label: String) =
        compose.onNodeWithTag(stepperTag(side, label))

    /** The schedule switch, found by its own label rather than by position among all toggles. */
    private fun scheduleToggle() =
        compose.onNode(isToggleable() and hasAnySibling(hasText("Limit to certain hours")))

    private fun xWeekday() = ruleStore.load().targets[Target.X]!!.weekdayMinutes

    @Test
    fun `tightening saves without the change window`() {
        show()
        openXLimits()
        stepper("minus", "Weekday cap").tapInSheet()   // X weekday cap 15 → 10: stricter
        closeSheet()
        goTo("Lock")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(10, xWeekday())
        compose.onNodeWithText("Saved.").assertExists()
    }

    @Test
    fun `loosening is blocked while locked`() {
        show()
        openXLimits()
        stepper("plus", "Weekday cap").tapInSheet()    // X weekday cap 15 → 20: looser
        closeSheet()
        goTo("Lock")
        compose.onNodeWithText("Accept one change").assertIsNotEnabled()
        compose.onNodeWithText("gates the whole save", substring = true).assertExists()
        assertEquals(15, xWeekday())
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
        openXLimits()
        stepper("plus", "Weekday cap").tapInSheet()
        closeSheet()
        goTo("Lock")
        compose.onNodeWithText("Accept one change").assertIsEnabled().performClick()
        assertEquals(20, xWeekday())
        compose.onNodeWithText("Saved — that was your one change").assertExists()
    }

    /**
     * A window buys **one** change (CONSTRAINTS §6). The gate enforces it, but the screens have to
     * say so *before* Save rather than only as a rejection — an open window above a greyed button
     * with no explanation is the most confusing state the Lock tab can be in.
     *
     * Guards a real regression: the redesign rebuilt this bar from the pre-§6 screen, so its Save
     * was enabled on `windowOpen` alone and would have offered to land two loosenings on one window.
     */
    @Test
    fun `an open window still refuses two loosenings at once`() {
        DurableUnlockStore(app).save(
            DurableUnlockState.Open(
                windowEndElapsedMs = SystemClock.elapsedRealtime() + 5 * 60_000L,
                bootCount = 0,
            ),
        )
        show()
        openXLimits()
        stepper("plus", "Weekday cap").tapInSheet()    // loosening #1: 15 → 20
        stepper("plus", "Weekend cap").tapInSheet()    // loosening #2 (20 → 25) — one too many
        closeSheet()
        goTo("Lock")
        compose.onNodeWithText("Accept one change").assertIsNotEnabled()
        compose.onNodeWithText("this edit loosens 2", substring = true).assertExists()
        assertEquals(15, xWeekday())              // nothing landed
    }

    @Test
    fun `schedule toggle authors a default window and counts as tightening`() {
        show()
        openXLimits()
        scheduleToggle().tapInSheet()
        compose.onNodeWithText("From").assertExists()
        compose.onNodeWithText("18:00").assertExists()
        closeSheet()
        goTo("Lock")
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun `stepping To past From authors an overnight window`() {
        show()
        openXLimits()
        scheduleToggle().tapInSheet()
        // To: 20:00 → 19:30 → 19:00 → 18:30 → (skips 18:00 = From) → 17:30, i.e. wraps past midnight.
        repeat(4) { stepper("minus", "To").tapInSheet() }
        compose.onNodeWithText("Runs past midnight", substring = true).assertExists()
    }
}
