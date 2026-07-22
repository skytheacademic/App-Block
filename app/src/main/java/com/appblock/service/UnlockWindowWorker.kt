package com.appblock.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.appblock.MainActivity
import com.appblock.R
import com.appblock.engine.DurableUnlockManager
import com.appblock.engine.DurableUnlockState
import com.appblock.security.DurableUnlockStore
import java.util.concurrent.TimeUnit

/**
 * Fires the "your 15-minute change window is open" notification when the 2-hour durable-unlock wait
 * elapses (CONSTRAINTS.md §6, 2026-07-22). Scheduled for the wait's end when the wait is requested.
 *
 * The worker only *notifies* — the window state itself is derived from the persisted monotonic
 * deadlines, so it's correct whether or not this fires on time. If the phone rebooted during the wait
 * the state ticks to Locked (the user's chosen "restart" behavior) and no notification is posted.
 *
 * Best-effort timing: WorkManager can defer under Doze. The 15-minute window is generous, but a very
 * delayed notification could arrive after it closed — the app then shows "expired, start again". An
 * exact alarm would tighten this at the cost of another special permission; deferred for now.
 */
class UnlockWindowWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val store = DurableUnlockStore(ctx)
        val next = DurableUnlockManager.tick(
            store.load(),
            SystemClock.elapsedRealtime(),
            AndroidClockIntegrity(ctx).bootCount(),
        )
        store.save(next)
        if (next is DurableUnlockState.Open) notifyOpen(ctx)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "appblock_unlock_window"
        private const val CHANNEL_ID = "appblock_unlock"
        private const val NOTIFICATION_ID = 2

        /** Schedule the notification for [waitMs] from now, replacing any prior pending request. */
        fun schedule(context: Context, waitMs: Long) {
            val request = OneTimeWorkRequestBuilder<UnlockWindowWorker>()
                .setInitialDelay(waitMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }

        private fun notifyOpen(context: Context) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.unlock_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = context.getString(R.string.unlock_text)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.unlock_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
