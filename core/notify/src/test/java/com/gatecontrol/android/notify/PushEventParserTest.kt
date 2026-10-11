package com.gatecontrol.android.notify

import com.gatecontrol.android.notify.model.PushActionType
import com.gatecontrol.android.notify.model.PushPriority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PushEventParserTest {

    private val gatewayOffline = """
        {"seq":123,"id":45,"event_id":"gateway_state","topic":"devices","priority":"critical",
         "title":"Gateway „Zuhause“ ist offline","body":"Seit 2 Minuten kein Lebenszeichen …",
         "created_at":"2026-10-10T21:42:03Z","expires_at":"2026-10-13T21:42:03Z",
         "collapse_key":"gateway:3","silent":false,
         "data":{"route":"gateways","actions":[{"id":"details","label":"Details","type":"open_app_route","target":"gateways"},
                                               {"id":"mute_1h","label":"1 h stumm","type":"mute_1h"}]}}
    """.trimIndent()

    @Test
    fun `hello carries keepalive, via and topics including admin-only system`() {
        val e = PushEventParser.parse(
            "hello", null,
            """{"server_time":"2026-10-10T21:42:03.120Z","keepalive_s":25,"retention_h":72,"via":"direct","unread":3,
               "topics":[{"id":"security","label":"Sicherheit"},{"id":"system","label":"System"},
                         {"id":"plugin:skoda:charging","label":"Fahrzeug · Laden abgeschlossen"},{"id":"bogus topic","label":"x"}]}""",
        )
        val hello = assertInstanceOf(PushEvent.Hello::class.java, e)
        assertEquals(25, hello.keepaliveS)
        assertEquals("direct", hello.via)
        assertEquals(3, hello.unread)
        assertEquals(listOf("security", "system", "plugin:skoda:charging"), hello.topics.map { it.id })
        assertTrue(hello.topics.last().isPlugin)
    }

    @Test
    fun `notification is parsed with actions and collapse key`() {
        val e = PushEventParser.parse("notification", "123", gatewayOffline)
        val m = assertInstanceOf(PushEvent.Notification::class.java, e).message
        assertEquals(123L, m.seq)
        assertEquals(45L, m.id)
        assertEquals("devices", m.topic)
        assertEquals(PushPriority.CRITICAL, m.priorityLevel)
        assertEquals("gateway:3", m.collapseKey)
        assertEquals("gateways", m.route)
        assertEquals(listOf("details", "mute_1h"), m.actions.map { it.id })
        assertEquals(PushActionType.OPEN_APP_ROUTE, m.actions[0].type)
        assertEquals("gateways", m.actions[0].target)
        assertTrue(m.isUnread)
        assertFalse(m.silent)
    }

    @Test
    fun `seq falls back to the SSE id`() {
        val e = PushEventParser.parse("notification", "77", """{"id":9,"topic":"services","title":"Fotos ist wieder erreichbar"}""")
        val m = assertInstanceOf(PushEvent.Notification::class.java, e).message
        assertEquals(77L, m.seq)
        assertEquals(PushPriority.NORMAL, m.priorityLevel)
    }

    @Test
    fun `actions outside the fixed list and foreign targets are dropped`() {
        val e = PushEventParser.parse(
            "notification", "5",
            """{"seq":5,"id":2,"topic":"admin_notice","title":"T","data":{"actions":[
                {"id":"x","label":"Evil","type":"open_url","target":"https://evil.example"},
                {"id":"p","label":"Portal","type":"open_portal","target":"//evil.example/x"},
                {"id":"q","label":"Portal","type":"open_portal","target":"https://evil.example"},
                {"id":"r","label":"Route","type":"open_app_route","target":"../../etc"},
                {"id":"ok","label":"Portal","type":"open_portal","target":"/portal/notify"},
                {"id":"d","label":"Erledigt","type":"done"}]}}""",
        )
        val m = assertInstanceOf(PushEvent.Notification::class.java, e).message
        assertEquals(listOf("ok", "d"), m.actions.map { it.id })
        assertEquals("/portal/notify", m.actions[0].target)
    }

    @Test
    fun `text is plain and capped`() {
        val e = PushEventParser.parse(
            "notification", "5",
            """{"seq":5,"id":2,"topic":"devices","title":"A\u0007B\nC${"x".repeat(200)}","body":"line1\nline2\u0000"}""",
        )
        val m = assertInstanceOf(PushEvent.Notification::class.java, e).message
        assertTrue(m.title.startsWith("AB C"))
        assertEquals(120, m.title.length)
        assertEquals("line1\nline2", m.body)
    }

    @Test
    fun `unknown topic falls back to admin notice, missing title is rejected`() {
        val ok = PushEventParser.parse("notification", "5", """{"seq":5,"id":2,"topic":"../x","title":"Hi"}""")
        assertEquals("admin_notice", assertInstanceOf(PushEvent.Notification::class.java, ok).message.topic)
        val bad = PushEventParser.parse("notification", "6", """{"seq":6,"id":3,"title":"  "}""")
        assertInstanceOf(PushEvent.Ignored::class.java, bad)
    }

    @Test
    fun `read and revoke carry notification ids`() {
        assertEquals(listOf(45L, 46L), (PushEventParser.parse("read", null, """{"ids":[45,46,45,-1]}""") as PushEvent.Read).ids)
        assertEquals(listOf(45L), (PushEventParser.parse("revoke", null, """{"ids":[45]}""") as PushEvent.Revoke).ids)
    }

    @Test
    fun `unknown and malformed events are ignored`() {
        assertInstanceOf(PushEvent.Ignored::class.java, PushEventParser.parse("policy", null, "{}"))
        assertInstanceOf(PushEvent.Ignored::class.java, PushEventParser.parse("notification", "1", "not json"))
        assertInstanceOf(PushEvent.Ignored::class.java, PushEventParser.parse(null, null, "hello"))
    }

    @Test
    fun `inbox item keeps its state`() {
        val dto = com.google.gson.Gson().fromJson(
            """{"seq":3,"id":1,"topic":"services","title":"T","state":"read"}""",
            com.gatecontrol.android.network.PushNotificationDto::class.java,
        )
        val m = PushEventParser.toMessage(dto)!!
        assertFalse(m.isUnread)
        assertNull(m.collapseKey)
    }
}
