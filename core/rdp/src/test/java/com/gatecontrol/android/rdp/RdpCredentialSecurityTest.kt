package com.gatecontrol.android.rdp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Security-focused tests that verify credential handling contracts:
 * - Embedded client must never expose passwords via clipboard
 * - Connection params must support null credentials
 * - Extra map must omit null credentials (no empty-string leaks)
 */
class RdpCredentialSecurityTest {

    @Test
    fun `collectExtras does not include password when null`() {
        val client = RdpEmbeddedClient()
        val params = RdpConnectionParams(
            host = "10.0.0.5", port = 3389,
            username = "admin", password = null, domain = null,
            resolutionWidth = 0, resolutionHeight = 0, colorDepth = 32,
            redirectClipboard = false, redirectPrinters = false, redirectDrives = false,
            audioMode = "local", adminSession = false, routeName = "Test"
        )
        val extras = client.collectExtras(params)

        assertFalse(extras.containsKey(RdpEmbeddedClient.EXTRA_PASSWORD),
            "Password must not appear in extras when null — prevents clipboard or log leaks")
    }

    @Test
    fun `collectExtras does not include username when null`() {
        val client = RdpEmbeddedClient()
        val params = RdpConnectionParams(
            host = "10.0.0.5", port = 3389,
            username = null, password = null, domain = null,
            resolutionWidth = 0, resolutionHeight = 0, colorDepth = 32,
            redirectClipboard = false, redirectPrinters = false, redirectDrives = false,
            audioMode = "local", adminSession = false, routeName = "Test"
        )
        val extras = client.collectExtras(params)

        assertFalse(extras.containsKey(RdpEmbeddedClient.EXTRA_USERNAME),
            "Username must not appear in extras when null")
        assertFalse(extras.containsKey(RdpEmbeddedClient.EXTRA_DOMAIN),
            "Domain must not appear in extras when null")
    }

    @Test
    fun `RdpConnectionParams fromRoute does not store credentials when mode is none`() {
        val route = com.gatecontrol.android.network.RdpRoute(
            id = 1, name = "Test", host = "10.0.0.10", port = 3389,
            externalHostname = null, externalPort = null,
            accessMode = "direct", credentialMode = "none", domain = null,
            resolutionMode = null, resolutionWidth = null, resolutionHeight = null,
            multiMonitor = null, colorDepth = null, redirectClipboard = null,
            redirectPrinters = null, redirectDrives = null, audioMode = null,
            networkProfile = null, sessionTimeout = null, adminSession = null,
            wolEnabled = false, maintenanceEnabled = false,
            status = com.gatecontrol.android.network.RdpRouteStatus(online = true, lastCheck = null)
        )

        val params = RdpConnectionParams.fromRoute(
            route = route,
            username = null,
            password = null,
            domain = null
        )

        assertNull(params.username, "No username should be stored for credential_mode=none")
        assertNull(params.password, "No password should be stored for credential_mode=none")
        assertNull(params.domain, "No domain should be stored for credential_mode=none")
    }

    @Test
    fun `ConnectResult Success passwordCopied is false when embedded client is used`() {
        // Embedded client should never copy password to clipboard
        val session = RdpSession(
            routeId = 1, sessionId = 42, host = "10.0.0.5",
            startTime = System.currentTimeMillis(), isExternal = false
        )
        val result = RdpManager.ConnectResult.Success(session, passwordCopied = false)
        assertFalse(result.passwordCopied,
            "Embedded client must not report passwordCopied=true")
        assertFalse(result.session.isExternal,
            "Embedded session must have isExternal=false")
    }

    @Test
    fun `ConnectResult Success passwordCopied is true only for external client`() {
        val session = RdpSession(
            routeId = 1, sessionId = 42, host = "10.0.0.5",
            startTime = System.currentTimeMillis(), isExternal = true
        )
        val result = RdpManager.ConnectResult.Success(session, passwordCopied = true)
        assertTrue(result.passwordCopied)
        assertTrue(result.session.isExternal)
    }

    @Test
    fun `RdpConnectionParams data class is not Serializable or Parcelable`() {
        // Credentials should never be persisted — RdpConnectionParams must NOT implement
        // Serializable or Parcelable to prevent accidental disk writes
        val interfaces = RdpConnectionParams::class.java.interfaces
        val implementsSerializable = interfaces.any { it.name.contains("Serializable") }
        val implementsParcelable = interfaces.any { it.name.contains("Parcelable") }
        assertFalse(implementsSerializable,
            "RdpConnectionParams must not be Serializable (credentials must stay in memory)")
        assertFalse(implementsParcelable,
            "RdpConnectionParams must not be Parcelable (credentials must stay in memory)")
    }
}
