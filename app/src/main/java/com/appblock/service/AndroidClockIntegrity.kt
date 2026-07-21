package com.appblock.service

import android.content.Context
import android.provider.Settings
import com.appblock.engine.ClockIntegrity

/**
 * Real device signals for the tamper guard. AUTO_TIME is the "Set time automatically" toggle — while
 * it's on the OS owns the wall clock and the user can't set a fake date. BOOT_COUNT increments every
 * boot since factory reset, so it detects reboots even though elapsedRealtime restarts at 0. Both are
 * world-readable Settings.Global values; no permission needed.
 */
class AndroidClockIntegrity(context: Context) : ClockIntegrity {

    private val resolver = context.applicationContext.contentResolver

    override fun autoTimeEnabled(): Boolean =
        Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME, 0) == 1

    override fun bootCount(): Int =
        Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, 0)
}
