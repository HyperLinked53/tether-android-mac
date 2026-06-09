package com.conduit.android.connection

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LAN session crypto, mirror of the Mac `util/crypto.ts` (protocol/README §6): HKDF-SHA256 key
 * derivation + AES-256-GCM. Built on the JDK's javax.crypto (no extra deps).
 */
object ConduitCrypto {
    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    /** HKDF-SHA256 → [len] bytes (len ≤ 32, single expand block). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, len: Int = 32): ByteArray {
        val prk = hmac(salt, ikm)
        val t = hmac(prk, info.toByteArray() + byteArrayOf(1))
        return t.copyOf(len)
    }

    /** AES-256-GCM: output = 12-byte nonce ‖ ciphertext ‖ 16-byte tag. */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        val nonce = data.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(data.copyOfRange(12, data.size))
    }

    private fun hmac(key: ByteArray, msg: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(msg)
}
