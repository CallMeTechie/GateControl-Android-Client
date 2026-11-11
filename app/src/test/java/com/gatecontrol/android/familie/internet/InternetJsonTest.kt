package com.gatecontrol.android.familie.internet

import com.gatecontrol.android.familie.presence.PresenceState
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** „Internet zu Hause“ and the Wi-Fi detection: the answers of the contract (plugin 1.20) and of older plugins. */
class InternetJsonTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val answer = """{"ok":true,"internet":{"available":true,"manage":true,"serverTime":"2026-11-11T16:52:10Z","timezone":"Europe/Berlin","children":[
      {"userId":4,"name":"Lena","avatarUrl":null,"color":"amber","colorHex":"#EAB308","initials":"LE",
       "presence":{"state":"home","since":"2026-11-11T15:40:00Z","source":"wifi"},
       "paused":null,"pausedBy":null,"extended":null,"extendedBy":null,
       "nextSchedule":{"from":"2026-11-11T20:00:00Z","to":"2026-11-12T06:00:00Z","block":"categories","categories":["video","social","games","porn"],"active":false},
       "profile":"Kinder","devices":2,"filtered":true,"error":null},
      {"userId":5,"name":"Tim","avatarUrl":"/avatars/AbCdEfGhIjKlMnOpQrStUv.webp","color":"orange","colorHex":"#F97316","initials":"TI",
       "presence":{"state":"home","since":"2026-11-11T16:05:00Z","source":"app"},
       "paused":"2026-11-11T18:00:00Z","pausedBy":{"userId":2,"name":"Anna","at":"2026-11-11T17:30:00Z","rule":{"id":12,"title":"Abendessen"}},
       "extended":null,"extendedBy":null,"nextSchedule":null,"profile":"Kinder","devices":2,"filtered":true,"error":null},
      {"userId":6,"name":"Mia","presence":{"state":"away","since":"2026-11-11T14:10:00Z","source":"app"},
       "paused":null,"pausedBy":null,"extended":"2026-11-11T20:30:00Z","extendedBy":{"userId":3,"name":"Marc","at":"2026-11-11T16:50:00Z","rule":null},
       "nextSchedule":{"from":"2026-11-11T20:00:00Z","to":"2026-11-12T06:00:00Z","block":"all","categories":[],"active":false},
       "profile":"Kleine","devices":1,"filtered":true,"error":null},
      {"userId":7,"name":"Ben","presence":{"state":"not_shared","since":null,"source":null},
       "paused":null,"pausedBy":null,"extended":null,"extendedBy":null,"nextSchedule":null,"profile":null,"devices":0,"filtered":false,"error":null},
      {"userId":8,"name":"Jo","error":"unavailable"},
      {"name":"no id"}, {"userId":4,"name":"twice"}
    ]}}"""

    @Test
    fun `the parents' view of the contract`() {
        val p = InternetJson.payload(json(answer))!!
        assertTrue(p.available && p.manage && p.visible)
        assertEquals("Europe/Berlin", p.timezone)
        assertEquals(listOf(4, 5, 6, 7, 8), p.children.map { it.userId }, "no id and a second entry of a child are dropped")

        val lena = p.child(4)!!
        assertEquals(InternetPresence(PresenceState.HOME, "2026-11-11T15:40:00Z", "wifi"), lena.presence)
        assertEquals("#EAB308", lena.color)
        assertEquals("LE", lena.face?.initials)
        assertEquals(2, lena.devices)
        assertTrue(lena.filtered)
        val s = lena.nextSchedule!!
        assertFalse(s.all)
        assertEquals(listOf("social", "games", "video"), s.categories, "known categories in the app's order, unknown ones dropped")

        val tim = p.child(5)!!
        assertEquals("/avatars/AbCdEfGhIjKlMnOpQrStUv.webp", tim.face?.avatarUrl)
        assertEquals(InternetBy(2, "Anna", "2026-11-11T17:30:00Z", ruleId = 12, ruleTitle = "Abendessen"), tim.pausedBy)
        assertTrue(tim.pausedBy!!.byRule)

        val mia = p.child(6)!!
        assertEquals("2026-11-11T20:30:00Z", mia.extended)
        assertFalse(mia.extendedBy!!.byRule)
        assertTrue(mia.nextSchedule!!.all)

        val ben = p.child(7)!!
        assertEquals(PresenceState.NOT_SHARED, ben.presence?.state)
        assertFalse(ben.filtered)
        assertEquals("unavailable", p.child(8)!!.error)
    }

    @Test
    fun `a rule without a title, a pause set elsewhere and a plugin before 1_20`() {
        val untitled = InternetJson.child(json("""{"userId":5,"name":"Tim","paused":"2026-11-11T18:00:00Z","pausedBy":{"userId":2,"name":"Anna","at":"2026-11-11T17:30:00Z","rule":{"id":12,"title":""}},"filtered":true}"""))!!
        assertEquals(12, untitled.pausedBy?.ruleId)
        assertEquals("", untitled.pausedBy?.ruleTitle)
        // Set by an admin: nobody of the family.
        val admin = InternetJson.child(json("""{"userId":5,"name":"Tim","paused":"2026-11-11T18:00:00Z","pausedBy":null,"filtered":true}"""))!!
        assertNull(admin.pausedBy)
        // 1.18: no presence, no rule, no serverTime — "not there", not an error.
        val old = InternetJson.payload(json("""{"ok":true,"internet":{"available":true,"manage":false,"children":[{"userId":5,"name":"Tim","paused":null,"pausedBy":{"userId":2,"name":"Anna","at":"2026-11-11T17:30:00Z"},"devices":"2","filtered":true}]}}"""))!!
        assertFalse(old.manage)
        assertNull(old.serverTime)
        assertNull(old.timezone)
        val c = old.children.single()
        assertNull(c.presence)
        assertNull(c.pausedBy?.ruleId)
        assertEquals(0, InternetJson.child(json("""{"userId":5,"name":"Tim","devices":-3}"""))!!.devices)
    }

    @Test
    fun `a server without internet at home`() {
        val off = InternetJson.payload(json("""{"ok":true,"internet":{"available":false,"manage":true,"children":[{"userId":5,"name":"Tim"}],"serverTime":"2026-11-11T16:52:10Z","timezone":"Europe/Berlin"}}"""))!!
        assertFalse(off.available)
        assertFalse(off.visible)
        assertTrue(off.children.isEmpty())
        assertNull(InternetJson.payload(json("""{"ok":true}""")), "not this API")
        // Parents without a minor child: nothing to show.
        assertFalse(InternetJson.payload(json("""{"ok":true,"internet":{"available":true,"manage":true,"children":[]}}"""))!!.visible)
    }

    @Test
    fun `the answer of a change and the bodies`() {
        val child = InternetJson.childAnswer(json("""{"ok":true,"child":{"userId":5,"name":"Tim","paused":"2026-11-11T17:22:10Z","pausedBy":{"userId":2,"name":"Anna","at":"2026-11-11T16:52:10Z","rule":null},"devices":2,"filtered":true,"error":null}}"""))!!
        assertEquals("2026-11-11T17:22:10Z", child.paused)
        assertNull(InternetJson.childAnswer(json("""{"ok":true}""")))

        assertEquals(json("""{"minutes":30}"""), InternetJson.pauseBody(PauseRequest.Minutes(30)))
        assertEquals(json("""{"until":"18:30"}"""), InternetJson.pauseBody(PauseRequest.Until("18:30")))
        assertEquals(json("""{"add":30}"""), InternetJson.pauseBody(PauseRequest.Add(30)))
        assertEquals(json("""{"minutes":10080}"""), InternetJson.pauseBody(PauseRequest.Minutes(99_999)), "at most a week")
        assertEquals(json("""{"minutes":30}"""), InternetJson.extendBody(30))
        assertEquals(json("""{"minutes":30,"fromNow":true}"""), InternetJson.extendBody(30, fromNow = true))
        assertEquals(json("""{"minutes":0}"""), InternetJson.extendBody(0), "0 ends the extra time")
    }

    @Test
    fun `my wifi detection and my own devices`() {
        val w = InternetJson.wifi(json("""{"ok":true,"wifi":{"available":true,"consent":true,"since":"2026-11-01T09:12:00Z","devices":[
          {"id":41,"name":"Lenas Handy","lastSeen":"2026-11-11T16:50:00Z","counts":true,"gateway":"Pi Keller"},
          {"id":42,"name":"Lenas iPad","lastSeen":null,"counts":false,"gateway":""},{"name":"no id"}]}}"""))!!
        assertTrue(w.available && w.consent)
        assertEquals(
            listOf(WifiDevice(41, "Lenas Handy", "2026-11-11T16:50:00Z", true, "Pi Keller"), WifiDevice(42, "Lenas iPad", null, false, null)),
            w.devices,
        )
        val off = InternetJson.wifi(json("""{"ok":true,"wifi":{"available":false,"consent":false,"since":null,"devices":null}}"""))!!
        assertFalse(off.available)
        assertTrue(off.devices.isEmpty())
        assertNull(InternetJson.wifi(json("""{"ok":true}""")), "a plugin before 1.17")
        assertEquals(json("""{"enabled":true}"""), InternetJson.wifiBody(true))
        assertEquals(json("""{"counts":false}"""), InternetJson.wifiDeviceBody(false))
        assertEquals("wifi", InternetJson.source("wifi"))
        assertNull(InternetJson.source("wlan"), "the wire value is wifi")
    }
}
