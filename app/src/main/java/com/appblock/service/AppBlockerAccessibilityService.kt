package com.appblock.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
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
import com.appblock.R
import com.appblock.data.PrefsEngineStore
import com.appblock.engine.Access
import com.appblock.engine.AppTargets
import com.appblock.engine.BlockReason
import com.appblock.engine.BrowserPolicy
import com.appblock.engine.BrowserTargets
import com.appblock.engine.BudgetCoordinator
import com.appblock.engine.Decision
import com.appblock.engine.DomainMatcher
import com.appblock.engine.InstagramSurface
import com.appblock.engine.SettingsWatch
import com.appblock.engine.Target
import com.appblock.security.BlocklistStore
import com.appblock.security.DurableUnlockController

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
    private lateinit var coordinator: BudgetCoordinator
    private lateinit var unlockController: DurableUnlockController
    private lateinit var blocklistStore: BlocklistStore

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var lastForegroundTarget: Target? = null
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

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return
            pump()                       // re-scan: split panes / in-app surface changes fire no event
            if (ticking) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        val clock = AndroidEngineClock()
        coordinator = BudgetCoordinator(
            clock,
            PrefsEngineStore(this, clock),
            AndroidClockIntegrity(this),
            ActiveRules.ruleSource(this),
            exceptionWaitMs = ActiveRules.exceptionWaitMs,
        )
        unlockController = DurableUnlockController(this)
        blocklistStore = BlocklistStore(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        val windowEvent = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (!windowEvent && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
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
     * One decision cycle: resolve what's foreground (surface-aware for Instagram, URL-aware for
     * browsers), tick the engine, apply the overlay, and keep ticking while anything blockable — a live
     * target, a visible Instagram window, or a visible browser — is on screen.
     */
    private fun pump() {
        val fg = refreshForeground()
        val decision = coordinator.tick()
        applyDecision(decision, fg.webBlock, fg.webHost)
        if (decision.target != null || surfaceAppVisible || browserVisible) startTicking() else stopTicking()
    }

    /**
     * The settings-watch (CONSTRAINTS lever A). A Settings screen about App-Block itself — the
     * Accessibility toggle, its "Turn off?" dialog, App info with Force stop / Uninstall, the
     * overlay-permission page — gets bounced to Home, so disabling the blocker isn't a
     * zero-friction escape. Stands down while setup is still incomplete (first-time permission
     * granting must not be bounced) and while the durable-change window is open, which makes
     * "switch the service off" a gated loosening like any other (CONSTRAINTS §6).
     * Returns true when it bounced (the event needs no further handling).
     */
    private fun selfDefense(event: AccessibilityEvent): Boolean {
        val pkg = event.packageName?.toString()
        if (!SettingsWatch.isWatched(pkg)) return false
        // Any-category isOpen on purpose: a websites window sat through the *longer* (72-h) wait,
        // so letting it reach Settings is never a shortcut past the 2-h apps gate.
        val standDown = !Watchdog.setupCompleted(this) || unlockController.isOpen()
        if (standDown) return false
        val bounce = SettingsWatch.shouldBounce(
            packageName = pkg,
            visibleTexts = visibleWatchedTexts(),
            selfLabel = getString(R.string.app_name),
            standDown = false,
        )
        if (!bounce) return false
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = SystemClock.elapsedRealtime()
        if (now - lastBounceToastElapsedMs > BOUNCE_TOAST_THROTTLE_MS) {
            lastBounceToastElapsedMs = now
            Toast.makeText(this, getString(R.string.self_defense_bounce), Toast.LENGTH_SHORT).show()
        }
        return true
    }

    /** All visible text (text + contentDescription) of windows owned by watched settings packages. */
    private fun visibleWatchedTexts(): List<CharSequence> {
        val texts = ArrayList<CharSequence>(64)
        runCatching {
            var budget = NODE_BUDGET
            for (window in windows) {
                val root = window.root ?: continue
                if (!SettingsWatch.isWatched(root.packageName?.toString())) continue
                budget = collectTexts(root, texts, budget)
                if (budget <= 0) break
            }
        }
        return texts
    }

    /** Depth-first text collection, capped at [budget] nodes so a huge tree can't stall the service. */
    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<CharSequence>, budget: Int): Int {
        if (budget <= 0) return 0
        var remaining = budget - 1
        node.text?.let { out.add(it) }
        node.contentDescription?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            if (remaining <= 0) break
            val child = node.getChild(i) ?: continue
            remaining = collectTexts(child, out, remaining)
        }
        return remaining
    }

    /**
     * Resolve what's foreground and inform the coordinator if the *target* changed. Tracking by target —
     * not package — is what lets Instagram flip between budgeted (reel player) and free (feed/DMs) within
     * the one package. Returns the full [Foreground] so [pump] also gets the website decision.
     */
    private fun refreshForeground(): Foreground {
        val fg = resolveForeground()
        surfaceAppVisible = fg.instagramVisible
        browserVisible = fg.browserVisible
        if (fg.target != lastForegroundTarget) {
            lastForegroundTarget = fg.target
            coordinator.onForegroundTarget(fg.target)
        }
        return fg
    }

    private data class Foreground(
        val target: Target?,
        val instagramVisible: Boolean,
        val browserVisible: Boolean = false,
        val webBlock: BrowserPolicy.WebBlock? = null,
        val webHost: String? = null,
    )

    /**
     * Scan the visible windows once, resolving three things: the budgeted [Target] (a whole-app TikTok/X
     * in any pane wins, else Instagram's reel surface), whether Instagram is visible, and the website
     * decision (an allowlisted browser on a blocked URL, or any non-allowlisted browser at all).
     */
    private fun resolveForeground(): Foreground = runCatching {
        var packageTarget: Target? = null
        var instagramRoot: AccessibilityNodeInfo? = null
        var browserVisible = false
        var webBlock: BrowserPolicy.WebBlock? = null
        var webHost: String? = null
        val browsers = browserPackages()
        for (window in windows) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (packageTarget == null) AppTargets.targetFor(pkg)?.let { packageTarget = it }
            if (instagramRoot == null && pkg == InstagramSurface.PACKAGE) instagramRoot = root
            if (webBlock == null && (BrowserTargets.isAllowlisted(pkg) || pkg in browsers)) {
                browserVisible = true
                val url = if (BrowserTargets.isAllowlisted(pkg)) urlInBrowser(root, pkg) else null
                webBlock = BrowserPolicy.decide(pkg, isBrowser = pkg in browsers, url = url, blocklist = blocklistStore.domains().toSet())
                if (webBlock == BrowserPolicy.WebBlock.BLOCKED_SITE && url != null) webHost = DomainMatcher.host(url)
            }
        }
        val target = packageTarget
            ?: instagramRoot?.let { InstagramSurface.targetFor(collectInstagramSignals(it)) }
        Foreground(target, instagramRoot != null, browserVisible, webBlock, webHost)
    }.getOrDefault(Foreground(null, instagramVisible = false))

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
        while (stack.isNotEmpty() && budget-- > 0) {
            val node = stack.removeLast()
            node.viewIdResourceName?.let { id ->
                if (id in InstagramSurface.SIGNAL_IDS) found.add(id)
            }
            if (found.size == SIGNAL_COUNT) break   // seen everything the rule needs
            for (i in 0 until node.childCount) {
                stack.addLast(node.getChild(i) ?: continue)
            }
        }
        return found
    }

    /** The URL shown in an allowlisted browser's omnibox ([BrowserTargets.urlBarId]), or null if
     *  unreadable / a blank tab. Bounded DFS — the url_bar sits near the top of the tree. */
    private fun urlInBrowser(root: AccessibilityNodeInfo, pkg: String): String? {
        val id = BrowserTargets.urlBarId(pkg)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var budget = URL_NODE_BUDGET
        while (stack.isNotEmpty() && budget-- > 0) {
            val node = stack.removeLast()
            if (node.viewIdResourceName == id) {
                return node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            }
            for (i in 0 until node.childCount) {
                stack.addLast(node.getChild(i) ?: continue)
            }
        }
        return null
    }

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
    private fun applyDecision(decision: Decision, webBlock: BrowserPolicy.WebBlock?, webHost: String?) {
        val message: CharSequence?
        val key: String?
        when {
            webBlock != null -> {
                message = webMessage(webBlock, webHost)
                key = "w:$webBlock:${webHost ?: ""}"
            }
            decision.access == Access.BLOCK && decision.target != null -> {
                message = blockMessage(decision.target, decision.reason)
                key = "t:${decision.target}:${decision.reason}"
            }
            else -> {
                message = null
                key = null
            }
        }
        if (message != null && key != null) {
            if (!showOverlay(message, key)) {
                // Overlay permission revoked or addView failed: blocking must not silently vanish.
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        } else {
            hideOverlay()
        }
    }

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
     * Show (or refresh in place) the block overlay with [message]; [key] identifies what's currently
     * shown, so a changed cause updates the text instead of leaving a stale one. Returns true when the
     * overlay is up (already or newly added).
     */
    private fun showOverlay(message: CharSequence, key: String): Boolean {
        overlayView?.let { view ->
            if (key != overlayKey) {
                view.findViewById<TextView>(R.id.block_message).text = message
                overlayKey = key
            }
            return true
        }
        if (!Settings.canDrawOverlays(this)) return false

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_block, null)
        view.findViewById<TextView>(R.id.block_message).text = message
        view.findViewById<Button>(R.id.block_close).setOnClickListener {
            hideOverlay()
            performGlobalAction(GLOBAL_ACTION_HOME)
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
        BlockReason.HARD_BLOCK -> getString(R.string.block_message)
        else -> getString(R.string.block_message_budget, labelFor(target))
    }

    /** The overlay text for a website / browser block (CONSTRAINTS §2). */
    private fun webMessage(webBlock: BrowserPolicy.WebBlock, host: String?): CharSequence = when (webBlock) {
        BrowserPolicy.WebBlock.BLOCKED_SITE ->
            if (host != null) getString(R.string.block_message_site_named, host)
            else getString(R.string.block_message_site)
        BrowserPolicy.WebBlock.NON_ALLOWLISTED_BROWSER -> getString(R.string.block_message_browser)
    }

    private fun hideOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
            overlayView = null
            overlayKey = null
        }
    }

    private fun labelFor(target: Target): String = when (target) {
        Target.TIKTOK -> "TikTok"
        Target.INSTAGRAM_REELS_EXPLORE -> "Instagram Reels & Explore"
        Target.X -> "X"
    }

    override fun onInterrupt() {
        // Required override; nothing to do.
    }

    override fun onDestroy() {
        isRunning = false
        stopTicking()
        hideOverlay()
        super.onDestroy()
    }

    companion object {
        private const val TICK_MS = 5_000L
        private const val NODE_BUDGET = 400
        /** Instagram trees are large; cap the reel-signal walk and stop early once signals are found. */
        private const val IG_NODE_BUDGET = 1_200
        private val SIGNAL_COUNT = InstagramSurface.SIGNAL_IDS.size
        /** Cap the omnibox search; the url_bar sits near the top, so this is plenty. */
        private const val URL_NODE_BUDGET = 600
        /** Min gap between content-change polls (Instagram + browsers), so per-frame events don't thrash. */
        private const val CONTENT_THROTTLE_MS = 700L
        /** How long the installed-browser set is cached before re-query (catches a new browser install). */
        private const val BROWSER_CACHE_TTL_MS = 60_000L
        private const val BOUNCE_TOAST_THROTTLE_MS = 3_000L

        /** Liveness flag for the watchdog: true only while the system has this service running. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
