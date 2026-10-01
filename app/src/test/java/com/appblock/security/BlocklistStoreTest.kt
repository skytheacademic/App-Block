package com.appblock.security

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test fun `add records when the domain went on the list`() {
        store.add("reddit.com")
        assertNotNull(store.sites().single().addedAtMillis)
    }

    /** A stray second add must not make an old commitment look new. */
    @Test fun `re-adding keeps the original date`() {
        store.add("reddit.com")
        val first = store.sites().single().addedAtMillis
        store.add("www.reddit.com")
        assertEquals(first, store.sites().single().addedAtMillis)
    }

    /** Domains blocked before dates were recorded read as undated, not as a decode failure. */
    @Test fun `a domain stored without a date reads as undated`() {
        app.getSharedPreferences("appblock_blocklist", 0).edit()
            .putStringSet("domains", setOf("reddit.com")).commit()
        assertNull(BlocklistStore(app).sites().single().addedAtMillis)
    }

    @Test fun `removing a domain drops its date too`() {
        store.add("reddit.com")
        store.removeIfAuthorized("reddit.com", authorized = true)
        store.add("reddit.com")
        assertNotNull(store.sites().single().addedAtMillis)
    }

    // ---- the seed file's sites (engine/SeedFile.kt): add once, never re-add, never remove ----

    @Test fun `a seed adds its domains, normalized`() {
        assertEquals(listOf("instagram.com", "weshop.ai"), store.applySeed(listOf("instagram.com", "https://www.weshop.ai/x")))
        assertEquals(listOf("instagram.com", "weshop.ai"), store.domains())
    }

    @Test fun `applying the same seed again adds nothing`() {
        store.applySeed(listOf("instagram.com"))
        assertEquals(emptyList<String>(), store.applySeed(listOf("instagram.com")))
        assertEquals(listOf("instagram.com"), store.domains())
    }

    /** Re-adding on every launch would let the file quietly overrule a 72-hour gated removal. */
    @Test fun `a seeded domain removed through a window stays removed`() {
        store.applySeed(listOf("instagram.com"))
        store.removeIfAuthorized("instagram.com", authorized = true)
        store.applySeed(listOf("instagram.com"))
        assertTrue(BlocklistStore(app).also { it.applySeed(listOf("instagram.com")) }.domains().isEmpty())
    }

    /** A seed never removes: a domain missing from the file is simply not the file's business. */
    @Test fun `a seed leaves hand-added domains alone`() {
        store.add("reddit.com")
        store.applySeed(listOf("instagram.com"))
        assertEquals(listOf("instagram.com", "reddit.com"), store.domains())
    }

    @Test fun `a seed does not re-date a domain already blocked by hand`() {
        app.getSharedPreferences("appblock_blocklist", 0).edit()
            .putStringSet("domains", setOf("instagram.com")).commit()
        BlocklistStore(app).applySeed(listOf("instagram.com"))
        assertNull(BlocklistStore(app).sites().single().addedAtMillis)
    }

    @Test fun `a later seed line is applied without bringing back a removed one`() {
        store.applySeed(listOf("instagram.com"))
        store.removeIfAuthorized("instagram.com", authorized = true)
        assertEquals(listOf("weshop.ai"), store.applySeed(listOf("instagram.com", "weshop.ai")))
        assertEquals(listOf("weshop.ai"), store.domains())
    }

    @Test fun `non-domains in a seed are skipped`() {
        assertEquals(listOf("instagram.com"), store.applySeed(listOf("cat videos", "instagram.com")))
    }
}
