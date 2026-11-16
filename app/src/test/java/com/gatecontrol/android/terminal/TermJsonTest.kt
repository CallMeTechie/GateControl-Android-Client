package com.gatecontrol.android.terminal

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** The plugin's and the core's answers for the SSH terminal (app contract „Fernzugriff Stufe 3 – Apps“ §1–2). */
class TermJsonTest {
    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject
    private val ref = "B".repeat(43)

    @Test
    fun `devices keeps SSH only, engine and tmux are checked`() {
        val r = TermJson.devices(json("""{"devices":[
            {"id":1,"name":"Büro-PC","protocol":"rdp","online":true},
            {"id":2,"name":"pi-werkstatt","protocol":"ssh","online":true,"engine":"term","tmux":"tmux","recording":"optional","tags":["Werkstatt"]},
            {"id":3,"name":"nas-keller","protocol":"ssh","online":true,"engine":"guac","tmux":"x"},
            {"id":4,"name":"alt","protocol":"ssh","online":false},
            {"id":5,"name":"bad tmux","protocol":"ssh","engine":"term","tmux":"a.b","recording":"sometimes"},
            {"id":0,"name":"no id","protocol":"ssh"},
            {"id":6,"name":"\u0007","protocol":"ssh"},
            {"id":2,"name":"duplicate","protocol":"ssh"}
          ],"rights":{"ssh":true,"rdp":false}}"""))!!
        assertEquals(listOf(2L, 3L, 4L, 5L), r.devices.map { it.id })
        val pi = r.devices[0]
        assertTrue(pi.native)
        assertEquals("tmux", pi.tmux)
        assertEquals("optional", pi.recording)
        assertEquals(listOf("Werkstatt"), pi.tags)
        // Guacamole: no native terminal, no tmux
        assertFalse(r.devices[1].native)
        assertNull(r.devices[1].tmux)
        // older plugin: no engine → browser only
        assertNull(r.devices[2].engine)
        // a tmux name the core cannot use, an unknown policy → none / off
        assertNull(r.devices[3].tmux)
        assertEquals("off", r.devices[3].recording)
        assertTrue(r.sshRight)
        assertFalse(TermJson.devices(json("""{"devices":[],"rights":{"ssh":false}}"""))!!.sshRight)
        assertNull(TermJson.devices(json("""{"ok":true}""")))
    }

    @Test
    fun `tmux list offers only attachable names`() {
        val l = TermJson.tmux(json("""{"sessions":[
            {"name":"tmux","windows":3,"attached":0,"created":"2026-11-16T08:00:00Z","activity":"2026-11-16T09:58:00Z","attachable":true},
            {"name":"build","windows":1,"attached":1,"activity":"2026-11-16T09:00:00Z","attachable":true},
            {"name":"backup.alt","windows":1,"attached":0,"attachable":false},
            {"name":"sneaky.x","windows":1,"attached":0,"attachable":true}
          ],"tmux":"tmux","hostKey":"pinned"}"""))!!
        assertEquals("pinned", l.hostKey)
        assertEquals(listOf("tmux", "build"), TermProtocol.resumable(l).map { it.name })
        // the plugin says attachable, the name does not: not offered either
        assertEquals(2, TermProtocol.hidden(l))
        assertEquals(Instant.parse("2026-11-16T09:58:00Z").toEpochMilli(), l.sessions[0].activity)
        assertEquals(1, l.sessions[1].attached)
        val u = TermJson.tmux(json("""{"sessions":[],"tmux":"tmux","hostKey":"unpinned"}"""))!!
        assertEquals("unpinned", u.hostKey)
        assertNull(TermJson.tmux(json("""{"tmux":"tmux"}""")))
    }

    @Test
    fun `ticket of engine term only`() {
        val t = TermJson.ticket(json("""{"session":{"ref":"$ref","expiresAt":"2026-11-16T10:02:00.000Z","engine":"term","recording":true,"tmux":{"attach":"build"},"device":{"id":2,"name":"pi-werkstatt"}}}"""))!!
        assertEquals(ref, t.ref)
        assertTrue(t.recording)
        assertEquals("build", t.tmux)
        assertEquals(2L, t.deviceId)
        assertEquals("pi-werkstatt", t.deviceName)
        assertNull(TermJson.ticket(json("""{"session":{"ref":"$ref","engine":"guac"}}""")))
        assertNull(TermJson.ticket(json("""{"session":{"ref":"short","engine":"term"}}""")))
    }

    @Test
    fun `token needs a ref-like token and a remote ws path`() {
        assertEquals(TermToken(ref, "/api/v1/remote/ssh-tunnel"), TermJson.token(json("""{"ok":true,"token":"$ref","wsPath":"/api/v1/remote/ssh-tunnel","ttl":60}""")))
        assertNull(TermJson.token(json("""{"ok":true,"token":"$ref","wsPath":"https://evil.example/x"}""")))
        assertNull(TermJson.token(json("""{"ok":false,"error":"not_found"}""")))
    }

    @Test
    fun `connect body follows the recording policy`() {
        assertEquals("""{"via":"app","protocol":"ssh","tmux":{"attach":"tmux"}}""", TermJson.connectBody(TmuxChoice.Attach("tmux"), "off", true).toString())
        assertEquals("""{"via":"app","protocol":"ssh","tmux":{"create":"tmux-2"},"record":false}""", TermJson.connectBody(TmuxChoice.Create("tmux-2"), "optional", false).toString())
        assertEquals("""{"via":"app","protocol":"ssh","record":true}""", TermJson.connectBody(null, "always", false).toString())
    }
}
