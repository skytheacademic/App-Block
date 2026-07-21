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
import com.appblock.engine.BudgetCoordinator
import com.appblock.engine.Decision
import com.appblock.engine.Target

/**
 * The live blocker. Two inputs drive it:
 *  1. Foreground changes (TYPE_WINDOW_STATE_CHANGED) — tell the coordinator which app is on screen.
 *  2. A ~5s heartbeat while a budgeted app is foreground — so the block appears the *moment* the daily
 *     budget runs out, not only when the user next switches apps (accessibility events won't fire while
 *     they just keep scrolling).
 *
 * The decision itself (allow vs block) is the pure engine's; this class only maps it to the overlay.
 * Still the weak tier: force-stop / reboot / uninstall defeat it — that's what the friction layer and
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

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return
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
        val clock = AndroidEngineClock()
        coordinator = BudgetCoordinator(clock, PrefsEngineStore(this, clock), ActiveRules.rules)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == getPackageName()) return

        coordinator.onForeground(packageName)
        val decision = coordinator.tick()
        applyDecision(decision)
        if (decision.target != null) startTicking() else stopTicking()
    }

    private fun applyDecision(decision: Decision) {
        if (decision.access == Access.BLOCK && decision.target != null) {
            showOverlay(decision.target)
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

    private fun showOverlay(target: Target) {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_block, null)
        view.findViewById<TextView>(R.id.block_message).text =
            getString(R.string.block_message_budget, labelFor(target))
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
        stopTicking()
        hideOverlay()
        super.onDestroy()
    }

    private companion object {
        const val TICK_MS = 5_000L
    }
}
