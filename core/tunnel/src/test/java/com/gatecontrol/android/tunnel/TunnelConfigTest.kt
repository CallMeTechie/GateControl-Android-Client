package com.gatecontrol.android.tunnel

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TunnelConfigTest {

    private val validConfig = """
        [Interface]
        PrivateKey = YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=
        Address = 10.8.0.5/32
        DNS = 1.1.1.1, 8.8.8.8
        MTU = 1420

        [Peer]
        PublicKey = c2VydmVycHVibGlja2V5YmFzZTY0ZW5jb2RlZHh4eA==
        PresharedKey = cHJlc2hhcmVka2V5YmFzZTY0ZW5jb2RlZHh4eHh4eA==
        Endpoint = vpn.example.com:51820
        AllowedIPs = 0.0.0.0/0
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun `parse extracts interface fields`() {
        val config = TunnelConfig.parse(validConfig)

        assertEquals("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=", config.privateKey)
        assertEquals("10.8.0.5/32", config.address)
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), config.dns)
        assertEquals(1420, config.mtu)
    }

    @Test
    fun `parse extracts peer fields`() {
        val config = TunnelConfig.parse(validConfig)

        assertEquals("c2VydmVycHVibGlja2V5YmFzZTY0ZW5jb2RlZHh4eA==", config.publicKey)
        assertEquals("cHJlc2hhcmVka2V5YmFzZTY0ZW5jb2RlZHh4eHh4eA==", config.presharedKey)
        assertEquals("vpn.example.com:51820", config.endpoint)
        assertEquals("0.0.0.0/0", config.allowedIps)
        assertEquals(25, config.persistentKeepalive)
    }

    @Test
    fun `parse handles missing optional fields`() {
        val minimalConfig = """
            [Interface]
            PrivateKey = YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=
            Address = 10.8.0.5/32

            [Peer]
            PublicKey = c2VydmVycHVibGlja2V5YmFzZTY0ZW5jb2RlZHh4eA==
            Endpoint = vpn.example.com:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        val config = TunnelConfig.parse(minimalConfig)

        assertNull(config.mtu)
        assertNull(config.persistentKeepalive)
        assertTrue(config.dns.isEmpty())
        assertNull(config.presharedKey)
    }

    @Test
    fun `toWgQuick round-trips parsed config`() {
        val config = TunnelConfig.parse(validConfig)
        val output = config.toWgQuick()

        assertTrue(output.contains("[Interface]"))
        assertTrue(output.contains("PrivateKey = YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY="))
        assertTrue(output.contains("Address = 10.8.0.5/32"))
        assertTrue(output.contains("DNS = 1.1.1.1, 8.8.8.8"))
        assertTrue(output.contains("MTU = 1420"))
        assertTrue(output.contains("[Peer]"))
        assertTrue(output.contains("PublicKey = c2VydmVycHVibGlja2V5YmFzZTY0ZW5jb2RlZHh4eA=="))
        assertTrue(output.contains("Endpoint = vpn.example.com:51820"))
        assertTrue(output.contains("AllowedIPs = 0.0.0.0/0"))
        assertTrue(output.contains("PersistentKeepalive = 25"))
    }

    @Test
    fun `parse throws on empty input`() {
        assertThrows<IllegalArgumentException> {
            TunnelConfig.parse("")
        }
    }

    @Test
    fun `parse throws on missing private key`() {
        val noPrivKey = """
            [Interface]
            Address = 10.8.0.5/32

            [Peer]
            PublicKey = c2VydmVycHVibGlja2V5YmFzZTY0ZW5jb2RlZHh4eA==
            Endpoint = vpn.example.com:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        assertThrows<IllegalArgumentException> {
            TunnelConfig.parse(noPrivKey)
        }
    }

    @Test
    fun `getServerHost extracts hostname`() {
        val config = TunnelConfig.parse(validConfig)
        assertEquals("vpn.example.com", config.getServerHost())
    }

    @Test
    fun `getServerPort extracts port`() {
        val config = TunnelConfig.parse(validConfig)
        assertEquals(51820, config.getServerPort())
    }

    @Test
    fun `getServerPort returns default port when missing`() {
        val config = TunnelConfig.parse(validConfig).copy(endpoint = "vpn.example.com")
        assertEquals(51820, config.getServerPort())
    }
}
