package com.appblock.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
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
import com.appblock.engine.BudgetCoordinator
import com.appblock.engine.Decision
import com.appblock.engine.SettingsWatch
import com.appblock.engine.Target
import com.appblock.security.DurableUnlockController

/**
 * The live blocker. Three inputs drive it:
 *  1. Window events (TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOWS_CHANGED) — something on screen changed.
 *  2. A window *scan* on every event and tick: a budgeted app counts as foreground if it occupies ANY
 *     visible window, not just the focused one — split-screen / Samsung App Pairs can't park TikTok
 *     in an unfocused pane for free.
 *  3. A ~5s heartbeat while a budgeted app is on screen — so the block appears the *moment* the daily
 *     budget runs out, not only when the user next switches apps.
 *  4. Self-defense (settings-watch, [SettingsWatch]): content + window events from system Settings are
 *     scanned for screens about App-Block itself (the Accessibility toggle, App info, overlay page) —
 *     found one → bounce Home, unless the durable-change window is open (that's the sanctioned path).
 *
 * The decision itself (allow vs block) is the pure engine's; this class maps it to the overlay. If the
 * overlay can't draw (permission revoked mid-session), blocking falls back to kicking the user to the
 * home screen every tick — revoking "Display over other apps" must not silently disable blocking.
 *
 * Still the weak tier: force-stop / uninstall defeat it — that's what the watchdog notification and
 * the optional Device Owner tier are for (see STATUS.md).
 */
class AppBlockerAccessibilityService : AccessibilityService() {

    private val windowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private var overlayView: View? = null
    private var overlayReason: BlockReason? = null
    private lateinit var coordinator: BudgetCoordinator
    private lateinit var unlockController: DurableUnlockController

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var lastForegroundPackage: String? = null
    private var lastBounceToastElapsedMs = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return
            refreshForeground(eventPackage = null)   // re-scan: split panes change without events
            val decision = coordinator.tick()
            applyDecision(decision)
            if (decision.target != null) {
                handler.postDelayed(this, TICK_MS)
            } else {
                stopTicking()
            }
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        val windowEvent = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (!windowEvent && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        if (selfDefense(event)) return
        if (!windowEvent) return  // content-changed events feed only the settings-watch
        refreshForeground(event.packageName?.toString())
        val decision = coordinator.tick()
        applyDecision(decision)
        if (decision.target != null) startTicking() else stopTicking()
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
     * Resolve what's effectively foreground and inform the coordinator if it changed. The window scan
     * wins (it sees budgeted apps in any visible pane, and it keeps the blocked target current while
     * our own overlay is the top window); the event's package is the fallback when no budgeted window
     * is visible. An event from our own package while the overlay is up is the overlay itself — never
     * treat that as "the user left the app".
     */
    private fun refreshForeground(eventPackage: String?) {
        val resolved = visibleTargetPackage()
            ?: eventPackage?.takeIf { it != packageName || overlayView == null }
            ?: return
        if (resolved == lastForegroundPackage) return
        lastForegroundPackage = resolved
        coordinator.onForeground(resolved)
    }

    /** A package of any currently-visible window that maps to a budgeted target, else null. */
    private fun visibleTargetPackage(): String? =
        runCatching {
            windows.firstNotNullOfOrNull { window ->
                window.root?.packageName?.toString()
                    ?.takeIf { AppTargets.targetFor(it) != null }
            }
        }.getOrNull()

    private fun applyDecision(decision: Decision) {
        if (decision.access == Access.BLOCK && decision.target != null) {
            val overlayUp = showOverlay(decision.target, decision.reason)
            if (!overlayUp) {
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

    /** Returns true when the block overlay is up (already or newly added). */
    private fun showOverlay(target: Target, reason: BlockReason?): Boolean {
        overlayView?.let { view ->
            if (reason != overlayReason) {
                // The overlay outlived its original cause (e.g. budget block rolled into a schedule
                // block) — refresh the message in place instead of showing a stale reason.
                view.findViewById<TextView>(R.id.block_message).text = blockMessage(target, reason)
                overlayReason = reason
            }
            return true
        }
        if (!Settings.canDrawOverlays(this)) return false

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_block, null)
        view.findViewById<TextView>(R.id.block_message).text = blockMessage(target, reason)
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
                overlayReason = reason
            }
        return overlayView != null
    }

    /** The overlay's explanation, matched to why the engine blocked (reason plumbing, TODO P2). */
    private fun blockMessage(target: Target, reason: BlockReason?): CharSequence = when (reason) {
        BlockReason.TAMPER -> getString(R.string.block_message_tamper)
        BlockReason.SCHEDULE -> getString(R.string.block_message_schedule, labelFor(target))
        BlockReason.HARD_BLOCK -> getString(R.string.block_message)
        else -> getString(R.string.block_message_budget, labelFor(target))
    }

    private fun hideOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
            overlayView = null
            overlayReason = null
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
        private const val BOUNCE_TOAST_THROTTLE_MS = 3_000L

        /** Liveness flag for the watchdog: true only while the system has this service running. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
