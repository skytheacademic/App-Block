package com.appblock.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.appblock.service.AppBlockerAccessibilityService

/**
 * Helpers for the two "special" permissions the MVP needs. Neither can be self-granted in code —
 * the app can only send the user to the right Settings screen (see the permission-tiers diagram).
 */

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected =
        ComponentName(context, AppBlockerAccessibilityService::class.java).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

fun accessibilitySettingsIntent(): Intent =
    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

fun overlayPermissionIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
