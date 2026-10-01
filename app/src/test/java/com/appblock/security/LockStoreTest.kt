package com.appblock.security

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The one-shot rule at the store, not just the screen: "no in-app re-key" was enforced only by the
 * Create button disappearing once a key existed, and a store that overwrote the verifier on request
 * was one stray code path from a free re-key.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LockStoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var store: LockStore

    @Before fun setUp() {
        app.getSharedPreferences("appblock_lock", 0).edit().clear().commit()
        store = LockStore(app)
    }

    @Test fun `the first key is stored and verifies`() {
        val key = LockKeys.generate()
        assertFalse(store.isConfigured())
        assertTrue(store.setKey(key))
        assertTrue(store.isConfigured())
        assertTrue(store.verify(key.code))
    }

    @Test fun `a second key is refused and the first still stands`() {
        val first = LockKeys.generate()
        val second = LockKeys.generate()
        assertTrue(store.setKey(first))
        assertFalse(store.setKey(second))
        assertTrue("the original key still verifies", store.verify(first.code))
        assertFalse("the refused key never did", store.verify(second.code))
    }

    @Test fun `no key verifies nothing`() {
        assertFalse(store.verify("ABCD-EFGH-JKMN-PRST-UVWX-YZ23"))
    }

    // ---- when the key was made, for the Lock tab's "Key set 12 Jul" (0.10.2) ----

    @Test fun `setting a key records when`() {
        store.setKey(LockKeys.generate(), nowMs = 1_790_000_000_000)
        assertEquals(1_790_000_000_000, store.keySetAtMs())
    }

    @Test fun `no key has no date`() {
        assertNull(store.keySetAtMs())
    }

    /** A refused second key must not move the date either — the row would then describe a key that isn't there. */
    @Test fun `a refused second key keeps the first date`() {
        store.setKey(LockKeys.generate(), nowMs = 1_000)
        store.setKey(LockKeys.generate(), nowMs = 2_000)
        assertEquals(1_000L, store.keySetAtMs())
    }

    /** A key made before 0.10.2 has a hash and no date; the row falls back to plain "Key set". */
    @Test fun `a key from before the date was recorded has none`() {
        store.setKey(LockKeys.generate())
        app.getSharedPreferences("appblock_lock", 0).edit().remove("durable_key_set_at").commit()
        assertTrue(LockStore(app).isConfigured())
        assertNull(LockStore(app).keySetAtMs())
    }
}
