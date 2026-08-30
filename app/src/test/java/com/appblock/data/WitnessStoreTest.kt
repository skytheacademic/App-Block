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
    private val pager = InstagramSurface.REEL_PAGER
    private val exploreBar = InstagramSurface.EXPLORE_ACTION_BAR

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

    @Test fun `a reel witness round-trips per signal id`() {
        val s = SignalWitnessStore(app)
        val w = SignalCanary.Witness(confirmedVersion = 300L, confirmedAtMs = 1L, installedVersion = 301L, installedSeenAtMs = 2L)
        s.save(pager, w)
        assertEquals(w, s.load(pager))
        assertEquals(SignalCanary.Witness(), s.load(exploreBar))     // another signal is untouched
    }

    @Test fun `instagram absent reads as no version`() {
        assertNull(SignalWitnessStore(app).installedVersion())
        assertEquals(Health.NO_APP, SignalWitnessStore(app).installedHealth(0L))
    }

    /** No ceiling here on purpose: a user who stopped watching reels must never be prompted for it. */
    @Test fun `a reel confirmation never expires on its own`() {
        install(InstagramSurface.PACKAGE, 300L)
        val s = SignalWitnessStore(app)
        s.confirm(pager, 0L)
        assertEquals(Health.CONFIRMED, s.refresh(pager, 400 * day))
    }

    /**
     * 🔴 The defect this store was re-shaped for. Confirming the pager used to confirm *the canary*,
     * so once the Explore-preview rule started resting on `explore_action_bar` too, Instagram could
     * rename that id, the rule could stop firing, and the drift notification would stay silent because
     * the pager was still being seen every day.
     */
    @Test fun `the surface verdict is only as healthy as its worst id`() {
        install(InstagramSurface.PACKAGE, 300L)
        val s = SignalWitnessStore(app)
        s.confirm(pager, 0L)
        // A newly witnessed id starts its OWN grace clock the first time it is observed, so adding
        // one can never post a drift notification on the day it ships — it is pending, not stale.
        assertEquals(Health.PENDING, s.installedHealth(0L))
        assertEquals(Health.CONFIRMED, s.refresh(pager, 15 * day))   // the pager alone looks fine…
        assertEquals(Health.STALE, s.installedHealth(15 * day))      // …the rule as a whole does not
    }

    @Test fun `confirming one signal does not confirm another`() {
        install(InstagramSurface.PACKAGE, 300L)
        val s = SignalWitnessStore(app)
        s.confirm(pager, 0L)
        assertEquals(Health.PENDING, s.refresh(exploreBar, day))
        s.confirm(exploreBar, day)
        assertEquals(Health.CONFIRMED, s.installedHealth(400 * day))
    }

    /**
     * The upgrade path. A phone that earned a pager confirmation before the store became per-id must
     * keep it — otherwise every existing install comes out of the update unconfirmed and posts a drift
     * notification about an id that never moved, on release day.
     */
    @Test fun `a confirmation earned before the store became per-id still counts`() {
        install(InstagramSurface.PACKAGE, 300L)
        writeLegacyWitness(confirmedVersion = 300L, confirmedAtMs = 0L, installedVersion = 300L, installedSeenAtMs = 0L)
        assertEquals(Health.CONFIRMED, SignalWitnessStore(app).refresh(pager, 400 * day))
    }

    @Test fun `a namespaced record wins over the legacy one`() {
        install(InstagramSurface.PACKAGE, 301L)
        writeLegacyWitness(confirmedVersion = 300L, confirmedAtMs = 0L, installedVersion = 300L, installedSeenAtMs = 0L)
        val s = SignalWitnessStore(app)
        s.confirm(pager, 10 * day)                                   // seen on the NEW version
        assertEquals(301L, s.load(pager).confirmedVersion)
    }

    /** The fallback is the pager's own history, so no other signal may inherit it. */
    @Test fun `the legacy fallback belongs to the pager alone`() {
        writeLegacyWitness(confirmedVersion = 300L, confirmedAtMs = 0L, installedVersion = 300L, installedSeenAtMs = 0L)
        assertEquals(SignalCanary.Witness(), SignalWitnessStore(app).load(exploreBar))
    }

    private fun writeLegacyWitness(
        confirmedVersion: Long,
        confirmedAtMs: Long,
        installedVersion: Long,
        installedSeenAtMs: Long,
    ) {
        app.getSharedPreferences("appblock_runtime", Context.MODE_PRIVATE).edit()
            .putLong("signal_confirmed_version", confirmedVersion)
            .putLong("signal_confirmed_at", confirmedAtMs)
            .putLong("signal_installed_version", installedVersion)
            .putLong("signal_installed_seen_at", installedSeenAtMs)
            .commit()
    }
}
