package com.gatecontrol.android.common

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

class E2EEHandlerTest {

    // -------------------------------------------------------------------------
    // Server-side simulation helper
    // -------------------------------------------------------------------------

    /**
     * Simulates server-side encryption using the client's public key.
     *
     * Steps:
     * 1. Decode client public key (X.509 / EC P-256)
     * 2. Generate server ephemeral ECDH P-256 keypair
     * 3. ECDH → shared secret
     * 4. HKDF-SHA256: salt = clientPub + serverPub, info = "gatecontrol-rdp-e2ee-v1", length = 32
     * 5. AES-256-GCM encrypt with random 12-byte IV
     * 6. Return EncryptedPayload (data = ciphertext + 16-byte authTag appended by GCM)
     */
    private fun serverEncrypt(
        clientPublicKeyBase64: String,
        plaintext: String
    ): E2EEHandler.EncryptedPayload {
        // 1. Decode client public key
        val clientPubBytes = Base64.getDecoder().decode(clientPublicKeyBase64)
        val keyFactory = KeyFactory.getInstance("EC")
        val clientPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(clientPubBytes))

        // 2. Generate server ephemeral keypair
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val serverKp = generator.generateKeyPair()
        val serverPubBytes = serverKp.public.encoded

        // 3. ECDH key agreement
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(serverKp.private)
        keyAgreement.doPhase(clientPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // 4. HKDF-SHA256
        val salt = clientPubBytes + serverPubBytes
        val info = "gatecontrol-rdp-e2ee-v1".toByteArray(Charsets.UTF_8)
        val derivedKey = hkdf(sharedSecret, salt, info, 32)

        // 5. AES-256-GCM encrypt
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, iv)
        val secretKey = SecretKeySpec(derivedKey, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        val ciphertextWithTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val encoder = Base64.getEncoder()
        // authTag is the last 16 bytes of ciphertextWithTag (GCM appends it)
        val authTagBase64 = encoder.encodeToString(ciphertextWithTag.takeLast(16).toByteArray())

        return E2EEHandler.EncryptedPayload(
            data = encoder.encodeToString(ciphertextWithTag),
            iv = encoder.encodeToString(iv),
            authTag = authTagBase64,
            serverPublicKey = encoder.encodeToString(serverPubBytes)
        )
    }

    // -------------------------------------------------------------------------
    // HKDF helper (mirrors E2EEHandler implementation)
    // -------------------------------------------------------------------------

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac("HmacSHA256", salt, ikm)
        val okm = hmac("HmacSHA256", prk, info + byteArrayOf(0x01))
        return okm.copyOf(length)
    }

    private fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data)
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `generateKeyPair returns Base64 public key`() {
        val handler = E2EEHandler()
        val pubKeyBase64 = handler.generateKeyPair()

        assertFalse(pubKeyBase64.isEmpty(), "Public key must not be empty")

        // Must decode without exception
        val decoded = Base64.getDecoder().decode(pubKeyBase64)
        assertTrue(decoded.isNotEmpty(), "Decoded public key must not be empty")

        // Must be a valid EC public key in X.509 format
        val keyFactory = KeyFactory.getInstance("EC")
        assertDoesNotThrow {
            keyFactory.generatePublic(X509EncodedKeySpec(decoded))
        }
    }

    @Test
    fun `decrypt round-trip succeeds`() {
        val handler = E2EEHandler()
        val clientPubKey = handler.generateKeyPair()

        val plaintext = "Hello, GateControl E2EE!"
        val payload = serverEncrypt(clientPubKey, plaintext)

        val decrypted = handler.decrypt(payload)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `decryptCredentials parses JSON fields`() {
        val handler = E2EEHandler()
        val clientPubKey = handler.generateKeyPair()

        val json = """{"username":"alice","password":"s3cr3t!","domain":"CORP"}"""
        val payload = serverEncrypt(clientPubKey, json)

        val credentials = handler.decryptCredentials(payload)
        assertEquals("alice", credentials.username)
        assertEquals("s3cr3t!", credentials.password)
        assertEquals("CORP", credentials.domain)
    }

    @Test
    fun `clear wipes key material`() {
        val handler = E2EEHandler()
        val clientPubKey = handler.generateKeyPair()
        val payload = serverEncrypt(clientPubKey, "test")

        // Sanity check: works before clear
        assertDoesNotThrow { handler.decrypt(payload) }

        handler.clear()

        // Must throw after clear
        assertThrows<IllegalStateException> {
            handler.decrypt(payload)
        }
    }

    @Test
    fun `tampered ciphertext throws`() {
        val handler = E2EEHandler()
        val clientPubKey = handler.generateKeyPair()

        val payload = serverEncrypt(clientPubKey, "sensitive data")

        // Flip a byte in the Base64-decoded ciphertext and re-encode
        val ciphertextBytes = Base64.getDecoder().decode(payload.data).also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val tamperedPayload = payload.copy(data = Base64.getEncoder().encodeToString(ciphertextBytes))

        assertThrows<Exception> {
            handler.decrypt(tamperedPayload)
        }
    }

    @Test
    fun `unicode credentials round-trip`() {
        val handler = E2EEHandler()
        val clientPubKey = handler.generateKeyPair()

        val password = "paßwört€"
        val json = """{"username":"müller","password":"$password","domain":"BÜRO"}"""
        val payload = serverEncrypt(clientPubKey, json)

        val credentials = handler.decryptCredentials(payload)
        assertEquals("müller", credentials.username)
        assertEquals(password, credentials.password)
        assertEquals("BÜRO", credentials.domain)
    }
}
