package com.gatecontrol.android.homecache

import com.gatecontrol.android.network.HomeNetworkStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HomeSiteMemoryTest {
    private var now = 1_000_000L
    private val memory = HomeSiteMemory { now }

    @Test
    fun `home is kept for a while`() {
        memory.put("192.168.1.5", HomeNetworkStatus.Home(viaTunnel = false))
        assertEquals(true, memory.get("192.168.1.5"))
        now += HomeSiteMemory.HOME_MS - 1
        assertEquals(true, memory.get("192.168.1.5"))
        now += 1
        assertNull(memory.get("192.168.1.5"))
    }

    @Test
    fun `other addresses are another network`() {
        memory.put("192.168.1.5", HomeNetworkStatus.Home(viaTunnel = false))
        assertNull(memory.get("10.0.0.7"))
    }

    @Test
    fun `not home, an older server and no answer mean no header`() {
        memory.put("a", HomeNetworkStatus.NotHome(viaTunnel = false, siteAware = true))
        memory.put("b", HomeNetworkStatus.Unsupported)
        memory.put("c", HomeNetworkStatus.Unknown)
        assertEquals(false, memory.get("a"))
        assertEquals(false, memory.get("b"))
        assertEquals(false, memory.get("c"))
        // no answer is asked again soon
        now += HomeSiteMemory.UNKNOWN_MS
        assertNull(memory.get("c"))
        assertEquals(false, memory.get("a"))
    }
}
