package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyAuthorityTest {

    // Deterministic stand-in for SHA-256 — enough to exercise the create/verify/normalize logic.
    private val hasher = Hasher { salt, code -> "$salt::$code" }

    @Test fun `normalize strips separators and uppercases`() {
        assertEquals("ABCDEF01", KeyAuthority.normalize("  abcd-ef01 "))
        assertEquals("ABCDEF01", KeyAuthority.normalize("ABCD EF01"))
    }

    @Test fun `the right code verifies regardless of formatting`() {
        val keyHash = KeyAuthority.create("ABCD-EF01", salt = "s", hasher = hasher)
        assertTrue(KeyAuthority.verify("ABCD-EF01", keyHash, hasher))
        assertTrue(KeyAuthority.verify("abcdef01", keyHash, hasher))   // lowercase, no dash
        assertTrue(KeyAuthority.verify("abcd ef01", keyHash, hasher))  // spaces
    }

    @Test fun `a wrong code does not verify`() {
        val keyHash = KeyAuthority.create("ABCD-EF01", salt = "s", hasher = hasher)
        assertFalse(KeyAuthority.verify("ABCD-EF02", keyHash, hasher))
        assertFalse(KeyAuthority.verify("", keyHash, hasher))
    }
}
