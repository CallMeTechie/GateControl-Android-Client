package com.gatecontrol.android.ui.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NetworkTabsTest {

    private val all = NetworkTabs.available(services = true, remote = true, dns = true)

    @Test
    fun `available keeps the order Dienste, DNS-Schutz, Remote`() {
        assertEquals(listOf("services", "dns", "remote"), all)
        assertEquals(listOf("dns"), NetworkTabs.available(services = false, remote = false, dns = true))
    }

    @Test
    fun `a requested segment wins over the stored default`() {
        assertEquals("remote", NetworkTabs.initial("remote", all, default = "dns"))
        assertEquals("remote", NetworkTabs.initial("rdp", all, default = "dns"), "the old Remote route")
    }

    @Test
    fun `without a request the stored default opens, else the first`() {
        assertEquals("dns", NetworkTabs.initial(null, all, default = "dns"))
        assertEquals("remote", NetworkTabs.initial(null, all, default = "rdp"))
        assertEquals("services", NetworkTabs.initial(null, all, default = null))
        assertEquals("services", NetworkTabs.initial(null, all))
    }

    @Test
    fun `a default the token may not use is ignored`() {
        val noRemote = NetworkTabs.available(services = true, remote = false, dns = true)
        assertEquals("services", NetworkTabs.initial(null, noRemote, default = "remote"))
        assertEquals("services", NetworkTabs.initial("remote", noRemote, default = "remote"))
        assertNull(NetworkTabs.initial(null, emptyList(), default = "dns"))
    }

    @Test
    fun `DNS-Schutz is a segment only where the server reports it`() {
        assertEquals(listOf("services", "dns", "remote"), NetworkTabs.available(services = true, remote = true, dns = true))
        // No DNS protection (older server, plugin off): nothing stands in its place.
        assertEquals(listOf("services", "remote"), NetworkTabs.available(services = true, remote = true, dns = false))
        assertEquals(listOf("services"), NetworkTabs.available(services = true, remote = false))
        assertEquals(emptyList<String>(), NetworkTabs.available(services = false, remote = false))
    }

    @Test
    fun `the removed Pi-hole segment opens DNS-Schutz, else the first segment`() {
        // A default stored or a link made while the app still had Pi-hole.
        assertEquals("dns", NetworkTabs.initial(null, all, default = "pihole"))
        assertEquals("dns", NetworkTabs.initial("pihole", all))
        assertEquals("dns", NetworkTabs.initial("dns", all, default = "remote"))
        val noDns = NetworkTabs.available(services = true, remote = true)
        assertEquals("services", NetworkTabs.initial(null, noDns, default = "pihole"))
        assertEquals("services", NetworkTabs.initial("pihole", noDns))
        assertEquals("remote", NetworkTabs.initial("pihole", noDns, default = "remote"))
        assertEquals("services", NetworkTabs.initial("dns", noDns))
        assertEquals("remote", NetworkTabs.initial(null, NetworkTabs.available(services = false, remote = true), default = "pihole"))
        assertNull(NetworkTabs.initial(null, emptyList(), default = "pihole"))
    }
}
