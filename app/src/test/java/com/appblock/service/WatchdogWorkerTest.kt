package com.appblock.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.TestWorkerBuilder
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/** The dead-blocker nag: silent before setup, loud once armed and the service isn't running. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WatchdogWorkerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun notificationCount(): Int =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.size

    @Before
    fun grantNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun runWorker() =
        TestWorkerBuilder.from(app, WatchdogWorker::class.java, Executors.newSingleThreadExecutor())
            .build()
            .doWork()

    @Test
    fun `stays quiet before first setup completes`() {
        runWorker()
        assertEquals(0, notificationCount())
    }

    @Test
    fun `nags once armed and the service is not running`() {
        Watchdog.markSetupCompleted(app)
        runWorker()   // in the test JVM the accessibility service is neither enabled nor running
        assertEquals(1, notificationCount())
    }

    /**
     * The nag is ongoing, so it can't be swiped away — which means it has to be withdrawn in code.
     * Before this it never was: you fixed the problem and kept "App-Block is not protecting you" on
     * screen indefinitely, which is how a health notification trains you to stop reading it.
     */
    @Test
    fun `withdraws the nag once everything is healthy again`() {
        Watchdog.report(app, Watchdog.Health.SERVICE_DEAD)
        assertEquals(1, notificationCount())
        Watchdog.report(app, Watchdog.Health.OK)
        assertEquals(0, notificationCount())
    }

    @Test
    fun `nags about a missing overlay permission`() {
        Watchdog.report(app, Watchdog.Health.NO_OVERLAY)
        assertEquals(1, notificationCount())
    }
}

/**
 * Which hole gets reported when several are open at once. Pure, so it needs no device: the ordering is
 * the whole content of the decision.
 */
class WatchdogHealthTest {

    private fun health(enabled: Boolean = true, running: Boolean = true, overlay: Boolean = true) =
        Watchdog.health(serviceEnabled = enabled, serviceRunning = running, canDrawOverlays = overlay)

    @Test fun `all three present is healthy`() {
        assertEquals(Watchdog.Health.OK, health())
    }

    /**
     * The gap this closes (audit finding B-9): the watchdog checked the service but never the overlay
     * permission, so losing it degraded every block to a silent home-screen kick every few seconds
     * with nothing anywhere saying why.
     */
    @Test fun `a missing overlay permission is not healthy`() {
        assertEquals(Watchdog.Health.NO_OVERLAY, health(overlay = false))
    }

    @Test fun `a dead service outranks a missing overlay`() {
        assertEquals(Watchdog.Health.SERVICE_DEAD, health(running = false, overlay = false))
    }

    /** Worst first: a service switched off in Settings blocks nothing at all. */
    @Test fun `a disabled service outranks everything`() {
        assertEquals(Watchdog.Health.SERVICE_DISABLED, health(enabled = false, running = false, overlay = false))
        assertEquals(Watchdog.Health.SERVICE_DISABLED, health(enabled = false))
    }
}
