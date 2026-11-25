package com.gatecontrol.android.media.server

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `v1/sources` and the other answers of the plugin „Medien“ (App-API v1, example of the contract). */
class MediaServerJsonTest {

    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    private val example = """
        { "ok": true, "v": 1, "rev": "rVdEa3gRkVX1W", "at": "2026-11-25T19:02:11.000Z",
          "media": true, "maxOfflineDays": 90, "offlineHintDays": 30,
          "network": { "rule": "home_direct_away_vpn", "vpnRequiredAway": true,
            "home": { "probes": [
              { "kind": "jellyfin", "url": "http://192.168.1.20:8096/System/Info/Public", "serverId": "4f1ca9" },
              { "kind": "tls", "url": "https://192.168.1.20:5006/filme", "certSha256": "${"9c41".repeat(16)}" },
              { "kind": "jellyfin", "url": "http://media.example.test/System/Info/Public", "serverId": "x" },
              { "kind": "tcp", "host": "192.168.1.20", "port": 445, "url": "tcp://192.168.1.20:445" },
              { "kind": "other", "url": "http://192.168.1.20" } ] } },
          "cast": { "enabled": true },
          "future": { "x": 1 },
          "sources": [
            { "id": "jf-1", "kind": "jellyfin", "name": "DS918+", "serverId": "4f1ca9", "version": "10.10.7",
              "address": "http://192.168.1.20:8096", "home": { "address": "http://192.168.1.20:8096" },
              "away": { "mode": "vpn" }, "user": { "id": "a71e", "name": "mila" }, "problem": null,
              "device": { "id": "gc-7Hq2mXk4Rb9tLp0sVw3N", "name": "GateControl · Pixel von Mila" },
              "paired": false, "quickConnect": true, "downloads": true, "alwaysOriginal": false },
            { "id": "sh-1", "kind": "share", "name": "Filme", "folder": "filme",
              "smb": { "address": "smb://192.168.1.20/filme" },
              "webdav": { "address": "https://192.168.1.20:5006/filme", "certSha256": "${"9C:41:".repeat(16).trimEnd(':')}" },
              "user": "medien-lesen", "secretRev": 3, "away": { "mode": "vpn" } },
            { "id": "jf-2", "kind": "jellyfin", "name": "Büro", "address": "http://192.168.1.30:8096",
              "away": { "mode": "direct", "address": "https://jellyfin.example.test" }, "user": null, "problem": "no_mapping", "device": null },
            { "id": "jf-3", "kind": "jellyfin", "name": "öffentlich ohne TLS", "address": "http://media.example.test:8096" },
            { "id": "../x", "kind": "jellyfin", "name": "x", "address": "http://192.168.1.20:8096" },
            { "id": "sh-2", "kind": "share", "name": "leer", "smb": null, "webdav": null }
          ] }
    """.trimIndent()

    @Test
    fun `the contract's example - sources, network rule and probes`() {
        val l = MediaServerJson.list(obj(example))!!
        assertEquals("rVdEa3gRkVX1W", l.rev)
        assertEquals(1795633331000L, l.at)
        assertTrue(l.media)
        assertEquals(90, l.maxOfflineDays)
        assertEquals(30, l.offlineHintDays)
        assertTrue(l.cast)
        assertTrue(l.network.vpnRequiredAway)
        // a probe outside the home network, a tcp probe (any device with the address passes it) and an unknown kind are left out
        assertEquals(2, l.network.probes.size)
        assertEquals(MediaHomeProbe.Jellyfin("http://192.168.1.20:8096/System/Info/Public", "4f1ca9"), l.network.probes[0])
        assertEquals("9c41".repeat(16), (l.network.probes[1] as MediaHomeProbe.Tls).certSha256)
        assertEquals(listOf("jf-1", "sh-1", "jf-2"), l.sources.map { it.id })

        val jf = l.sources[0]
        assertEquals(MediaServerSource.Kind.JELLYFIN, jf.kind)
        assertEquals("http://192.168.1.20:8096", jf.homeAddress)
        assertEquals("a71e", jf.userId)
        assertEquals("gc-7Hq2mXk4Rb9tLp0sVw3N", jf.deviceId)
        assertEquals("GateControl · Pixel von Mila", jf.deviceName)
        assertNull(jf.awayAddress, "away only through the VPN")

        val sh = l.sources[1]
        assertEquals("smb://192.168.1.20/filme", sh.smb)
        assertEquals("https://192.168.1.20:5006/filme", sh.webdav)
        assertEquals("9c41".repeat(16), sh.certSha256, "colons and capitals do not matter")
        assertEquals("medien-lesen", sh.shareUser)
        assertEquals(3, sh.secretRev)

        val direct = l.sources[2]
        assertEquals("https://jellyfin.example.test", direct.awayAddress)
        assertEquals("no_mapping", direct.problem)
        assertNull(direct.userId)
        assertNull(direct.deviceId)
    }

