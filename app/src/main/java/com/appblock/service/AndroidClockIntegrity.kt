package com.appblock.service

import android.content.Context
import android.provider.Settings
import com.appblock.engine.ClockIntegrity

/**
 * Real device signals for the tamper guard. AUTO_TIME is the "Set time automatically" toggle — while
 * it's on the OS owns the wall clock and the user can't set a fake date. AUTO_TIME_ZONE is One UI's
 * *separate* "Set time zone automatically" toggle, and it owns the other half of local time: with it
 * off, picking a distant zone moves the clock ~20 hours without touching the UTC epoch at all.
 * BOOT_COUNT increments every boot since factory reset, so it detects reboots even though
 * elapsedRealtime restarts at 0. All three are world-readable Settings.Global values; no permission
 * needed.
 */
class AndroidClockIntegrity(context: Context) : ClockIntegrity {

    private val resolver = context.applicationContext.contentResolver

    override fun autoTimeEnabled(): Boolean =
        Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME, 0) == 1

    override fun autoTimeZoneEnabled(): Boolean =
        Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME_ZONE, 0) == 1

    override fun bootCount(): Int =
        Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, 0)
}
