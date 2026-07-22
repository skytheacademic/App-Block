package com.appblock.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestWorkerBuilder
import com.appblock.engine.DurableUnlockState
import com.appblock.security.DurableUnlockStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/**
 * The "window is open" notifier, on the JVM via Robolectric: it must notify exactly when the ticked
 * state lands on Open — and stay silent when a reboot restarted the wait or the wait isn't over.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UnlockWindowWorkerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store: DurableUnlockStore get() = DurableUnlockStore(app)

    private fun notificationCount(): Int =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.size

    @Before
    fun grantNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun runWorker(): ListenableWorker.Result =
        TestWorkerBuilder.from(app, UnlockWindowWorker::class.java, Executors.newSingleThreadExecutor())
            .build()
            .doWork()

    @Test
    fun `elapsed wait flips the state open and notifies`() {
        val now = SystemClock.elapsedRealtime()
        store.save(DurableUnlockState.Pending(activeAtElapsedMs = now - 1, windowEndElapsedMs = now + 60_000, bootCount = 0))
        val result = runWorker()
        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(store.load() is DurableUnlockState.Open)
        assertEquals(1, notificationCount())
    }

    @Test
    fun `reboot during the wait relocks and stays silent`() {
        val now = SystemClock.elapsedRealtime()
        store.save(DurableUnlockState.Pending(activeAtElapsedMs = now - 1, windowEndElapsedMs = now + 60_000, bootCount = 99))
        runWorker()
        assertTrue(store.load() is DurableUnlockState.Locked)
        assertEquals(0, notificationCount())
    }

    @Test
    fun `early fire keeps waiting silently`() {
        val now = SystemClock.elapsedRealtime()
        store.save(DurableUnlockState.Pending(activeAtElapsedMs = now + 60_000, windowEndElapsedMs = now + 120_000, bootCount = 0))
        runWorker()
        assertTrue(store.load() is DurableUnlockState.Pending)
        assertEquals(0, notificationCount())
    }
}
