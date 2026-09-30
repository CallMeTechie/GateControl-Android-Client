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
        PublicKey = c2VydmVycHVibGlja2V5YmFzZTY0ZW5jb2RlZHh5eiE=
        PresharedKey = cHJlc2hhcmVka2V5YmFzZTY0ZW5jb2RlZHh4eHh4eCE=
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

        assertEquals("c2VydmVycHVibGlja2V5YmFzZTY0ZW5jb2RlZHh5eiE=", config.publicKey)
        assertEquals("cHJlc2hhcmVka2V5YmFzZTY0ZW5jb2RlZHh4eHh4eCE=", config.presharedKey)
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
            PublicKey = c2VydmVycHVibGlja2V5YmFzZTY0ZW5jb2RlZHh5eiE=
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
        assertTrue(output.contains("PublicKey = c2VydmVycHVibGlja2V5YmFzZTY0ZW5jb2RlZHh5eiE="))
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
            PublicKey = c2VydmVycHVibGlja2V5YmFzZTY0ZW5jb2RlZHh5eiE=
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

    private val secondPeer = """

        [Peer] # second site
        PublicKey = b3RoZXJwZWVycHVibGlja2V5YmFzZTY0ZW5jb2RlZHh=
        Endpoint = other.example.com:51820
        AllowedIPs = 192.168.50.0/24
    """.trimIndent()

    @Test
    fun `peerCount counts peer sections and ignores comments`() {
        assertEquals(1, TunnelConfig.peerCount(validConfig))
        assertEquals(2, TunnelConfig.peerCount(validConfig + "\n" + secondPeer))
        assertEquals(1, TunnelConfig.peerCount(validConfig + "\n# [Peer]\n"))
    }

    @Test
    fun `parse rejects a config with multiple peers instead of dropping one`() {
        val ex = assertThrows<IllegalArgumentException> {
            TunnelConfig.parse(validConfig + "\n" + secondPeer)
        }
        assertTrue(ex.message!!.contains("2 [Peer] sections"))
    }

    @Test
    fun `isSupported requires a valid single-peer config`() {
        assertTrue(TunnelConfig.isSupported(validConfig))
        assertFalse(TunnelConfig.isSupported(validConfig + "\n" + secondPeer))
        assertFalse(TunnelConfig.isSupported("[Interface]\nPrivateKey = x"))
    }
}
