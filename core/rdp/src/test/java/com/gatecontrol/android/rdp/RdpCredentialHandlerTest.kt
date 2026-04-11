package com.gatecontrol.android.rdp

import com.gatecontrol.android.common.E2EEHandler
import com.gatecontrol.android.network.E2eePayload
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class RdpCredentialHandlerTest {

    // -------------------------------------------------------------------------
    // Server-side simulation helper (mirrors E2EEHandlerTest)
    // -------------------------------------------------------------------------

    private fun serverEncrypt(clientPublicKeyBase64: String, plaintext: String): E2eePayload {
        val clientPubBytes = Base64.getDecoder().decode(clientPublicKeyBase64)
        val keyFactory = KeyFactory.getInstance("EC")
        val clientPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(clientPubBytes))

        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val serverKp = generator.generateKeyPair()
        val serverPubBytes = serverKp.public.encoded

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(serverKp.private)
        keyAgreement.doPhase(clientPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        val salt = clientPubBytes + serverPubBytes
        val info = "gatecontrol-rdp-e2ee-v1".toByteArray(Charsets.UTF_8)
        val derivedKey = hkdf(sharedSecret, salt, info, 32)

        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derivedKey, "AES"), GCMParameterSpec(128, iv))
        val ciphertextWithTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Node.js server sends ciphertext and authTag as separate fields.
        val tagSize = 16
        val ciphertextOnly = ciphertextWithTag.copyOfRange(0, ciphertextWithTag.size - tagSize)
        val authTagBytes = ciphertextWithTag.copyOfRange(ciphertextWithTag.size - tagSize, ciphertextWithTag.size)

        val encoder = Base64.getEncoder()
        return E2eePayload(
            data = encoder.encodeToString(ciphertextOnly),
            iv = encoder.encodeToString(iv),
            authTag = encoder.encodeToString(authTagBytes),
            serverPublicKey = encoder.encodeToString(serverPubBytes)
        )
    }

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
    fun `generatePublicKey returns non-empty Base64 string`() {
        val handler = RdpCredentialHandler()
        val publicKey = handler.generatePublicKey()

        assertFalse(publicKey.isEmpty(), "Public key must not be empty")

        val decoded = Base64.getDecoder().decode(publicKey)
        assertTrue(decoded.isNotEmpty(), "Decoded public key must not be empty")

        // Verify it is a valid EC public key
        val keyFactory = KeyFactory.getInstance("EC")
        assertDoesNotThrow {
            keyFactory.generatePublic(X509EncodedKeySpec(decoded))
        }
    }

    @Test
    fun `generatePublicKey returns different key each call`() {
        val handler1 = RdpCredentialHandler()
        val handler2 = RdpCredentialHandler()

        val key1 = handler1.generatePublicKey()
        val key2 = handler2.generatePublicKey()

        // Ephemeral keypairs should be different (astronomically unlikely to collide)
        assertNotEquals(key1, key2)
    }

    @Test
    fun `decryptCredentials correctly unwraps full credentials`() {
        val handler = RdpCredentialHandler()
        val publicKey = handler.generatePublicKey()

        val json = """{"username":"alice","password":"s3cr3t!","domain":"CORP"}"""
        val e2eePayload = serverEncrypt(publicKey, json)

        val creds = handler.decryptCredentials(e2eePayload)

        assertEquals("alice", creds.username)
        assertEquals("s3cr3t!", creds.password)
        assertEquals("CORP", creds.domain)
    }

    @Test
    fun `decryptCredentials handles null password and domain`() {
        val handler = RdpCredentialHandler()
        val publicKey = handler.generatePublicKey()

        val json = """{"username":"bob","password":null,"domain":null}"""
        val e2eePayload = serverEncrypt(publicKey, json)

        val creds = handler.decryptCredentials(e2eePayload)

        assertEquals("bob", creds.username)
        assertNull(creds.password)
        assertNull(creds.domain)
    }

    @Test
    fun `clear prevents further decryption`() {
        val handler = RdpCredentialHandler()
        val publicKey = handler.generatePublicKey()

        val json = """{"username":"alice","password":"pw","domain":null}"""
        val e2eePayload = serverEncrypt(publicKey, json)

        // Works before clear
        assertDoesNotThrow { handler.decryptCredentials(e2eePayload) }

        handler.clear()

        // Must throw after clear because the keypair is wiped
        assertThrows<Exception> {
            handler.decryptCredentials(e2eePayload)
        }
    }

    @Test
    fun `clear is idempotent`() {
        val handler = RdpCredentialHandler()
        handler.generatePublicKey()

        // Calling clear twice should not throw
        assertDoesNotThrow {
            handler.clear()
            handler.clear()
        }
    }
}
