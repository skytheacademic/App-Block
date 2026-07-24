package com.appblock.security

import android.content.Context
import com.appblock.engine.EngineCodec
import com.appblock.engine.Hasher
import com.appblock.engine.KeyAuthority
import com.appblock.engine.KeyHash
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Android side of the durable-change unlock (CONSTRAINTS.md §6). The engine ([KeyAuthority]) owns the
 * pure verify/normalize logic; this file supplies the real crypto (SHA-256 + SecureRandom), the
 * persisted [KeyHash], and the in-memory unlock session.
 */

/** SHA-256 of `salt:code`, hex. One-way — the stored hash never reveals the code. */
class Sha256Hasher : Hasher {
    override fun hash(salt: String, code: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$salt:$code".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** A freshly generated unlock secret: the [code] to show/stash once, and the [salt] hashed with it. */
data class GeneratedKey(val code: String, val salt: String)

object LockKeys {

    private val random = SecureRandom()

    /**
     * Unlock-code alphabet: uppercase letters + digits with every easily-confused glyph removed — no
     * O/0, no I/1/L, no Q (Gate A feedback 2026-07-22: these misread by eye and by OCR when the stashed
     * code is read back or scanned). 30 symbols; drawn from directly rather than Base32-packed (which
     * would force back in the ambiguous glyphs to reach 32). [KeyAuthority.normalize] uppercases, so
     * lowercase input still verifies.
     */
    const val ALPHABET = "ABCDEFGHJKMNPRSTUVWXYZ23456789"

    /** 24 chars × log2(30) ≈ 118 bits of entropy — ample for a stash-and-reenter commitment key. */
    private const val CODE_LENGTH = 24

    /**
     * A new random unlock secret: [CODE_LENGTH] characters from [ALPHABET], grouped in 4s for the human
     * to read (the QR encodes the grouped code; normalization drops the dashes, so scanning or typing
     * either form verifies), plus a 16-byte hex salt. `nextInt(bound)` is rejection-sampled, so the draw
     * is unbiased across the 30 symbols.
     */
    fun generate(): GeneratedKey {
        val code = (0 until CODE_LENGTH)
            .map { ALPHABET[random.nextInt(ALPHABET.length)] }
            .joinToString("")
            .chunked(4)
            .joinToString("-")
        val saltBytes = ByteArray(16).also(random::nextBytes)
        val salt = saltBytes.joinToString("") { "%02x".format(it) }
        return GeneratedKey(code = code, salt = salt)
    }
}

/**
 * Persists the durable-change key verifier. Stored in its own prefs file; only the salted hash is
 * ever written, never the code. Not configured (null) = no lock set yet: the first-run setup screen
 * creates one. Clearing app data wipes this too — an accepted friction-only limitation (CONSTRAINTS
 * §8; Device Owner is the real fix later).
 */
class LockStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConfigured(): Boolean = keyHash() != null

    fun keyHash(): KeyHash? = EngineCodec.decodeKeyHash(prefs.getString(KEY_HASH, null))

    /** Store the verifier for a freshly generated key. */
    fun setKey(generated: GeneratedKey) {
        val keyHash = KeyAuthority.create(generated.code, generated.salt, Sha256Hasher())
        prefs.edit().putString(KEY_HASH, EngineCodec.encodeKeyHash(keyHash)).apply()
    }

    /** True iff [code] matches the stored key. False when no key is configured. */
    fun verify(code: String): Boolean {
        val stored = keyHash() ?: return false
        return KeyAuthority.verify(code, stored, Sha256Hasher())
    }

    private companion object {
        const val PREFS = "appblock_lock"
        const val KEY_HASH = "durable_key_hash"
    }
}
