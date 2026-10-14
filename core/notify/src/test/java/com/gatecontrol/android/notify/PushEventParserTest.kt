package com.gatecontrol.android.notify

import com.gatecontrol.android.notify.model.PushActionType
import com.gatecontrol.android.notify.model.PushPriority
import com.gatecontrol.android.notify.model.QuietHours
import com.gatecontrol.android.notify.model.QuietInfo
import com.gatecontrol.android.notify.model.ServerControl
import com.gatecontrol.android.notify.model.ServerState
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
    fun `the data object is kept as JSON text for app features`() {
        val e = PushEventParser.parse(
            "notification", "7",
            """{"seq":7,"id":8,"topic":"plugin:gatecontrol-familie:locate","title":"Anna möchte wissen, wo du bist",
               "data":{"route":"plg-gatecontrol-familie","familie":{"type":"locate_request","requestId":12,"mode":"confirm","from":"Anna"}}}""",
        )
        val m = assertInstanceOf(PushEvent.Notification::class.java, e).message
        assertEquals("plg-gatecontrol-familie", m.route)
        assertTrue(m.dataJson!!.contains("\"locate_request\""))
        val noData = assertInstanceOf(PushEvent.Notification::class.java, PushEventParser.parse("notification", "9", """{"seq":9,"id":9,"title":"x"}""")).message
        assertNull(noData.dataJson)
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

    // ─── Phase 5: control events, hello.state, quiet hours ──────────────

    private fun control(json: String) = PushEventParser.parse("control", null, json)

    @Test
    fun `control policy_changed carries the opaque version`() {
        assertEquals(
            PushEvent.Control(ServerControl.PolicyChanged("3f2a9c0b7d1e4a65")),
            control("""{"type":"policy_changed","policy_version":"3f2a9c0b7d1e4a65"}"""),
        )
        // Lenient: a number is kept as its text.
        assertEquals(
            PushEvent.Control(ServerControl.PolicyChanged("12")),
            control("""{"type":"policy_changed","policy_version":12}"""),
        )
        // Missing or odd version: still a change, version unknown.
        assertEquals(PushEvent.Control(ServerControl.PolicyChanged(null)), control("""{"type":"policy_changed"}"""))
        assertEquals(
            PushEvent.Control(ServerControl.PolicyChanged(null)),
            control("""{"type":"policy_changed","policy_version":"<script>"}"""),
        )
    }

    @Test
    fun `control support_bundle_requested keeps a plausible timestamp only`() {
        assertEquals(
            PushEvent.Control(ServerControl.SupportBundleRequested("2026-10-11 08:15:00")),
            control("""{"type":"support_bundle_requested","requested_at":"2026-10-11 08:15:00"}"""),
        )
        // ISO-8601 is accepted as well.
        assertEquals(
            PushEvent.Control(ServerControl.SupportBundleRequested("2026-10-11T08:15:00.000Z")),
            control("""{"type":"support_bundle_requested","requested_at":"2026-10-11T08:15:00.000Z"}"""),
        )
        assertEquals(
            PushEvent.Control(ServerControl.SupportBundleRequested(null)),
            control("""{"type":"support_bundle_requested","requested_at":"drop table"}"""),
        )
    }

    @Test
    fun `control peer_state needs a boolean`() {
        assertEquals(PushEvent.Control(ServerControl.PeerState(false)), control("""{"type":"peer_state","enabled":false}"""))
        assertEquals(PushEvent.Control(ServerControl.PeerState(true)), control("""{"type":"peer_state","enabled":true}"""))
        assertInstanceOf(PushEvent.Ignored::class.java, control("""{"type":"peer_state"}"""))
        assertInstanceOf(PushEvent.Ignored::class.java, control("""{"type":"peer_state","enabled":"no"}"""))
    }

    @Test
    fun `control config_changed, unknown types and garbage`() {
        assertEquals(PushEvent.Control(ServerControl.ConfigChanged), control("""{"type":"config_changed"}"""))
        assertEquals(PushEvent.Ignored("control", "unknown_control"), control("""{"type":"reboot"}"""))
        assertInstanceOf(PushEvent.Ignored::class.java, control("not json"))
        assertInstanceOf(PushEvent.Ignored::class.java, control("[1,2]"))
    }

    @Test
    fun `hello state is parsed with every field`() {
        val hello = PushEventParser.parse(
            "hello", null,
            """{"keepalive_s":25,"via":"direct","state":{"policy_version":"3f2a9c0b7d1e4a65","peer_enabled":true,
               "support_bundle_requested":true,"support_bundle_requested_at":"2026-10-11 08:15:00","config_hash":"9f86d081884c7d65"}}""",
        ) as PushEvent.Hello
        assertEquals(ServerState("3f2a9c0b7d1e4a65", true, true, "2026-10-11 08:15:00", "9f86d081884c7d65"), hello.state)
        // Token without a bound peer: global policy, peer off, nothing else.
        val noPeer = PushEventParser.parse(
            "hello", null,
            """{"state":{"policy_version":"0123456789abcdef","peer_enabled":false,"support_bundle_requested":false,
               "support_bundle_requested_at":null,"config_hash":null}}""",
        ) as PushEvent.Hello
        assertEquals(ServerState("0123456789abcdef", false, false, null, null), noPeer.state)
    }

    @Test
    fun `hello state with missing fields leaves them unknown`() {
        val partial = PushEventParser.parse("hello", null, """{"state":{"peer_enabled":false,"config_hash":null}}""") as PushEvent.Hello
        assertEquals(ServerState(peerEnabled = false), partial.state)
        val empty = PushEventParser.parse("hello", null, """{"state":{}}""") as PushEvent.Hello
        assertEquals(ServerState(), empty.state)
        // Older server: no state at all.
        val old = PushEventParser.parse("hello", null, """{"keepalive_s":25}""") as PushEvent.Hello
        assertNull(old.state)
        val notAnObject = PushEventParser.parse("hello", null, """{"state":"yes"}""") as PushEvent.Hello
        assertNull(notAnObject.state)
    }

    @Test
    fun `hello quiet hours map to known, none and unknown`() {
        fun quiet(json: String) = (PushEventParser.parse("hello", null, json) as PushEvent.Hello).quiet
        assertEquals(
            QuietInfo(true, QuietHours("22:00", "07:00", "Europe/Berlin", true)),
            quiet("""{"quiet":{"from":"22:00","to":"07:00","tz":"Europe/Berlin","critical_bypass":true}}"""),
        )
        assertEquals(
            QuietInfo(true, QuietHours("23:30", "06:00", null, false)),
            quiet("""{"quiet":{"from":"23:30","to":"06:00","critical_bypass":false}}"""),
        )
        assertEquals(QuietInfo.NONE, quiet("""{"quiet":null}"""))
        assertEquals(QuietInfo.NONE, quiet("""{"quiet":{"from":"22:00","to":"22:00","tz":"UTC","critical_bypass":true}}"""))
        assertEquals(QuietInfo.UNKNOWN, quiet("""{"keepalive_s":25}"""))
        assertEquals(QuietInfo.UNKNOWN, quiet("""{"quiet":{"from":"25:00","to":"07:00"}}"""))
        assertEquals(QuietInfo.UNKNOWN, quiet("""{"quiet":"22-7"}"""))
    }

    @Test
    fun `prefs answer quiet hours use the same mapping`() {
        val o = com.google.gson.JsonParser.parseString(
            """{"ok":true,"enabled":true,"mode":"always","muted_topics":[],"restricted":false,
               "quiet":{"from":"22:00","to":"07:00","tz":"Europe/Berlin","critical_bypass":true}}""",
        ).asJsonObject
        assertEquals("22:00", PushEventParser.quietInfo(o).hours?.from)
        val old = com.google.gson.JsonParser.parseString("""{"ok":true,"enabled":true}""").asJsonObject
        assertEquals(QuietInfo.UNKNOWN, PushEventParser.quietInfo(old))
    }
}
