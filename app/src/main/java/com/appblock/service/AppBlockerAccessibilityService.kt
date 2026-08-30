package com.appblock.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.appblock.ActiveRules
import com.appblock.BuildConfig
import com.appblock.MainActivity
import com.appblock.R
import com.appblock.data.InstalledApps
import com.appblock.data.OmniboxWitnessStore
import com.appblock.data.PrefsEngineStore
import com.appblock.data.SignalWitnessStore
import com.appblock.engine.Access
import com.appblock.engine.AddressWatch
import com.appblock.engine.AppTargets
import com.appblock.engine.BlockFacts
import com.appblock.engine.BlockReason
import com.appblock.engine.BrowserPolicy
import com.appblock.engine.BrowserTargets
import com.appblock.engine.BudgetCoordinator
import com.appblock.engine.Decision
import com.appblock.engine.DisplayCensus
import com.appblock.engine.DisplayCoverage
import com.appblock.engine.DisplayHolds
import com.appblock.engine.DisplayOverlays
import com.appblock.engine.DomainMatcher
import com.appblock.engine.InstagramSurface
import com.appblock.engine.OverlayRepairWatch
import com.appblock.engine.RuleSource
import com.appblock.engine.Schedule
import com.appblock.engine.ServiceLiveness
import com.appblock.engine.SettingsWatch
import com.appblock.engine.SignalCanary
import com.appblock.engine.Target
import com.appblock.security.BlocklistStore
import com.appblock.security.DurableUnlockController
import com.appblock.security.LockStore
// The shared formatters, so the block screen's clock readings and durations are rendered by the same
// rules as the Today hero and the limits table. See com.appblock.ui.Format's own doc — the fact row
// is one of the three call sites it exists for.
import com.appblock.ui.formatCoarse
import com.appblock.ui.formatHm
import com.appblock.ui.formatWindow
import com.appblock.util.overlayAppOpAllows

/**
 * The live blocker. Inputs that drive it:
 *  1. Window events (TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOWS_CHANGED) — something on screen changed.
 *  2. A window *scan* on every event and tick: a budgeted app counts as foreground if it occupies ANY
 *     visible window, not just the focused one — split-screen / Samsung App Pairs can't park TikTok
 *     in an unfocused pane for free. ⚠️ **This was only half true until 2026-08-30.** The scan did walk
 *     every window, but it kept the *first* package target it found and discarded the rest, so with two
 *     budgeted apps on screen the one underneath was neither gated nor metered. The claim above is the
 *     one this line was always making; [AppTargets.foregroundTargets] is what finally makes it hold.
 *  3. A ~5s heartbeat while a budgeted app / Instagram / a browser is on screen — so the block appears
 *     the *moment* the budget runs out (or a reel opens, or a blocked URL loads), not only on the next
 *     app switch.
 *  4. Self-defense (settings-watch, [SettingsWatch]): content + window events from system Settings are
 *     scanned for screens about App-Block itself; found one → bounce Home, unless a change window is open.
 *  5. Website blocking (CONSTRAINTS §2, [BrowserPolicy]): on an allowlisted browser (Chrome/Brave) the
 *     omnibox URL is matched against the private blocklist; any *other* browser is blocked outright.
 *
 * The decision itself is the pure engine's; this class maps it to the overlay. If the overlay can't draw
 * (permission revoked mid-session), blocking falls back to kicking the user Home every tick.
 *
 * Still the weak tier: force-stop / uninstall defeat it — that's what the watchdog notification and the
 * optional Device Owner tier are for (see STATUS.md).
 */
class AppBlockerAccessibilityService : AccessibilityService() {

    private val windowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private val displayManager by lazy { getSystemService(DISPLAY_SERVICE) as DisplayManager }

    /**
     * One block overlay per covered display. Replaces the single `overlayView` / `overlayKey` /
     * `overlayExitBrowserPkg` triple, each of which was a service-wide singleton that becomes wrong the
     * moment two displays can be covered at once — see [DisplayOverlays] for what each one broke.
     *
     * Display 0 still draws through the service's own [windowManager] and inflates from the service
     * context, i.e. literally today's code path, on a change that ships unverified on DeX hardware.
     */
    private val overlays by lazy {
        DisplayOverlays<WindowManager, View, FactRows>(
            mayDraw = { Settings.canDrawOverlays(this) },
            defaultWindowManager = { windowManager },
            secondaryWindowManager = ::secondaryWindowManager,
            inflate = ::inflateOverlay,
            add = { wm, view -> runCatching { wm.addView(view, overlayParams()) }.isSuccess },
            remove = { wm, view -> runCatching { wm.removeView(view) } },
            bindMessage = { view, message ->
                runCatching { view.findViewById<TextView>(R.id.block_message).text = message }
            },
            bindFacts = ::writeFactRows,
        )
    }
    private lateinit var clock: AndroidEngineClock
    private lateinit var coordinator: BudgetCoordinator

    /** Latches the tamper guard the moment automatic date/time or time zone is switched off. */
    private var clockSettingsWatch: ClockSettingsWatch? = null
    private lateinit var ruleSource: RuleSource
    private lateinit var unlockController: DurableUnlockController
    private lateinit var blocklistStore: BlocklistStore
    private lateinit var witnessStore: SignalWitnessStore
    private lateinit var omniboxWitnessStore: OmniboxWitnessStore

    /** Briefly-cached set of targets with a live rule — see [activeTargets]. */
    private var activeTargetsCache: Set<Target>? = null
    private var activeTargetsAtMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var lastForegroundTargets: List<Target> = emptyList()
    /** True while an Instagram window is visible — keeps the tick alive so a reel open is caught even
     *  when the current surface (e.g. the feed) isn't itself budgeted. */
    private var surfaceAppVisible = false
    /** True while an allowlisted browser is visible — keeps the tick alive so navigation to a blocked
     *  site is caught by re-reading the omnibox. */
    private var browserVisible = false
    private var lastBounceToastElapsedMs = 0L
    private var lastContentPumpElapsedMs = 0L
    private var browserCache: Set<String> = emptySet()
    private var browserCacheAtMs = 0L
    /** Last line emitted by [diagnose], so debug logging only fires when the resolved state changes. */
    private var lastDiagLine: String? = null
    /** Last line emitted by [diagnoseSelfDefense] — same once-per-distinct-state rule as [lastDiagLine]. */
    private var lastWatchDiagLine: String? = null
    /** Last line emitted by [diagnoseDisplays] — same once-per-distinct-state rule as [lastDiagLine]. */
    private var lastDspDiagLine: String? = null
    /** Whether the last window read used `getWindowsOnAllDisplays()`. `api=legacy` on an Android 16
     *  phone means the SDK guard is inverted and the multi-display path never ran. */
    private var lastAllDisplaysApi = false
    /** The display holding the active window on the last scan — where `GLOBAL_ACTION_HOME` will land. */
    private var lastActiveDisplayId: Int? = null
    /** Which displays offered watched Settings windows on the last self-defense pass. */
    private var lastWatchedDisplays: Set<Int> = emptySet()
    /** Node budget left after the last settings walk — an existing silent fail-open, now instrumented. */
    private var lastSettingsBudgetLeft = 0
    /** The displays [applyDecision] last decided to cover. */
    private var lastCover: Set<Int> = emptySet()
    /** Per-display census of the last scan, for [diagnose] and [diagnoseDisplays]. */
    private var lastCensus: List<DisplayCensus.Display> = emptyList()
    /** Last time a [DisplayManager.DisplayListener] callback drove a pump — see [scheduleDisplayPump]. */
    private var lastDisplayPumpElapsedMs = 0L
    private var displayListener: DisplayManager.DisplayListener? = null
    /** Whether the last [visibleWatchedScreen] read had to fall back to the active window because
     *  `getWindows()` had not offered it. Diagnostic only, but it is *the* N-2 question: if this is
     *  true on the device-admin page, `getWindows()` was the silent failure. */
    private var lastActiveWindowRescued = false
    /** Nodes walked by the last Instagram signal scan — diagnostic only (tells a pruned tree apart
     *  from a tree that simply doesn't have the reel pager in it). */
    private var lastIgNodeCount = 0

