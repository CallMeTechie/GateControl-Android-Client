package com.gatecontrol.android.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SplitTunnelJsonTest {

    @Test
    fun `networks round-trip`() {
        val nets = listOf(SplitTunnelJson.Network("192.168.0.0/16", "LAN"), SplitTunnelJson.Network("fd00::/8", ""))
        assertEquals(nets, SplitTunnelJson.decodeNetworks(SplitTunnelJson.encodeNetworks(nets)))
    }

    @Test
    fun `apps round-trip`() {
        val apps = listOf("com.example.a", "org.example.b")
        assertEquals(apps, SplitTunnelJson.decodeApps(SplitTunnelJson.encodeApps(apps)))
    }

    @Test
    fun `empty and broken input yield empty lists`() {
        assertTrue(SplitTunnelJson.decodeNetworks("").isEmpty())
        assertTrue(SplitTunnelJson.decodeNetworks("[]").isEmpty())
        assertTrue(SplitTunnelJson.decodeNetworks("{not json").isEmpty())
        assertTrue(SplitTunnelJson.decodeApps("[{\"label\":\"x\"}]").isEmpty())
    }

    @Test
    fun `missing label defaults to empty`() {
        assertEquals(listOf(SplitTunnelJson.Network("10.0.0.0/8", "")), SplitTunnelJson.decodeNetworks("[{\"cidr\":\"10.0.0.0/8\"}]"))
    }
}
