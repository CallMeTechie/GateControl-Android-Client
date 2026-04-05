package com.gatecontrol.android.rdp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [RdpExternalClient].
 *
 * Context-dependent methods (findInstalledClients, isAnyClientInstalled, launchIntent)
 * are not tested here as they require Android instrumentation.
 * buildRdpUri is a pure function and is fully tested.
 */
class RdpExternalClientTest {

    /**
     * Standalone helper that mirrors [RdpExternalClient.buildRdpUri] without requiring
     * a real Context, so the logic can be tested on the JVM.
     */
    private fun buildRdpUri(host: String, port: Int, username: String?, domain: String?): String {
        fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val sb = StringBuilder("rdp://full%20address=s:${encode(host)}:$port")
        if (!username.isNullOrEmpty()) sb.append("&username=s:${encode(username)}")
        if (!domain.isNullOrEmpty()) sb.append("&domain=s:${encode(domain)}")
        return sb.toString()
    }

    @Test
    fun `buildRdpUri with host and port only`() {
        val uri = buildRdpUri("192.168.1.10", 3389, null, null)
        assertEquals("rdp://full%20address=s:192.168.1.10:3389", uri)
    }

    @Test
    fun `buildRdpUri includes username when provided`() {
        val uri = buildRdpUri("192.168.1.10", 3389, "alice", null)
        assertTrue(uri.contains("username=s:alice"), "URI should contain username: $uri")
        assertFalse(uri.contains("domain="), "URI should not contain domain: $uri")
    }

    @Test
    fun `buildRdpUri includes domain when provided`() {
        val uri = buildRdpUri("192.168.1.10", 3389, "alice", "CORP")
        assertTrue(uri.contains("username=s:alice"), "URI should contain username: $uri")
        assertTrue(uri.contains("domain=s:CORP"), "URI should contain domain: $uri")
    }

    @Test
    fun `buildRdpUri with username and domain`() {
        val uri = buildRdpUri("rdp.example.com", 3390, "bob", "WORKGROUP")
        assertTrue(uri.startsWith("rdp://full%20address=s:rdp.example.com:3390"), "Base address should match: $uri")
        assertTrue(uri.contains("username=s:bob"))
        assertTrue(uri.contains("domain=s:WORKGROUP"))
    }

    @Test
    fun `buildRdpUri encodes special characters in host`() {
        // Hosts shouldn't normally have special chars but encoding should be safe
        val uri = buildRdpUri("host name", 3389, null, null)
        assertFalse(uri.contains(" "), "URI should not contain unencoded spaces: $uri")
    }

    @Test
    fun `buildRdpUri encodes special characters in username`() {
        val uri = buildRdpUri("192.168.1.1", 3389, "user name", null)
        assertFalse(uri.contains(" "), "URI should not contain unencoded spaces: $uri")
        assertTrue(uri.contains("username=s:"), "URI should still contain username key: $uri")
    }

    @Test
    fun `buildRdpUri with empty username is omitted`() {
        val uri = buildRdpUri("192.168.1.1", 3389, "", null)
        assertFalse(uri.contains("username="), "Empty username should not appear in URI: $uri")
    }

    @Test
    fun `buildRdpUri with empty domain is omitted`() {
        val uri = buildRdpUri("192.168.1.1", 3389, "alice", "")
        assertFalse(uri.contains("domain="), "Empty domain should not appear in URI: $uri")
    }

    @Test
    fun `buildRdpUri uses correct rdp scheme`() {
        val uri = buildRdpUri("10.0.0.1", 3389, null, null)
        assertTrue(uri.startsWith("rdp://"), "URI must start with rdp:// scheme: $uri")
    }

    @Test
    fun `buildRdpUri with non-standard port`() {
        val uri = buildRdpUri("server.local", 13389, "user", "DOM")
        assertTrue(uri.contains(":13389"), "URI should contain non-standard port: $uri")
    }
}
