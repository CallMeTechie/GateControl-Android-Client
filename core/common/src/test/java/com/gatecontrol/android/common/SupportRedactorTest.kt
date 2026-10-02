package com.gatecontrol.android.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupportRedactorTest {

    private val wgKey = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
    private val psk = "FpCyhws9cxwWoV4xELtfJvjJN+zQVRPISllRWgeopVE="
    private val mask = SupportRedactor.MASK

    private fun r(s: String) = SupportRedactor.redactText(s)

    @Test
    fun `masks PrivateKey and PresharedKey, keeps the rest of the config`() {
        val conf = "[Interface]\nPrivateKey = $wgKey\nAddress = 10.8.0.2/32\nDNS = 10.8.0.1\n\n" +
            "[Peer]\npresharedkey=$psk\nEndpoint = vpn.example.com:51820\nAllowedIPs = 0.0.0.0/0"
        val out = r(conf)
        assertFalse(out.contains(wgKey))
        assertFalse(out.contains(psk))
        assertTrue(Regex("(?m)^PrivateKey = \\[REDACTED]$").containsMatchIn(out), out)
        assertTrue(Regex("(?m)^presharedkey=\\[REDACTED]$").containsMatchIn(out), out)
        for (keep in listOf("Address = 10.8.0.2/32", "DNS = 10.8.0.1", "Endpoint = vpn.example.com:51820", "AllowedIPs = 0.0.0.0/0")) {
            assertTrue(out.contains(keep), keep)
        }
    }

    @Test
    fun `masks auth headers, cookies and API tokens`() {
        val cases = listOf(
            "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl" to listOf("c2lnbmF0dXJl", "eyJzdWIi"),
            "authorization=Basic dXNlcjpwYXNz" to listOf("dXNlcjpwYXNz"),
            "--> X-API-Token: gc_live_0123456789abcdef" to listOf("0123456789abcdef"),
            "X-API-Key: secretvalue123" to listOf("secretvalue123"),
            "Cookie: gc.sid=s%3Aabc; other=1" to listOf("gc.sid", "other=1"),
            "Set-Cookie: session=xyz; Path=/" to listOf("session=xyz"),
            "I/Api: token gc_0123456789abcdef0123 rejected" to listOf("gc_0123456789abcdef0123"),
        )
        for ((line, leaks) in cases) {
            val out = r(line)
            assertTrue(out.contains(mask), "not masked: $line")
            for (leak in leaks) assertFalse(out.contains(leak), "leak $leak in $out")
        }
    }

    @Test
    fun `masks key=value and JSON pairs with secret names`() {
        val out = r("password=hunter2&user=bob {\"apiKey\": \"k-123\", \"client_secret\":\"s3\", \"rdpPassword\":\"pw!\"} setupCode: AB12-CD34-EF56-7890")
        for (s in listOf("hunter2", "k-123", "\"s3\"", "pw!", "AB12-CD34-EF56-7890")) assertFalse(out.contains(s), "leak $s: $out")
        assertTrue(out.contains("user=bob"))
    }

    @Test
    fun `masks key-like values`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk=\nAAAAB3NzaC1yc2EAAAADAQABAAABAQ\n-----END OPENSSH PRIVATE KEY-----"
        val hex = "ab".repeat(32)
        val out = r("peer $wgKey fp $hex code 1a2b-3c4d-5e6f-7a8b\n$pem")
        for (s in listOf(wgKey, hex, "1a2b-3c4d-5e6f-7a8b", "b3BlbnNzaC1rZXk=", "AAAAB3NzaC1yc2E")) assertFalse(out.contains(s), "leak $s: $out")
    }

    @Test
    fun `keeps ordinary log lines`() {
        for (line in listOf(
            "2026-10-02 10:00:00.123 I/TunnelManager: Tunnel connected to 203.0.113.5:51820 after 2 attempts",
            "2026-10-02 10:00:01.000 W/TunnelMonitor: handshake too old (200 s)",
            "uuid 123e4567-e89b-12d3-a456-426614174000",
        )) assertEquals(line, r(line))
    }

    @Test
    fun `linear time on hostile input`() {
        for (s in listOf("-a".repeat(50_000), "-----BEGIN RSA PRIVATE KEY-----".repeat(3_000), "Authorization: ".repeat(9_000), "password".repeat(20_000))) {
            val t0 = System.currentTimeMillis()
            r(s)
            assertTrue(System.currentTimeMillis() - t0 < 3_000, "slow on ${s.take(20)}")
        }
    }

    @Test
    fun `redactValue masks secret keys at any depth and drops unsafe keys`() {
        @Suppress("UNCHECKED_CAST")
        val out = SupportRedactor.redactValue(
            mapOf(
                "server" to mapOf("url" to "https://gate.example.com", "apiToken" to "gc_abcdefgh", "peerId" to 7),
                "list" to listOf(mapOf("password" to "p"), "PrivateKey = $wgKey"),
                "hasToken" to false,
                "emptySecret" to "",
                "__proto__" to mapOf("polluted" to true),
            ),
        ) as Map<String, Any?>
        val server = out["server"] as Map<*, *>
        assertEquals(mask, server["apiToken"])
        assertEquals("https://gate.example.com", server["url"])
        assertEquals(7, server["peerId"])
        val list = out["list"] as List<*>
        assertEquals(mask, (list[0] as Map<*, *>)["password"])
        assertEquals("PrivateKey = $mask", list[1])
        assertEquals(false, out["hasToken"])
        assertEquals("", out["emptySecret"])
        assertFalse(out.containsKey("__proto__"))
    }

    @Test
    fun `isSecretKey`() {
        for (k in listOf("apiKey", "api_key", "apiToken", "privateKey", "PresharedKey", "password", "token", "X-API-Token", "cookie", "Authorization", "enrollmentCode", "setup_code")) {
            assertTrue(SupportRedactor.isSecretKey(k), k)
        }
        for (k in listOf("serverUrl", "peerId", "autoConnect", "endpoint", "version", "dnsServers", "splitTunnelMode")) {
            assertFalse(SupportRedactor.isSecretKey(k), k)
        }
    }
}
