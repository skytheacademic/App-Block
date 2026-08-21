package com.appblock.data

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblock.engine.InstagramSurface
import com.appblock.engine.SignalCanary
import com.appblock.engine.SignalCanary.Health
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The two witness stores round-trip what the canaries know, and the omnibox one applies the
 * confirmation ceiling the reel one deliberately doesn't.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WitnessStoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val day = 24L * 60 * 60 * 1_000
    private val chrome = "com.android.chrome"
    private val brave = "com.brave.browser"

    @Before fun clear() {
        app.getSharedPreferences("appblock_runtime", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun install(pkg: String, version: Long) {
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply {
                packageName = pkg
                longVersionCode = version
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
            },
        )
    }

    // ---- omnibox ----

    @Test fun `an omnibox witness round-trips per browser`() {
        val s = OmniboxWitnessStore(app)
        val w = SignalCanary.Witness(confirmedVersion = 700L, confirmedAtMs = 10L, installedVersion = 701L, installedSeenAtMs = 20L)
        s.save(chrome, w)
        assertEquals(w, s.load(chrome))
        assertEquals(SignalCanary.Witness(), s.load(brave))          // another browser is untouched
    }

    @Test fun `a browser that is not installed has no version`() {
        assertNull(OmniboxWitnessStore(app).installedVersion(chrome))
    }

    @Test fun `a confirmation expires after a month without a readable address bar`() {
        install(chrome, 700L)
        val s = OmniboxWitnessStore(app)
        val t0 = 1_000_000L
        assertEquals(Health.PENDING, s.refresh(chrome, t0))         // first seen, nothing read yet
        s.confirm(chrome, t0)
        assertEquals(Health.CONFIRMED, s.refresh(chrome, t0 + 20 * day))
        assertEquals(Health.STALE, s.refresh(chrome, t0 + 31 * day))
        s.confirm(chrome, t0 + 31 * day)                              // one page load with the bar visible
        assertEquals(Health.CONFIRMED, s.refresh(chrome, t0 + 31 * day + 1))
    }

    @Test fun `a browser update starts the grace, and a read on the new version confirms it`() {
        install(chrome, 700L)
        val s = OmniboxWitnessStore(app)
        s.confirm(chrome, 0L)
        install(chrome, 701L)
        assertEquals(Health.PENDING, s.refresh(chrome, day))
        assertEquals(Health.STALE, s.refresh(chrome, 8 * day))
        s.confirm(chrome, 8 * day)
        assertEquals(Health.CONFIRMED, s.refresh(chrome, 8 * day))
    }

    // ---- reels ----

    @Test fun `a reel witness round-trips`() {
        val s = SignalWitnessStore(app)
        val w = SignalCanary.Witness(confirmedVersion = 300L, confirmedAtMs = 1L, installedVersion = 301L, installedSeenAtMs = 2L)
        s.save(w)
        assertEquals(w, s.load())
    }

    @Test fun `instagram absent reads as no version`() {
        assertNull(SignalWitnessStore(app).installedVersion())
        assertEquals(Health.NO_APP, SignalWitnessStore(app).refresh(0L))
    }

    /** No ceiling here on purpose: a user who stopped watching reels must never be prompted for it. */
    @Test fun `a reel confirmation never expires on its own`() {
        install(InstagramSurface.PACKAGE, 300L)
        val s = SignalWitnessStore(app)
        s.confirm(0L)
        assertEquals(Health.CONFIRMED, s.refresh(400 * day))
    }
}
