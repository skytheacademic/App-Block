package com.appblock.security

import com.appblock.engine.KeyAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unlock-code alphabet (Gate A feedback 2026-07-22): the human-readable/stashed code must contain
 * no easily-confused glyphs — O/0, I/1/L, Q — so it reads back correctly by eye or OCR. Also checks a
 * generated code still round-trips through the real SHA-256 verifier.
 */
class LockKeysTest {

    private val ambiguous = setOf('O', '0', 'Q', 'I', '1', 'L')

    @Test fun `alphabet has 30 unique symbols and no confusable glyphs`() {
        assertEquals(30, LockKeys.ALPHABET.length)
        assertEquals(30, LockKeys.ALPHABET.toSet().size)          // no duplicates
        assertTrue(ambiguous.none { it in LockKeys.ALPHABET })
    }

    @Test fun `every generated code avoids the confusable glyphs`() {
        repeat(300) {
            val normalized = KeyAuthority.normalize(LockKeys.generate().code)
            assertTrue("'$normalized' has a char outside the alphabet",
                normalized.all { it in LockKeys.ALPHABET })
            assertTrue("'$normalized' contains an ambiguous glyph",
                normalized.none { it in ambiguous })
        }
    }

    @Test fun `a generated code verifies against its own stored hash`() {
        val key = LockKeys.generate()
        val hash = KeyAuthority.create(key.code, key.salt, Sha256Hasher())
        assertTrue(KeyAuthority.verify(key.code, hash, Sha256Hasher()))
        // Formatting doesn't matter — lowercased and de-dashed still verifies.
        assertTrue(KeyAuthority.verify(key.code.lowercase().replace("-", ""), hash, Sha256Hasher()))
        // A different code does not.
        assertFalse(KeyAuthority.verify(key.code + "A", hash, Sha256Hasher()))
    }

    @Test fun `two generated codes differ`() {
        assertFalse(LockKeys.generate().code == LockKeys.generate().code)
    }
}
