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
}
