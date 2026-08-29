package com.appblock

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import com.appblock.service.Watchdog
import com.appblock.ui.AppRoot
import com.appblock.ui.theme.AppBlockTheme
import com.appblock.util.accessibilitySettingsIntent
import com.appblock.util.areNotificationsEnabled
import com.appblock.util.batteryExemptionIntent
import com.appblock.util.deviceAdminActivationIntent
import com.appblock.util.isAccessibilityServiceEnabled
import com.appblock.util.isAccessibilityShortcutTarget
import com.appblock.util.isBatteryExempt
import com.appblock.util.isDeviceAdminActive
import com.appblock.util.notificationSettingsIntent
import com.appblock.util.overlayPermissionHeld
import com.appblock.util.overlayPermissionIntent

class MainActivity : ComponentActivity() {

    // Re-checked in onResume so the Lock tab's protection list updates when the user comes back from
    // Settings. All are *special* grants: given outside the app, revocable at any time, and
    // impossible to self-grant — so polling on resume is the only way to know.
    private val accessibilityEnabled = mutableStateOf(false)
    private val overlayGranted = mutableStateOf(false)
    private val adminActive = mutableStateOf(false)
    private val batteryExempt = mutableStateOf(false)
    private val notificationsEnabled = mutableStateOf(false)
    // Not a grant at all — the opposite. True while Android has an accessibility shortcut pointed at
    // the service, which it does from the moment the app is installed. See isAccessibilityShortcutTarget.
    private val shortcutClaimed = mutableStateOf(false)

    // The watchdog's "blocking died" notification needs this on Android 13+; a denial just means no
    // nag — which the Lock tab now says out loud. Re-read on the result so the row flips at once.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsEnabled.value = areNotificationsEnabled(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Watchdog.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AppBlockTheme {
                AppRoot(
                    accessibilityEnabled = accessibilityEnabled.value,
                    overlayGranted = overlayGranted.value,
                    adminActive = adminActive.value,
                    batteryExempt = batteryExempt.value,
                    notificationsEnabled = notificationsEnabled.value,
                    shortcutClaimed = shortcutClaimed.value,
                    onOpenAccessibility = { startActivity(accessibilitySettingsIntent()) },
                    onOpenOverlay = { startActivity(overlayPermissionIntent(this)) },
                    onOpenDateSettings = { startActivity(Intent(Settings.ACTION_DATE_SETTINGS)) },
                    onActivateAdmin = { startActivity(deviceAdminActivationIntent(this)) },
                    onRequestExemption = { startActivity(batteryExemptionIntent(this)) },
                    onAllowNotifications = ::allowNotifications,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled.value = isAccessibilityServiceEnabled(this)
        // Corroborated, not the bare `Settings.canDrawOverlays` this used to be: that read spent
        // several minutes on 2026-08-29 saying the overlay permission was gone while it was held, and
        // this row said so on screen. See overlayPermissionHeld.
        overlayGranted.value = overlayPermissionHeld(this)
        adminActive.value = isDeviceAdminActive(this)
        batteryExempt.value = isBatteryExempt(this)
        notificationsEnabled.value = areNotificationsEnabled(this)
        shortcutClaimed.value = isAccessibilityShortcutTarget(this)
        if (accessibilityEnabled.value && overlayGranted.value) {
            // Both special permissions seen granted once → the watchdog may nag if they ever lapse.
            Watchdog.markSetupCompleted(this)
        }
    }

    /**
     * The system dialog first — it is unwatched (permission controller, not Settings) and works until
     * the user has refused twice. After that Android returns "denied" without showing anything, and
     * the only route left is the per-app notification page, so that is the fallback. On Android 12
     * and below there is no runtime permission and the page is the whole answer.
     */
    private fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(notificationSettingsIntent(this))
        }
    }
}
