package com.appblock.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
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
import com.appblock.engine.DomainMatcher
import com.appblock.engine.InstagramSurface
import com.appblock.engine.OcclusionHold
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

/**
 * The live blocker. Inputs that drive it:
 *  1. Window events (TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOWS_CHANGED) — something on screen changed.
 *  2. A window *scan* on every event and tick: a budgeted app counts as foreground if it occupies ANY
 *     visible window, not just the focused one — split-screen / Samsung App Pairs can't park TikTok
 *     in an unfocused pane for free.
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

    private var overlayView: View? = null
    private var overlayKey: String? = null
    /**
     * The browser to steer away from a blocked page when the user taps the overlay's exit, or null
     * when what's blocked is an app (→ Home instead). See [exitOverlay].
     */
    private var overlayExitBrowserPkg: String? = null
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
    /** Nodes walked by the last Instagram signal scan — diagnostic only (tells a pruned tree apart
     *  from a tree that simply doesn't have the reel pager in it). */
    private var lastIgNodeCount = 0
    /** The last real read taken before our overlay started occluding it — see [holdThroughOcclusion]. */
    private val occlusionHold = OcclusionHold<Foreground>()
    /**
     * The package of the last window-state change, which is how the hold learns the user has moved on:
     * events keep arriving with a package name even while `getWindows()` is pruned to nothing. See
     * [noteForegroundPackage] for what deliberately doesn't count.
     */
    private var lastWindowPackage: String? = null
    /** Cached `canDrawOverlays` — see [canDrawOverlay]. */
    private var overlayGranted = true
    private var overlayGrantedAtMs = 0L
    /** Last Settings-side watchdog check — see [checkHealthWhileInSettings]. */
    private var lastHealthCheckElapsedMs = 0L
    /** When the reel pager was last recorded as seen — null means not yet this service lifetime, which
     *  must confirm immediately rather than wait out the throttle. See [noteReelSignal]. */
    private var lastSignalConfirmElapsedMs: Long? = null
    /** Per-browser address-bar watch: turns a missing url_bar into "not yet" or "not ever". Fed the
     *  read by [omniboxFor], the on-screen browser set by [resolveForeground], and the durable
     *  version-keyed vouch by [omniboxIdsVouched]. */
    private val addressWatch = AddressWatch(idsVouched = ::omniboxIdsVouched)
    /** Cached omnibox-vouch verdicts, package → (vouched, elapsed-at). See [omniboxIdsVouched]. */
    private val omniboxVouchCache = HashMap<String, Pair<Boolean, Long>>()
    /** Last time each browser's readable omnibox was written through to the witness store. */
    private val lastOmniboxConfirmElapsedMs = HashMap<String, Long>()

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
     *    the once-a-second oscillation [holdThroughOcclusion] exists to stop.
     *  - so only our real UI qualifies, identified by class: opening App-Block *is* leaving the blocked
     *    app, and the block screen shouldn't sit on top of the app's own settings.
     */
    private fun noteForegroundPackage(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName && event.className?.toString() != MainActivity::class.java.name) return
        lastWindowPackage = pkg
        // Reaching here is the proof that the moved-on release condition has a live channel to work
        // with, which is what retires the hold's timeout backstop (C-1). It is fed only by the events
        // that survive the filter above, so the overlay cannot prove the point by its own churn.
        occlusionHold.noteForegroundEvent()
    }

    /**
     * One decision cycle: resolve what's foreground (surface-aware for Instagram, URL-aware for
     * browsers), tick the engine, apply the overlay, and keep ticking while anything blockable — a live
     * target, a visible Instagram window, or a visible browser — is on screen.
     */
    private fun pump() {
        val fg = refreshForeground()
        val decision = coordinator.tick()
        applyDecision(decision, fg.webBlock, fg.webHost, fg.webPkg)
        // Seed the occlusion hold with the read that *caused* this overlay. Without it there is a
        // one-scan gap: the overlay goes up, the next scan already comes back pruned, and nothing has
        // been held yet - so the block drops for ~200ms and hands back a free frame of the reel.
        if (overlayView != null && (fg.target != null || fg.webBlock != null)) {
            occlusionHold.seed(fg, lastWindowPackage, SystemClock.elapsedRealtime())
        }
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
     * its words — see [visibleWatchedScreen].
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
        val bounce = SettingsWatch.shouldBounce(
            packageName = pkg,
            screen = visibleWatchedScreen(),
            selfLabel = getString(R.string.app_name),
            setupIncomplete = !Watchdog.setupCompleted(this),
            windowOpen = unlockController.isOpen(),
            repairMode = !canDrawOverlay(),
        )
        if (!bounce) return false
        performGlobalAction(GLOBAL_ACTION_HOME)
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
     * Whether the block overlay can still be drawn, cached for [OVERLAY_CHECK_TTL_MS] because this is
     * consulted per Settings event and is a binder call. Defaults to granted so a check that hasn't
     * happened yet leaves the self-defense armed rather than open.
     */
    private fun canDrawOverlay(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - overlayGrantedAtMs > OVERLAY_CHECK_TTL_MS) {
            overlayGranted = Settings.canDrawOverlays(this)
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
     */
    private fun visibleWatchedScreen(): SettingsWatch.Screen {
        val titles = ArrayList<CharSequence>(6)
        val texts = ArrayList<CharSequence>(64)
        var budget = NODE_BUDGET
        for (window in visibleWindows()) {
            runCatching {
                val root = window.root ?: return@runCatching
                if (!SettingsWatch.isWatched(root.packageName?.toString())) return@runCatching
                val firstIndex = texts.size
                val heading = ArrayList<CharSequence>(1)
                budget = collectTexts(root, texts, heading, budget)
                val found = titles.size
                window.title?.takeIf { it.isNotBlank() }?.let { titles.add(it) }
                titles.addAll(heading)
                if (titles.size == found) texts.getOrNull(firstIndex)?.let { titles.add(it) }
            }
            if (budget <= 0) break
        }
        return SettingsWatch.Screen(titles, texts)
    }

    /**
     * Depth-first text collection, capped at [budget] nodes so a huge tree can't stall the service.
     * The first node marked as a heading also lands in [heading] — that is a screen title on every
     * layout that bothers to declare one, and it survives scrolling, which the position of the text
     * itself does not.
     */
    private fun collectTexts(
        node: AccessibilityNodeInfo,
        out: MutableList<CharSequence>,
        heading: MutableList<CharSequence>,
        budget: Int,
    ): Int {
        if (budget <= 0) return 0
        var remaining = budget - 1
        runCatching {
            node.text?.let {
                out.add(it)
                if (heading.isEmpty() && isHeading(node)) heading.add(it)
            }
            node.contentDescription?.let { out.add(it) }
        }
        for (child in childrenOf(node)) {
            if (remaining <= 0) break
            remaining = collectTexts(child, out, heading, remaining)
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
     * Resolve what's foreground and inform the coordinator if the *target* changed. Tracking by target —
     * not package — is what lets Instagram flip between budgeted (reel player) and free (feed/DMs) within
     * the one package. Returns the full [Foreground] so [pump] also gets the website decision.
     */
    private fun refreshForeground(): Foreground {
        val fg = resolveForeground()
        surfaceAppVisible = fg.instagramVisible
        browserVisible = fg.browserVisible
        if (fg.targets != lastForegroundTargets) {
            lastForegroundTargets = fg.targets
            coordinator.onForegroundTargets(fg.targets)
        }
        return fg
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
    }

    /**
     * Scan the visible windows once, resolving three things: the budgeted [Target] (a whole-app TikTok/X
     * in any pane wins, else Instagram's reel surface), whether Instagram is visible, and the website
     * decision (an allowlisted browser on a blocked URL, or any non-allowlisted browser at all).
     */
    private fun resolveForeground(): Foreground {
        var packageTarget: Target? = null
        var instagramRoot: AccessibilityNodeInfo? = null
        var browserVisible = false
        var webBlock: BrowserPolicy.WebBlock? = null
        var webHost: String? = null
        var browserPkg: String? = null      // whose omnibox we read — also the overlay's exit target
        var omnibox: BrowserTargets.Omnibox? = null   // diagnostics only — what the address bar said
        // Every allowlisted browser *on screen*, which is wider than the one we read: only the first
        // browser window gets an omnibox read, but a second one in the other split-screen pane is still
        // present and its watch must survive the pass. See AddressWatch.retain.
        val visibleBrowsers = HashSet<String>()
        val browsers = browserPackages()
        val windowList = visibleWindows()
        for (window in windowList) {
            // Isolate each window. A live tree churns under the walk (a playing reel especially), so
            // reading it can throw on a recycled / not-sealed node. One bad window must never collapse
            // the whole resolution to "nothing foreground" - silently doing that stops accrual AND the
            // 5s tick, which is exactly how a blocker fails open.
            runCatching {
                val root = window.root ?: return@runCatching
                val pkg = root.packageName?.toString() ?: return@runCatching
                if (packageTarget == null) AppTargets.targetFor(pkg, activeTargets())?.let { packageTarget = it }
                if (instagramRoot == null && pkg == InstagramSurface.PACKAGE) instagramRoot = root
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
        // Retire the watch of any browser that isn't on screen any more, so its next appearance is read
        // as the fresh tree it is: full grace, and no earlier successful read still vouching for it.
        // Done after the loop, so a browser we just read is never evicted by its own pass.
        addressWatch.retain(visibleBrowsers)
        val signals = instagramRoot?.let { collectInstagramSignals(it) }
        if (signals != null && InstagramSurface.REEL_PAGER in signals) noteReelSignal()
        // Both, not either. `packageTarget` used to win outright, which made the surface target
        // unreachable whenever a package also matched — and for Instagram a package now always
        // matches (its whole-app closing hours), so the reels budget would have gone dark.
        val surfaceTarget = signals?.let { InstagramSurface.targetFor(it) }
        val targets = listOfNotNull(packageTarget, surfaceTarget).distinct()
        val target = targets.firstOrNull()
        val read = Foreground(targets, instagramRoot != null, browserVisible, webBlock, webHost, browserPkg)
        val effective = holdThroughOcclusion(read)
        diagnose(
            windowList.size, packageTarget, instagramRoot != null, signals, target, effective.target,
            browserPkg, omnibox, webBlock, effective.webBlock,
        )
        return effective
    }

    /**
     * Keep blocking while our own overlay is what's hiding the evidence.
     *
     * Measured on the S25 (Gate B, 2026-07-23): the instant the block overlay goes up, the framework
     * prunes the occluded app's accessibility tree to its bare root — Instagram drops from 692 nodes to
     * 1–2. The reel signal vanishes, the target resolves to null, the overlay comes down, the tree comes
     * back, and the whole thing oscillates about once a second. A browser's `url_bar` reads empty the
     * same way, so website blocking (Batch 3) would flicker identically. Package-matched targets
     * (TikTok, X) are immune — `root.packageName` survives the pruning, which is why this never showed
     * up before Instagram.
     *
     * How far the pruning goes depends on how opaque the overlay is: at 95% alpha Instagram stayed
     * enumerated with its tree cut to 1–2 nodes; fully opaque, it is dropped from `getWindows()`
     * altogether (`windows=1` — only our own overlay left). So "is the blocked app still on screen?"
     * is not a usable release condition; we blinded ourselves by blocking.
     *
     * The hold used to be unconditional while the overlay was up, on the reasoning that behind a
     * full-screen overlay the user can't navigate anyway. That reasoning was wrong: Home still works
     * behind an overlay, so the block screen would follow the user to the launcher and stay there,
     * leaving the overlay's own Close button as the only way out. [OcclusionHold] adds the two release
     * conditions — the event stream reporting a different foreground package, and a one-minute backstop
     * for when those events don't come — while keeping the failure direction right: a blocker must
     * never talk itself out of blocking.
     *
     * The backstop is conditional on that stream having gone quiet from the start (C-1, Gate F Phase 3):
     * firing it unconditionally dropped the overlay for ~0.2 s every 60 s, leaving the blocked page
     * unobstructed and tappable each time. [noteForegroundPackage] is what stands it down.
     *
     * The engine's other exits are untouched either way, since the hold freezes only *foreground
     * resolution* and never the allow/block decision: Close ([hideOverlay] + [exitOverlay]), a granted
     * exception, the 4am reset.
     */
    private fun holdThroughOcclusion(read: Foreground): Foreground {
        if (overlayView == null) {
            occlusionHold.release()
            return read
        }
        val now = SystemClock.elapsedRealtime()
        if (read.target != null || read.webBlock != null) {   // a real read got through; refresh the hold
            occlusionHold.arm(read, lastWindowPackage, now)
            return read
        }
        return occlusionHold.sustain(lastWindowPackage, now) ?: read
    }

    /** The visible windows, or an empty list if the system refuses them (never throw out of a scan). */
    private fun visibleWindows(): List<android.view.accessibility.AccessibilityWindowInfo> =
        runCatching { windows }.getOrNull().orEmpty()

    /**
     * Why a decision came out the way it did, on QA builds only, logged once per distinct state so
     * logcat stays readable. This is the instrument Gate B needed: `adb logcat -s AppBlockFg` answers
     * "was Instagram enumerated / did the reel signal appear / what target resolved" in one line.
     *
     * Gated on FAST_CAPS as well as DEBUG on purpose: `debugFast` is built non-debuggable (so `run-as`
     * can't reach its prefs), which makes BuildConfig.DEBUG false — and debugFast is precisely the
     * build the phone gates are run on. The signed release logs nothing.
     */
    private fun diagnose(
        windowCount: Int,
        packageTarget: Target?,
        instagramVisible: Boolean,
        signals: Set<String>?,
        target: Target?,
        effectiveTarget: Target?,
        browserPkg: String?,
        omnibox: BrowserTargets.Omnibox?,
        webBlock: BrowserPolicy.WebBlock?,
        effectiveWeb: BrowserPolicy.WebBlock?,
    ) {
        if (!BuildConfig.DEBUG && !BuildConfig.FAST_CAPS) return
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
        val line = "windows=$windowCount pkgTarget=$packageTarget ig=$instagramVisible " +
            "igNodes=$lastIgNodeCount igSignals=${signals?.map { it.substringAfterLast('/') }} " +
            "target=$target overlay=${overlayView != null}$held$web"
        if (line == lastDiagLine) return
        lastDiagLine = line
        android.util.Log.d(DIAG_TAG, line)
    }

    /**
     * Depth-first collect only the Instagram resource-ids the surface rule cares about
     * ([InstagramSurface.SIGNAL_IDS]), capped at [IG_NODE_BUDGET] nodes and short-circuiting once every
     * signal is seen — so a large Instagram tree can't stall the service.
     */
    private fun collectInstagramSignals(root: AccessibilityNodeInfo): Set<String> {
        val found = HashSet<String>(SIGNAL_COUNT)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var budget = IG_NODE_BUDGET
        var walked = 0
        while (stack.isNotEmpty() && budget-- > 0) {
            val node = stack.removeLast()
            walked++
            idOf(node)?.let { id ->
                if (id in InstagramSurface.SIGNAL_IDS) found.add(id)
            }
            if (found.size == SIGNAL_COUNT) break   // seen everything the rule needs
            stack.addAll(childrenOf(node))
        }
        lastIgNodeCount = walked
        return found
    }

    /**
     * Record that the reel pager was really seen, which is what re-confirms [SignalCanary] against the
     * installed Instagram version. Throttled hard: the scan runs a couple of times a second while
     * Instagram is open, and each confirmation is a PackageManager lookup plus a prefs write, whereas
     * one sighting an hour carries exactly the same information.
     */
    private fun noteReelSignal() {
        val now = SystemClock.elapsedRealtime()
        val last = lastSignalConfirmElapsedMs
        if (last != null && now - last < SIGNAL_CONFIRM_THROTTLE_MS) return
        lastSignalConfirmElapsedMs = now
        runCatching { witnessStore.confirm(clock.wallClockMs()) }
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
     * Apply the combined decision. A website/browser block ([webBlock]) takes precedence — a browser is
     * never itself a budgeted target, so the two don't really collide, but web-first is the clear rule.
     */
    private fun applyDecision(
        decision: Decision,
        webBlock: BrowserPolicy.WebBlock?,
        webHost: String?,
        webPkg: String?,
    ) {
        val message: CharSequence?
        val key: String?
        val facts: BlockFacts.Facts?
        when {
            webBlock != null -> {
                message = webMessage(webBlock, webHost)
                key = "w:$webBlock:${webHost ?: ""}"
                facts = BlockFacts.forWeb(webBlock)
                // Only a blocked *site* steers the browser; a non-allowlisted browser is an app block.
                overlayExitBrowserPkg =
                    if (webBlock == BrowserPolicy.WebBlock.BLOCKED_SITE) webPkg else null
            }
            decision.access == Access.BLOCK && decision.target != null -> {
                message = blockMessage(decision.target, decision.reason)
                key = "t:${decision.target}:${decision.reason}"
                facts = BlockFacts.forTarget(
                    reason = decision.reason,
                    schedule = scheduleFor(decision.target),
                    alwaysBlocked = decision.target in AppTargets.alwaysBlockedTargets,
                    now = clock.nowLocal(),
                    exceptionWaitMs = ActiveRules.exceptionWaitMs,
                )
                overlayExitBrowserPkg = null
            }
            else -> {
                message = null
                key = null
                facts = null
            }
        }
        if (message != null && key != null && facts != null) {
            if (!showOverlay(message, key, facts)) {
                // Overlay permission revoked or addView failed: blocking must not silently vanish.
                // Home, not the browser-steering exit - this fires every tick, and re-issuing a
                // navigation intent at 5s intervals would be its own kind of loop.
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        } else {
            hideOverlay()
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
    private fun exitOverlay() {
        val pkg = overlayExitBrowserPkg
        if (pkg != null && navigateToNeutral(pkg)) return
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** Point [pkg] at [NEUTRAL_URL]; false if it wouldn't take the intent (caller falls back to Home). */
    private fun navigateToNeutral(pkg: String): Boolean = runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(NEUTRAL_URL))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
     * Show (or refresh in place) the block overlay with [message] and [facts]; [key] identifies what's
     * currently shown, so a changed cause updates the text instead of leaving a stale one. Returns true
     * when the overlay is up (already or newly added).
     *
     * The message is gated on [key] and the fact rows deliberately are not: their cause hasn't changed
     * but their *numbers* have, and "in 8 h 48 m" left alone for eight hours is worse than no countdown
     * at all. [applyFacts] compares before it writes, so the 5-second tick costs nothing until a
     * rendered minute actually rolls over.
     */
    private fun showOverlay(message: CharSequence, key: String, facts: BlockFacts.Facts): Boolean {
        overlayView?.let { view ->
            if (key != overlayKey) {
                view.findViewById<TextView>(R.id.block_message).text = message
                overlayKey = key
            }
            applyFacts(view, facts)
            return true
        }
        if (!Settings.canDrawOverlays(this)) return false

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_block, null)
        view.findViewById<TextView>(R.id.block_message).text = message
        applyFacts(view, facts)
        view.findViewById<Button>(R.id.block_close).setOnClickListener {
            hideOverlay()
            exitOverlay()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )

        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                overlayView = view
                overlayKey = key
            }
        return overlayView != null
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

    /** The four strings the two fact rows are showing — cached so an unchanged tick writes nothing. */
    private data class FactRows(
        val whenLabel: String,
        val whenValue: String,
        val routeLabel: String,
        val routeValue: String,
    )

    private var overlayFacts: FactRows? = null

    private fun applyFacts(view: View, facts: BlockFacts.Facts) {
        val rows = render(facts)
        if (rows == overlayFacts) return
        view.findViewById<TextView>(R.id.block_fact_when_label).text = rows.whenLabel
        view.findViewById<TextView>(R.id.block_fact_when_value).text = rows.whenValue
        view.findViewById<TextView>(R.id.block_fact_route_label).text = rows.routeLabel
        view.findViewById<TextView>(R.id.block_fact_route_value).text = rows.routeValue
        overlayFacts = rows
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

    private fun hideOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
            overlayView = null
            overlayKey = null
            // The cache belongs to the view that just went away; keeping it would leave the next
            // overlay showing the inflated placeholder rows whenever the facts happened to match.
            overlayFacts = null
        }
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
        stopTicking()
        hideOverlay()
        super.onDestroy()
    }

    companion object {
        private const val TICK_MS = 5_000L
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
        /** How long `canDrawOverlays` is cached before re-checking — see [canDrawOverlay]. Short,
         *  because it decides when the self-defense stands down to let the user re-grant it. */
        private const val OVERLAY_CHECK_TTL_MS = 5_000L
        /** Min gap between Settings-side watchdog checks — see [checkHealthWhileInSettings]. Five
         *  binder reads per interval, only while Settings is up; short enough that a flipped toggle
         *  is nagged about before the user leaves the page. */
        private const val HEALTH_CHECK_THROTTLE_MS = 15_000L
        /** Min gap between reel-pager confirmations — see [noteReelSignal]. */
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
