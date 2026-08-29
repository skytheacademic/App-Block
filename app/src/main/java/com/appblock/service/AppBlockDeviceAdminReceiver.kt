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
 * privacy → Other security settings → Device admin apps → App-Block protection → Deactivate. The
 * 2026-08-21 audit (N-2) found that page was *not* bounced — its title isn't ours and "Deactivate"
 * wasn't a control word — and that nothing watched the admin afterwards, so ten taps reopened the
 * Modes bypass silently and only a cable session could close it again. Three things changed:
 * `deactivate` joined [com.appblock.engine.SettingsWatch.settingsControls], the watchdog reports
 * `ADMIN_INACTIVE`, and the Lock tab offers the system's own activation prompt so the phone can
 * repair it alone.
 */
class AppBlockDeviceAdminReceiver : DeviceAdminReceiver() {

    /** Shown on the confirmation screen before deactivation. */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.device_admin_disable_warning)

    /**
     * Nag immediately rather than within fifteen minutes. The admin is still listed as active while
     * this callback runs (the framework removes it after the broadcast completes), so the live read
     * would say "fine" — the state is passed in instead.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        Watchdog.report(context, Watchdog.currentHealth(context, adminActive = false))
    }

    /** The mirror image: withdraw the nag the moment the admin is back, not on the next poll. */
    override fun onEnabled(context: Context, intent: Intent) {
        Watchdog.report(context, Watchdog.currentHealth(context, adminActive = true))
    }
}
