package com.gatecontrol.android.common

import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class E2EEHandler {

    data class EncryptedPayload(
        val data: String,           // Base64: ciphertext only (without tag)
        val iv: String,             // Base64: 12-byte IV
        val authTag: String,        // Base64: 16-byte GCM auth tag (separate from data)
        val serverPublicKey: String // Base64: server ECDH public key (X.509 or raw)
    )

    data class Credentials(
        val username: String,
        val password: String?,
        val domain: String?
    )

    private var keyPair: KeyPair? = null

    /**
     * Generate ephemeral ECDH P-256 keypair.
     * Returns the Base64-encoded X.509 public key.
     * Stores the keypair internally for later decryption.
     */
    fun generateKeyPair(): String {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val kp = generator.generateKeyPair()
        keyPair = kp
        return Base64.getEncoder().encodeToString(kp.public.encoded)
    }

    /**
     * Decrypt an EncryptedPayload using the stored ECDH keypair.
     *
     * Steps:
     * 1. Decode server public key from Base64 → X509EncodedKeySpec → ECPublicKey
     * 2. ECDH key agreement with stored private key
     * 3. HKDF-SHA256: salt = clientPubEncoded + serverPubEncoded,
     *                  info = "gatecontrol-rdp-e2ee-v1", length = 32
     * 4. AES-256-GCM decrypt (data contains ciphertext + authTag together)
     *
     * @throws IllegalStateException if keypair has not been generated or has been cleared
     */
    fun decrypt(payload: EncryptedPayload): String {
        val kp = keyPair ?: throw IllegalStateException("Keypair not generated or has been cleared")

        // 1. Decode server public key
        val serverPubBytes = Base64.getDecoder().decode(payload.serverPublicKey)
        val keyFactory = KeyFactory.getInstance("EC")
        val serverPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(serverPubBytes))

        // 2. ECDH key agreement
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(kp.private)
        keyAgreement.doPhase(serverPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // 3. HKDF-SHA256
        val clientPubBytes = kp.public.encoded
        val salt = clientPubBytes + serverPubBytes
        val info = "gatecontrol-rdp-e2ee-v1".toByteArray(Charsets.UTF_8)
        val derivedKey = hkdf(sharedSecret, salt, info, 32)

        // 4. AES-256-GCM decrypt
        //    Server sends ciphertext and authTag as separate Base64 fields.
        //    Java's AES/GCM/NoPadding expects ciphertext || authTag concatenated
        //    as input to doFinal().
        val iv = Base64.getDecoder().decode(payload.iv)
        val ciphertext = Base64.getDecoder().decode(payload.data)
        val authTag = Base64.getDecoder().decode(payload.authTag)
        val ciphertextWithTag = ciphertext + authTag

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, iv) // 128-bit auth tag
        val secretKey = SecretKeySpec(derivedKey, "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val plaintext = cipher.doFinal(ciphertextWithTag)
        return String(plaintext, Charsets.UTF_8)
    }

    /**
     * Decrypt and parse credentials from a JSON-encoded EncryptedPayload.
     */
    fun decryptCredentials(payload: EncryptedPayload): Credentials {
        val json = decrypt(payload)
        val obj = JSONObject(json)
        return Credentials(
            username = obj.optString("username", ""),
            password = obj.optString("password").ifEmpty { null },
            domain = obj.optString("domain").ifEmpty { null },
        )
    }

    /**
     * Wipe the keypair from memory.
     */
    fun clear() {
        keyPair = null
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * HKDF-SHA256 (RFC 5869, single-block expansion).
     *
     * PRK = HMAC-SHA256(salt, ikm)
     * OKM = HMAC-SHA256(PRK, info || 0x01)[:length]
     */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hmacAlgo = "HmacSHA256"

        // Extract
        val prk = hmac(hmacAlgo, salt, ikm)

        // Expand (single block — sufficient for length ≤ 32)
        val t1Input = info + byteArrayOf(0x01)
        val okm = hmac(hmacAlgo, prk, t1Input)

        return okm.copyOf(length)
    }

    private fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data)
    }

}
