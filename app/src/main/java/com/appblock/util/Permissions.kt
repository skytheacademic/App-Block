package com.appblock.util

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.appblock.R
import com.appblock.service.AppBlockDeviceAdminReceiver
import com.appblock.service.AppBlockerAccessibilityService

/**
 * Helpers for the "special" grants blocking depends on. None can be self-granted in code — the app
 * can only read whether it holds each one and send the user to the right system screen or dialog
 * (see the permission-tiers diagram).
 *
 * The first two are the MVP's: the accessibility service and the overlay. The next three were added
 * after the 2026-08-21 audit, which found that each was a *free* toggle somewhere in Settings that
 * nothing in the app checked, so losing one failed silently:
 *
 *  - the device-admin entry (N-2) — being an active admin is what stops One UI Modes suspending the
 *    package, and the page that deactivates it is ten taps away;
 *  - the battery-optimization exemption (N-3) — the one line of Samsung sleep hardening, flippable
 *    on a list page the settings-watch correctly ignores, after which Deep sleeping apps can stop the
 *    service (and the watchdog with it);
 *  - notifications — the watchdog's only voice; denied, every nag above is posted into nothing.
 *
 * Each gets a read and a repair. The reads feed the watchdog and the Lock tab's protection list; the
 * repairs are one-tap system prompts, so closing the door costs less than opening it did.
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

private fun adminComponent(context: Context) =
    ComponentName(context, AppBlockDeviceAdminReceiver::class.java)

/** True while App-Block is an *active* device admin — the state that makes it un-suspendable. */
fun isDeviceAdminActive(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    return dpm.isAdminActive(adminComponent(context))
}

/**
 * The system's own "Activate this device admin app?" screen, pre-filled with our receiver and the
 * reason. Activation is a tightening, so it is offered freely; it used to need
 * `adb shell dpm set-active-admin` from the laptop, which meant a reinstall (or one tap on
 * "Deactivate") silently reopened the Modes bypass until the next cable session.
 */
fun deviceAdminActivationIntent(context: Context): Intent =
    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
        .putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            context.getString(R.string.device_admin_description),
        )

/** True while App-Block is on the battery-optimization whitelist (`dumpsys deviceidle whitelist`). */
fun isBatteryExempt(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * The system's "Allow App-Block to always run in the background?" dialog — one tap to restore the
 * exemption. Lint's `BatteryLife` warning is a Play-policy concern about apps that shouldn't need
 * this; a sideloaded blocker whose one hardening step *is* this exemption is the case it allows.
 */
@SuppressLint("BatteryLife")
fun batteryExemptionIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )

/** Both halves: the Android 13+ runtime permission and the app-level notification switch. */
fun areNotificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/**
 * The per-app notification settings page — the fallback when the runtime request can no longer be
 * shown (denied twice, or the app-level switch is off). ⚠️ Not yet captured on the S25: if One UI
 * titles that page with the app's name, the settings-watch will bounce it (rule 1), and it needs the
 * same stand-down treatment as the overlay page got in C-2. On the phone checklist.
 */
fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
