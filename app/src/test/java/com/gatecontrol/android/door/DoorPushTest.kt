package com.gatecontrol.android.door

import com.gatecontrol.android.familie.PluginAvailability
import com.gatecontrol.android.network.ClientPlugin
import com.gatecontrol.android.network.ClientPluginTab
import com.gatecontrol.android.notify.model.PushMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoorPushTest {

    private fun msg(topic: String, data: String?, route: String? = "plg-gatecontrol-door") = PushMessage(
        seq = 1, id = 1, eventId = "e", topic = topic, priority = "high", title = "Es klingelt", body = "",
        createdAt = null, expiresAt = null, collapseKey = null, silent = false, route = route, actions = emptyList(), dataJson = data,
    )

    @Test
    fun `a ring opens its detail`() {
        val m = msg("plugin:gatecontrol-door:doorbell", """{"route":"plg-gatecontrol-door","door":{"type":"ding","eventId":120,"cameraId":4,"lockId":1}}""")
        val info = DoorPush.parse(m)!!
        assertEquals(DoorPushInfo("ding", 120, 4, 1), info)
        assertTrue(info.isRing)
        assertEquals(DoorDest.Event(120), DoorPush.destination(m))
        val motion = msg("plugin:gatecontrol-door:motion", """{"door":{"type":"motion","eventId":"121","cameraId":4}}""")
        assertEquals(DoorDest.Event(121), DoorPush.destination(motion))
    }

    @Test
    fun `other door messages open the tab`() {
        val auto = msg("plugin:gatecontrol-door:auto", """{"door":{"type":"auto_unlocked","lockId":1}}""")
        assertEquals("auto_unlocked", DoorPush.parse(auto)!!.type)
        assertFalse(DoorPush.parse(auto)!!.isRing)
        assertEquals(DoorDest.Home, DoorPush.destination(auto))
        // A ring without event id, or no data at all: the tab.
        assertEquals(DoorDest.Home, DoorPush.destination(msg("plugin:gatecontrol-door:doorbell", """{"door":{"type":"ding"}}""")))
        assertEquals(DoorDest.Home, DoorPush.destination(msg("plugin:gatecontrol-door:door", null)))
    }

    @Test
    fun `messages of others are not door messages`() {
        val other = msg("plugin:gatecontrol-familie:sos", """{"door":{"type":"ding","eventId":1}}""", route = "plg-gatecontrol-familie")
        assertNull(DoorPush.parse(other))
        assertNull(DoorPush.destination(other))
        assertFalse(DoorPush.isDoorMessage(other))
    }

    @Test
    fun `the door tab comes from the plugin list`() {
        val icon = "M5 21V4a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v17M3 21h18M15 12h.01"
        val withTab = PluginAvailability.from(listOf(ClientPlugin("gatecontrol-door", "Tür & Kamera", "1.0.0", ClientPluginTab("Tür", icon))))
        assertTrue(withTab.doorTab)
        assertEquals(icon, withTab.doorIcon)
        assertFalse(withTab.familieTab)
        val noTab = PluginAvailability.from(listOf(ClientPlugin("gatecontrol-door", "Tür & Kamera", "1.0.0", null)))
        assertFalse(noTab.doorTab)
        val evil = PluginAvailability.from(listOf(ClientPlugin("gatecontrol-door", tab = ClientPluginTab("Tür", "<svg onload=x>"))))
        assertTrue(evil.doorTab)
        assertNull(evil.doorIcon)
    }

    @Test
    fun `destination keys round-trip`() {
        listOf(DoorDest.Home, DoorDest.History, DoorDest.Event(42)).forEach { assertEquals(it, DoorDest.parse(it.key)) }
        assertNull(DoorDest.parse("event:-1"))
        assertNull(DoorDest.parse("event:abc"))
        assertNull(DoorDest.parse("nope"))
        assertNull(DoorDest.parse(null))
    }
}
