package com.appblock.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblock.engine.DurableSettings
import com.appblock.engine.EngineCodec
import com.appblock.engine.Target
import com.appblock.engine.TargetSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Seeding, the authorized re-seed, and what happens to a config that no longer decodes.
 *
 * The corruption path is the one that mattered (audit finding C-3): it used to share the first-launch
 * branch, so anything unreadable was silently replaced with build defaults and the original destroyed.
 * That failed **open** — a config tightened over weeks collapsed to the three built-in targets, which
 * is a loosening with no key, no wait and no window.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PrefsRuleStoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val reddit = Target.forPackage("com.reddit.frontpage")

    private val seed = DurableSettings(
        version = 1,
        targets = mapOf(Target.TIKTOK to TargetSettings(true, 30, 30, 60)),
        exceptionWindowMinutes = 60,
    )

    /** What a real install looks like: the seed plus an app added from the picker. */
    private val configured = seed.copy(
        targets = seed.targets + (reddit to TargetSettings(true, 15, 15, 30)),
    )

    private fun prefs() = app.getSharedPreferences("appblock_rules", Context.MODE_PRIVATE)

    private fun store() = PrefsRuleStore(app, seed)

    private fun writeRaw(raw: String) {
        prefs().edit().putString("durable_settings", raw).commit()
    }

    @Before fun clear() {
        prefs().edit().clear().commit()
    }

    @Test fun `an empty store seeds and reports nothing corrupt`() {
        assertEquals(seed, store().load())
        assertNull(store().corruptBlob())
    }

    @Test fun `a saved config round-trips`() {
        store().save(configured)
        assertEquals(configured, store().load())
    }

    @Test fun `a version bump re-seeds the built-ins from source`() {
        writeRaw(EngineCodec.encodeDurable(seed.copy(version = 99, targets = mapOf(Target.TIKTOK to TargetSettings(true, 90, 90, 120)))))
        assertEquals(seed, store().load())
        // ...and that is authorized, not corruption, so nothing is quarantined.
        assertNull(store().corruptBlob())
    }

    /**
     * The seed can never contain a `pkg:` target — those only exist because the user added them on
     * the phone — so a re-seed has nothing to say about them and used to delete them anyway. Two
     * config reconstructions before this carried them across by hand.
     */
    @Test fun `a version bump keeps the apps added from the picker, with their settings`() {
        writeRaw(EngineCodec.encodeDurable(configured.copy(version = 99)))
        val loaded = store().load()
        assertEquals(seed.version, loaded.version)
        assertEquals(seed.targets[Target.TIKTOK], loaded.targets[Target.TIKTOK])
        assertEquals(TargetSettings(true, 15, 15, 30), loaded.targets[reddit])
        assertTrue("the carried config is what gets persisted", store().load() == loaded)
    }

    // ---- corruption (audit finding C-3) ----

    @Test fun `an unreadable config is reported, not swallowed`() {
        writeRaw("this is not a settings blob")
        val store = store()
        store.load()
        assertNotNull("the user must be told their rules were lost", store.corruptBlob())
    }

    /** The original text survives, so a decoding bug can be diagnosed instead of erasing its evidence. */
    @Test fun `the unreadable config itself is preserved`() {
        writeRaw("garbage|but|mine")
        val store = store()
        store.load()
        assertEquals("garbage|but|mine", store.corruptBlob())
    }

    /** Enforcement continues meanwhile — the app is never left enforcing nothing. */
    @Test fun `enforcement falls back to the seed`() {
        writeRaw("nope")
        assertEquals(seed, store().load())
    }

    /**
     * A second failure must not overwrite the first. The original is the useful one; whatever is
     * written after the fallback re-seed is just the fallback.
     */
    @Test fun `only the first unreadable config is kept`() {
        writeRaw("the original")
        store().load()
        writeRaw("a later failure")
        store().load()
        assertEquals("the original", store().corruptBlob())
    }

    /**
     * The trap this avoids: load() re-seeds on the corrupt path, and re-seeding calls save(). If save
     * cleared the flag, it would be wiped in the same breath that set it and the warning would never
     * appear.
     */
    @Test fun `re-seeding does not clear the flag it just set`() {
        writeRaw("bad")
        val store = store()
        store.load()
        store.load()
        store.load()
        assertNotNull(store.corruptBlob())
    }

    @Test fun `acknowledging clears it`() {
        writeRaw("bad")
        val store = store()
        store.load()
        store.acknowledgeCorrupt()
        assertNull(store.corruptBlob())
        // And it stays clear — the fallback config that replaced it decodes fine.
        store.load()
        assertNull(store.corruptBlob())
    }

    /** A store that has never seen corruption says so, so the warning can't fire on a healthy install. */
    @Test fun `a healthy install never reports corruption`() {
        store().save(configured)
        repeat(3) { store().load() }
        assertNull(store().corruptBlob())
    }

    @Test fun `an empty string is corruption, not a first launch`() {
        writeRaw("")
        val store = store()
        store.load()
        assertTrue("an empty value was stored, so something wrote it", store.corruptBlob() != null)
    }

    // ---- the seed file's apps (engine/SeedFile.kt): add once, never overwrite, never re-add ----

    private val youtube = Target.forPackage("com.google.android.youtube")
    private val seedApps = mapOf(
        reddit to TargetSettings(true, 20, 30, 40),
        youtube to TargetSettings(true, 10, 10, 20),
    )

    private fun seededStore(apps: Map<Target, TargetSettings> = seedApps) = PrefsRuleStore(app, seed, apps)

    @Test fun `a fresh install comes up with the seed file's apps`() {
        assertEquals(seed.targets + seedApps, seededStore().load().targets)
        assertEquals("and persists them", seed.targets + seedApps, store().load().targets)
    }

    /** The case that made the file worth having: an install that already has a config gains the app. */
    @Test fun `an existing config gains a seed app it does not have`() {
        store().save(seed)
        assertEquals(seedApps[reddit], seededStore().load().targets[reddit])
    }

    /**
     * Never overwrites. The phone may have tightened the app since; putting the file's numbers back
     * would be a loosening nobody gated.
     */
    @Test fun `an app already configured keeps its own settings`() {
        store().save(configured)                                  // reddit at 15/15/30
        assertEquals(TargetSettings(true, 15, 15, 30), seededStore().load().targets[reddit])
    }

    /** Never re-adds. An app removed through a change window must not come back on the next launch. */
    @Test fun `a seed app removed later stays removed`() {
        seededStore().load()
        store().save(seed)                                        // the gated removal
        repeat(3) { seededStore().load() }
        assertTrue(reddit !in seededStore().load().targets)
    }

    /** One line added to the file later is applied — without dragging back the one that was removed. */
    @Test fun `a new seed line is applied on its own`() {
        seededStore(mapOf(reddit to seedApps.getValue(reddit))).load()
        store().save(seed)                                        // reddit removed through a window
        val loaded = seededStore().load()                         // a build that also seeds youtube
        assertTrue(reddit !in loaded.targets)
        assertEquals(seedApps[youtube], loaded.targets[youtube])
    }

    /** The common case is every engine pass: nothing new, so nothing may be written. */
    @Test fun `an applied seed costs no write`() {
        val first = seededStore().load()
        val before = prefs().getString("durable_settings", null)
        repeat(3) { assertEquals(first, seededStore().load()) }
        assertEquals(before, prefs().getString("durable_settings", null))
    }

    @Test fun `a version bump keeps seed apps with the settings the phone has`() {
        seededStore().load()
        val tightened = TargetSettings(true, 5, 5, 10)
        store().save(store().load().let { it.copy(version = 99, targets = it.targets + (reddit to tightened)) })
        val loaded = seededStore().load()
        assertEquals(seed.version, loaded.version)
        assertEquals(tightened, loaded.targets[reddit])
    }

    /**
     * The corrupt fallback is the one place a seed app is applied regardless of the record: the config
     * it replaces can no longer be read, and the seed file is the best record of it there is.
     */
    @Test fun `the corrupt fallback includes every seed app`() {
        seededStore().load()
        writeRaw("nope")
        val store = seededStore()
        assertEquals(seed.targets + seedApps, store.load().targets)
        assertNotNull(store.corruptBlob())
    }

    @Test fun `no seed apps changes nothing`() {
        store().save(configured)
        assertEquals(configured, seededStore(emptyMap()).load())
    }
}
