package com.gatecontrol.android.chat

import com.gatecontrol.android.familie.places.GeofenceBudget
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

/**
 * Messenger 1.27: „Nachrichten senden über“ (`replyPref`, `reply.fallback`), the plus menu over every way
 * ([ChatAttachLogic]: GateControl's own kinds to the GateControl way, the location over the way of the text where it
 * takes one), Ortsnachrichten (the objects of `place-messages`, the time window in local time, once an hour, the
 * 30 minutes after the trigger, the question's 15 minutes) and the share of Android's 100 geofences.
 */
class ChatPlaceMessagesTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    // Kim Muster: a GateControl account and a WhatsApp chat
    private fun personJson(replyPref: String = "auto", reply: String = """{"way":"whatsapp","label":"WhatsApp","conversationId":41}""", waSupports: String = """["text","image","file","voice","location"]""", withGc: Boolean = true) =
        """{"id":12,"name":"Kim Muster","nameFrom":"account","userId":5,"conversationId":${if (withGc) 40 else 41},"names":["Kim Muster","Kim M."],
        "replyPref":"$replyPref",
        "ways":[
          ${if (withGc) """{"way":"gatecontrol","label":"GateControl","conversationId":40,"title":"Kim Muster","bridge":null,"can":{"send":true}},""" else ""}
          {"way":"whatsapp","label":"WhatsApp","conversationId":41,"title":"Kim M.","bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","supports":$waSupports},"can":{"send":true}}
        ],
        "reply":$reply}"""

    private fun entry(person: String, id: Long = 40) = ChatJson.conversation(
        json(
            """{"id":$id,"kind":"direct","title":"Kim Muster","peer":{"userId":5,"name":"Kim Muster"},"members":[{"userId":1,"name":"Lea"},{"userId":5,"name":"Kim Muster"}],
            "me":{"role":"member","notify":"all"},"muted":false,"unread":0,"updatedAt":"2026-11-17T10:00:00.000Z","can":{"send":true},"person":$person}""",
        ),
    )!!

    private fun wa(supports: String = """["text","image","file","voice","location"]""") = ChatJson.conversation(
        json(
            """{"id":41,"kind":"direct","title":"Kim M.","members":[{"userId":1,"name":"Lea"}],"me":{"role":"member","notify":"all"},"muted":false,"unread":0,
            "updatedAt":"2026-11-17T10:00:00.000Z","bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","supports":$supports},"can":{"send":true}}""",
        ),
    )!!

    private fun signal() = ChatJson.conversation(
        json(
            """{"id":42,"kind":"direct","title":"Jo Test","members":[{"userId":1,"name":"Lea"}],"me":{"role":"member","notify":"all"},"muted":false,"unread":0,
            "updatedAt":"2026-11-17T10:00:00.000Z","bridge":{"network":"signal","label":"Signal","state":"ok","supports":["text","image","file"]},"can":{"send":true}}""",
        ),
    )!!

    private val on = ChatFeatures(tokens = setOf("chat_people"), placeMessages = true)

    // ─── the feature word ───────────────────────────────────────────

    @Test
    fun `features placeMessages switches the new menu, the way choice and Ortsnachrichten on`() {
        val f = ChatJson.features(json("""{"direct":true,"placeMessages":true,"chat_people":true}"""))
        assertTrue(ChatPlaceMessages.on(f))
        assertFalse(ChatPlaceMessages.on(ChatJson.features(json("""{"direct":true}"""))))
        assertFalse(ChatPlaceMessages.on(null))
        // not a token of its own (upper case): no „newer app“ hint for it
        assertFalse("placeMessages" in f.tokens)
        assertFalse(ChatFeatureHint.show(f, null))
    }

    @Test
    fun `the event of a changed Ortsnachricht reaches the account`() {
        val e = ChatEvents.parse(MESSENGER_PLUGIN, ChatPlaceMessages.EVENT, json("""{"id":3,"reason":"fired"}"""))
        assertEquals(ChatEvent.AccountChanged(ChatPlaceMessages.EVENT), e)
        assertNull(ChatEvents.parse(MESSENGER_PLUGIN, "placeMessages.changed", json("""{"id":3}""")))
    }

    // ─── replyPref ──────────────────────────────────────────────────

    @Test
    fun `replyPref and reply fallback are read and written`() {
        val p = ChatPeople.person(json(personJson("whatsapp", """{"way":"gatecontrol","label":"GateControl","conversationId":40,"fallback":true}""")))!!
        assertEquals("whatsapp", p.replyPref)
        assertTrue(p.reply!!.fallback)
        assertEquals("WhatsApp", ChatPeople.fallbackFrom(p))
        val again = ChatPeople.person(ChatPeople.write(p))!!
        assertEquals(p, again)
        // without the word (an older messenger): automatic, no fallback
        val old = ChatPeople.person(json(personJson().replace("\"replyPref\":\"auto\",", "")))!!
        assertEquals(ChatPeople.REPLY_AUTO, old.replyPref)
        assertNull(ChatPeople.fallbackFrom(old))
        // a way the person does not have: automatic
        assertEquals(ChatPeople.REPLY_AUTO, ChatPeople.person(json(personJson("signal")))!!.replyPref)
    }

    @Test
    fun `the choices are the ways the person has, GateControl first`() {
        val p = ChatPeople.person(json(personJson()))!!
        assertEquals(listOf("auto", "gatecontrol", "whatsapp"), ChatPeople.replyChoices(p))
        assertEquals("""{"replyPref":"gatecontrol"}""", ChatPeople.replyPrefBody("gatecontrol").toString())
        val one = ChatPeople.person(json(personJson(withGc = false, reply = """{"way":"whatsapp","label":"WhatsApp","conversationId":41}""")))!!
        assertTrue(ChatPeople.replyChoices(one).isEmpty())
    }

    @Test
    fun `the placeholder follows person reply, an answer its message's way`() {
        val p = ChatPeople.person(json(personJson("gatecontrol", """{"way":"gatecontrol","label":"GateControl","conversationId":40}""")))!!
        assertEquals("GateControl", ChatPeople.composerWay(p, null, null))
        assertEquals(40L, ChatPeople.sendTo(p, null))
    }

    // ─── the plus menu ──────────────────────────────────────────────

    @Test
    fun `text over WhatsApp - everything stays, GateControl's kinds carry the logo, the location goes over WhatsApp`() {
        val c = entry(personJson())
        val plan = ChatAttachLogic.plan(40, c.person, wa(), on, camera = true)
        assertTrue(plan.gallery && plan.camera && plan.files)
        assertEquals(ChatAttachRoute(40, badge = true), plan.gateControl)
        assertEquals(ChatAttachRoute(41, badge = false), plan.location)
        assertTrue(plan.placeMessage)
    }

    @Test
    fun `a bridge without location - the location goes over GateControl with the logo`() {
        val c = entry(personJson(waSupports = """["text","image"]"""))
        val plan = ChatAttachLogic.plan(40, c.person, wa("""["text","image"]"""), on, camera = false)
        assertEquals(ChatAttachRoute(40, badge = true), plan.location)
        assertFalse(plan.camera)
        assertFalse(plan.files)
    }

    @Test
    fun `text over GateControl - no logo, everything to GateControl`() {
        val c = entry(personJson("gatecontrol", """{"way":"gatecontrol","label":"GateControl","conversationId":40}"""))
        val plan = ChatAttachLogic.plan(40, c.person, c, on, camera = true)
        assertEquals(ChatAttachRoute(40, badge = false), plan.gateControl)
        assertEquals(ChatAttachRoute(40, badge = false), plan.location)
    }

    @Test
    fun `a person without a GateControl way - no GateControl kinds, the location only where the way takes it`() {
        val p = ChatPeople.person(json(personJson(withGc = false)))!!
        val plan = ChatAttachLogic.plan(41, p, wa(), on, camera = true)
        assertNull(plan.gateControl)
        assertEquals(ChatAttachRoute(41, badge = false), plan.location)
        val noLoc = ChatAttachLogic.plan(41, ChatPeople.person(json(personJson(withGc = false, waSupports = """["text"]""")))!!, wa("""["text"]"""), on, camera = true)
        assertNull(noLoc.location)
        assertFalse(noLoc.gallery)
    }

    @Test
    fun `a single Signal chat - no location (only a link there), no GateControl kinds`() {
        val s = signal()
        val plan = ChatAttachLogic.plan(42, null, s, on, camera = true)
        assertNull(plan.location)
        assertNull(plan.gateControl)
        assertTrue(plan.gallery && plan.files)
    }

    @Test
    fun `a plain GateControl chat and the location switched off`() {
        val c = entry(personJson(), id = 40).copy(person = null)
        val plan = ChatAttachLogic.plan(40, null, c, on, camera = true)
        assertEquals(ChatAttachRoute(40, badge = false), plan.gateControl)
        assertEquals(ChatAttachRoute(40, badge = false), plan.location)
        assertNull(ChatAttachLogic.plan(40, null, c, on.copy(location = false), camera = true).location)
    }

    @Test
    fun `an older messenger - the menu as before`() {
        val legacy = ChatAttachLogic.legacy(40, wa(), ChatFeatures(), camera = true)
        assertNull(legacy.location)
        assertNull(legacy.gateControl)
        assertFalse(legacy.placeMessage)
        val own = ChatAttachLogic.legacy(40, null, ChatFeatures(), camera = true)
        assertEquals(ChatAttachRoute(40, false), own.gateControl)
    }

    @Test
    fun `incoming bridge location is a normal location message`() {
        val m = ChatJson.message(
            json(
                """{"id":512,"conversationId":41,"senderId":null,"kind":"location","mine":false,"createdAt":"2026-11-17T16:00:00.000Z","text":null,
                "location":{"lat":51.31,"lon":7.41,"label":null},"reactions":[],"way":"whatsapp",
                "bridge":{"sender":{"contactId":7,"name":"Kim Muster"},"state":null,"reason":null,"placeholder":null,"remote":false,"channel":false}}""",
            ),
        )!!
        assertEquals(ChatMessage.KIND_LOCATION, m.kind)
        assertEquals(51.31, m.location!!.lat)
        assertNull(m.location!!.label)
        assertFalse(m.isUnknownKind)
    }

    // ─── Ortsnachrichten: the objects ───────────────────────────────

    private val pmJson = """{"id":3,"deviceId":"android-0011223344556677","trigger":"enter",
        "place":{"familieId":7,"name":"Zuhause","lat":51.2345,"lon":7.6543,"radiusM":150},
        "text":"Bin da, Tür ist offen.","conversationId":40,"way":"auto","repeat":"always",
        "window":{"days":[1,2,3,4,5],"from":"16:00","to":"20:00"},"ask":true,"enabled":true,"lastFiredAt":null,"doneAt":null,
        "createdAt":"2026-11-17T10:00:00.000Z","updatedAt":"2026-11-17T10:00:00.000Z",
        "target":{"title":"Kim Muster","personId":12,"reply":{"way":"whatsapp","label":"WhatsApp","conversationId":41}}}"""

    @Test
    fun `an Ortsnachricht is read, kept and written as the server sends it`() {
        val l = ChatPlaceMessages.list(json("""{"placeMessages":[$pmJson],"limits":{"activeMax":10,"textMax":1000,"radiusMinM":100,"radiusMaxM":2000,"radiusDefaultM":150,"everyS":3600,"lateS":1800}}"""))!!
        val p = l.items.single()
        assertEquals("7", p.place.familieId)
        assertEquals(ChatPmWindow(listOf(1, 2, 3, 4, 5), "16:00", "20:00"), p.window)
        assertEquals("WhatsApp", p.target?.reply?.label)
        assertTrue(p.active)
        assertEquals(10, l.limits.activeMax)
        assertEquals(l, ChatPlaceMessages.list(ChatPlaceMessages.writeList(l)))
        // the body of a change: familieId as the number it came as
        val body = ChatPlaceMessages.body(ChatPlaceMessages.draftOf(p), "android-0011223344556677")!!
        assertEquals(7, body.getAsJsonObject("place").get("familieId").asInt)
        assertEquals("auto", body.get("way").asString)
        assertTrue(body.get("ask").asBoolean)
    }

    @Test
    fun `the form's defaults and what it cannot send yet`() {
        val d = ChatPmDraft()
        assertTrue(d.ask)
        assertEquals(ChatPlaceMessages.ONCE, d.repeat)
        assertNull(ChatPlaceMessages.body(d, "android-x"))
        val place = ChatPlaceMessages.fromFamilie(ChatPlace("7", "Zuhause", 51.0, 7.0, 50))!!
        assertEquals(100, place.radiusM)
        assertEquals(2000, ChatPlaceMessages.fromFamilie(ChatPlace("8", "Wald", 51.0, 7.0, 5000))!!.radiusM)
        assertEquals(150, ChatPlaceMessages.fromFamilie(ChatPlace("9", "Schule", 51.0, 7.0, null))!!.radiusM)
        assertNull(ChatPlaceMessages.fromFamilie(ChatPlace("10", "Ohne Punkt", null, null, null)))
        assertNull(ChatPlaceMessages.body(d.copy(place = place, conversationId = 40, text = "   "), "android-x"))
        assertNotNull(ChatPlaceMessages.body(d.copy(place = place, conversationId = 40, text = "Bin da"), "android-x"))
    }

    @Test
    fun `the place line of an own message`() {
        val m = ChatJson.message(
            json(
                """{"id":600,"conversationId":41,"senderId":1,"kind":"text","mine":true,"createdAt":"2026-11-17T16:58:00.000Z","text":"Bin da, Tür ist offen.","reactions":[],
                "placeMessage":{"name":"Zuhause","trigger":"enter"}}""",
            ),
        )!!
        assertEquals(ChatPlaceMark("Zuhause", "enter"), m.placeMessage)
        assertNull(ChatJson.message(json("""{"id":601,"conversationId":41,"kind":"text","mine":false,"createdAt":"2026-11-17T16:58:00.000Z","text":"x","reactions":[],"placeMessage":null}"""))!!.placeMessage)
    }

    // ─── the time window (local time) ───────────────────────────────

    private val workdays = ChatPmWindow(listOf(1, 2, 3, 4, 5), "16:00", "20:00")

    @Test
    fun `within the window on the days, from up to but not including to`() {
        // 2026-11-17 is a Tuesday
        assertTrue(ChatPlaceMessages.inWindow(workdays, LocalDateTime.of(2026, 11, 17, 16, 0)))
        assertTrue(ChatPlaceMessages.inWindow(workdays, LocalDateTime.of(2026, 11, 17, 19, 59)))
        assertFalse(ChatPlaceMessages.inWindow(workdays, LocalDateTime.of(2026, 11, 17, 20, 0)))
        assertFalse(ChatPlaceMessages.inWindow(workdays, LocalDateTime.of(2026, 11, 17, 15, 59)))
        // Saturday
        assertFalse(ChatPlaceMessages.inWindow(workdays, LocalDateTime.of(2026, 11, 21, 17, 0)))
        assertTrue(ChatPlaceMessages.inWindow(null, LocalDateTime.of(2026, 11, 21, 3, 0)))
    }

    @Test
    fun `a window over midnight belongs to the day it starts on`() {
        val night = ChatPmWindow(listOf(5), "22:00", "06:00")
        // Friday 23:00 and Saturday 05:00: yes; Saturday 23:00: no; Friday 05:00 (Thursday's night): no
        assertTrue(ChatPlaceMessages.inWindow(night, LocalDateTime.of(2026, 11, 20, 23, 0)))
        assertTrue(ChatPlaceMessages.inWindow(night, LocalDateTime.of(2026, 11, 21, 5, 0)))
        assertFalse(ChatPlaceMessages.inWindow(night, LocalDateTime.of(2026, 11, 21, 23, 0)))
        assertFalse(ChatPlaceMessages.inWindow(night, LocalDateTime.of(2026, 11, 20, 5, 0)))
        assertFalse(ChatPlaceMessages.inWindow(night, LocalDateTime.of(2026, 11, 21, 12, 0)))
        // Sunday's night reaches into Monday
        assertTrue(ChatPlaceMessages.inWindow(ChatPmWindow(listOf(7), "22:00", "06:00"), LocalDateTime.of(2026, 11, 23, 1, 0)))
        // from = to: the whole day
        assertTrue(ChatPlaceMessages.inWindow(ChatPmWindow(listOf(2), "08:00", "08:00"), LocalDateTime.of(2026, 11, 17, 3, 0)))
    }

    @Test
    fun `days in a row read as one run`() {
        assertEquals(1 to 5, ChatPlaceMessages.dayRun(listOf(5, 1, 2, 3, 4)))
        assertNull(ChatPlaceMessages.dayRun(listOf(1, 3)))
        assertNull(ChatPlaceMessages.dayRun(listOf(6)))
    }

    // ─── once an hour, 30 minutes, 15 minutes ───────────────────────

    @Test
    fun `at most once an hour`() {
        val t = 1_000_000_000L
        assertFalse(ChatPlaceMessages.tooSoon(null, t))
        assertTrue(ChatPlaceMessages.tooSoon(t, t))
        assertTrue(ChatPlaceMessages.tooSoon(t, t + 59 * 60_000L))
        assertFalse(ChatPlaceMessages.tooSoon(t, t + 60 * 60_000L))
        // a clock set back: not too soon for ever
        assertFalse(ChatPlaceMessages.tooSoon(t, t - 1))
    }

    @Test
    fun `without a network up to 30 minutes, then dropped`() {
        val at = 2_000_000_000L
        assertFalse(ChatPlaceMessages.late(at, at + 30 * 60_000L))
        assertTrue(ChatPlaceMessages.late(at, at + 30 * 60_000L + 1))
        assertFalse(ChatPlaceMessages.askExpired(at, at + 15 * 60_000L))
        assertTrue(ChatPlaceMessages.askExpired(at, at + 15 * 60_000L + 1))
    }

    @Test
    fun `what fires - the own, active ones of the trigger, in the window, not within the hour`() {
        val base = ChatPlaceMessages.item(json(pmJson))!!
        val now = LocalDateTime.of(2026, 11, 17, 17, 0)
        val nowMs = Instant.parse("2026-11-17T16:00:00Z").toEpochMilli()
        val dev = "android-0011223344556677"
        val items = listOf(
            base,
            base.copy(id = 4, trigger = ChatPlaceMessages.EXIT),
            base.copy(id = 5, deviceId = "android-ffffffffffffffff"),
            base.copy(id = 6, enabled = false),
            base.copy(id = 7, doneAt = "2026-11-16T10:00:00.000Z"),
            base.copy(id = 8, lastFiredAt = "2026-11-17T15:30:00.000Z"),
            base.copy(id = 9, window = ChatPmWindow(listOf(6, 7), "16:00", "20:00")),
            base.copy(id = 10),
        )
        val due = ChatPlaceMessages.due(items, listOf(3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), ChatPlaceMessages.ENTER, dev, now, nowMs, mapOf(10L to nowMs - 10 * 60_000L))
        assertEquals(listOf(3L), due.map { it.id })
        assertTrue(ChatPlaceMessages.due(items, listOf(3L), ChatPlaceMessages.ENTER, dev, now, nowMs, mapOf(3L to nowMs - 61 * 60_000L)).isNotEmpty())
    }

    @Test
    fun `the request id of a geofence and the device id`() {
        assertEquals("pm:3", ChatPlaceMessages.requestId(3))
        assertEquals(3L, ChatPlaceMessages.idOf("pm:3"))
        assertNull(ChatPlaceMessages.idOf("nz:3"))
        assertNull(ChatPlaceMessages.idOf("familie-place-3"))
        assertTrue(ChatPlaceMessages.isDeviceId("android-0011223344556677"))
        assertFalse(ChatPlaceMessages.isDeviceId("Android Handy"))
    }

    // ─── Android's 100 geofences ────────────────────────────────────

    @Test
    fun `the Ortsnachrichten take a share of 10, the family comes first, the hub gets the rest`() {
        assertEquals(10, GeofenceBudget.placeMessages(0))
        assertEquals(10, GeofenceBudget.placeMessages(85))
        assertEquals(5, GeofenceBudget.placeMessages(90))
        assertEquals(60, GeofenceBudget.notizen(0, 10))
        assertEquals(35, GeofenceBudget.notizen(50, 10))
        assertEquals(0, GeofenceBudget.notizen(90, 5))
        for (family in 0..GeofenceBudget.FAMILY_MAX) {
            val pm = GeofenceBudget.placeMessages(family)
            val hub = GeofenceBudget.notizen(family, pm)
            assertTrue(family + pm + hub + GeofenceBudget.RESERVE + GeofenceBudget.MOVE_FENCE <= GeofenceBudget.ANDROID_MAX, "family $family")
        }
        assertEquals(listOf("a", "b"), GeofenceBudget.keep(listOf("a", "b", "c"), 90, 3))
    }

    @Test
    fun `the phone watches its own active ones, the oldest first, within its share`() {
        val base = ChatPlaceMessages.item(json(pmJson))!!
        val dev = base.deviceId
        val items = (1..14L).map { base.copy(id = it, createdAt = "2026-11-%02dT10:00:00.000Z".format(it)) } + base.copy(id = 99, deviceId = "android-ffffffffffffffff")
        val w = ChatPlaceMessages.watched(items, dev, GeofenceBudget.placeMessages(0))
        assertEquals((1..10L).toList(), w.map { it.id })
        assertTrue(ChatPlaceMessages.watched(items, dev, 0).isEmpty())
    }
}
