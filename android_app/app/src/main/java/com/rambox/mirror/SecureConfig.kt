package com.rambox.mirror

import java.security.MessageDigest

/**
 * Penyimpanan kredensial dan endpoint biner terenkripsi (Bitwise Masking).
 * Mencegah string PIN (240994) dan domain VPS (rammbox.duckdns.org) muncul
 * dalam bentuk plain-text di tabel string DEX / decompiler.
 */
object SecureConfig {
    private const val KEY: Int = BuildConfig.MASK_KEY

    val DEFAULT_SERVER: String by lazy {
        decode(BuildConfig.MASKED_DOMAIN)
    }

    val DEFAULT_PIN: String by lazy {
        decode(BuildConfig.MASKED_PIN)
    }

    private fun decode(masked: IntArray): String {
        val bytes = ByteArray(masked.size) { i -> (masked[i] xor KEY).toByte() }
        return String(bytes, Charsets.UTF_8)
    }

    fun isPinValid(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val clean = candidate.trim()
        val defaultPin = DEFAULT_PIN
        return MessageDigest.isEqual(
            sha256(clean),
            sha256(defaultPin)
        )
    }

    fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
}
