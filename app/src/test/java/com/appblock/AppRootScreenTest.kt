package com.appblock

import android.app.Application
import android.content.pm.PackageInfo
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
import com.appblock.data.OmniboxWitnessStore
import com.appblock.engine.DurableUnlockState
import com.appblock.engine.RuleStore
import com.appblock.engine.SignalCanary
import com.appblock.engine.Target
import com.appblock.security.DurableUnlockStore
import com.appblock.security.LockKeys
import com.appblock.security.LockStore
import com.appblock.ui.AppRoot
import com.appblock.ui.theme.AppBlockTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
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

    /** The allowlisted browser the drift cases stage; [com.appblock.engine.BrowserTargets.allowlist]. */
    private val CHROME = "com.android.chrome"

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        // The real store, cleared, rather than an in-memory double: the screen builds its own from
        // ActiveRules, so asserting against the same prefs file is what proves the save landed.
        app.getSharedPreferences("appblock_rules", 0).edit().clear().commit()
        app.getSharedPreferences("appblock_lock", 0).edit().clear().commit()
        // The canary witnesses live here. Cleared for the same reason as the two above: the drift row
        // is staged by writing one, and a case that inherited another's would be asserting nothing.
        app.getSharedPreferences("appblock_runtime", 0).edit().clear().commit()
        ruleStore = ActiveRules.ruleStore(app)
        ruleStore.load()   // seed
    }

    private fun show(adminActive: Boolean = true, batteryExempt: Boolean = true) {
        compose.setContent {
            AppBlockTheme {
                AppRoot(
                    accessibilityEnabled = true,
                    overlayGranted = true,
                    adminActive = adminActive,
                    batteryExempt = batteryExempt,
                    notificationsEnabled = true,
                    onOpenAccessibility = {},
                    onOpenOverlay = {},
                    onOpenDateSettings = {},
                    onActivateAdmin = {},
                    onRequestExemption = {},
                    onAllowNotifications = {},
                )
            }
        }
    }

    /**
     * The two doors the 2026-08-21 audit found (N-2, N-3) reach the protection list as cards with a
     * repair action, and read as plain rows while held. Before this nothing in the app read either
     * state, so "Deactivate" and "Optimize battery usage" were free and silent.
     */
    @Test
    fun `an open door is a card on the Lock tab, a held one is a row`() {
        show(adminActive = false, batteryExempt = false)
        goTo("Lock")
        compose.onNodeWithText("Activate").assertExists()
        compose.onNodeWithText("Exempt").assertExists()
    }

    @Test
    fun `held doors show no repair action`() {
        show()
        goTo("Lock")
        compose.onNodeWithText("Protection admin").assertExists()
        compose.onNodeWithText("Activate").assertDoesNotExist()
        compose.onNodeWithText("Exempt").assertDoesNotExist()
    }

    /** Tabs are found by their icon's description — "Apps" is also the screen's own title. */
    private fun goTo(tab: String) = compose.onNodeWithContentDescription(tab).performClick()

    /**
     * Open X's limits: the card's name area is the tap target, not a separate Edit button.
     *
     * **Why X and not TikTok**, which every scenario below used until 2026-08-05: TikTok now seeds
     * *disabled* ([com.appblock.engine.DefaultRules.seededOff]), and at the time `DurableChangeGate`
     * treated a target that is off before and after as contributing nothing whatever its numbers
     * said. Stepping its caps became a no-op, so five gate tests failed while the gate itself was
     * "fine" — the fixture had gone inert, not the behaviour. (That rule was audit finding G-1 and
     * is gone: a dormant target's caps are gated like a running one's. The fixture stays on X
     * because the reason it was chosen still holds — enforced, with distinct weekday (15) and
     * weekend (20) caps, so the two-loosenings case has two fields to move.)
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

    /**
     * Loosening with **no key stored** — which is what `setUp` leaves behind, since it clears the
     * lock prefs along with the rules.
     *
     * This test used to assert the *gated* hint ("gates the whole save: key → 2 h → 15 min") and
     * passed, which meant it was quietly certifying the wrong copy: that sentence quotes a price
     * that cannot be paid, because [com.appblock.security.LockStore.verify] refuses every code while
     * nothing is stored, so no window can ever open and the Save stays dead forever rather than for
     * two hours. Keyless is *stricter* than locked, not looser.
     */
    @Test
    fun `loosening with no key says so, rather than quoting the 2 h price`() {
        show()
        openXLimits()
        stepper("plus", "Weekday cap").tapInSheet()    // X weekday cap 15 → 20: looser
        closeSheet()
        goTo("Lock")
        compose.onNodeWithText("Accept one change").assertIsNotEnabled()
        compose.onNodeWithText("no key to open a window with", substring = true).assertExists()
        assertEquals(15, xWeekday())
    }

    /** The same refusal *with* a key: still blocked, but now the quoted cost is one you can pay. */
    @Test
    fun `loosening is blocked while locked, and names the cost once a key exists`() {
        LockStore(app).setKey(LockKeys.generate())
        show()
        openXLimits()
        stepper("plus", "Weekday cap").tapInSheet()
        closeSheet()
        goTo("Lock")
        compose.onNodeWithText("Accept one change").assertIsNotEnabled()
        compose.onNodeWithText("gates the whole save", substring = true).assertExists()
        assertEquals(15, xWeekday())
    }

    /**
     * The omnibox-drift row, the in-app half of the B-7 canary (the notification half has fired since
     * 2026-08-03; this was deferred so it wouldn't become a seventh feature to hand-port through the
     * redesign merge).
     *
     * Staged rather than asserted on a real device state: Chrome is installed into the shadow package
     * manager at versionCode 2, and the witness records a confirmation on version **1** first seen
     * eight days ago — past [com.appblock.data.OmniboxWitnessStore.GRACE_MS] — which is precisely
     * [SignalCanary.Health.STALE]: the browser updated a week ago and its address bar has not been read
     * since.
     */
    @Test
    fun `address-bar drift reaches the Lock protection list, not only the notification`() {
        installChrome()
        val eightDaysAgo = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1_000
        OmniboxWitnessStore(app).save(
            CHROME,
            SignalCanary.Witness(
                confirmedVersion = 1L,
                confirmedAtMs = eightDaysAgo,
                installedVersion = 2L,
                installedSeenAtMs = eightDaysAgo,
            ),
        )
        show()
        goTo("Lock")
        compose.onNodeWithText("Website blocking needs a re-check").assertExists()
    }

    /**
     * The discriminator, not just the absence of a browser: Chrome *is* installed and its omnibox has
     * been read on the version that's installed now. A row that appeared here would be the drift
     * warning firing at ordinary browsing — the same "fires hardest at the user who is complying"
     * failure [SignalCanary] rejects the naive canary for.
     */
    @Test
    fun `a browser whose address bar reads fine raises no drift row`() {
        installChrome()
        OmniboxWitnessStore(app).confirm(CHROME, System.currentTimeMillis())
        show()
        goTo("Lock")
        compose.onNodeWithText("Website blocking needs a re-check").assertDoesNotExist()
    }

    private fun installChrome() {
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply {
                packageName = CHROME
                longVersionCode = 2L
            },
        )
    }

    /** Apps' foot line is the other place the keyless state was overstating what a loosening costs. */
    @Test
    fun `the Apps lock line does not promise a window when there is no key`() {
        show()
        goTo("Apps")
        compose.onNodeWithText("loosening needs a key", substring = true).assertExists()
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
