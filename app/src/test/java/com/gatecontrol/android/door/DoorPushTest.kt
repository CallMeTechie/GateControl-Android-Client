package com.gatecontrol.android.door

import com.gatecontrol.android.familie.PluginAvailability
import com.gatecontrol.android.network.ClientPlugin
import com.gatecontrol.android.network.ClientPluginTab
import com.gatecontrol.android.notify.model.PushAction
import com.gatecontrol.android.notify.model.PushActionType
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.push.ButtonKind
import com.gatecontrol.android.push.NotificationQuickActions
import com.gatecontrol.android.push.PushNavTarget
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
    fun `other door messages open the door screen`() {
        val auto = msg("plugin:gatecontrol-door:auto", """{"door":{"type":"auto_unlocked","lockId":1}}""")
        assertEquals("auto_unlocked", DoorPush.parse(auto)!!.type)
        assertFalse(DoorPush.parse(auto)!!.isRing)
        assertEquals(DoorDest.Home, DoorPush.destination(auto))
        // A ring without event id, or no data at all: the door screen.
        assertEquals(DoorDest.Home, DoorPush.destination(msg("plugin:gatecontrol-door:doorbell", """{"door":{"type":"ding"}}""")))
        assertEquals(DoorDest.Home, DoorPush.destination(msg("plugin:gatecontrol-door:door", null)))
    }

    @Test
    fun `a person at a home camera opens the door screen with that camera`() {
        val m = msg(
            "plugin:gatecontrol-door:kamera",
            """{"door":{"type":"home_camera_event","cameraId":"c3f9a1b2c4d5e6f7","eventId":"e0123456789abcde"}}""",
            route = null,
        )
        val info = DoorPush.parse(m)!!
        assertTrue(info.isHomeCamera)
        assertFalse(info.isRing)
        assertEquals("c3f9a1b2c4d5e6f7", info.homeCameraId)
        // Home camera ids are not numbers: no Ring camera, no event detail.
        assertNull(info.cameraId)
        assertNull(info.eventId)
        assertEquals(DoorDest.Camera("c3f9a1b2c4d5e6f7"), DoorPush.destination(m))
        assertEquals("camera:c3f9a1b2c4d5e6f7", DoorPush.destination(m)!!.key)
        // A malformed camera id: the door screen.
        val bad = msg("plugin:gatecontrol-door:kamera", """{"door":{"type":"home_camera_event","cameraId":"../x"}}""", route = null)
        assertFalse(DoorPush.parse(bad)!!.isHomeCamera)
        assertEquals(DoorDest.Home, DoorPush.destination(bad))
    }

    @Test
    fun `plugin 1_7_0 the button Ansehen of a person push opens that camera`() {
        val view = PushAction(id = "open", label = "Ansehen", type = PushActionType.OPEN_APP_ROUTE, target = DoorPush.ROUTE)
        val m = msg(
            "plugin:gatecontrol-door:kamera",
            """{"door":{"type":"home_camera_event","cameraId":"c3f9a1b2c4d5e6f7","eventId":"e0123456789abcde"}}""",
        ).copy(actions = listOf(view))
        // The notification: "Ansehen" first (it opens the app), "Gesehen" after it.
        val seen = PushAction(id = "seen", label = "Gesehen", type = PushActionType.ACK)
        val plan = NotificationQuickActions.plan(m.actions, seen)
        assertEquals(ButtonKind.OPEN, plan.first().kind)
        assertEquals("Ansehen", plan.first().action.label)
        // Its intent carries the camera; the route opens the door screen showing it.
        val dest = DoorPush.destination(m)!!.key
        assertEquals("camera:c3f9a1b2c4d5e6f7", dest)
        assertEquals(PushNavTarget.Door(dest, 1), PushNavTarget.forRoute(view.target, m.id, dest))
    }

    @Test
    fun `camera destinations survive the navigation argument`() {
        assertEquals(DoorDest.Camera("c3f9a1b2c4d5e6f7"), DoorDest.parse("camera:c3f9a1b2c4d5e6f7"))
        assertNull(DoorDest.parse("camera:c3F9"))
        assertNull(DoorDest.parse("camera:"))
    }

    @Test
    fun `messages of others are not door messages`() {
        val other = msg("plugin:gatecontrol-familie:sos", """{"door":{"type":"ding","eventId":1}}""", route = "plg-gatecontrol-familie")
        assertNull(DoorPush.parse(other))
        assertNull(DoorPush.destination(other))
        assertFalse(DoorPush.isDoorMessage(other))
    }

    @Test
    fun `the plugin list only says the door plugin is there, its app tab entry is ignored`() {
        val icon = "M5 21V4a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v17M3 21h18M15 12h.01"
        val withTab = PluginAvailability.from(listOf(ClientPlugin("gatecontrol-door", "Tür & Kamera", "1.0.0", ClientPluginTab("Tür", icon))))
        assertTrue(withTab.door)
        assertFalse(withTab.familieTab)
        // Listed without a tab entry: available all the same.
        assertTrue(PluginAvailability.from(listOf(ClientPlugin("gatecontrol-door", "Tür & Kamera", "1.0.0", null))).door)
        assertFalse(PluginAvailability.from(listOf(ClientPlugin("gatecontrol-skoda", "Skoda", "1", null))).door)
        assertFalse(PluginAvailability.NONE.door)
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
