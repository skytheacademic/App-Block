package com.appblock.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.appblock.MainActivity
import com.appblock.R
import com.appblock.data.OmniboxWitnessStore
import com.appblock.data.SignalWitnessStore
import com.appblock.engine.SignalCanary
import com.appblock.util.isAccessibilityServiceEnabled
import com.appblock.util.isBatteryExempt
import com.appblock.util.isDeviceAdminActive
import com.appblock.util.overlayPermissionHeld
import java.util.concurrent.TimeUnit

/**
 * Detects the "blocking silently died" states and nags loudly. Every ~15 min (WorkManager minimum) it
 * checks what blocking actually depends on — the accessibility service being enabled in Settings,
 * that service actually running, the overlay permission still being granted — plus, since the
 * 2026-08-21 audit, the two grants that keep the *service itself* alive and un-suspendable: the
 * device-admin entry (N-2) and the battery exemption (N-3). It posts a high-priority notification
 * when any of them is missing. Catches Samsung's battery killer, crashes, a revoked "Appear on top",
 * a tap on "Deactivate", and a flipped "Optimize battery usage".
 *
 * Honest limit: a manual Force Stop puts the app in the stopped state, where JobScheduler won't run
 * this worker either — closing *that* hole needs the Device Owner tier. Periodic work does survive
 * reboots, so no boot receiver is needed. And a nag is a notification: with those denied, every
 * state below is detected and reported into nothing, which is why the Lock tab carries a
 * notifications row of its own.
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!Watchdog.setupCompleted(ctx)) return Result.success()  // don't nag before first setup
        Watchdog.report(ctx, Watchdog.currentHealth(ctx))
        // installedHealth owns "which signal ids count", the same way its omnibox twin below owns
        // "which browsers count" — the Lock protection row asks the identical question.
        Watchdog.reportSignalDrift(
            ctx,
            SignalWitnessStore(ctx).installedHealth(System.currentTimeMillis()),
        )
        // installedHealth owns "which browsers count" — shared with the Lock protection row, which asks
        // the identical question and must not answer it differently.
        Watchdog.reportOmniboxDrift(
            ctx,
            OmniboxWitnessStore(ctx).installedHealth(System.currentTimeMillis()),
        )
        return Result.success()
    }
}

object Watchdog {

    private const val WORK_NAME = "appblock_watchdog"
    private const val CHANNEL_ID = "appblock_watchdog"
    private const val NOTIFICATION_ID = Notifications.HEALTH
    private const val SIGNAL_CHANNEL_ID = "appblock_signal"
    private const val SIGNAL_NOTIFICATION_ID = Notifications.SIGNAL_DRIFT
    private const val OMNIBOX_NOTIFICATION_ID = Notifications.OMNIBOX_DRIFT
    private const val RUNTIME_PREFS = "appblock_runtime"
    private const val KEY_SETUP_DONE = "setup_done"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Called once the UI has seen both special permissions granted — arms the watchdog's nagging. */
    fun markSetupCompleted(context: Context) {
        runtimePrefs(context).edit().putBoolean(KEY_SETUP_DONE, true).apply()
    }

    fun setupCompleted(context: Context): Boolean =
        runtimePrefs(context).getBoolean(KEY_SETUP_DONE, false)

    /**
     * What state blocking is actually in. Ordered worst-first, because only one notification can be
     * shown and the user should be told about the biggest hole: a service that is off or dead blocks
     * nothing at all, whereas a missing overlay still stops the app — just crudely.
     *
     * The last two are a different kind of bad: blocking still works, but the thing that stops it
     * being switched off from outside has gone. They rank below the three "broken now" states and
     * above OK, with the admin first because its bypass is the cheaper one (One UI Modes, about a
     * minute) and the exemption's needs a further Device care step.
     */
    enum class Health { OK, SERVICE_DISABLED, SERVICE_DEAD, NO_OVERLAY, ADMIN_INACTIVE, NOT_EXEMPT }

    /** Pure so the precedence above is testable without a device. */
    fun health(
        serviceEnabled: Boolean,
        serviceRunning: Boolean,
        canDrawOverlays: Boolean,
        adminActive: Boolean = true,
        batteryExempt: Boolean = true,
    ): Health =
        when {
            !serviceEnabled -> Health.SERVICE_DISABLED
            !serviceRunning -> Health.SERVICE_DEAD
            !canDrawOverlays -> Health.NO_OVERLAY
            !adminActive -> Health.ADMIN_INACTIVE
            !batteryExempt -> Health.NOT_EXEMPT
            else -> Health.OK
        }

    /**
     * [adminActive] can be supplied because the one caller that knows better than the live read is
     * the admin receiver itself: inside `onDisabled` the framework still lists the admin as active.
     */
    fun currentHealth(context: Context, adminActive: Boolean = isDeviceAdminActive(context)): Health =
        health(
            serviceEnabled = isAccessibilityServiceEnabled(context),
            serviceRunning = AppBlockerAccessibilityService.isRunning,
            // The corroborated read, not `Settings.canDrawOverlays` — that one spent several minutes
            // on 2026-08-29 claiming the overlay permission was gone while it plainly was not, and
            // this nag was one of the three things that believed it. A notification that cries wolf
            // about a permission nobody touched is how the whole channel stops being believed.
            canDrawOverlays = overlayPermissionHeld(context),
            adminActive = adminActive,
            batteryExempt = isBatteryExempt(context),
        )

    /**
     * Post — or clear — the health notification.
     *
     * Clearing matters as much as posting. The nag is `setOngoing`, so before this it could never go
     * away once shown: the user would fix the problem and keep the "App-Block is not protecting you"
     * banner forever, which teaches them to ignore the one notification whose whole job is to be
     * believed.
     */
    fun report(context: Context, health: Health) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (health == Health.OK) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return  // can't post; MainActivity re-requests the permission on next open
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.watchdog_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(
            when (health) {
                Health.SERVICE_DISABLED -> R.string.watchdog_text_disabled
                Health.SERVICE_DEAD -> R.string.watchdog_text_dead
                Health.NO_OVERLAY -> R.string.watchdog_text_no_overlay
                Health.ADMIN_INACTIVE -> R.string.watchdog_text_admin
                Health.NOT_EXEMPT -> R.string.watchdog_text_exempt
                Health.OK -> error("unreachable: OK cancels above")
            },
        )
        // A missing overlay is a degraded blocker, not an absent one, and an open door is neither —
        // saying "not protecting you" for those would be the kind of overstatement that gets the
        // whole notification distrusted. Each state names exactly what it has lost.
        val title = context.getString(
            when (health) {
                Health.NO_OVERLAY -> R.string.watchdog_title_degraded
                Health.ADMIN_INACTIVE -> R.string.watchdog_title_admin
                Health.NOT_EXEMPT -> R.string.watchdog_title_exempt
                else -> R.string.watchdog_title
            },
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * The reel-detection drift prompt ([SignalCanary]). Deliberately unlike the health nag above: its
     * own channel at default importance, and dismissable. This one can be a false positive — Instagram
     * updates and the user simply hasn't opened Reels since — so it must cost a swipe, not a
     * permanent banner. It cannot use `setOngoing` for the same reason.
     */
    fun reportSignalDrift(context: Context, health: SignalCanary.Health) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (health != SignalCanary.Health.STALE) {
            manager.cancel(SIGNAL_NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                SIGNAL_CHANNEL_ID,
                context.getString(R.string.signal_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val text = context.getString(R.string.signal_text)
        manager.notify(
            SIGNAL_NOTIFICATION_ID,
            NotificationCompat.Builder(context, SIGNAL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.signal_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * The address-bar drift prompt — the loud half of the B-7 redesign (2026-08-03).
     *
     * Website blocking used to fail *closed* on an unreadable address bar, which caught a renamed
     * `url_bar` but also blocked ordinary browsing (returning to a tab whose toolbar had scrolled away).
     * The version-keyed vouch removed the false positive; this is what stops that from turning the
     * silent failure back on. Chrome updating and its omnibox id moving now costs a week of allowing
     * plus this prompt, instead of an unexplained block or a blocklist quietly enforcing nothing.
     *
     * Shares the drift channel and the shape of [reportSignalDrift] deliberately — same class of
     * message, same "this can be a false positive, so it must cost a swipe" reasoning, distinct id.
     */
    fun reportOmniboxDrift(context: Context, health: SignalCanary.Health) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (health != SignalCanary.Health.STALE) {
            manager.cancel(OMNIBOX_NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                SIGNAL_CHANNEL_ID,
                context.getString(R.string.signal_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val text = context.getString(R.string.omnibox_text)
        manager.notify(
            OMNIBOX_NOTIFICATION_ID,
            NotificationCompat.Builder(context, SIGNAL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.omnibox_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun runtimePrefs(context: Context) =
        context.applicationContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
}
