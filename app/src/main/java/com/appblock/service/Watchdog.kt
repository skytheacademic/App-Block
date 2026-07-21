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
import com.appblock.util.isAccessibilityServiceEnabled
import java.util.concurrent.TimeUnit

/**
 * Detects the "blocking silently died" states and nags loudly. Every ~15 min (WorkManager minimum) it
 * checks that the accessibility service is (a) still enabled in Settings and (b) actually running,
 * and posts a high-priority notification if not. Catches Samsung's battery killer and crashes.
 *
 * Honest limit: a manual Force Stop puts the app in the stopped state, where JobScheduler won't run
 * this worker either — closing *that* hole needs the Device Owner tier. Periodic work does survive
 * reboots, so no boot receiver is needed.
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!Watchdog.setupCompleted(ctx)) return Result.success()  // don't nag before first setup
        val enabled = isAccessibilityServiceEnabled(ctx)
        val alive = AppBlockerAccessibilityService.isRunning
        if (!enabled || !alive) {
            Watchdog.notifyDead(ctx, serviceEnabled = enabled)
        }
        return Result.success()
    }
}

object Watchdog {

    private const val WORK_NAME = "appblock_watchdog"
    private const val CHANNEL_ID = "appblock_watchdog"
    private const val NOTIFICATION_ID = 1
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

    fun notifyDead(context: Context, serviceEnabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return  // can't post; MainActivity re-requests the permission on next open
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
            if (serviceEnabled) R.string.watchdog_text_dead else R.string.watchdog_text_disabled,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.watchdog_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun runtimePrefs(context: Context) =
        context.applicationContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
}