    @Test
    fun `unchanged since the rev, no right, a refusal`() {
        val u = MediaServerJson.list(obj("""{"ok":true,"v":1,"rev":"r2","unchanged":true,"at":"2026-11-25T19:02:11.000Z"}"""))!!
        assertTrue(u.unchanged)
        assertEquals("r2", u.rev)
        val none = MediaServerJson.list(obj("""{"ok":true,"v":1,"rev":"r3","media":false,"sources":[{"id":"jf-1","kind":"jellyfin","address":"http://192.168.1.20:8096"}]}"""))!!
        assertFalse(none.media)
        assertTrue(none.sources.isEmpty())
        assertNull(MediaServerJson.list(obj("""{"ok":false,"code":"x"}""")))
        assertNull(MediaServerJson.list(obj("""{"ok":true,"rev":"bad rev with spaces"}""")))

        assertTrue(MediaServerJson.error(404, obj("""{"ok":false,"code":"not_found","reason":"plugin_unavailable"}""")).unavailable)
        assertTrue(MediaServerJson.error(403, obj("""{"ok":false,"v":1,"code":"license_required","reason":"not_own"}""")).unavailable)
        assertFalse(MediaServerJson.error(403, obj("""{"ok":false,"v":1,"code":"no_access"}""")).unavailable)
        assertEquals("no_mapping", MediaServerJson.error(409, obj("""{"ok":false,"v":1,"code":"no_mapping"}""")).code)
    }

    @Test
    fun `away direct only with HTTPS`() {
        val s = MediaServerJson.source(obj("""{"id":"jf-1","kind":"jellyfin","address":"http://192.168.1.20:8096","away":{"mode":"direct","address":"http://jellyfin.example.test"}}"""))!!
        assertNull(s.awayAddress)
        val share = MediaServerJson.source(obj("""{"id":"sh-1","kind":"share","name":"Filme","webdav":{"address":"https://192.168.1.20:5006/filme"},"away":{"mode":"direct","address":"https://nas.example.test/filme"}}"""))!!
        assertEquals("https://nas.example.test/filme", share.awayAddress)
    }

    @Test
    fun `the secret and the revocation`() {
        val s = MediaServerJson.secret(obj("""{"ok":true,"v":1,"user":"medien-lesen","password":" pw ","rev":4}"""))!!
        assertEquals(" pw ", s.password, "a password is taken as it is")
        assertEquals(4, s.rev)
        assertFalse(s.toString().contains("pw"))

        val one = MediaServerJson.revoke(obj("""{"kind":"media.revoke","sourceIds":["jf-1","../x"],"reason":"right"}"""))!!
        assertEquals(listOf("jf-1"), one.sourceIds)
        assertEquals("right", one.reason)
        assertNull(MediaServerJson.revoke(obj("""{"kind":"media.revoke","sourceIds":null,"reason":"device_removed"}"""))!!.sourceIds)
        assertNull(MediaServerJson.revoke(obj("""{"kind":"media.cast"}""")))
        assertNull(MediaServerJson.revoke(JsonParser.parseString("[1]")))
    }
}
