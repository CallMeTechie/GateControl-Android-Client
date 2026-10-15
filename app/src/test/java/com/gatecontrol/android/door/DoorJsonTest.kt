package com.gatecontrol.android.door

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/** Every answer shape of the door plugin's app API (README "App API"). */
class DoorJsonTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val me = json(
        """
        {"ok":true,
         "me":{"userId":3,"name":"Marc","canAct":true,"autoUnlock":false,
               "window":{"days":[1,2,3,4,5],"from":"07:00","to":"20:00"},"windowOpen":true},
         "locks":[
           {"id":1,"name":"Haustür","type":"smartlock","right":"lock","actions":["unlock","lock"],"blocked":null,
            "confirm":["unlock"],"doorSensor":true,
            "state":{"lock":"locked","door":"closed","batteryCritical":false,"batteryPercent":80,"batteryCharging":false,
                     "reachable":true,"updatedAt":"2026-10-14T08:00:00Z"}},
           {"id":2,"name":"Hoftor","type":"opener","right":"open","actions":["open"],"blocked":"outside_window",
            "confirm":["open"],"doorSensor":false,
            "state":{"lock":"online","door":null,"batteryCritical":false,"batteryPercent":null,"batteryCharging":false,
                     "reachable":true,"updatedAt":null}},
           {"id":"x","name":"broken"},
           {"id":5,"name":"No right","right":"admin","actions":["open"]}
         ],
         "cameras":[
           {"id":4,"name":"Klingel Haustür","kind":"doorbell_v4","doorbell":true,"lockId":1,"online":true,"battery":87,
            "prefs":{"ding":true,"motion":false},"lastImageAt":"2026-10-14T07:59:40Z"},
           {"id":6,"name":"Garten","kind":null,"doorbell":false,"lockId":null,"online":null,"battery":null,
            "prefs":{"ding":true,"motion":true},"lastImageAt":null}
         ],
         "ring":{"connected":true,"broken":false},
         "serverTime":"2026-10-14T08:00:00Z"}
        """,
    )

    @Test
    fun `me is parsed with person, locks, cameras and ring`() {
        val o = DoorJson.overview(me)!!
        assertEquals(3L, o.me.userId)
        assertEquals("Marc", o.me.name)
        assertTrue(o.me.canAct)
        assertFalse(o.me.autoUnlock)
        assertEquals(DoorWindow(listOf(1, 2, 3, 4, 5), "07:00", "20:00"), o.me.window)
        assertTrue(o.me.windowOpen)
        assertEquals(listOf(1L, 2L), o.locks.map { it.id })
        assertTrue(o.ring.connected)
        assertFalse(o.ring.broken)
        assertEquals("2026-10-14T08:00:00Z", o.serverTime)

        val front = o.locks[0]
        assertEquals("Haustür", front.name)
        assertEquals(DoorLock.TYPE_SMARTLOCK, front.type)
        assertEquals(DoorRight.LOCK, front.right)
        // Fixed order, whatever the server sent.
        assertEquals(listOf("lock", "unlock"), front.actions)
        assertNull(front.blocked)
        assertEquals(listOf("unlock"), front.confirm)
        assertTrue(front.doorSensor)
        assertEquals(DoorLockState("locked", "closed", false, 80, false, true, "2026-10-14T08:00:00Z"), front.state)

        val gate = o.locks[1]
        assertTrue(gate.isOpener)
        assertEquals(DoorBlocked.OUTSIDE_WINDOW, gate.blocked)
        assertNull(gate.state.door)
        assertNull(gate.state.batteryPercent)
        assertNull(gate.state.updatedAt)

        val bell = o.cameras[0]
        assertEquals(4L, bell.id)
        assertTrue(bell.doorbell)
        assertEquals(1L, bell.lockId)
        assertEquals(true, bell.online)
        assertEquals(87, bell.battery)
        assertEquals(DoorCameraPrefs(ding = true, motion = false), bell.prefs)
        assertEquals("2026-10-14T07:59:40Z", bell.lastImageAt)
        val garden = o.cameras[1]
        assertNull(garden.online)
        assertNull(garden.lockId)
        assertNull(garden.kind)
        assertNull(garden.lastImageAt)
    }

    @Test
    fun `without me the answer is unusable, without window the person has none`() {
        assertNull(DoorJson.overview(json("""{"ok":true,"locks":[]}""")))
        val o = DoorJson.overview(json("""{"me":{"userId":9,"canAct":"yes","window":null}}"""))!!
        assertNull(o.me.window)
        // Unknown means no: a malformed canAct never enables buttons.
        assertFalse(o.me.canAct)
        assertTrue(o.locks.isEmpty())
        assertFalse(o.ring.connected)
    }

    @Test
    fun `security first - unknown blocked values, actions and states are handled safely`() {
        val l = DoorJson.lock(
            json(
                """{"id":7,"name":"Keller","type":"weird","right":"open","actions":["open","explode","open"],
                   "blocked":"maintenance","confirm":["open","x"],"doorSensor":1,"state":{"lock":"exploded","door":"ajar","reachable":1}}""",
            ),
        )!!
        assertEquals(DoorLock.TYPE_SMARTLOCK, l.type)
        assertEquals(listOf("open"), l.actions)
        assertEquals("maintenance", l.blocked)
        assertEquals(listOf("open"), l.confirm)
        assertTrue(l.doorSensor)
        assertEquals("unknown", l.state.lock)
        assertEquals("unknown", l.state.door)
        assertTrue(l.state.reachable)

        val garbageBlocked = DoorJson.lock(json("""{"id":8,"right":"lock","actions":["lock"],"blocked":42}"""))!!
        assertEquals("unknown", garbageBlocked.blocked)
        assertEquals("#8", garbageBlocked.name)
        // Missing state: not reachable, unknown.
        assertFalse(garbageBlocked.state.reachable)
        assertEquals("unknown", garbageBlocked.state.lock)
        assertNull(DoorJson.lock(json("""{"id":-1,"right":"lock"}""")))
        assertNull(DoorJson.lock(json("""{"id":1,"right":"owner"}""")))
    }

    @Test
    fun `names are plain text`() {
        val l = DoorJson.lock(json("""{"id":1,"name":"Haus\u0000tür\n","right":"view","actions":[]}"""))!!
        assertEquals("Haus tür", l.name)
    }

    @Test
    fun `action answer carries the message and the new lock`() {
        val r = DoorJson.actionResult(
            json("""{"ok":true,"message":"Haustür aufgesperrt","lock":{"id":1,"name":"Haustür","right":"lock","actions":["lock","unlock"],"state":{"lock":"unlocked","reachable":true}}}"""),
        )
        assertEquals("Haustür aufgesperrt", r.message)
        assertEquals("unlocked", r.lock!!.state.lock)
    }

    @Test
    fun `snapshot is decoded from base64, oversized or broken pictures are refused`() {
        val bytes = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val s = DoorJson.snapshot(json("""{"ok":true,"snapshot":{"cameraId":4,"mime":"image/jpeg","size":7,"at":"2026-10-14T08:00:05Z","data":"$b64"}}"""))!!
        assertEquals(4L, s.cameraId)
        assertEquals("image/jpeg", s.image.mime)
        assertEquals("2026-10-14T08:00:05Z", s.image.at)
        assertArrayEquals(bytes, s.image.bytes)

        assertNull(DoorJson.snapshot(json("""{"snapshot":{"cameraId":4,"mime":"image/gif","data":"$b64"}}""")))
        assertNull(DoorJson.snapshot(json("""{"snapshot":{"cameraId":4,"mime":"image/jpeg","data":"%%%not base64"}}""")))
        assertNull(DoorJson.snapshot(json("""{"snapshot":{"cameraId":4,"mime":"image/jpeg","data":""}}""")))
        val huge = Base64.getEncoder().encodeToString(ByteArray(DoorJson.MAX_IMAGE_BYTES + 10))
        assertNull(DoorJson.image(json("""{"mime":"image/png","data":"$huge"}""")))
    }

    @Test
    fun `prefs answer`() {
        assertEquals(DoorCameraPrefs(true, true), DoorJson.prefs(json("""{"ok":true,"prefs":{"ding":true,"motion":true}}""")))
        assertNull(DoorJson.prefs(json("""{"ok":true}""")))
    }

    @Test
    fun `history page with every kind and the cursor`() {
        val p = DoorJson.history(
            json(
                """{"ok":true,"nextCursor":118,"items":[
                  {"id":120,"at":"2026-10-14T08:00:00Z","kind":"action","lockId":1,"lockName":"Haustür","cameraId":null,"cameraName":null,
                   "userId":3,"userName":"Marc","via":"app","caller":null,"action":"unlock","result":"ok","reason":null,"reasonText":null,"hasImage":false},
                  {"id":119,"at":"2026-10-14T07:59:40Z","kind":"ding","lockId":1,"lockName":"Haustür","cameraId":4,"cameraName":"Klingel",
                   "userId":null,"userName":null,"via":"ring","caller":null,"action":null,"result":null,"reason":null,"reasonText":null,"hasImage":true},
                  {"id":117,"at":"2026-10-14T07:00:00Z","kind":"action","lockId":1,"via":"automation","caller":"gatecontrol-familie",
                   "action":"unlock","result":"refused","reason":"door_open","reasonText":"Tür ist offen","hasImage":false},
                  {"id":116,"kind":"alert","action":"night"},
                  {"id":115,"kind":"unknown"}
                ]}""",
            ),
        )!!
        assertEquals(118L, p.nextCursor)
        assertEquals(listOf(120L, 119L, 117L, 116L), p.items.map { it.id })
        val ring = p.items[1]
        assertTrue(ring.hasDetail)
        assertTrue(ring.hasImage)
        assertEquals("Klingel", ring.cameraName)
        assertFalse(p.items[0].hasDetail)
        val refused = p.items[2]
        assertEquals("refused", refused.result)
        assertEquals("door_open", refused.reason)
        assertEquals("Tür ist offen", refused.reasonText)
        assertEquals("gatecontrol-familie", refused.caller)
        assertNull(DoorJson.history(json("""{"ok":true,"nextCursor":null,"items":[]}"""))!!.nextCursor)
        assertNull(DoorJson.history(json("""{"ok":true}""")))
    }

    @Test
    fun `event detail with picture and lock, or without`() {
        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val e = DoorJson.event(
            json(
                """{"ok":true,"event":{"id":119,"at":"2026-10-14T07:59:40Z","kind":"ding","lockId":1,"lockName":"Haustür","cameraId":4,"cameraName":"Klingel",
                   "hasImage":true,"image":{"mime":"image/jpeg","size":3,"at":"2026-10-14T07:59:41Z","data":"$b64"},
                   "lock":{"id":1,"name":"Haustür","type":"smartlock","right":"open","actions":["lock","unlock","open"],"blocked":null,"confirm":["unlock","open"],
                           "doorSensor":false,"state":{"lock":"locked","reachable":true}}}}""",
            ),
        )!!
        assertEquals(119L, e.item.id)
        assertNotNull(e.image)
        assertEquals(DoorRight.OPEN, e.lock!!.right)
        assertTrue(DoorLogic.canOpenFromEvent(e.lock))

        val bare = DoorJson.event(json("""{"ok":true,"event":{"id":5,"kind":"motion","image":null,"lock":null}}"""))!!
        assertNull(bare.image)
        assertNull(bare.lock)
        assertFalse(DoorLogic.canOpenFromEvent(bare.lock))
        assertNull(DoorJson.event(json("""{"ok":true}""")))
    }

    @Test
    fun `errors carry code, wait and the plugin's text`() {
        val e = json("""{"ok":false,"code":"too_soon","error":"Bitte 12 s warten","retryAfter":12}""")
        assertEquals("too_soon", DoorJson.errorCode(e))
        assertEquals(12, DoorJson.retryAfter(e))
        assertEquals("Bitte 12 s warten", DoorJson.errorText(e))
        val field = json("""{"ok":false,"code":"invalid","error":"ungültig","field":"ding"}""")
        assertEquals("ding", DoorJson.errorField(field))
        assertNull(DoorJson.errorCode(json("""{"ok":false,"code":"Not A Code"}""")))
        assertNull(DoorJson.retryAfter(json("""{"retryAfter":-3}""")))
        assertNull(DoorJson.errorCode(null))
    }
}
