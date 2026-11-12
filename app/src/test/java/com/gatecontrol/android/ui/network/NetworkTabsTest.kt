package com.gatecontrol.android.ui.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NetworkTabsTest {

    private val all = NetworkTabs.available(services = true, pihole = true, remote = true)

    @Test
    fun `available keeps the order Dienste, Pi-hole, Remote`() {
        assertEquals(listOf("services", "pihole", "remote"), all)
        assertEquals(listOf("pihole"), NetworkTabs.available(services = false, pihole = true, remote = false))
    }

    @Test
    fun `a requested segment wins over the stored default`() {
        assertEquals("remote", NetworkTabs.initial("remote", all, default = "pihole"))
        assertEquals("remote", NetworkTabs.initial("rdp", all, default = "pihole"), "the old Remote route")
    }

    @Test
    fun `without a request the stored default opens, else the first`() {
        assertEquals("pihole", NetworkTabs.initial(null, all, default = "pihole"))
        assertEquals("remote", NetworkTabs.initial(null, all, default = "rdp"))
        assertEquals("services", NetworkTabs.initial(null, all, default = null))
        assertEquals("services", NetworkTabs.initial(null, all))
    }

    @Test
    fun `a default the token may not use is ignored`() {
        val noRemote = NetworkTabs.available(services = true, pihole = true, remote = false)
        assertEquals("services", NetworkTabs.initial(null, noRemote, default = "remote"))
        assertEquals("services", NetworkTabs.initial("remote", noRemote, default = "remote"))
        assertNull(NetworkTabs.initial(null, emptyList(), default = "pihole"))
    }

    @Test
    fun `a server with DNS protection shows DNS-Schutz in place of Pi-hole`() {
        assertEquals(listOf("services", "dns", "remote"), NetworkTabs.available(services = true, pihole = true, remote = true, dns = true))
        assertEquals(listOf("dns"), NetworkTabs.available(services = false, pihole = false, remote = false, dns = true))
        // No DNS protection (older server, plugin off): Pi-hole as before.
        assertEquals(listOf("services", "pihole"), NetworkTabs.available(services = true, pihole = true, remote = false, dns = false))
    }

    @Test
    fun `Pi-hole and DNS-Schutz stand for each other as request and stored default`() {
        val withDns = NetworkTabs.available(services = true, pihole = true, remote = true, dns = true)
        assertEquals("dns", NetworkTabs.initial(null, withDns, default = "pihole"), "the default chosen before the server changed")
        assertEquals("dns", NetworkTabs.initial("pihole", withDns))
        assertEquals("dns", NetworkTabs.initial("dns", withDns, default = "remote"))
        // The DNS protection is gone again: the stored "dns" opens Pi-hole.
        assertEquals("pihole", NetworkTabs.initial(null, all, default = "dns"))
        assertEquals("services", NetworkTabs.initial("dns", NetworkTabs.available(services = true, pihole = false, remote = true)))
    }
}