    /** How many Instagram windows the last scan walked. >1 means a popup/sheet was stacked over the
     *  app, which is exactly the case that used to read the wrong one. Diagnostic only. */
    private var lastIgWindowCount = 0
    /**
     * The last real read taken on each display before our own overlay started occluding it.
     *
     * One hold **per display**, because the framework's occlusion pruning is computed per display: with
     * one global hold, a tap on the phone releases the monitor's block. [DisplayHolds] carries the whole
     * argument, including the Gate B measurements this used to document. It also owns the per-display
     * "last window package" the moved-on test needs — see [noteForegroundPackage] for what deliberately
     * does not count as moving on.
     */
    private val holds = DisplayHolds<Foreground>()
    /** Cached overlay readings — see [readOverlayGrant]. */
    private var overlayGranted = true to true
    private var overlayGrantedAtMs = 0L
    /**
     * Owns the repair-mode stand-down, which used to be the bare expression `!canDrawOverlays()` and
     * on 2026-08-29 disarmed the entire Settings tier for minutes on a reading that was wrong. See
     * [OverlayRepairWatch] — it needs both readings to agree, and says so when it engages.
     */
    private val repairWatch = OverlayRepairWatch()
    /** Last Settings-side watchdog check — see [checkHealthWhileInSettings]. */
    private var lastHealthCheckElapsedMs = 0L
    /** When each witnessed Instagram id was last recorded as seen. An id absent from the map has not
     *  been seen this service lifetime and must confirm immediately rather than wait out the throttle.
     *  Keyed per id (rather than one timestamp for all) so a signal seen constantly cannot throttle a
     *  rarer one out of ever being confirmed. See [noteSignals]. */
    private val lastSignalConfirmElapsedMs = HashMap<String, Long>()
    /** Per-browser address-bar watch: turns a missing url_bar into "not yet" or "not ever". Fed the
     *  read by [omniboxFor], the on-screen browser set by [scanDisplays], and the durable
     *  version-keyed vouch by [omniboxIdsVouched]. */
    private val addressWatch = AddressWatch(idsVouched = ::omniboxIdsVouched)
    /** Cached omnibox-vouch verdicts, package → (vouched, elapsed-at). See [omniboxIdsVouched]. */
    private val omniboxVouchCache = HashMap<String, Pair<Boolean, Long>>()
    /** Last time each browser's readable omnibox was written through to the witness store. */
    private val lastOmniboxConfirmElapsedMs = HashMap<String, Long>()
    /** What each display's address bar said on the last scan — diagnostics only, for [diagnose]'s
     *  `addr=` field. Cleared at the start of every scan so a departed display cannot keep vouching. */
    private val lastOmniboxRead = HashMap<Int, BrowserTargets.Omnibox>()
    /** The union of Instagram signal ids seen on the last scan, across every display — the `igSignals=`
     *  field, and the canary's input. Diagnostic use only; the *decision* is per window per display. */
    private var lastSignals: Set<String> = emptySet()

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return
            pump()                       // re-scan: split panes / in-app surface changes fire no event
            if (ticking) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        liveness.connected(this)
        clock = AndroidEngineClock()
        ruleSource = ActiveRules.ruleSource(this)
        coordinator = BudgetCoordinator(
            clock,
            PrefsEngineStore(this, clock),
            AndroidClockIntegrity(this),
            ruleSource,
            exceptionWaitMs = ActiveRules.exceptionWaitMs,
        )
        unlockController = DurableUnlockController(this)
        blocklistStore = BlocklistStore(this)
        witnessStore = SignalWitnessStore(this)
        omniboxWitnessStore = OmniboxWitnessStore(this)
        clockSettingsWatch?.stop()
        clockSettingsWatch = ClockSettingsWatch(this) {
            runCatching { coordinator.onClockSettingChanged() }
        }.also { it.start() }
        registerDisplayListener()
    }

    /**
     * Watch for displays appearing and disappearing, so plugging a monitor in is noticed without waiting
     * for the next accessibility event.
     *
     * Registered with the service's own main-looper [handler], which is what keeps every display
     * callback on the same thread as the rest of the service state: **no new concurrency** around the
     * hold map or the attachment map, no locks, nothing volatile.
     *
     * The whole registration is wrapped, because [onServiceConnected] must never throw — a throw here
     * would kill the service and the watchdog would not notice for up to fifteen minutes.
     */
    private fun registerDisplayListener() {
        runCatching {
            displayListener?.let { displayManager.unregisterDisplayListener(it) }   // reconnect guard
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = scheduleDisplayPump()
                override fun onDisplayRemoved(displayId: Int) = scheduleDisplayPump()
                // Fires on every rotation of display 0 and, on this LTPO panel, on adaptive
                // refresh-rate changes. It must NEVER tear down a live overlay — that is a dropped
                // block-screen frame per rotation, i.e. the C-1 defect class. A re-pump is safe
                // because nothing is cached per display.
                override fun onDisplayChanged(displayId: Int) = scheduleDisplayPump()
            }
            displayManager.registerDisplayListener(listener, handler)
            displayListener = listener
        }
    }

    /**
     * All three display callbacks mean the same thing: re-enumerate.
     *
     * Deliberately non-load-bearing — every outcome is reachable from the window map on the next event
     * or tick, so a callback that never arrives costs latency, never correctness. Throttled so that an
     * adaptive-refresh-rate storm on the phone's own panel cannot become a pump storm.
     */
    private fun scheduleDisplayPump() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDisplayPumpElapsedMs < DISPLAY_PUMP_THROTTLE_MS) return
        lastDisplayPumpElapsedMs = now
        runCatching { pump() }
        diagnoseDisplays()
    }

    /**
     * Targets with a live rule — which is what makes a user-added app (Batch 4) enforced, since the
     * rule list *is* the registry.
     *
     * Cached briefly because this runs inside the per-window resolve (~700ms while Instagram is up)
     * and would otherwise re-read and re-decode prefs every pass. The staleness can only delay a
     * *newly added* block by a couple of seconds, immediately after the user deliberately added it;
     * a removal has already cost a 2-hour wait by the time it lands, so a moment of extra strictness
     * there is the safe direction.
     */
    private fun activeTargets(): Set<Target> {
        val now = SystemClock.elapsedRealtime()
        activeTargetsCache?.let { if (now - activeTargetsAtMs < ACTIVE_TARGETS_TTL_MS) return it }
        val fresh = ruleSource.rules().mapTo(mutableSetOf()) { it.target }
        activeTargetsCache = fresh
        activeTargetsAtMs = now
        return fresh
    }

    /**
     * Every event goes through here, so nothing thrown inside may escape: an uncaught exception on
     * this callback kills the service, and a dead service blocks nothing until the watchdog notices up
     * to 15 minutes later. The `lateinit` fields are the concrete risk — events can arrive before
     * [onServiceConnected] has finished wiring them, and that throws
     * `UninitializedPropertyAccessException` rather than anything the inner guards catch.
     *
     * Swallowing is the right call here specifically because the loop is self-healing: the next event
     * or the 5-second tick re-runs the whole decision from scratch, so one lost pass costs nothing.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching { handleEvent(event) }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        val windowEvent = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (!windowEvent && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) noteForegroundPackage(event)
        if (selfDefense(event)) return
        if (windowEvent) {
            pump()
            return
        }
        // Content-changed: Instagram (reel↔feed flips) and allowlisted browsers (navigating to a new URL)
        // change what to block without a window event, so poll them (throttled) while they're on screen.
        val pkg = event.packageName?.toString()
        if (pkg == InstagramSurface.PACKAGE || (pkg != null && BrowserTargets.isAllowlisted(pkg))) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastContentPumpElapsedMs >= CONTENT_THROTTLE_MS) {
                lastContentPumpElapsedMs = now
                pump()
            }
        }
    }

    /**
     * Record who owns the screen, for the occlusion hold's "the user moved on" test.
     *
     * Two kinds of event carry our own package and must NOT count as moving on:
     *  - the block overlay itself being added or removed, and the self-defense Toast. Treating those as
     *    a foreground change would release the hold every time the overlay appears, which is precisely
     *    the once-a-second oscillation [DisplayHolds] exists to stop.
     *  - so only our real UI qualifies, identified by class: opening App-Block *is* leaving the blocked
     *    app, and the block screen shouldn't sit on top of the app's own settings.
     *
     * ## Which display the event is filed under
     *
     * At API 33+ an event carries its own display id, and one it could not attribute
     * (`INVALID_DISPLAY` = −1) is **dropped rather than filed under display 0** — filing it there would
     * let it release the *phone's* hold, which is the loosening direction. Below 33 no event carries a
     * display id at all, so every event files under display 0, byte-identical to today.
     */
    private fun noteForegroundPackage(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName && event.className?.toString() != MainActivity::class.java.name) return
        val displayId =
            if (attributable) DisplayCensus.attribute(eventDisplayId(event)) ?: return
            else DisplayCensus.DEFAULT_DISPLAY
        // Reaching here is the proof that the moved-on release condition has a live channel to work
        // with, which is what retires that display's timeout backstop (C-1). It is fed only by the
        // events that survive the filter above, so the overlay cannot prove the point by its own churn.
        holds.noteForegroundEvent(displayId, pkg)
    }

    /** The event's own display, or null below API 33 / when the framework never filled it in. */
    private fun eventDisplayId(event: AccessibilityEvent): Int? =
        if (attributable) runCatching { event.displayId }.getOrNull() else null

    /** Can the multi-display window read run? `getWindowsOnAllDisplays()` is API 30. */
    private val multiDisplay get() = Build.VERSION.SDK_INT >= MULTI_DISPLAY_SDK

    /**
     * Can an event be attributed to a display? `AccessibilityRecord.getDisplayId()` is API 33. Below
     * that NO event carries one, so every event files under display 0 — exactly what every line of this
     * service assumed before DeX.
     */
    private val attributable get() = Build.VERSION.SDK_INT >= EVENT_DISPLAY_SDK

    /**
     * One decision cycle: resolve what's foreground on **every** display (surface-aware for Instagram,
     * URL-aware for browsers), tick the engine once, cover the displays that need covering, and keep
     * ticking while anything blockable — a live target, a visible Instagram window, or a visible
     * browser — is on any screen.
     *
     * ⚠️ **The order below is load-bearing in two places, and both traps have been paid for once in
     * this file already.**
     *
     *  1. `coveredOnEntry` is the set as of **entry**, never after [DisplayOverlays.reconcile]. Reading
     *     it afterwards would treat a display we just covered as having been covered when its read was
     *     taken, flipping the arm/sustain branch. This is the per-display form of the old
     *     `holdThroughOcclusion` relying on `overlayView == null` being the *previous* pass's state.
     *  2. The **seed stays after** the overlay goes up, or the one-scan free frame comes back: the
     *     overlay appears, the next scan is already pruned, nothing has been held yet, and the block
     *     drops for ~200 ms.
     */
    private fun pump() {
        val now = SystemClock.elapsedRealtime()
        val coveredOnEntry = overlays.covered()
        val raw = scanDisplays()
        holds.retain(raw.keys + enumeratedDisplayIds())
        val effective = holds.effective(raw, Foreground::blockable, coveredOnEntry, now)
        diagnose(raw, effective)
        refreshForeground(mergeForegrounds(effective))
        val decision = coordinator.tick()
        applyDecision(decision, effective)
        val coveredNow = overlays.covered()
        for ((id, read) in effective) {
            if (id in coveredNow && read.blockable) holds.seed(id, read, now)
        }
        diagnoseDisplays()
        if (decision.target != null || surfaceAppVisible || browserVisible) startTicking() else stopTicking()
    }

    /**
     * The settings-watch (CONSTRAINTS lever A). A Settings screen about App-Block itself — the
     * Accessibility toggle, its "Turn off?" dialog, App info with Force stop / Uninstall, the
     * overlay-permission page — gets bounced to Home, so disabling the blocker isn't a zero-friction
     * escape. It also covers the wireless-debugging screen, which names nothing of ours (B-10).
     *
     * What counts as "about App-Block" is [SettingsWatch]'s call, and it is narrower than it looks:
     * a screen merely *containing* our label is a list of every app on the phone as often as it is a
     * page about us (C-4). The service's job is only to hand over the screen's identity along with
     * its words — see [visibleWatchedScreens].
     *
     * ⚠️ **One [SettingsWatch.Screen] per display, OR-ed — never merged into one.** That is a
     * correctness argument, not a preference: `isBypassScreen` is `markers && !exclusions`, so a string
     * from the *phone's* Settings screen could satisfy an exclusion and suppress a bounce the monitor
     * had earned — a fail-open manufactured by a refactor, on the tier that guards every other tier.
     *
     * An open durable-change window makes "switch the service off" a gated loosening like any other
     * (CONSTRAINTS §6) — but only that. It no longer stands down the *installer* tier, so it can't
     * also buy an uninstall; see [SettingsWatch.shouldBounce] for why that mattered. Setup being
     * incomplete stands down everything, and a missing overlay permission drops the Settings tier to
     * repair mode so the blocker can't guard the app out of its own repair.
     *
     * Returns true when it bounced (the event needs no further handling).
     */
    private fun selfDefense(event: AccessibilityEvent): Boolean {
        val pkg = event.packageName?.toString()
        if (!SettingsWatch.isWatched(pkg)) return false
        checkHealthWhileInSettings()
        // The stand-downs are passed through rather than short-circuited here, because they no longer
        // agree: an open window stands down the Settings tier but leaves the installer tier armed
        // (B-8). Only setup-incomplete disarms everything. Cost is one text walk while a window is
        // open in Settings, which is bounded and rare.
        val screens = visibleWatchedScreens()
        lastWatchedDisplays = screens.keys
        val setupIncomplete = !Watchdog.setupCompleted(this)
        val windowOpen = unlockController.isOpen()
        val (canDraw, opAllows) = readOverlayGrant()
        val repairMode = repairWatch.observe(canDraw, opAllows, SystemClock.elapsedRealtime())
        // Repair mode disarms every rule below, so it may never be silent again (2026-08-29). Forcing
        // the health report past its throttle is what turns "the guard is down" into a notification the
        // user can see, on the pass it happens rather than up to fifteen minutes later.
        if (repairWatch.justEngaged) announceRepairMode()
        // The three stand-downs are read ONCE per pass, above, and applied identically to every
        // display's screen. Only the screen itself is per display.
        val bounce = screens.values.any { screen ->
            SettingsWatch.shouldBounce(
                packageName = pkg,
                screen = screen,
                selfLabel = getString(R.string.app_name),
                setupIncomplete = setupIncomplete,
                windowOpen = windowOpen,
                repairMode = repairMode,
            )
        }
        diagnoseSelfDefense(pkg, screens, setupIncomplete, windowOpen, repairMode, bounce)
        if (!bounce) return false
        performGlobalAction(GLOBAL_ACTION_HOME)
        DisplayCoverage.bounceDisplay(screens.keys, lastActiveDisplayId)?.let { launchHomeOn(it) }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBounceToastElapsedMs > BOUNCE_TOAST_THROTTLE_MS) {
            lastBounceToastElapsedMs = now
            // Which price this bounce actually carries. Read at toast time rather than cached: the
            // key can be created while the service is alive, and the throttle makes this at most one
            // prefs read per 3 s.
            val message =
                if (LockStore(this).isConfigured()) R.string.self_defense_bounce
                else R.string.self_defense_bounce_nokey
            Toast.makeText(this, getString(message), Toast.LENGTH_SHORT).show()
        }
        return true
    }

    /**
     * A second, display-targeted Home for a watched Settings screen the global action may miss.
     *
     * `performGlobalAction(GLOBAL_ACTION_HOME)` injects a HOME key event, which goes to the
     * **input-focused** display and is not selectable. In DeX dual mode the phone can hold focus while a
     * page about App-Block sits open on the monitor, so the global action lands on the wrong screen —
     * a fail-open on the tier that guards every other tier.
     *
     * Purely additive: the global action has already fired, so if the platform refuses this we are
     * exactly where we would have been without it, and `active=` in the `AppBlockWatch` line says so.
     * `setLaunchDisplayId` is API 26, i.e. minSdk, so it needs no guard.
     */
    private fun launchHomeOn(displayId: Int) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
            )
        }
    }

    /**
     * The watchdog's check, run from here as well as from its 15-minute worker, throttled to once per
     * [HEALTH_CHECK_THROTTLE_MS] and only while a watched Settings package is on screen.
     *
     * Two of the doors the 2026-08-21 audit found — the device-admin entry (N-2) and the battery
     * exemption (N-3) — are switched off on *list* pages, which the settings-watch rightly never
     * bounces; detection plus self-repair is the answer for those, not a bounce. The worker alone
     * would notice up to fifteen minutes later, by which time the user has moved on and the nag reads
     * as noise. Checked here, the toggle flips and the notification lands while the page is still on
     * screen, which is the moment it reads as a consequence. Settings is the only place any of these
     * can change from, so this is also the only place worth paying for the check.
     */
    private fun checkHealthWhileInSettings() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHealthCheckElapsedMs < HEALTH_CHECK_THROTTLE_MS) return
        lastHealthCheckElapsedMs = now
        if (!Watchdog.setupCompleted(this)) return
        Watchdog.report(this, Watchdog.currentHealth(this))
    }

    /**
     * Repair mode has just engaged, so the Settings tier is standing down — say so out loud.
     *
     * The throttle is reset rather than respected: [checkHealthWhileInSettings] has almost always run
     * moments earlier on this same screen, so honouring it would swallow the one report that matters.
     * The health this posts is `NO_OVERLAY`, which is exactly right — repair mode only engages when
     * both readings agree the permission is gone, or when a disagreement has outlived its grace, and
     * "Appear on top is off, blocked apps just throw you Home" is the honest description of both.
     *
     * This is the fix for the *silence*, which is what made the 2026-08-29 outage a P0 rather than a
     * bug: the guard came down, nothing said so, and the two symptoms it produced were written up that
     * morning as two unrelated mysteries.
     */
    private fun announceRepairMode() {
        if (!Watchdog.setupCompleted(this)) return
        lastHealthCheckElapsedMs = SystemClock.elapsedRealtime()
        runCatching { Watchdog.report(this, Watchdog.currentHealth(this)) }
    }

    /**
     * Why a settings-watch decision came out the way it did, on QA builds only, logged once per
     * distinct state. The instrument N-2 needed and did not have: on the phone the device-admin page
     * simply failed to bounce, and separating "the screen never reached the rules" from "the rules
     * said no" cost a whole cable session of `dumpsys` and inference.
     *
     * Read it as: `pkg` · how many strings the screen offered · whether our label was among them ·
     * which control word matched, if any · whether it sat on a **checkable** control (rule 3, the
     * accessibility-button picker) · `rescued=true` when the active window had to be pulled in
     * because `getWindows()` had not offered it (**this is the N-2 answer** — true on the device-admin
     * page means `getWindows()` was the silent failure) · the three stand-downs · the verdict.
     *
     * `repairMode` now carries its evidence rather than just its value: `repairMode=false(disagree 42s)`
     * means `canDrawOverlays()` is saying no while the app op says the permission is held, and the tier
     * is deliberately staying armed. That is the state that ran silently for minutes on 2026-08-29 and
     * cost two mis-written audit entries; printed, it names itself.
     *
     * `adb logcat -s AppBlockWatch`, open Device admin apps, tap the App-Block row, read one line.
     *
     * Strings are counted, never printed. Every string on a watched Settings screen is fair game to
     * log by the same argument the omnibox diagnostic uses in reverse — but titles alone would leak
     * which Settings pages were visited, and the counts answer the question just as well.
     */
    private fun diagnoseSelfDefense(
        packageName: String?,
        screens: Map<Int, SettingsWatch.Screen>,
        setupIncomplete: Boolean,
        windowOpen: Boolean,
        repairMode: Boolean,
        bounce: Boolean,
    ) {
        if (!BuildConfig.DEBUG && !BuildConfig.FAST_CAPS) return
        val label = getString(R.string.app_name)
        // Counts sum across displays so titles=/texts=/checkables= keep meaning what they meant, and the
        // three `namesUs` flags stay "anywhere on any watched screen", which is what the rules ask.
        val titles = screens.values.flatMap { it.titles }
        val texts = screens.values.flatMap { it.texts }
        val checkables = screens.values.flatMap { it.checkables }
        val namesUs = texts.any { it.contains(label, ignoreCase = true) }
        val titleNamesUs = titles.any { it.contains(label, ignoreCase = true) }
        val checkNamesUs = checkables.any { it.contains(label, ignoreCase = true) }
        val control = SettingsWatch.settingsControls.firstOrNull { needle ->
            texts.any { it.contains(needle, ignoreCase = true) }
        }
        // Bucketed, not exact: this line is deduplicated by equality, and a per-second value would make
        // every event a new line — an instrument that floods the log it is read from is not one.
        val disagreeMs = repairWatch.disagreementMs(SystemClock.elapsedRealtime())
        val repair =
            if (disagreeMs >= DISAGREE_BUCKET_MS) {
                "$repairMode(disagree ~${disagreeMs / DISAGREE_BUCKET_MS * (DISAGREE_BUCKET_MS / 1_000)}s)"
            } else {
                "$repairMode"
            }
        // dsp= says whether the self-defense tier can even SEE the monitor's Settings page; active= says
        // where the injected HOME is going to land. `dsp=[3] active=0` with `bounce=true` is the
        // fail-open shape: the tier fired and the kick went to the wrong screen. budgetLeft= covers an
        // EXISTING silent fail-open — running the settings walk out of budget makes it miss the screen,
        // and until now there was no instrument for it at all.
        val line = "pkg=${packageName?.substringAfterLast('.')} " +
            "titles=${titles.size} texts=${texts.size} " +
            "checkables=${checkables.size} " +
            "namesUs=$namesUs titleNamesUs=$titleNamesUs checkNamesUs=$checkNamesUs control=$control " +
            "rescued=$lastActiveWindowRescued " +
            "dsp=${DisplayCensus.order(screens.keys)} active=$lastActiveDisplayId " +
            "budgetLeft=$lastSettingsBudgetLeft " +
            "setupIncomplete=$setupIncomplete windowOpen=$windowOpen repairMode=$repair " +
            "bounce=$bounce"
        if (line == lastWatchDiagLine) return
        lastWatchDiagLine = line
        android.util.Log.d(WATCH_TAG, line)
    }

    /**
     * The two overlay-permission readings — `Settings.canDrawOverlays()` and the corroborating app-op
     * check — cached together for [OVERLAY_CHECK_TTL_MS] because this is consulted per Settings event
     * and both are binder calls. Defaults to granted-and-allowed so a check that hasn't happened yet
     * leaves the self-defense armed rather than open.
     *
     * Cached as a pair on purpose: [OverlayRepairWatch] is judging whether the two *agree*, so reading
     * them at different moments would let the cache manufacture a disagreement that never existed.
     */
    private fun readOverlayGrant(): Pair<Boolean, Boolean> {
        val now = SystemClock.elapsedRealtime()
        if (now - overlayGrantedAtMs > OVERLAY_CHECK_TTL_MS) {
            overlayGranted = Settings.canDrawOverlays(this) to overlayAppOpAllows(this)
            overlayGrantedAtMs = now
        }
        return overlayGranted
    }

    /**
     * All visible text (text + contentDescription) of windows owned by watched settings packages,
     * with each window's *identity* offered separately — see [SettingsWatch.Screen] for why a flat bag
     * of strings was audit finding C-4.
     *
     * Two title candidates are taken per window and **both** kept rather than the first that answers:
     * the framework's window title is often just the activity label ("Settings"), and letting that
     * mask the collapsing-toolbar heading would quietly disarm the title rule on the one screen that
     * matters most, the Accessibility toggle page.
     *
     * The first text in reading order is a candidate only when a window offers neither, and that
     * restraint is the point. It is right on every Settings screen captured at Gate F — the toolbar
     * comes first in the tree, giving "Apps", "Appear on top", "Installed apps", "Developer options" —
     * but a list scrolled so that App-Block's own row is at the top would read as a screen about
     * App-Block, which is the exact false positive this whole change exists to remove.
     *
     * ## One screen per display, and a budget per display
     *
     * A Settings page about App-Block can be opened on a DeX monitor, and until 2026-08-30 this read
     * only the default display's windows — so that page reached no rule at all and was never bounced.
     * The tier that guards every other tier was single-display.
     *
     * The budget is **per display, not shared**, and that is mandatory rather than tidy: a shared 800
     * lets a deep Settings page on one display starve the walk on the other, and a starved walk reads as
     * an innocent screen. The `walked` window-id set stays shared, because accessibility window ids come
     * from one global counter and cannot collide across displays.
     *
     * `getRootInActiveWindow()` is documented **global** — *"It could be from any logical display"* — so
     * the N-2 second channel does not go blind on DeX. But its result no longer belongs to display 0 by
     * assumption, which is what the attribution below is for.
     */
    private fun visibleWatchedScreens(): Map<Int, SettingsWatch.Screen> {
        val out = LinkedHashMap<Int, SettingsWatch.Screen>(2)
        val walked = HashSet<Int>(6)
        val byDisplay = windowsByDisplay()
        var lowestBudget = NODE_BUDGET
        for (displayId in DisplayCensus.order(byDisplay.keys)) {
            val titles = ArrayList<CharSequence>(6)
            val texts = ArrayList<CharSequence>(64)
            val checkables = ArrayList<CharSequence>(8)
            var budget = NODE_BUDGET
            for (window in byDisplay[displayId].orEmpty()) {
                runCatching {
                    val root = window.root ?: return@runCatching
                    if (!SettingsWatch.isWatched(root.packageName?.toString())) return@runCatching
                    walked.add(window.id)
                    val firstIndex = texts.size
                    val heading = ArrayList<CharSequence>(1)
                    budget = collectTexts(root, texts, heading, checkables, budget)
                    val found = titles.size
                    window.title?.takeIf { it.isNotBlank() }?.let { titles.add(it) }
                    titles.addAll(heading)
                    if (titles.size == found) texts.getOrNull(firstIndex)?.let { titles.add(it) }
                }
                if (budget <= 0) break
            }
            lowestBudget = minOf(lowestBudget, budget)
            if (texts.isNotEmpty() || titles.isNotEmpty()) {
                out[displayId] = SettingsWatch.Screen(titles, texts, checkables)
            }
        }
        lastActiveWindowRescued = false
        // Second channel for the window the user is actually looking at — see
        // [SettingsWatch.shouldWalkActiveWindow] for the N-2 hardware failure that bought it. Only
        // ever *adds* a window the first pass missed, so on the ordinary screen this is one set lookup.
        if (lowestBudget > 0) {
            runCatching {
                val active = rootInActiveWindow ?: return@runCatching
                if (!SettingsWatch.shouldWalkActiveWindow(
                        walkedWindowIds = walked,
                        activeWindowId = active.windowId,
                        activePackageName = active.packageName?.toString(),
                    )
                ) {
                    return@runCatching
                }
                lastActiveWindowRescued = true
                val heading = ArrayList<CharSequence>(1)
                val rescuedTexts = ArrayList<CharSequence>(64)
                val rescuedCheckables = ArrayList<CharSequence>(8)
                lowestBudget = collectTexts(active, rescuedTexts, heading, rescuedCheckables, lowestBudget)
                // Heading yes, first-text-in-reading-order no. The window title this window would have
                // been judged against isn't available on this path, and the first-text candidate is
                // only safe *because* it is the last resort of a window that offered neither (C-4): a
                // list scrolled so App-Block's row sits at the top would otherwise read as a page about
                // App-Block. Rule 2 still sees every string, which is what N-2 needs.
                val id = rescuedDisplayId(active)
                val existing = out[id]
                out[id] = SettingsWatch.Screen(
                    titles = (existing?.titles.orEmpty() + heading),
                    texts = (existing?.texts.orEmpty() + rescuedTexts),
                    checkables = (existing?.checkables.orEmpty() + rescuedCheckables),
                )
            }
        }
        lastSettingsBudgetLeft = lowestBudget
        return out
    }

    /**
     * Which display the rescued active window belongs to.
     *
     * `AccessibilityWindowInfo.getDisplayId()` is API 30; below that only one display can exist, so
     * defaulting to [DisplayCensus.DEFAULT_DISPLAY] is exact rather than a guess.
     */
    private fun rescuedDisplayId(active: AccessibilityNodeInfo): Int = runCatching {
        if (Build.VERSION.SDK_INT >= MULTI_DISPLAY_SDK) {
            active.window?.displayId ?: DisplayCensus.DEFAULT_DISPLAY
        } else {
            DisplayCensus.DEFAULT_DISPLAY
        }
    }.getOrDefault(DisplayCensus.DEFAULT_DISPLAY)

    /**
     * Depth-first text collection, capped at [budget] nodes so a huge tree can't stall the service.
     * The first node marked as a heading also lands in [heading] — that is a screen title on every
     * layout that bothers to declare one, and it survives scrolling, which the position of the text
     * itself does not.
     *
     * A node the framework reports as `isCheckable` also lands in [checkables], which is what lets the
     * watch tell "a switch **called** App-Block" from "a row *next to* an unlabelled switch" — the one
     * distinction that separates the accessibility-button picker from every list it otherwise looks
     * exactly like. See [SettingsWatch.Screen.checkables] for the two captures that settled it. Both
     * text and contentDescription are taken, because on the picker One UI fills in both and there is no
     * reason to bet on which survives a Samsung layout change.
     */
    private fun collectTexts(
        node: AccessibilityNodeInfo,
        out: MutableList<CharSequence>,
        heading: MutableList<CharSequence>,
        checkables: MutableList<CharSequence>,
        budget: Int,
    ): Int {
        if (budget <= 0) return 0
        var remaining = budget - 1
        runCatching {
            val checkable = runCatching { node.isCheckable }.getOrDefault(false)
            node.text?.let {
                out.add(it)
                if (checkable) checkables.add(it)
                if (heading.isEmpty() && isHeading(node)) heading.add(it)
            }
            node.contentDescription?.let {
                out.add(it)
                if (checkable) checkables.add(it)
            }
        }
        for (child in childrenOf(node)) {
            if (remaining <= 0) break
            remaining = collectTexts(child, out, heading, checkables, remaining)
        }
        return remaining
    }

    /**
     * Android's own "this node is a heading" flag, added in API 28 — below that it simply never fires
     * and the window title / first-text candidates carry the rule. Read through a version check rather
     * than a min-SDK bump because 26 costs nothing to keep and this is one of three sources.
     */
    private fun isHeading(node: AccessibilityNodeInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && node.isHeading

    /**
     * Inform the coordinator if the *target* changed. Tracking by target — not package — is what lets
     * Instagram flip between budgeted (reel player) and free (feed/DMs) within the one package.
     *
     * ⚠️ [fg] is the **merged** read across displays, and its target list must be order-stable between
     * passes with unchanged content. An order that flapped would call `onForegroundTargets` every pass,
     * which banks the accrued time and re-picks the accruing target — splitting one sitting between two
     * budgets so that neither ever reaches its cap. That is why the ordering lives in
     * [DisplayCensus.mergeTargets], with a test, rather than in a map's iteration order.
     */
    private fun refreshForeground(fg: Foreground) {
        surfaceAppVisible = fg.instagramVisible
        browserVisible = fg.browserVisible
        if (fg.targets != lastForegroundTargets) {
            lastForegroundTargets = fg.targets
            coordinator.onForegroundTargets(fg.targets)
        }
    }

    /**
     * One [Foreground] for the coordinator and the block message, folded from the per-display reads.
     *
     * Deliberately impure and short: **coverage is decided per display from the per-display reads and
     * never from this merge**, so a mistake here can only pick the wrong *message*. The one part that is
     * not cosmetic — the target order, which decides accrual — is delegated to
     * [DisplayCensus.mergeTargets], which is pure and tested.
     *
     * Visibility flags are OR-ed across displays, so a browser or Instagram on the monitor keeps the
     * 5-second tick alive.
     */
    private fun mergeForegrounds(reads: Map<Int, Foreground>): Foreground {
        val ordered = DisplayCensus.order(reads.keys).mapNotNull { reads[it] }
        return Foreground(
            targets = DisplayCensus.mergeTargets(reads.mapValues { it.value.targets }),
            instagramVisible = ordered.any { it.instagramVisible },
            browserVisible = ordered.any { it.browserVisible },
            webBlock = ordered.firstNotNullOfOrNull { it.webBlock },
            webHost = ordered.firstNotNullOfOrNull { it.webHost },
            webPkg = ordered.firstNotNullOfOrNull { it.webPkg },
        )
    }

    private data class Foreground(
        /**
         * Every target on screen, strictest-wins, in precedence order: the package match first, then
         * any surface-detected one. Usually 0 or 1 — two only for Instagram, which carries app-wide
         * closing hours *and* a reels budget.
         */
        val targets: List<Target>,
        val instagramVisible: Boolean,
        val browserVisible: Boolean = false,
        val webBlock: BrowserPolicy.WebBlock? = null,
        val webHost: String? = null,
        /** Which browser produced [webBlock] — the overlay's exit needs to steer that same browser. */
        val webPkg: String? = null,
    ) {
        /**
         * "Is anything limited on screen?" — the question every existing caller was really asking of
         * the old single-target field. Kept as a derived property so those readers stay correct while
         * the resolution underneath became plural.
         */
        val target: Target? get() = targets.firstOrNull()

        /**
         * Is anything block-worthy on this display? — the arm condition the old `holdThroughOcclusion`
         * used inline, named so [DisplayHolds.effective] can take it as `Foreground::blockable`.
         */
        val blockable: Boolean get() = target != null || webBlock != null
    }

    /**
     * Scan **every** display's visible windows, one [Foreground] each.
     *
     * ⚠️ **Two things must happen once for the whole pass, not once per display**, and getting either
     * wrong looks like tidy refactoring:
     *
     *  - **`addressWatch.retain`** takes the union of browsers visible on *any* display. Called per
     *    display it would evict a browser visible on the other one, and eviction hands back a fresh
     *    settling grace — so a missing `url_bar` resolves to "not yet" (allow) instead of "not ever"
     *    (block), and **website blocking silently stops firing, on the phone too, the moment a monitor
     *    is connected.**
     *  - **`noteSignals`** takes the union, per its own KDoc. Per display it would still be correct — a
     *    union of a union — but would pay the [SignalCanary] confirm throttle twice.
     *
     * [InstagramSurface.targetForWindows] is called **once per display**, on that display's own
     * per-window signal list. Flattening across displays would let `explorePreview` pair a
     * `context_menu` on the monitor with an `explore_action_bar` on the phone and manufacture a block —
     * over-blocking, so the safe direction, but a false block nobody could explain. Per-display costs
     * nothing real: a `PopupWindow` is anchored to its host window and can never land on a different
     * display from its own grid.
     *
     * Node budgets are spent **per display**, deliberately. A shared budget's failure mode is
     * starvation, and a starved walk reads as "free surface" — fail-open.
     *
     * **Single display: one iteration, the identical body, the same accumulators over the same window
     * set.** Identical to what this did before.
     */
    private fun scanDisplays(): Map<Int, Foreground> {
        lastIgWindowCount = 0
        lastIgNodeCount = 0
        lastOmniboxRead.clear()
        val byDisplay = windowsByDisplay()
        val enumerated = enumeratedDisplayIds()
        lastActiveDisplayId = activeDisplayId(byDisplay)
        val visibleBrowsers = HashSet<String>()
        val allSignals = HashSet<String>()
        var sawInstagram = false
        val out = LinkedHashMap<Int, Foreground>(byDisplay.size)
        val census = ArrayList<DisplayCensus.Display>(byDisplay.size + 1)
        for (displayId in DisplayCensus.order(byDisplay.keys + enumerated)) {
            val windows = byDisplay[displayId]
            if (windows == null) {
                // DisplayManager lists it, accessibility gave us no window list for it. `untracked=` in
                // the census is what names this, and on DeX it is the answer to whether the detection
                // half works on this hardware at all.
                census.add(DisplayCensus.Display(displayId, enumerated = true, windowCount = null))
                continue
            }
            val scan = resolveDisplay(displayId, windows, visibleBrowsers, allSignals)
            out[displayId] = scan.foreground
            if (scan.foreground.instagramVisible) sawInstagram = true
            census.add(
                DisplayCensus.Display(
                    id = displayId,
                    enumerated = displayId in enumerated,
                    windowCount = windows.size,
                    topPackage = scan.topPackage?.substringAfterLast('.'),
                    target = scan.foreground.target?.key,
                    carriesCause = scan.foreground.blockable,
                ),
            )
        }
        lastCensus = census
        lastSignals = allSignals
        // Once for the whole pass — see the KDoc. A per-display call here is a silent website-blocking
        // bypass, and a per-display noteSignals double-pays the canary throttle.
        addressWatch.retain(visibleBrowsers)
        if (sawInstagram) noteSignals(allSignals)
        return out
    }

    /** One display's scan result, plus the two log-only facts the census needs from it. */
    private class DisplayScan(val foreground: Foreground, val topPackage: String?)

    /**
     * One display's foreground, resolving three things from that display's own windows: the budgeted
     * [Target] (a whole-app TikTok/X in any pane wins, else Instagram's reel surface), whether Instagram
     * is visible, and the website decision (an allowlisted browser on a blocked URL, or any
     * non-allowlisted browser at all).
     *
     * The body is what [scanDisplays] used to be in full; [visibleBrowsers] and [allSignals] are the two
     * cross-display accumulators it must contribute to rather than act on.
     */
    private fun resolveDisplay(
        displayId: Int,
        windowList: List<AccessibilityWindowInfo>,
        visibleBrowsers: MutableSet<String>,
        allSignals: MutableSet<String>,
    ): DisplayScan {
        // Every distinct package owning a visible window, in getWindows() order (topmost first) — not
        // the first one that happened to match a target. This used to be a single `packageTarget` var
        // filled by the first hit, which meant exactly one budgeted app reached the engine however many
        // were on screen, and a second app in the other split-screen pane was neither gated nor metered.
        // See AppTargets.foregroundTargets, which owns the mapping and the reasoning.
        val onScreenPackages = LinkedHashSet<String>(4)
        // Every Instagram window, not the first one. A reel's long-press menu is a second window owned
        // by com.instagram.android, and getWindows() offers it first (topmost). Taking only the first
        // meant reading a 28-node context menu instead of the reel player under it. See
        // InstagramSurface.targetForWindows.
        val instagramRoots = ArrayList<AccessibilityNodeInfo>(2)
        var browserVisible = false
        var webBlock: BrowserPolicy.WebBlock? = null
        var webHost: String? = null
        var browserPkg: String? = null      // whose omnibox we read — also the overlay's exit target
        var omnibox: BrowserTargets.Omnibox? = null   // diagnostics only — what the address bar said
        // Every allowlisted browser *on screen*, which is wider than the one we read: only the first
        // browser window gets an omnibox read, but a second one in the other split-screen pane is still
        // present and its watch must survive the pass. Accumulated ACROSS displays — see AddressWatch.retain.
        val browsers = browserPackages()
        for (window in windowList) {
            // Isolate each window. A live tree churns under the walk (a playing reel especially), so
            // reading it can throw on a recycled / not-sealed node. One bad window must never collapse
            // the whole resolution to "nothing foreground" - silently doing that stops accrual AND the
            // 5s tick, which is exactly how a blocker fails open.
            runCatching {
                val root = window.root ?: return@runCatching
                val pkg = root.packageName?.toString() ?: return@runCatching
                onScreenPackages.add(pkg)
                if (pkg == InstagramSurface.PACKAGE) instagramRoots.add(root)
                if (BrowserTargets.isAllowlisted(pkg)) visibleBrowsers.add(pkg)
                if (webBlock == null &&
                    (BrowserTargets.isAllowlisted(pkg) || BrowserTargets.isWebApp(pkg) || pkg in browsers)
                ) {
                    browserVisible = true
                    val read =
                        if (BrowserTargets.isAllowlisted(pkg)) omniboxFor(root, pkg)
                        else BrowserTargets.Omnibox.Unknown
                    browserPkg = pkg
                    omnibox = read
                    webBlock = BrowserPolicy.decide(
                        pkg,
                        isBrowser = pkg in browsers,
                        omnibox = read,
                        blocklist = blocklistStore.domains().toSet(),
                    )
                    if (webBlock == BrowserPolicy.WebBlock.BLOCKED_SITE && read is BrowserTargets.Omnibox.Url) {
                        webHost = DomainMatcher.host(read.value)
                    }
                }
            }
        }
        // One signal set per Instagram window, sharing this DISPLAY's node budget so a cheap popup
        // cannot starve the walk of the window that actually matters.
        val perWindowSignals = collectInstagramSignals(instagramRoots)
        // Union — for the canary and the log only, and accumulated across displays by the caller. The
        // *decision* is per-window within this display (see below).
        perWindowSignals.forEach { allSignals.addAll(it) }
        // Both, not either. The package match used to win outright, which made the surface target
        // unreachable whenever a package also matched — and for Instagram a package now always
        // matches (its whole-app closing hours), so the reels budget would have gone dark.
        // Strictest-wins across windows, never on the union: a share sheet carrying a sender field
        // must not exempt a firehose reel playing in a different window. Per DISPLAY, so a preview card
        // on one screen cannot pair with a grid on the other.
        val surfaceTarget = InstagramSurface.targetForWindows(perWindowSignals)
        // The mapping itself is pure and tested; this side only supplies what it saw. Order matters —
        // the engine gates every target here but accrues to the first, so topmost must stay first.
        val targets =
            AppTargets.foregroundTargets(onScreenPackages.toList(), activeTargets(), surfaceTarget)
        omnibox?.let { lastOmniboxRead[displayId] = it }
        return DisplayScan(
            Foreground(
                targets,
                instagramRoots.isNotEmpty(),
                browserVisible,
                webBlock,
                webHost,
                browserPkg,
            ),
            topPackage = onScreenPackages.firstOrNull(),
        )
    }

    /**
     * The visible windows **per logical display**, or an empty map if the system refuses them (never
     * throw out of a scan).
     *
     * Needs **no** new `accessibility_service_config.xml` flag: `getWindowsOnAllDisplays()`'s
     * requirements are character-for-character `getWindows()`' — `canRetrieveWindowContent` plus
     * `flagRetrieveInteractiveWindows` — and both are already declared. Worth saying out loud, because
     * silently-failing declared config is this project's recurring theme.
     *
     * It also costs nothing extra: `getWindows()` is literally `getWindowsOnAllDisplays().get(0)`, one
     * binder call served from the same accessibility cache. Only the node walking that follows scales
     * with the number of displays.
     *
     * ⚠️ **INVARIANT: display 0's list is never worse than it is today.** That a one-display device
     * returns a single entry keyed 0 is inference from AOSP, not something measured on this phone, so it
     * is enforced here as a rule rather than assumed elsewhere: if the all-displays read comes back
     * empty, or without key 0, key 0 is refilled from the legacy call that has always worked.
     */
    private fun windowsByDisplay(): Map<Int, List<AccessibilityWindowInfo>> {
        val out = LinkedHashMap<Int, List<AccessibilityWindowInfo>>(2)
        if (multiDisplay) {
            runCatching { windowsOnAllDisplays }.getOrNull()?.let { sparse ->
                for (i in 0 until sparse.size()) out[sparse.keyAt(i)] = sparse.valueAt(i).orEmpty()
            }
        }
        lastAllDisplaysApi = out.isNotEmpty()
        if (DisplayCensus.mustBackfillDefault(out.keys)) {
            out[DisplayCensus.DEFAULT_DISPLAY] = runCatching { windows }.getOrNull().orEmpty()
        }
        return out
    }

    /**
     * The display holding the active window — where an injected HOME key will land.
     *
     * `AccessibilityWindowInfo.isActive` is API 21, so no guard is needed; on a single-display device
     * this is 0 or null and [DisplayCoverage.homeFallback]'s third clause coincides with its second.
     */
    private fun activeDisplayId(byDisplay: Map<Int, List<AccessibilityWindowInfo>>): Int? =
        byDisplay.entries.firstOrNull { (_, windows) ->
            windows.any { runCatching { it.isActive }.getOrDefault(false) }
        }?.key

    /**
     * What `DisplayManager` lists for this uid. Another app's private virtual display — a screen
     * recorder's, a cast's — is never returned, which is one of the three independent layers that keep
     * us off screens we have no business covering.
     *
     * Feeds [DisplayHolds.retain] (unioned with the window-map keys) and the census's `dm=` field.
     * **Never a policy input:** coverage follows the window map, never enumeration.
     */
    private fun enumeratedDisplayIds(): Set<Int> =
        runCatching { displayManager.displays.map { it.displayId }.toSet() }.getOrDefault(emptySet())

    /**
     * Why a decision came out the way it did, on QA builds only, logged once per distinct state so
     * logcat stays readable. This is the instrument Gate B needed: `adb logcat -s AppBlockFg` answers
     * "was Instagram enumerated / did the reel signal appear / what target resolved" in one line.
     *
     * Gated on FAST_CAPS as well as DEBUG on purpose: `debugFast` is built non-debuggable (so `run-as`
     * can't reach its prefs), which makes BuildConfig.DEBUG false — and debugFast is precisely the
     * build the phone gates are run on. The signed release logs nothing.
     */
    private fun diagnose(raw: Map<Int, Foreground>, effective: Map<Int, Foreground>) {
        if (!BuildConfig.DEBUG && !BuildConfig.FAST_CAPS) return
        // The main line is DISPLAY 0's, character-for-character what it has always been. Other displays
        // are appended by DisplayCensus.blocks(), which returns the empty string when there is only one
        // — so on a phone with no monitor this line does not change at all, and every existing log note
        // and grep in the repo stays valid.
        val default = DisplayCensus.DEFAULT_DISPLAY
        val rawRead = raw[default]
        val effectiveRead = effective[default]
        val windowCount = lastCensus.firstOrNull { it.id == default }?.windowCount ?: 0
        val targets = rawRead?.targets.orEmpty()
        val instagramVisible = rawRead?.instagramVisible == true
        val signals = if (instagramVisible) lastSignals else null
        val target = rawRead?.target
        val effectiveTarget = effectiveRead?.target
        val browserPkg = rawRead?.webPkg
        val omnibox = lastOmniboxRead[default]
        val webBlock = rawRead?.webBlock
        val effectiveWeb = effectiveRead?.webBlock
        // Report the hold for *either* kind of block. Comparing only targets hid it entirely for
        // websites (both sides are null there), so Gate D's log showed HELD zero times while the hold
        // was in fact doing its job — an instrument that lies about the thing it exists to measure.
        val held = when {
            effectiveTarget != target -> " HELD=$effectiveTarget"
            effectiveWeb != webBlock -> " HELD=$effectiveWeb"
            else -> ""
        }
        // Browser half, for Gate D. addr distinguishes every way website blocking can fail: browser
        // not recognised at all, address bar being typed in, not found yet, proven unreadable, or read
        // fine but not matched.
        //
        // Host only, never the full URL. The whole point of the omnibox read is that it sees every
        // page you visit, and debugFast is the build that runs on the real phone during real browsing
        // — so logging paths and query strings would put your actual browsing history in logcat, where
        // any app holding READ_LOGS could take it. The host is the only part the matcher uses anyway,
        // so nothing diagnostic is lost.
        val addr = when (omnibox) {
            null -> "NONE"
            is BrowserTargets.Omnibox.Url -> DomainMatcher.host(omnibox.value) ?: "UNPARSED"
            else -> omnibox.toString().substringAfterLast('$')
        }
        val web = if (browserPkg == null) "" else
            " browser=${browserPkg.substringAfterLast('.')} addr=$addr web=$webBlock"
        // `targets=` replaced the old `pkgTarget=` (2026-08-30). The single value could not show the
        // defect it was most needed for: a second budgeted app on screen being dropped. The winner is
        // still printed separately as `target=`, so nothing is lost and the list is what shows a miss.
        val line = "windows=$windowCount targets=$targets ig=$instagramVisible " +
            "igWindows=$lastIgWindowCount igNodes=$lastIgNodeCount " +
            "igSignals=${signals?.map { it.substringAfterLast('/') }} " +
            "target=$target overlay=${default in overlays.covered()}$held$web" +
            DisplayCensus.blocks(censusNow())
        if (line == lastDiagLine) return
        lastDiagLine = line
        android.util.Log.d(DIAG_TAG, line)
    }

    /**
     * [lastCensus] with the two facts that are only known *after* the fold and the reconcile — whether a
     * hold is sustaining that display, and whether our overlay is actually on it.
     */
    private fun censusNow(): List<DisplayCensus.Display> {
        val covered = overlays.covered()
        val failed = overlays.failed()
        return lastCensus.map { display ->
            display.copy(
                held = holds.isArmed(display.id),
                overlay = when {
                    display.id in covered -> DisplayCensus.Overlay.UP
                    display.id in failed -> DisplayCensus.Overlay.FAILED
                    else -> DisplayCensus.Overlay.NONE
                },
            )
        }
    }

    /**
     * The per-display census, on its own tag so `-s AppBlockDsp` isolates it from the per-frame
     * foreground chatter. Emitted from every scan **and** from every [DisplayManager.DisplayListener]
     * callback, so plugging a monitor in produces a line even when nothing is blocked. Deduplicated per
     * distinct state and gated exactly like [diagnose] — the signed release logs nothing.
     *
     * `untracked=[…]` is the load-bearing field: what `DisplayManager` listed that accessibility gave no
     * window list for. It answers the one question this change cannot answer from source.
     */
    private fun diagnoseDisplays() {
        if (!BuildConfig.DEBUG && !BuildConfig.FAST_CAPS) return
        val line = DisplayCensus.line(
            displays = censusNow(),
            allDisplaysApi = lastAllDisplaysApi,
            cover = lastCover,
            covered = overlays.covered(),
            holds = holds.describe(),
            crossCheck = crossCheckAnnotation(),
            dexDisplays = dexAnnotation(),
        )
        if (line == lastDspDiagLine) return
        lastDspDiagLine = line
        android.util.Log.d(DSP_TAG, line)
    }

    /**
     * Each window's own `getDisplayId()` cross-checked against the map key it arrived under.
     * `MISMATCH` means the two authorities disagree, and the key is the one to trust.
     *
     * `AccessibilityWindowInfo.getDisplayId()` is API 30 and the check is written explicitly rather than
     * left to an enclosing branch. Diagnostics only — never a scan input.
     */
    private fun crossCheckAnnotation(): String? {
        if (Build.VERSION.SDK_INT < MULTI_DISPLAY_SDK) return null
        return runCatching {
            val byDisplay = windowsByDisplay()
            val agree = byDisplay.all { (id, windows) ->
                windows.all { runCatching { it.displayId }.getOrDefault(id) == id }
            }
            if (agree) "ok" else "MISMATCH"
        }.getOrNull()
    }

    /**
     * Whether a display appears in Samsung's DESKTOP display category.
     *
     * ⚠️ **Annotation only, never a mechanism.** Depending on an OEM category string to decide anything
     * would be a fail-open dependency of exactly the kind the threat model forbids — one rename and the
     * blocker goes quiet. Coverage follows the window map, always.
     */
    private fun dexAnnotation(): List<Int>? = runCatching {
        displayManager.getDisplays(DEX_DISPLAY_CATEGORY).map { it.displayId }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Depth-first collect only the Instagram resource-ids the surface rule cares about
     * ([InstagramSurface.SIGNAL_IDS]), capped at [IG_NODE_BUDGET] nodes and short-circuiting once every
     * signal is seen — so a large Instagram tree can't stall the service.
     *
     * **One set per window, kept apart on purpose.** Instagram can own more than one window at a time
     * (a reel's long-press menu is an anchored `PopupWindow` of the same package), and which window a
     * signal came from is load-bearing: [InstagramSurface.targetForWindows] exempts a reel only when
     * the DM sender field shares a window with the pager. Flattening here would throw that away.
     */
    private fun collectInstagramSignals(roots: List<AccessibilityNodeInfo>): List<Set<String>> {
        // Accumulated across displays within one pass, and reset at the start of scanDisplays() — this
        // is now called once per display, so clobbering would report only the last one.
        lastIgWindowCount += roots.size
        if (roots.isEmpty()) return emptyList()
        // One budget per DISPLAY, shared by that display's windows, so N windows can't cost N × the
        // walk. A reel's popup menu is ~28 nodes against a 1_200 budget, so in the case this fix exists
        // for the second walk is nearly free. `igWindows` in the diagnostic line is what would show a
        // future Instagram spending the budget before the player is reached.
        //
        // Per display rather than per pass, deliberately: a shared budget's failure mode is starvation,
        // and a starved walk reads as "free surface" — the fail-open direction. Worst case doubles to
        // 2 × 1_200 against a measured real cost of 99 + 28 nodes for the case this exists for.
        var budget = IG_NODE_BUDGET
        var walked = 0
        val perWindow = ArrayList<Set<String>>(roots.size)
        for (root in roots) {
            val found = HashSet<String>(SIGNAL_COUNT)
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            while (stack.isNotEmpty() && budget-- > 0) {
                val node = stack.removeLast()
                walked++
                idOf(node)?.let { id ->
                    if (id in InstagramSurface.SIGNAL_IDS) found.add(id)
                }
                if (found.size == SIGNAL_COUNT) break   // seen everything the rule needs
                stack.addAll(childrenOf(node))
            }
            perWindow.add(found)
        }
        lastIgNodeCount += walked
        return perWindow
    }

    /**
     * Record which of Instagram's ids were really seen, which is what re-confirms [SignalCanary]
     * against the installed Instagram version.
     *
     * Judged on the **union** across windows, unlike every rule in [InstagramSurface]: a sighting is a
     * sighting wherever it came from, and the per-window discipline exists to stop one window's signal
     * *exempting* something in another — a concern this has no counterpart to.
     *
     * Only [InstagramSurface.WITNESSED_IDS] are confirmed, and which ids those are is decided there,
     * with the reasoning. Throttled hard and per id: the scan runs a couple of times a second while
     * Instagram is open and each confirmation is a PackageManager lookup plus a prefs write, whereas
     * one sighting an hour carries exactly the same information.
     */
    private fun noteSignals(seen: Set<String>) {
        val now = SystemClock.elapsedRealtime()
        for (id in InstagramSurface.WITNESSED_IDS) {
            if (id !in seen) continue
            val last = lastSignalConfirmElapsedMs[id]
            if (last != null && now - last < SIGNAL_CONFIRM_THROTTLE_MS) continue
            lastSignalConfirmElapsedMs[id] = now
            runCatching { witnessStore.confirm(id, clock.wallClockMs()) }
        }
    }

    /** [node]'s resource-id, or null if the node died under us mid-walk. */
    private fun idOf(node: AccessibilityNodeInfo): String? =
        runCatching { node.viewIdResourceName }.getOrNull()

    /**
     * [node]'s children, tolerating a tree that changes during the walk: a recycled node throws from
     * `childCount`/`getChild`, and losing one subtree is far better than losing the whole scan.
     */
    private fun childrenOf(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val count = runCatching { node.childCount }.getOrDefault(0)
        if (count <= 0) return emptyList()
        val children = ArrayList<AccessibilityNodeInfo>(count)
        for (i in 0 until count) {
            runCatching { node.getChild(i) }.getOrNull()?.let { children.add(it) }
        }
        return children
    }

    /**
     * What an allowlisted browser's address bar ([BrowserTargets.urlBarId]) is showing, or null when
     * the node isn't in the tree at all. Bounded DFS — the url_bar sits near the top of the tree.
     *
     * Null and [BrowserTargets.Omnibox.Editing] are deliberately different answers: "there is no
     * address bar here" and "the address bar is showing nothing committed" look identical to the old
     * nullable-String return, and only [omniboxFor] can tell them apart into allow and block.
     */
    private fun omniboxIn(root: AccessibilityNodeInfo, pkg: String): BrowserTargets.Omnibox? {
        val id = BrowserTargets.urlBarId(pkg)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var budget = URL_NODE_BUDGET
        while (stack.isNotEmpty() && budget-- > 0) {
            val node = stack.removeLast()
            if (idOf(node) == id) {
                // Only a committed URL counts - not mid-typing autocomplete, not the empty-field hint.
                // See BrowserTargets.committedUrl; both failures were seen live at Gate D.
                val url = runCatching {
                    BrowserTargets.committedUrl(
                        text = node.text?.toString(),
                        focused = node.isFocused,
                        showingHintText = node.isShowingHintText,
                    )
                }.getOrNull()
                return if (url != null) BrowserTargets.Omnibox.Url(url) else BrowserTargets.Omnibox.Editing
            }
            stack.addAll(childrenOf(node))
        }
        return null
    }

    /**
     * The raw read plus the timing that resolves a missing address bar into "not yet" or "not ever" —
     * see [AddressWatch], which owns that judgement and the state behind it. All this side contributes
     * is the tree read and the clock, neither of which can leave the service.
     */
    private fun omniboxFor(root: AccessibilityNodeInfo, pkg: String): BrowserTargets.Omnibox {
        // Clock read before the walk, so the walk's own cost never counts against the grace.
        val now = SystemClock.elapsedRealtime()
        val resolved = addressWatch.observe(omniboxIn(root, pkg), pkg, now)
        if (resolved is BrowserTargets.Omnibox.Url) noteOmniboxRead(pkg, now)
        return resolved
    }

    /**
     * Record that this browser's address bar really was readable, which is what re-confirms its id for
     * the installed version (see [OmniboxWitnessStore]). Throttled per package, because a committed URL
     * reads on every pass while a browser is on screen and each confirm is a prefs write plus a
     * PackageManager hit — but never throttled the *first* time in a service lifetime, since that is
     * the write that clears a stale canary.
     */
    private fun noteOmniboxRead(pkg: String, nowMs: Long) {
        val last = lastOmniboxConfirmElapsedMs[pkg]
        if (last != null && nowMs - last < OMNIBOX_CONFIRM_THROTTLE_MS) return
        lastOmniboxConfirmElapsedMs[pkg] = nowMs
        runCatching { omniboxWitnessStore.confirm(pkg, clock.wallClockMs()) }
    }

    /**
     * Whether an absent address bar in [pkg] is still innocent on the strength of the durable,
     * version-keyed vouch — the fix for the scrolled-tab false block (B-7, 2026-08-03).
     *
     * Cached per package for [OMNIBOX_HEALTH_TTL_MS]: this is asked on every pass while a browser is
     * visible, and answering it costs a prefs read and a PackageManager lookup. Staleness is harmless
     * in both directions — the window is a minute, and the underlying fact only changes when a browser
     * is updated.
     *
     * Fails **open** if anything goes wrong, including being asked before [onServiceConnected] has
     * wired the store. An exception here would otherwise mean "no vouch", i.e. the block this whole
     * change exists to stop, fired because a prefs read threw.
     */
    private fun omniboxIdsVouched(pkg: String): Boolean = runCatching {
        val now = SystemClock.elapsedRealtime()
        val cached = omniboxVouchCache[pkg]
        if (cached != null && now - cached.second < OMNIBOX_HEALTH_TTL_MS) return@runCatching cached.first
        val vouched = OmniboxWitnessStore.vouches(
            omniboxWitnessStore.refresh(pkg, clock.wallClockMs()),
        )
        omniboxVouchCache[pkg] = vouched to now
        vouched
    }.getOrDefault(true)

    /**
     * Installed browsers = packages handling a wildcard http VIEW+BROWSABLE intent, cached for
     * [BROWSER_CACHE_TTL_MS] so a freshly-installed browser is noticed within the minute without a
     * per-tick query. The `.invalid` probe host matches only true wildcard browsers, not apps with
     * specific http deep links. Needs the <queries> block in the manifest (Android 11+ visibility).
     */
    private fun browserPackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (browserCache.isEmpty() || now - browserCacheAtMs > BROWSER_CACHE_TTL_MS) {
            browserCache = runCatching {
                val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://appblock.invalid"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                packageManager.queryIntentActivities(probe, PackageManager.MATCH_ALL)
                    .mapNotNull { it.activityInfo?.packageName }
                    .toSet()
            }.getOrDefault(browserCache)
            browserCacheAtMs = now
        }
        return browserCache
    }

    /**
     * Apply the decision to **every display**: work out which ones must be covered and why, build each
     * one's block screen, reconcile the overlays, and kick Home when the coverage could not be achieved.
     *
     * A website/browser block takes precedence over a target block — a browser is never itself a
     * budgeted target, so the two don't really collide, but web-first is the clear rule, and it is now
     * applied **per display** because the phone can be blocked for one reason while the monitor is
     * blocked for the other.
     *
     * **Single display:** [DisplayCoverage.causes] yields at most `{0 → WEB|TARGET}` by that same
     * web-first precedence, producing the same message, and [DisplayCoverage.homeFallback] reduces to
     * `covered.isEmpty()` — i.e. exactly today's `if (!showOverlay(...)) performGlobalAction(HOME)`.
     */
    private fun applyDecision(decision: Decision, effective: Map<Int, Foreground>) {
        val cover = DisplayCoverage.causes(
            webBlocked = effective.filterValues { it.webBlock != null }.keys,
            targetsOn = effective.filterValues { it.targets.isNotEmpty() }.keys,
            engineBlocking = decision.access == Access.BLOCK && decision.target != null,
        )
        val want = cover.mapNotNull { (displayId, cause) ->
            contentFor(cause, decision, effective[displayId])?.let { displayId to it }
        }.toMap()
        // What we actually asked for, not what `causes` proposed: a display whose content could not be
        // built is never going to be covered, and recording it here would pin `sat=false` forever.
        lastCover = want.keys
        val covered = overlays.reconcile(want)
        if (DisplayCoverage.homeFallback(want.keys, covered, lastActiveDisplayId)) {
            // Overlay permission revoked or addView failed: blocking must not silently vanish.
            // Home, not the browser-steering exit - this fires every tick, and re-issuing a
            // navigation intent at 5s intervals would be its own kind of loop.
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    /**
     * What one display's block screen says, and where its Close button steers.
     *
     * The three branches are the ones [applyDecision] used to hold inline, unchanged apart from reading
     * the web half from **that display's own** read rather than from a single service-wide value.
     */
    private fun contentFor(
        cause: DisplayCoverage.Cause,
        decision: Decision,
        read: Foreground?,
    ): DisplayOverlays.Content<FactRows>? = when (cause) {
        DisplayCoverage.Cause.WEB -> {
            val webBlock = read?.webBlock
            if (webBlock == null) null else DisplayOverlays.Content(
                message = webMessage(webBlock, read.webHost),
                key = "w:$webBlock:${read.webHost ?: ""}",
                facts = render(BlockFacts.forWeb(webBlock)),
                // Only a blocked *site* steers the browser; a non-allowlisted browser is an app block.
                exitBrowserPkg =
                    if (webBlock == BrowserPolicy.WebBlock.BLOCKED_SITE) read.webPkg else null,
            )
        }
        DisplayCoverage.Cause.TARGET -> {
            val target = decision.target
            if (target == null) null else DisplayOverlays.Content(
                message = blockMessage(target, decision.reason),
                key = "t:$target:${decision.reason}",
                facts = render(
                    BlockFacts.forTarget(
                        reason = decision.reason,
                        schedule = scheduleFor(target),
                        alwaysBlocked = target in AppTargets.alwaysBlockedTargets,
                        now = clock.nowLocal(),
                        exceptionWaitMs = ActiveRules.exceptionWaitMs,
                    ),
                ),
                exitBrowserPkg = null,
            )
        }
    }

    /**
     * Leave whatever was blocked, when the user taps the overlay's exit.
     *
     * An app block means "leave this app" → Home. A blocked *site* means "leave this page", and getting
     * that right took two attempts on hardware (Gate D, 2026-07-24):
     *  - **Home is wrong.** It leaves the blocked page loaded in the tab, so the next Chrome launch
     *    restores it and blocks instantly. With the overlay taking every touch, the user can't navigate
     *    off it either — a lock-out loop, not a block. On a daily driver behind the 72-h removal gate,
     *    that's the browser bricked for three days.
     *  - **Back is also wrong**, and fails *worse*, because redirects live in the history. Measured:
     *    `old.reddit.com/r/all` → server redirects to `/r/all/`. Back returns to `/r/all`, which
     *    immediately bounces forward to `/r/all/` — blocked again, ~700ms per cycle, indefinitely. Any
     *    `http→https` or bare→`www` redirect reproduces this, so it would have been constant.
     *
     * So steer the browser to [NEUTRAL_URL] instead. It's a destination no redirect can bounce off,
     * it needs no network and no third-party page, and it leaves the tab in a state that won't
     * re-block on the next launch. Falls back to Home if the browser refuses the intent.
     */
    private fun exitOverlay(displayId: Int) {
        val pkg = overlays.contentOn(displayId)?.exitBrowserPkg
        if (pkg != null && navigateToNeutral(pkg, displayId)) return
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Point [pkg] at [NEUTRAL_URL] **on [displayId]**; false if it wouldn't take the intent (caller
     * falls back to Home).
     *
     * Display 0 passes no `ActivityOptions` at all, so its behaviour is byte-identical to before. A
     * secondary display asks for the launch to land there — without it, Close on the monitor's blocked
     * browser yanks Chrome onto the phone instead of steering the tab that is actually blocked.
     */
    private fun navigateToNeutral(pkg: String, displayId: Int): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(NEUTRAL_URL))
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (displayId == DisplayCensus.DEFAULT_DISPLAY) {
            startActivity(intent)
        } else {
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        }
        true
    }.getOrDefault(false)

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    private fun stopTicking() {
        ticking = false
        handler.removeCallbacks(tickRunnable)
    }

    /**
     * The block overlay's layout params — identical on every display, deliberately.
     *
     * ⚠️ **No `FLAG_NOT_FOCUSABLE`, and that matters more on DeX than on the phone.** Samsung's own
     * multi-display sample sets it, but that sample is a decorative overlay and this is a *barrier*: a
     * non-focusable overlay would let a DeX hardware keyboard's keystrokes reach the blocked app
     * underneath. Consequence accepted: a focusable overlay can take that display's input focus, so a
     * physical keyboard may stop typing into the phone while the monitor is blocked — an annoyance,
     * not a bypass.
     */
    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.OPAQUE,
    )

    /**
     * The window manager for a **secondary** display — `createDisplayContext(display)`, deliberately
     * **not** `createWindowContext`.
     *
     * ⚠️ TODO.md's DeX sketch named `createWindowContext(display, TYPE_APPLICATION_OVERLAY, null)`. That
     * overload is **API 31, not 30**, and the API-30 call it would be mistaken for —
     * `createWindowContext(int, Bundle)` — is documented to throw `UnsupportedOperationException` on a
     * `Context` that does not attach to a display, *"such as Application or Service"*. This **is** a
     * Service. Under the `runCatching` this path needs anyway, that throw becomes a **silent
     * no-overlay**: the blocker reports healthy and covers nothing.
     *
     * `createDisplayContext` is API 17, needs no guard at minSdk 26, and is what Samsung's own DeX
     * documentation prescribes for drawing with `TYPE_APPLICATION_OVERLAY` on the DeX display.
     *
     * Nothing is cached: DeX display ids are reported regenerated per session, and a cached context for
     * a recycled id would `addView` onto a dead display.
     */
    private fun secondaryWindowManager(displayId: Int): WindowManager? = runCatching {
        val display = displayManager.getDisplay(displayId) ?: return null
        createDisplayContext(display).getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }.getOrNull()

    /**
     * Build one display's block screen.
     *
     * Display 0 inflates from the service context — byte-identical to what it has always done. A
     * secondary display inflates from **its own** display context, so the monitor gets display-correct
     * density instead of phone metrics: at phone density the block screen renders oversized and the
     * Close button can land off-screen, which turns a block into a lock-out with no visible exit.
     *
     * But a render nicety is never traded for a missing overlay: if the display context throws, this
     * falls back to the service context and still produces a view.
     */
    // InflateParams: the inflated view has no parent by design — it is handed to WindowManager.
    @SuppressLint("InflateParams")
    private fun inflateOverlay(displayId: Int, content: DisplayOverlays.Content<FactRows>): View? {
        val context =
            if (displayId == DisplayCensus.DEFAULT_DISPLAY) this
            else runCatching { displayManager.getDisplay(displayId)?.let(::createDisplayContext) }
                .getOrNull() ?: this
        return runCatching { LayoutInflater.from(context).inflate(R.layout.overlay_block, null) }
            .recoverCatching { LayoutInflater.from(this).inflate(R.layout.overlay_block, null) }
            .getOrNull()
            ?.also { view ->
                view.findViewById<TextView>(R.id.block_message).text = content.message
                writeFactRows(view, content.facts)
                view.findViewById<Button>(R.id.block_close).setOnClickListener {
                    // Per display, never global: tearing down every overlay on one Close would hand
                    // back a repeatable free window on the other display.
                    overlays.hideOn(displayId)
                    exitOverlay(displayId)
                }
            }
    }

    /** The overlay's explanation, matched to why the engine blocked a budgeted target. */
    private fun blockMessage(target: Target, reason: BlockReason?): CharSequence = when (reason) {
        BlockReason.TAMPER -> getString(R.string.block_message_tamper)
        BlockReason.SCHEDULE -> getString(R.string.block_message_schedule, labelFor(target))
        // The only hard blocks that exist are the always-blocked bypass tools (B-10), and a generic
        // "you chose to block this" would be a lie there — the user never chose it and can't undo it
        // from the phone. Say which, and say so.
        BlockReason.HARD_BLOCK ->
            if (target in AppTargets.alwaysBlockedTargets) getString(R.string.block_message_bypass_tool)
            else getString(R.string.block_message)
        else -> getString(R.string.block_message_budget, labelFor(target))
    }

    /** The overlay text for a website / browser block (CONSTRAINTS §2). */
    private fun webMessage(webBlock: BrowserPolicy.WebBlock, host: String?): CharSequence = when (webBlock) {
        BrowserPolicy.WebBlock.BLOCKED_SITE ->
            if (host != null) getString(R.string.block_message_site_named, host)
            else getString(R.string.block_message_site)
        BrowserPolicy.WebBlock.NON_ALLOWLISTED_BROWSER -> getString(R.string.block_message_browser)
        BrowserPolicy.WebBlock.WEB_APP -> getString(R.string.block_message_web_app)
        BrowserPolicy.WebBlock.UNREADABLE_ADDRESS -> getString(R.string.block_message_unreadable)
    }

    /** The blocked target's own schedule, for the countdown to its next window. */
    private fun scheduleFor(target: Target): Schedule? =
        ruleSource.rules().firstOrNull { it.target == target }?.schedule

    /**
     * Write the four fact-row strings into one overlay's view.
     *
     * ⚠️ It only **writes**. The "has anything changed?" comparison that used to live here against a
     * single service-wide `overlayFacts` now lives **per attachment** inside [DisplayOverlays] — with
     * one shared cache the first display's write populates it, the second display's rows compare equal,
     * the write is skipped, and the second block screen renders the layout's placeholder rows forever.
     * Those placeholders quote a wrong price, twice in the loosening direction.
     */
    private fun writeFactRows(view: View, rows: FactRows) {
        view.findViewById<TextView>(R.id.block_fact_when_label).text = rows.whenLabel
        view.findViewById<TextView>(R.id.block_fact_when_value).text = rows.whenValue
        view.findViewById<TextView>(R.id.block_fact_route_label).text = rows.routeLabel
        view.findViewById<TextView>(R.id.block_fact_route_value).text = rows.routeValue
    }

    /**
     * [BlockFacts] carries no strings, so this is the one place its cases become words. Every clock
     * reading and duration is rendered by the shared formatters, not typed — the same rule the Today
     * hero and the limits table follow, so the block screen can't disagree with them about a minute.
     */
    private fun render(facts: BlockFacts.Facts): FactRows {
        val (whenLabel, whenValue) = when (val r = facts.returns) {
            is BlockFacts.Returns.AtDayReset -> R.string.block_fact_when_resets to
                getString(R.string.block_fact_at, formatHm(r.minuteOfDay), formatCoarse(r.secondsUntil))
            is BlockFacts.Returns.AtWindow -> R.string.block_fact_when_reopens to
                getString(R.string.block_fact_at, formatHm(r.minuteOfDay), formatCoarse(r.secondsUntil))
            BlockFacts.Returns.NoAllowedHours -> R.string.block_fact_when_reopens to
                getString(R.string.block_fact_no_hours)
            BlockFacts.Returns.NotOnItsOwn -> R.string.block_fact_when_lifts to
                getString(R.string.block_fact_not_on_its_own)
            BlockFacts.Returns.WhenAddressReadable -> R.string.block_fact_when_clears to
                getString(R.string.block_fact_when_readable)
        }
        val (routeLabel, routeValue) = when (val r = facts.route) {
            is BlockFacts.Route.ExceptionWait -> R.string.block_fact_route_more_time to
                getString(R.string.block_fact_wait, formatWindow((r.waitMs / 60_000L).toInt()))
            is BlockFacts.Route.ChangeWindow -> R.string.block_fact_route_unblocking to
                getString(
                    R.string.block_fact_change_window,
                    formatWindow((DurableUnlockController.waitMsFor(r.category) / 60_000L).toInt()),
                )
            BlockFacts.Route.EditTheHours -> R.string.block_fact_route_more_time to
                getString(R.string.block_fact_edit_hours)
            BlockFacts.Route.RestoreAutomaticTime -> R.string.block_fact_route_to_clear to
                getString(R.string.block_fact_auto_time)
            BlockFacts.Route.NotFromThisPhone -> R.string.block_fact_route_unblocking to
                getString(R.string.block_fact_not_from_phone)
            BlockFacts.Route.UseAnAllowedBrowser -> R.string.block_fact_route_way_through to
                getString(R.string.block_fact_allowed_browser)
            BlockFacts.Route.ShowTheAddressBar -> R.string.block_fact_route_way_through to
                getString(R.string.block_fact_show_address_bar)
        }
        return FactRows(getString(whenLabel), whenValue, getString(routeLabel), routeValue)
    }

    /** Built-in names are curated; a user-added app borrows its own launcher label. */
    private fun labelFor(target: Target): String = when (target) {
        Target.TIKTOK -> "TikTok"
        // Plain "Instagram" on the block screen: the overlay is covering the whole app, so naming a
        // scope there would be noise. The Apps tab is where the two rows need telling apart.
        Target.INSTAGRAM_APP -> "Instagram"
        Target.INSTAGRAM_REELS_EXPLORE -> "Instagram Reels & Explore"
        Target.X -> "X"
        else -> target.userPackage?.let { InstalledApps.labelFor(this, it) } ?: target.key
    }

    override fun onInterrupt() {
        // Required override; nothing to do.
    }

    override fun onDestroy() {
        // Identity-checked: if a replacement has already connected, this instance is a corpse and must
        // not retract its claim. See ServiceLiveness.
        liveness.destroyed(this)
        clockSettingsWatch?.stop()
        clockSettingsWatch = null
        runCatching { displayListener?.let { displayManager.unregisterDisplayListener(it) } }
        displayListener = null
        stopTicking()
        overlays.removeAll()
        super.onDestroy()
    }

    companion object {
        private const val TICK_MS = 5_000L
        /** `getWindowsOnAllDisplays()` is API 30 — the detection half's only guard. */
        private const val MULTI_DISPLAY_SDK = 30
        /** `AccessibilityRecord.getDisplayId()` is API 33 — the attribution guard. Below it every event
         *  files under display 0, which is exactly what every line of this service assumed before DeX. */
        private const val EVENT_DISPLAY_SDK = 33
        /** Min gap between DisplayListener-driven pumps — see [scheduleDisplayPump]. */
        private const val DISPLAY_PUMP_THROTTLE_MS = 1_000L
        /** Samsung's DeX display category. **Annotation only** — see [dexAnnotation]. */
        private const val DEX_DISPLAY_CATEGORY =
            "com.samsung.android.hardware.display.category.DESKTOP"
        /** Settings-watch text walk. Raised with flagIncludeNotImportantViews (more nodes per screen)
         *  so a deep Settings page can't exhaust the budget before the App-Block text is reached -
         *  running out early would make the self-defense fail open. */
        private const val NODE_BUDGET = 800
        /** Instagram trees are large; cap the reel-signal walk and stop early once signals are found. */
        private const val IG_NODE_BUDGET = 1_200
        private val SIGNAL_COUNT = InstagramSurface.SIGNAL_IDS.size
        /** Cap the omnibox search; the url_bar sits near the top, so this is plenty. */
        private const val URL_NODE_BUDGET = 600
        /** Min gap between content-change polls (Instagram + browsers), so per-frame events don't thrash. */
        private const val CONTENT_THROTTLE_MS = 700L
        /** How long the installed-browser set is cached before re-query (catches a new browser install). */
        private const val BROWSER_CACHE_TTL_MS = 60_000L

        /** How long the active-target set is reused before re-reading the rules — see [activeTargets]. */
        private const val ACTIVE_TARGETS_TTL_MS = 3_000L
        private const val BOUNCE_TOAST_THROTTLE_MS = 3_000L
        /** How long the overlay readings are cached before re-checking — see [readOverlayGrant]. Short,
         *  because they decide when the self-defense stands down to let the user re-grant it. */
        private const val OVERLAY_CHECK_TTL_MS = 5_000L
        /** Bucket size for the diagnostic's disagreement age — see [diagnoseSelfDefense]. */
        private const val DISAGREE_BUCKET_MS = 30_000L
        /** Min gap between Settings-side watchdog checks — see [checkHealthWhileInSettings]. Five
         *  binder reads per interval, only while Settings is up; short enough that a flipped toggle
         *  is nagged about before the user leaves the page. */
        private const val HEALTH_CHECK_THROTTLE_MS = 15_000L
        /** Min gap between Instagram signal confirmations, per id — see [noteSignals]. */
        private const val SIGNAL_CONFIRM_THROTTLE_MS = 60L * 60 * 1_000
        /** Min gap between omnibox confirmations, per browser — see [noteOmniboxRead]. Shorter than the
         *  reel throttle because a readable omnibox is the ordinary case, so it costs a write far more
         *  often, and losing one confirmation costs nothing. */
        private const val OMNIBOX_CONFIRM_THROTTLE_MS = 10L * 60 * 1_000
        /** How long an omnibox vouch verdict is cached — see [omniboxIdsVouched]. The fact behind it
         *  only changes when a browser updates, so a minute of staleness is free. */
        private const val OMNIBOX_HEALTH_TTL_MS = 60_000L
        /** `adb logcat -s AppBlockFg` — debug builds only, see [diagnose]. */
        private const val DIAG_TAG = "AppBlockFg"
        /** Settings-watch diagnostic — see [diagnoseSelfDefense]. Its own tag so `-s AppBlockWatch`
         *  isolates it from the per-frame foreground chatter on [DIAG_TAG]. */
        private const val WATCH_TAG = "AppBlockWatch"
        /** Per-display census — see [diagnoseDisplays]. Its own tag so `-s AppBlockDsp` isolates it. */
        private const val DSP_TAG = "AppBlockDsp"
        /** Where a blocked page's exit sends the browser — verified on-device that Chrome accepts it
         *  as a VIEW intent and lands on a blank page. No network, no third-party site, and nothing a
         *  redirect can bounce off. See [exitOverlay]. */
        private const val NEUTRAL_URL = "about:blank"

        /**
         * Liveness for the watchdog and the Lock tab's protection list.
         *
         * Delegated to [ServiceLiveness] rather than kept as a `Boolean` here, because Android does not
         * promise to destroy the outgoing instance before connecting its replacement, and a plain flag
         * shared by two instances is then written last by the corpse. Defensive, not a fix for anything
         * observed — see that class for why the 2026-08-04 report turned out not to be this.
         */
        private val liveness = ServiceLiveness()

        val isRunning: Boolean get() = liveness.isRunning
    }
}
