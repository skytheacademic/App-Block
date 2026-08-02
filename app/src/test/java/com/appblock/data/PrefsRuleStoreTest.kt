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

    @Test fun `a version bump re-seeds from source`() {
        writeRaw(EngineCodec.encodeDurable(configured.copy(version = 99)))
        assertEquals(seed, store().load())
        // ...and that is authorized, not corruption, so nothing is quarantined.
        assertNull(store().corruptBlob())
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
}
