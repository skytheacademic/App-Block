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

/** The private website blocklist: add is instant, remove is gated on an open websites window. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BlocklistStoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var store: BlocklistStore

    @Before fun setUp() {
        app.getSharedPreferences("appblock_blocklist", 0).edit().clear().commit()
        store = BlocklistStore(app)
    }

    @Test fun `add normalizes and is instant`() {
        assertEquals("reddit.com", store.add("https://www.reddit.com/r/x"))
        assertEquals("youtube.com", store.add("YouTube.com"))
        assertEquals(listOf("reddit.com", "youtube.com"), store.domains())
        assertTrue(store.contains("reddit.com"))
        assertTrue(store.contains("https://reddit.com/r/x"))   // normalized before the membership check
    }

    @Test fun `add rejects a non-domain`() {
        assertNull(store.add("cat videos"))
        assertTrue(store.domains().isEmpty())
    }

    @Test fun `remove is refused without an open websites window`() {
        store.add("reddit.com")
        assertFalse(store.removeIfAuthorized("reddit.com", authorized = false))
        assertEquals(listOf("reddit.com"), store.domains())   // still blocked
    }

    @Test fun `remove succeeds only when authorized`() {
        store.add("reddit.com")
        assertTrue(store.removeIfAuthorized("reddit.com", authorized = true))
        assertTrue(store.domains().isEmpty())
    }

    @Test fun `remove of an absent domain is a no-op even when authorized`() {
        store.add("reddit.com")
        assertFalse(store.removeIfAuthorized("youtube.com", authorized = true))
        assertEquals(listOf("reddit.com"), store.domains())
    }

    @Test fun `add is idempotent`() {
        store.add("reddit.com")
        store.add("www.reddit.com")   // normalizes to the same domain
        assertEquals(listOf("reddit.com"), store.domains())
    }
}
