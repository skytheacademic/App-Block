package com.appblock.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.TestWorkerBuilder
import com.appblock.util.isBatteryExempt
import com.appblock.util.isDeviceAdminActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * The two "door is open" states from the 2026-08-21 audit. Neither breaks blocking today; each
     * removes the thing that stops it being broken from outside, and each used to be invisible.
     */
    @Test
    fun `nags about a deactivated device admin and a removed battery exemption`() {
        Watchdog.report(app, Watchdog.Health.ADMIN_INACTIVE)
        assertEquals(1, notificationCount())
        Watchdog.report(app, Watchdog.Health.OK)
        assertEquals(0, notificationCount())
        Watchdog.report(app, Watchdog.Health.NOT_EXEMPT)
        assertEquals(1, notificationCount())
    }

    /**
     * The reads behind those states, against the framework's own answer. `isAdminActive` is what
     * PackageManager's suspend refusal keys on; `isIgnoringBatteryOptimizations` is the deviceidle
     * whitelist line. Both read false on a fresh install, which is the honest default — a build
     * that has never been activated or exempted *is* open on both counts.
     */
    @Test
    fun `reads the admin and exemption state the framework holds`() {
        assertFalse(isDeviceAdminActive(app))
        assertFalse(isBatteryExempt(app))

        shadowOf(app.getSystemService(DevicePolicyManager::class.java))
            .setActiveAdmin(ComponentName(app, AppBlockDeviceAdminReceiver::class.java))
        shadowOf(app.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(app.packageName, true)

        assertTrue(isDeviceAdminActive(app))
        assertTrue(isBatteryExempt(app))
    }

    /**
     * The admin receiver's `onDisabled` runs while the framework still lists the admin as active, so
     * the live read would say "fine" at exactly the wrong moment. The override is what lets it nag
     * anyway.
     */
    @Test
    fun `the admin receiver can report the deactivation the framework has not recorded yet`() {
        shadowOf(app.getSystemService(DevicePolicyManager::class.java))
            .setActiveAdmin(ComponentName(app, AppBlockDeviceAdminReceiver::class.java))
        // In the test JVM the service is neither enabled nor running, so those dominate; the point
        // is only that the override is honoured over the live read.
        assertEquals(Watchdog.Health.SERVICE_DISABLED, Watchdog.currentHealth(app, adminActive = false))
        assertEquals(Watchdog.Health.SERVICE_DISABLED, Watchdog.currentHealth(app))
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

    // ---- the two open-door states (audit 2026-08-21, N-2 / N-3) ----

    private fun doors(admin: Boolean = true, exempt: Boolean = true) =
        Watchdog.health(
            serviceEnabled = true, serviceRunning = true, canDrawOverlays = true,
            adminActive = admin, batteryExempt = exempt,
        )

    @Test fun `a deactivated admin is not healthy`() {
        assertEquals(Watchdog.Health.ADMIN_INACTIVE, doors(admin = false))
    }

    @Test fun `a removed battery exemption is not healthy`() {
        assertEquals(Watchdog.Health.NOT_EXEMPT, doors(exempt = false))
    }

    /** The admin's bypass is the cheaper one (One UI Modes, about a minute), so it is named first. */
    @Test fun `the admin outranks the exemption`() {
        assertEquals(Watchdog.Health.ADMIN_INACTIVE, doors(admin = false, exempt = false))
    }

    /** Blocking that is broken now outranks a door that is merely open. */
    @Test fun `a broken blocker outranks an open door`() {
        assertEquals(
            Watchdog.Health.NO_OVERLAY,
            Watchdog.health(
                serviceEnabled = true, serviceRunning = true, canDrawOverlays = false,
                adminActive = false, batteryExempt = false,
            ),
        )
    }

    /** The defaults read as held, so the three older call sites keep their meaning unchanged. */
    @Test fun `callers that do not know about the doors are not failed by them`() {
        assertEquals(Watchdog.Health.OK, health())
    }
}
