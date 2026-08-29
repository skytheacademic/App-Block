package com.appblock.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
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

/**
 * The two Secure settings that point an accessibility *shortcut* at a service: the floating
 * button / gesture (`accessibility_button_targets`) and the volume-key chord
 * (`accessibility_shortcut_target_service`). Both are `@hide` constants in the framework, so the keys
 * are written out — the *values* are what matter and those are stable, colon-separated component lists.
 */
private val shortcutTargetKeys =
    listOf("accessibility_button_targets", "accessibility_shortcut_target_service")

/**
 * True while some accessibility shortcut is pointed at this service — measured on the S25 (One UI 8,
 * 2026-08-29) and **not** something the user has to have asked for: installing the app is enough.
 *
 * The chain, in full, because the previous note here said the opposite ("no floating button appears
 * because of this line") and was wrong on hardware:
 *
 *  1. `flagRequestAccessibilityButton` (N-1) makes us eligible to be a button target, and One UI adds
 *     us to `accessibility_button_targets` **on install**, unasked.
 *  2. With gesture navigation (`navigation_mode=2`) One UI forces `accessibility_button_mode=1`, which
 *     draws the floating pill.
 *  3. Long-press pill → Edit → untick "App-Block detection" and the service is **gone from
 *     `enabled_accessibility_services`** — measured twice, four taps, no key, no computer, and nothing
 *     noticed because the watchdog runs inside the service that just died.
 *
 * Step 3 is now bounced by [com.appblock.engine.SettingsWatch]'s checkable rule, which is the actual
 * fix. This read is what makes the door *visible*: it feeds the Lock tab's protection list, so "there
 * is a pill on my screen that points at App-Block" is a stated fact rather than something discovered
 * during an audit.
 *
 * Deliberately **not** a watchdog notification. There is nothing the phone can do about it — clearing
 * the target needs `adb shell settings put secure accessibility_button_targets ""`, and the one
 * on-device route is the picker we now guard — and a permanent, unactionable nag is exactly what
 * teaches the user to ignore the notification whose whole job is to be believed (see
 * [com.appblock.service.Watchdog.report]).
 *
 * Matches on the component's own string rather than parsing, because Samsung writes these entries in
 * more than one shape (flattened short form, flattened long form) and all of them contain the class
 * name. False positives are impossible in practice: no other package is called `com.appblock`.
 */
fun isAccessibilityShortcutTarget(context: Context): Boolean {
    val service = ComponentName(context, AppBlockerAccessibilityService::class.java)
    val needle = service.className
    return shortcutTargetKeys.any { key ->
        val value = runCatching {
            Settings.Secure.getString(context.contentResolver, key)
        }.getOrNull()
        value != null && value.contains(needle, ignoreCase = true)
    }
}

/**
 * Whether "Appear on top" is actually held — asked of **two** sources, because on hardware the usual
 * one lies.
 *
 * Measured on the S25, 2026-08-29, in the act: `Settings.canDrawOverlays()` returned false for several
 * minutes while `adb shell appops get com.appblock SYSTEM_ALERT_WINDOW` read `allow` the whole time.
 * Nothing had been revoked and nothing was granted to end it; only time passed. That transient is not
 * cosmetic — it is read by three different things, and every one of them failed in the same window:
 *
 *  - the settings-watch dropped into repair mode and **silently stood the whole Settings tier down**,
 *    so the accessibility toggle, App info and the device-admin page were all free (six consecutive
 *    unbounced trials, 7/7 bounces once it passed);
 *  - the watchdog posted "App-Block is only half working" about an overlay that was never lost;
 *  - the Lock tab's own protection row said the permission was gone.
 *
 * `canDrawOverlays` goes through `AppOpsManager.noteOp`, which is the *transactional* check and can
 * come back MODE_IGNORED for reasons that have nothing to do with the grant. This asks
 * `unsafeCheckOpNoThrow` instead — the same op, the non-transactional read, and the one `appops get`
 * prints, i.e. the source that was measurably right. MODE_DEFAULT means "the op was never set, defer
 * to the permission", so that case falls through to the manifest grant.
 *
 * The direction of the OR is deliberate: it can only ever say *held* where the old read said *lost*.
 * A genuine revoke sets the op to MODE_IGNORED, so both sources agree and every consumer behaves
 * exactly as before — see [com.appblock.engine.OverlayRepairWatch] for the one place that also needs
 * the disagreement to expire, so that appops lying in the *other* direction can't wall the app out of
 * its own repair for good (C-2).
 *
 * Not used by the overlay itself: `showOverlay` asks `Settings.canDrawOverlays` and then tries
 * `addView`, because there the question is literally "will this call work", and it already falls back
 * to kick-to-home when it doesn't.
 */
fun overlayPermissionHeld(context: Context): Boolean =
    Settings.canDrawOverlays(context) || overlayAppOpAllows(context)

/**
 * The second opinion behind [overlayPermissionHeld]; false if the framework refuses to answer.
 *
 * Public because [com.appblock.engine.OverlayRepairWatch] needs the two readings *separately* — the
 * disagreement between them is the whole signal there, and an already-OR'd boolean has thrown it away.
 *
 * Both spellings of the check are deprecated in the current SDK — the pre-Q one in favour of the Q one,
 * and the Q one in favour of an attribution-source overload that does not exist below API 31. There is
 * no undeprecated way to ask this question across minSdk 26 → 36, so the suppression is the answer
 * rather than a shortcut. The behaviour asserted here is the *non-transactional* read either way.
 */
@Suppress("DEPRECATION")
fun overlayAppOpAllows(context: Context): Boolean = runCatching {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val uid = Process.myUid()
    val pkg = context.packageName
    val mode =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, pkg)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, pkg)
        }
    when (mode) {
        AppOpsManager.MODE_ALLOWED -> true
        // Never set either way → the op defers to the manifest permission, so ask that instead.
        AppOpsManager.MODE_DEFAULT ->
            context.checkSelfPermission(Manifest.permission.SYSTEM_ALERT_WINDOW) ==
                PackageManager.PERMISSION_GRANTED
        else -> false
    }
}.getOrDefault(false)

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
