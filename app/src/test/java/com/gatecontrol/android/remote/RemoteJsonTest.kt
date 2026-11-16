package com.gatecontrol.android.remote

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/** The answers and signals of the plugin „Fernzugriff“ 1.3.0 for this phone (contract „Fernzugriff Stufe 5“ §5). */
class RemoteJsonTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject
    private val ref = "A".repeat(43)
    private val exp = "2026-11-16T10:00:00.000Z"
    private val expMs = Instant.parse(exp).toEpochMilli()

    @Test
    fun `register, code, state and share mode`() {
        assertEquals(ScreenHost(100004, "code", true, "ready"), RemoteJson.host(json("""{"ok":true,"host":{"deviceId":100004,"platform":"android","shareMode":"code","allowOpen":true,"state":"ready"},"codeRequired":true}""")))
        assertEquals("open", RemoteJson.host(json("""{"host":{"deviceId":1,"shareMode":"open","allowOpen":false}}"""))!!.shareMode)
        assertNull(RemoteJson.host(json("""{"host":{"deviceId":0}}""")))
        assertEquals(ScreenCode("482-917-305", expMs), RemoteJson.code(json("""{"code":"482-917-305","expiresAt":"$exp"}""")))
        assertNull(RemoteJson.code(json("""{"code":"48291730","expiresAt":"$exp"}""")))
        assertNull(RemoteJson.code(json("""{"code":"482-917-305"}""")))
        val st = RemoteJson.state(json("""{"shareMode":"open","allowOpen":true,"pending":[{"requestId":"rq_abcdefgh12","from":{"userId":2,"name":"Tom Berger","avatarUrl":"/avatars/2.webp"},"mode":"view","via":"browser","kind":"screen","expiresAt":"$exp"},{"requestId":"bad"}]}"""))!!
        assertEquals("open", st.shareMode)
        assertEquals(listOf(ScreenRequest("rq_abcdefgh12", "Tom Berger", "/avatars/2.webp", expMs)), st.pending)
        assertEquals("code", RemoteJson.shareMode(json("""{"shareMode":"code"}""")))
        assertNull(RemoteJson.shareMode(json("""{"shareMode":"all"}""")))
    }

    @Test
    fun `a request — checked id, a clean name, only a relative picture, only for the screen`() {
        fun req(extra: String) = RemoteJson.request(json("""{"requestId":"rq_abcdefgh12","from":{"name":"Tom\u0007 Berger"$extra},"expiresAt":"$exp"}"""))
        assertEquals("Tom Berger", req("")!!.fromName)
        assertNull(req(""","avatarUrl":"https://evil.example/x"""")!!.avatarUrl)
        assertNull(req(""","avatarUrl":"//evil.example/x"""")!!.avatarUrl)
        assertNull(RemoteJson.request(json("""{"requestId":"rq_abcdefgh12","kind":"rdp","from":{"name":"Tom"},"expiresAt":"$exp"}""")), "a PC's request is not ours")
        assertNull(RemoteJson.request(json("""{"requestId":"rq_a/../b","from":{"name":"Tom"},"expiresAt":"$exp"}""")))
        assertNull(RemoteJson.request(json("""{"requestId":"rq_abcdefgh12","from":{},"expiresAt":"$exp"}""")))
    }

    @Test
    fun `the sending side of an allowed request and the core's token`() {
        assertEquals(ScreenTicket(ref, expMs), RemoteJson.ticket(json("""{"request":{"state":"allowed"},"screen":{"ref":"$ref","expiresAt":"$exp","wsPath":"/api/v1/remote/screen-relay"}}""")))
        assertNull(RemoteJson.ticket(json("""{"request":{"state":"denied"}}""")))
        assertNull(RemoteJson.ticket(json("""{"screen":{"ref":"short"}}""")))
        assertEquals(ScreenToken(ref, "/api/v1/remote/screen-relay"), RemoteJson.token(json("""{"ok":true,"token":"$ref","wsPath":"/api/v1/remote/screen-relay","ttl":60,"role":"sender"}""")))
        assertNull(RemoteJson.token(json("""{"ok":true,"token":"$ref","wsPath":"/api/v1/remote/screen-relay","role":"viewer"}""")), "never the viewer's side")
        assertNull(RemoteJson.token(json("""{"ok":true,"token":"$ref","wsPath":"https://evil/x"}""")))
        assertEquals("no_share_right", RemoteJson.errorCode(json("""{"ok":false,"code":"no_share_right","error":"Du darfst …"}""")))
        assertEquals("not_found", RemoteJson.errorCode(json("""{"ok":false,"error":"not_found"}""")))
    }

    @Test
    fun `the signals of the plugin`() {
        val r = RemoteJson.signal("""{"type":"remote.request","requestId":"rq_abcdefgh12","from":{"name":"Tom"},"mode":"view","via":"app","kind":"screen","expiresAt":"$exp"}""")
        assertEquals(ScreenSignal.Request(ScreenRequest("rq_abcdefgh12", "Tom", null, expMs)), r)
        assertEquals(ScreenSignal.Cancel("rq_abcdefgh12"), RemoteJson.signal("""{"type":"remote.request.cancel","requestId":"rq_abcdefgh12"}"""))
        assertEquals(ScreenSignal.CodeVoid, RemoteJson.signal("""{"type":"remote.code.void"}"""))
        assertEquals(ScreenSignal.End("rq_abcdefgh12", "viewer"), RemoteJson.signal("""{"type":"remote.screen.end","requestId":"rq_abcdefgh12","reason":"viewer"}"""))
        assertNull(RemoteJson.signal("""{"type":"remote.firewall.open","sessionKey":"fw_x"}"""), "a PC's signal")
        assertNull(RemoteJson.signal("nope"))
        assertNull(RemoteJson.signal(null))
        assertEquals("plugin:gatecontrol-remote:remote_signal", RemoteJson.SIGNAL_TOPIC)
    }

    @Test
    fun `stage 6 — a request for control only where this build can control, the granted mode of the answer`() {
        val asked = com.google.gson.JsonParser.parseString(
            """{"requestId":"rq_abcdefgh12","from":{"name":"Tom"},"mode":"control","kind":"screen","expiresAt":"2026-11-16T10:00:00Z"}""",
        ).asJsonObject
        assertEquals(ScreenControl.supported, RemoteJson.request(asked)!!.control)
        val view = com.google.gson.JsonParser.parseString(
            """{"requestId":"rq_abcdefgh12","from":{"name":"Tom"},"mode":"view","kind":"screen","expiresAt":"2026-11-16T10:00:00Z"}""",
        ).asJsonObject
        assertEquals(false, RemoteJson.request(view)!!.control)
        val granted = com.google.gson.JsonParser.parseString("""{"request":{"state":"allowed","mode":"control"}}""").asJsonObject
        assertEquals(ScreenControl.supported, RemoteJson.grantedControl(granted))
        assertEquals(false, RemoteJson.grantedControl(com.google.gson.JsonParser.parseString("""{"request":{"mode":"view"}}""").asJsonObject))
    }
}
