package com.appblock.engine

/**
 * The unlock key for durable changes (CONSTRAINTS.md §6). The secret code is generated once at setup,
 * shown to the user as a QR + text to stash off-phone, then only its salted hash is persisted — the
 * plaintext is never recoverable from the app, so finding it later means going to the stash, not the
 * app's storage. To make a durable *loosening* change the user re-supplies the code (scan the QR or
 * paste it); [KeyAuthority.verify] hashes it and compares.
 *
 * Hashing is behind [Hasher] so this stays pure/JVM-testable; the Android layer supplies SHA-256.
 */
data class KeyHash(val salt: String, val hash: String)

/** Salted one-way hash of an unlock code. Android impl = SHA-256; tests use a trivial deterministic one. */
fun interface Hasher {
    fun hash(salt: String, code: String): String
}

object KeyAuthority {

    /**
     * Normalize a scanned/typed code so formatting never matters: trim, drop spaces/dashes, uppercase.
     * The same normalization runs at create and verify time, so "abcd-ef01" and "ABCDEF01" match.
     */
    fun normalize(code: String): String =
        code.trim().replace("-", "").replace(" ", "").uppercase()

    /** Build the stored verifier for a freshly generated [code] + [salt]. */
    fun create(code: String, salt: String, hasher: Hasher): KeyHash =
        KeyHash(salt, hasher.hash(salt, normalize(code)))

    /** True iff [code] matches the stored [keyHash]. Constant-time compare to avoid a timing side channel. */
    fun verify(code: String, keyHash: KeyHash, hasher: Hasher): Boolean =
        constantTimeEquals(hasher.hash(keyHash.salt, normalize(code)), keyHash.hash)

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
