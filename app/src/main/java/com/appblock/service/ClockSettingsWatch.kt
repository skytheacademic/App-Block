package com.appblock.service

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Reports the instant either of the two Settings.Global toggles the tamper guard trusts — AUTO_TIME
 * and AUTO_TIME_ZONE — changes, so the guard can latch while the user is still on the Settings
 * page rather than on the next engine pass.
 *
 * Nothing ticks while the user is in Settings (no budgeted app is foreground), so before this the
 * toggle, the date change and the re-enable could all land between two passes and the guard would
 * meet a trusted clock with no reason to look twice. The observer turns the off-toggle into the
 * event; what the guard then does with it is BudgetCoordinator.onClockSettingChanged.
 *
 * Both URIs are world-observable; no permission is needed. The callback runs on the main looper,
 * the same thread as the service's own callbacks.
 */
class ClockSettingsWatch(context: Context, private val onChange: () -> Unit) {

    private val resolver = context.applicationContext.contentResolver

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = onChange()
        override fun onChange(selfChange: Boolean, uri: Uri?) = onChange()
    }

    fun start() {
        for (setting in WATCHED) {
            resolver.registerContentObserver(Settings.Global.getUriFor(setting), false, observer)
        }
    }

    fun stop() {
        resolver.unregisterContentObserver(observer)
    }

    companion object {
        val WATCHED: List<String> = listOf(Settings.Global.AUTO_TIME, Settings.Global.AUTO_TIME_ZONE)
    }
}
