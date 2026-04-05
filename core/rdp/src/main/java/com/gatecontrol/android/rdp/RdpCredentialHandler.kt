package com.gatecontrol.android.rdp

import com.gatecontrol.android.common.E2EEHandler
import com.gatecontrol.android.network.E2eePayload

class RdpCredentialHandler {

    private val e2eeHandler = E2EEHandler()

    /**
     * Generate an ephemeral ECDH P-256 keypair and return the Base64-encoded public key.
     * The private key is stored internally for later decryption.
     */
    fun generatePublicKey(): String = e2eeHandler.generateKeyPair()

    /**
     * Decrypt credentials from an E2EE payload returned by the server.
     */
    fun decryptCredentials(e2eePayload: E2eePayload): RdpCredentials {
        val payload = E2EEHandler.EncryptedPayload(
            data = e2eePayload.data,
            iv = e2eePayload.iv,
            authTag = e2eePayload.authTag,
            serverPublicKey = e2eePayload.serverPublicKey
        )
        val creds = e2eeHandler.decryptCredentials(payload)
        return RdpCredentials(
            username = creds.username,
            password = creds.password,
            domain = creds.domain
        )
    }

    /**
     * Wipe the keypair from memory. After calling this, decryptCredentials will throw.
     */
    fun clear() = e2eeHandler.clear()
}
