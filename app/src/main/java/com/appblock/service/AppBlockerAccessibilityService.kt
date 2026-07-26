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
import com.appblock.BuildConfig
import com.appblock.MainActivity
import com.appblock.R
import com.appblock.data.InstalledApps
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
import com.appblock.engine.OcclusionHold
import com.appblock.engine.RuleSource
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
    /**
     * The browser to steer away from a blocked page when the user taps the overlay's exit, or null
     * when what's blocked is an app (→ Home instead). See [exitOverlay].
     */
    private var overlayExitBrowserPkg: String? = null
    private lateinit var coordinator: BudgetCoordinator
    private lateinit var ruleSource: RuleSource
    private lateinit var unlockController: DurableUnlockController
    private lateinit var blocklistStore: BlocklistStore

    /** Briefly-cached set of targets with a live rule — see [activeTargets]. */
    private var activeTargetsCache: Set<Target>? = null
    private var activeTargetsAtMs = 0L

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
        var budget = NODE_BUDGET
        for (window in visibleWindows()) {
            runCatching {
                val root = window.root ?: return@runCatching
                if (!SettingsWatch.isWatched(root.packageName?.toString())) return@runCatching
                budget = collectTexts(root, texts, budget)
            }
            if (budget <= 0) break
        }
        return texts
    }

    /** Depth-first text collection, capped at [budget] nodes so a huge tree can't stall the service. */
    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<CharSequence>, budget: Int): Int {
        if (budget <= 0) return 0
        var remaining = budget - 1
        runCatching {
            node.text?.let { out.add(it) }
            node.contentDescription?.let { out.add(it) }
        }
        for (child in childrenOf(node)) {
            if (remaining <= 0) break
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
        /** Which browser produced [webBlock] — the overlay's exit needs to steer that same browser. */
        val webPkg: String? = null,
    )

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
        var rawUrl: String? = null          // diagnostics only — what the omnibox actually read
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
                if (webBlock == null && (BrowserTargets.isAllowlisted(pkg) || pkg in browsers)) {
                    browserVisible = true
                    val url = if (BrowserTargets.isAllowlisted(pkg)) urlInBrowser(root, pkg) else null
                    browserPkg = pkg
                    rawUrl = url
                    webBlock = BrowserPolicy.decide(pkg, isBrowser = pkg in browsers, url = url, blocklist = blocklistStore.domains().toSet())
                    if (webBlock == BrowserPolicy.WebBlock.BLOCKED_SITE && url != null) webHost = DomainMatcher.host(url)
                }
            }
        }
        val signals = instagramRoot?.let { collectInstagramSignals(it) }
        val target = packageTarget ?: signals?.let { InstagramSurface.targetFor(it) }
        val read = Foreground(target, instagramRoot != null, browserVisible, webBlock, webHost, browserPkg)
        val effective = holdThroughOcclusion(read)
        diagnose(
            windowList.size, packageTarget, instagramRoot != null, signals, target, effective.target,
            browserPkg, rawUrl, webBlock, effective.webBlock,
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
        rawUrl: String?,
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
        // Browser half, for Gate D. urlRead distinguishes the three ways website blocking can fail:
        // browser not recognised at all, url_bar unreadable (null), or read fine but not matched.
        val web = if (browserPkg == null) "" else
            " browser=${browserPkg.substringAfterLast('.')} urlRead=${rawUrl ?: "NULL"} web=$webBlock"
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

    /** The URL shown in an allowlisted browser's omnibox ([BrowserTargets.urlBarId]), or null if
     *  unreadable / a blank tab. Bounded DFS — the url_bar sits near the top of the tree. */
    private fun urlInBrowser(root: AccessibilityNodeInfo, pkg: String): String? {
        val id = BrowserTargets.urlBarId(pkg)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var budget = URL_NODE_BUDGET
        while (stack.isNotEmpty() && budget-- > 0) {
            val node = stack.removeLast()
            if (idOf(node) == id) {
                // Only a committed URL counts - not mid-typing autocomplete, not the empty-field hint.
                // See BrowserTargets.committedUrl; both failures were seen live at Gate D.
                return runCatching {
                    BrowserTargets.committedUrl(
                        text = node.text?.toString(),
                        focused = node.isFocused,
                        showingHintText = node.isShowingHintText,
                    )
                }.getOrNull()
            }
            stack.addAll(childrenOf(node))
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
    private fun applyDecision(
        decision: Decision,
        webBlock: BrowserPolicy.WebBlock?,
        webHost: String?,
        webPkg: String?,
    ) {
        val message: CharSequence?
        val key: String?
        when {
            webBlock != null -> {
                message = webMessage(webBlock, webHost)
                key = "w:$webBlock:${webHost ?: ""}"
                // Only a blocked *site* steers the browser; a non-allowlisted browser is an app block.
                overlayExitBrowserPkg =
                    if (webBlock == BrowserPolicy.WebBlock.BLOCKED_SITE) webPkg else null
            }
            decision.access == Access.BLOCK && decision.target != null -> {
                message = blockMessage(decision.target, decision.reason)
                key = "t:${decision.target}:${decision.reason}"
                overlayExitBrowserPkg = null
            }
            else -> {
                message = null
                key = null
            }
        }
        if (message != null && key != null) {
            if (!showOverlay(message, key)) {
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

    /** Built-in names are curated; a user-added app borrows its own launcher label. */
    private fun labelFor(target: Target): String = when (target) {
        Target.TIKTOK -> "TikTok"
        Target.INSTAGRAM_REELS_EXPLORE -> "Instagram Reels & Explore"
        Target.X -> "X"
        else -> target.userPackage?.let { InstalledApps.labelFor(this, it) } ?: target.key
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
        /** `adb logcat -s AppBlockFg` — debug builds only, see [diagnose]. */
        private const val DIAG_TAG = "AppBlockFg"
        /** Where a blocked page's exit sends the browser — verified on-device that Chrome accepts it
         *  as a VIEW intent and lands on a blank page. No network, no third-party site, and nothing a
         *  redirect can bounce off. See [exitOverlay]. */
        private const val NEUTRAL_URL = "about:blank"

        /** Liveness flag for the watchdog: true only while the system has this service running. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
