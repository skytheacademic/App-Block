package com.appblock.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.appblock.R

/**
 * Exists to make App-Block un-suspendable, not to exercise any device-admin power.
 *
 * One UI's Modes ("Restrict app usage") disarms the blocker by calling
 * `PackageManager.setPackagesSuspendedAsUser(suspended = true)` — captured on hardware 2026-08-06:
 *
 * ```
 * Routine@Core: SuspendAppHelper: suspendPackages = [...]
 * PackageManager: setPackagesSuspendedAsUser, suspended: true,
 *     callingUid: 10052, callingPackage: com.samsung.android.app.routines
 * ```
 *
 * That is the ordinary AOSP path, so the ordinary AOSP refusals apply — verified live on this build
 * by asking to suspend the launcher and the platform package and being told no, while a control app
 * suspended fine. `canSuspendPackageForUser()` refuses any package that is an active device admin,
 * so simply *being* one — with zero policies, see `res/xml/device_admin.xml` — should put App-Block
 * out of Modes' reach.
 *
 * Deactivating the admin remains a bypass, but a different and slower one: Settings → Security and
 * privacy → Other security settings → Device admin apps, which is a screen the watchdog can bounce.
 * Wiring [onDisabled] into the tamper latch is deliberately left for that follow-up rather than
 * bolted on here — this class ships to answer one question.
 */
class AppBlockDeviceAdminReceiver : DeviceAdminReceiver() {

    /** Shown on the confirmation screen before deactivation. The only user-facing surface here. */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.device_admin_disable_warning)
}
