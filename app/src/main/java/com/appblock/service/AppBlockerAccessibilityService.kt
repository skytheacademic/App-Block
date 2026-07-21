package com.appblock.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import com.appblock.R
import com.appblock.data.BlockedAppsRepository

/**
 * MVP blocking engine.
 *
 * Listens for foreground-app changes (TYPE_WINDOW_STATE_CHANGED). When a blocked package comes to
 * the front, it draws a full-screen overlay (SYSTEM_ALERT_WINDOW) over it; when the foreground
 * moves to a non-blocked app, it removes the overlay.
 *
 * This is deliberately the *weak* MVP: force-stop / reboot / uninstall all defeat it. That is what
 * the friction layer (cooldowns, delayed settings, external passphrase) and — if chosen later —
 * the Device Owner tier are for. See STATUS.md's bypass playbook.
 */
class AppBlockerAccessibilityService : AccessibilityService() {

    private val windowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private var overlayView: View? = null
    private lateinit var repository: BlockedAppsRepository

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = BlockedAppsRepository(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == getPackageName()) return

        if (repository.isBlocked(packageName)) {
            showOverlay()
        } else {
            hideOverlay()
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_block, null)
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

    override fun onInterrupt() {
        // Required override; nothing to do.
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }
}
