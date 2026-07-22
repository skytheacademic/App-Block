package com.appblock.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.TextView
import com.appblock.ActiveRules
import com.appblock.R
import com.appblock.data.PrefsEngineStore
import com.appblock.engine.Access
import com.appblock.engine.AppTargets
import com.appblock.engine.BudgetCoordinator
import com.appblock.engine.Decision
import com.appblock.engine.Target

/**
 * The live blocker. Three inputs drive it:
 *  1. Window events (TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOWS_CHANGED) — something on screen changed.
 *  2. A window *scan* on every event and tick: a budgeted app counts as foreground if it occupies ANY
 *     visible window, not just the focused one — split-screen / Samsung App Pairs can't park TikTok
 *     in an unfocused pane for free.
 *  3. A ~5s heartbeat while a budgeted app is on screen — so the block appears the *moment* the daily
 *     budget runs out, not only when the user next switches apps.
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
    private lateinit var coordinator: BudgetCoordinator

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var lastForegroundPackage: String? = null

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
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }
        refreshForeground(event.packageName?.toString())
        val decision = coordinator.tick()
        applyDecision(decision)
        if (decision.target != null) startTicking() else stopTicking()
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
            val overlayUp = showOverlay(decision.target)
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
    private fun showOverlay(target: Target): Boolean {
        if (overlayView != null) return true
        if (!Settings.canDrawOverlays(this)) return false

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_block, null)
        val tamper = coordinator.tamperReason() != null
        view.findViewById<TextView>(R.id.block_message).text =
            if (tamper) {
                getString(R.string.block_message_tamper)
            } else {
                getString(R.string.block_message_budget, labelFor(target))
            }
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
            .onSuccess { overlayView = view }
        return overlayView != null
    }

    private fun hideOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
            overlayView = null
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

        /** Liveness flag for the watchdog: true only while the system has this service running. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
