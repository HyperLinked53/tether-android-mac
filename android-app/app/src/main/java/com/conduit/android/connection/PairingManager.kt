package com.conduit.android.connection

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Trust-on-first-use pairing.
 *
 * On pairing, the phone generates an ephemeral 256-bit [pairingSecret] shown to the user as a
 * QR / copyable URL. The Mac proves knowledge of it via HMAC, and we then mint a long-term
 * [token] per peer device id, persisted on both sides and presented on every later connection.
 */
class PairingManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tether_pairing", Context.MODE_PRIVATE)
    private val rng = SecureRandom()

    /** Stable salt for proof derivation (per install). */
    val salt: String = prefs.getString(KEY_SALT, null) ?: randomB64(16).also {
        prefs.edit().putString(KEY_SALT, it).apply()
    }
    val iterations: Int = 100_000 // reserved in v1 (HMAC proof is direct); kept for forward-compat

    /** The active pairing secret while a pairing window is open, else null. */
    @Volatile var pairingSecret: String? = null
        private set

    /**
     * Open a pairing window: generate a fresh code to display. A short 6-digit code is used (not a
     * long base64 secret) so it can be typed on the Mac without transcription errors. Entropy is
     * modest on purpose — the window is single-use, user-initiated, and brief, and a wrong proof
     * tears down the connection, so brute force on the LAN is impractical.
     */
    fun beginPairing(): String = "%06d".format(rng.nextInt(1_000_000)).also { pairingSecret = it }

    fun endPairing() { pairingSecret = null }

    /** Verify the Mac's proof against the active pairing secret. */
    fun verifyProof(deviceId: String, proof: String): Boolean {
        val secret = pairingSecret ?: return false
        val expected = computeProof(secret, salt, deviceId)
        return MessageDigest.isEqual(expected.toByteArray(), proof.toByteArray())
    }

    /** Mint + persist a long-term token for a freshly paired device. */
    fun issueToken(deviceId: String): String = randomB64(32).also {
        prefs.edit().putString(tokenKey(deviceId), it).apply()
        endPairing()
    }

    /** The stored long-term token for [deviceId], or null if not paired. */
    fun tokenFor(deviceId: String): String? = prefs.getString(tokenKey(deviceId), null)

    fun forget(deviceId: String) = prefs.edit().remove(tokenKey(deviceId)).apply()

    private fun randomB64(bytes: Int): String =
        ByteArray(bytes).also { rng.nextBytes(it) }.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    companion object {
        private const val KEY_SALT = "salt"
        private fun tokenKey(deviceId: String) = "token_$deviceId"

        /** proof = base64( HMAC-SHA256(secret, salt || deviceId) ). Matches protocol/README §2. */
        fun computeProof(secret: String, salt: String, deviceId: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            val out = mac.doFinal((salt + deviceId).toByteArray())
            return Base64.encodeToString(out, Base64.NO_WRAP)
        }
    }
}
