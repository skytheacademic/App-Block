package com.appblock.security

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
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
}
