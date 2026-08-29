package com.appblock.service

import android.app.Application
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The observer that turns "automatic time was switched off" into an event rather than a later discovery. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ClockSettingsWatchTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun notify(setting: String) {
        app.contentResolver.notifyChange(Settings.Global.getUriFor(setting), null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `a change to either toggle reaches the callback`() {
        var calls = 0
        val watch = ClockSettingsWatch(app) { calls++ }
        watch.start()

        notify(Settings.Global.AUTO_TIME)
        assertTrue("AUTO_TIME change not delivered", calls >= 1)
        val afterTime = calls

        notify(Settings.Global.AUTO_TIME_ZONE)
        assertTrue("AUTO_TIME_ZONE change not delivered", calls > afterTime)
        watch.stop()
    }

    @Test fun `after stop nothing reaches the callback`() {
        var calls = 0
        val watch = ClockSettingsWatch(app) { calls++ }
        watch.start()
        watch.stop()
        notify(Settings.Global.AUTO_TIME)
        notify(Settings.Global.AUTO_TIME_ZONE)
        assertEquals(0, calls)
    }

    @Test fun `the watched settings are exactly the two the tamper guard trusts`() {
        assertEquals(listOf(Settings.Global.AUTO_TIME, Settings.Global.AUTO_TIME_ZONE), ClockSettingsWatch.WATCHED)
    }
}
