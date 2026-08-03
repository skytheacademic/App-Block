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
import com.appblock.util.isAccessibilityServiceEnabled
import com.appblock.util.overlayPermissionIntent

class MainActivity : ComponentActivity() {

    // Re-checked in onResume so the Lock tab's protection list updates when the user comes back from
    // Settings. Both are *special* permissions: granted outside the app, revocable at any time, and
    // impossible to self-grant — so polling on resume is the only way to know.
    private val accessibilityEnabled = mutableStateOf(false)
    private val overlayGranted = mutableStateOf(false)

    // The watchdog's "blocking died" notification needs this on Android 13+; a denial just means no nag.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
                    onOpenAccessibility = { startActivity(accessibilitySettingsIntent()) },
                    onOpenOverlay = { startActivity(overlayPermissionIntent(this)) },
                    onOpenDateSettings = { startActivity(Intent(Settings.ACTION_DATE_SETTINGS)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled.value = isAccessibilityServiceEnabled(this)
        overlayGranted.value = Settings.canDrawOverlays(this)
        if (accessibilityEnabled.value && overlayGranted.value) {
            // Both special permissions seen granted once → the watchdog may nag if they ever lapse.
            Watchdog.markSetupCompleted(this)
        }
    }
}
