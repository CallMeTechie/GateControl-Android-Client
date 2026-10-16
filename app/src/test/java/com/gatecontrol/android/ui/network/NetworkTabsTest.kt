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
}
