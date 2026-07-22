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
    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * A new random unlock secret: 20 bytes of entropy as a Base32 code (grouped in 4s for the human
     * to read), plus a 16-byte hex salt. The QR encodes the grouped code; normalization drops the
     * dashes, so scanning or typing either form verifies.
     */
    fun generate(): GeneratedKey {
        val codeBytes = ByteArray(20).also(random::nextBytes)
        val code = base32(codeBytes).chunked(4).joinToString("-")
        val saltBytes = ByteArray(16).also(random::nextBytes)
        val salt = saltBytes.joinToString("") { "%02x".format(it) }
        return GeneratedKey(code = code, salt = salt)
    }

    private fun base32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1f
                sb.append(BASE32[index])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1f
            sb.append(BASE32[index])
        }
        return sb.toString()
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
