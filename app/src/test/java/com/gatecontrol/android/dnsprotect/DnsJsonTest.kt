package com.gatecontrol.android.dnsprotect

import com.gatecontrol.android.network.PermissionsResponse
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnsJsonTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    /** The example of the contract (plugin gatecontrol-dns 1.0.0, `GET status`). */
    private val contractStatus = """
        { "ok": true, "state": "active", "enabled": true,
          "device": { "peerId": 21, "name": "Leas Handy", "platform": "android", "state": "filtered", "ownDns": null },
          "profile": { "id": "p1a2b3c4d", "name": "Kinder", "safeSearch": true },
          "pause": null, "outage": null,
          "counters": { "scope": "device", "queries": 3310, "blocked": 893, "percent": 27 },
          "top": [{ "domain": "ads.tiktok.com", "count": 148 }],
          "may": { "pause": true, "resume": false, "extend": false, "reason": null,
                   "durations": [5, 30, 60], "maxMinutes": 60, "forever": false },
          "today": [{ "from": "21:00", "to": "07:00", "block": "categories", "categories": ["social", "games", "video"] }],
          "devices": [], "manage": false, "log": { "enabled": true, "visible": true },
          "serverTime": "2026-11-12T13:48:00.000Z" }
    """.trimIndent()

    @Test
    fun `the status of the contract`() {
        val s = DnsJson.status(json(contractStatus))!!
        assertEquals("active", s.state)
        assertEquals(DnsDevice(21, "Leas Handy", "filtered", null), s.device)
        assertEquals("Kinder", s.profileName)
        assertNull(s.pause)
        assertNull(s.outageSince)
        assertEquals(DnsCounters("device", 3310, 893, 27.0), s.counters)
        assertEquals(listOf(DnsTopDomain("ads.tiktok.com", 148)), s.top)
        assertEquals(DnsMay(pause = true, durations = listOf(5, 30, 60), maxMinutes = 60), s.may)
        assertEquals(listOf(DnsScheduleEntry("21:00", "07:00", "categories", listOf("social", "games", "video"))), s.today)
        assertFalse(s.manage)
        assertEquals("2026-11-12T13:48:00.000Z", s.serverTime)
    }

    @Test
    fun `a suspended device, the filter down, a token without a peer`() {
        val paused = DnsJson.status(
            json(
                """{ "state": "paused", "device": null, "top": null,
                     "pause": { "scope": "peer", "until": "2026-11-12T14:12:00.000Z", "openEnd": false, "since": "2026-11-12T13:42:00.000Z",
                                "by": { "userId": 2, "name": "Marc" }, "byMe": true, "remainingSeconds": 1450 },
                     "may": { "pause": false, "resume": true, "extend": true, "reason": null, "durations": [5, 30, 60], "maxMinutes": 60, "forever": false } }""",
            ),
        )!!
        assertNull(paused.device)
        assertNull(paused.top, "no list delivered: the section is left out")
        assertEquals(DnsPause("peer", "2026-11-12T14:12:00.000Z", false, "2026-11-12T13:42:00.000Z", "Marc", true, 1450), paused.pause)
        assertTrue(paused.may.resume)

        val open = DnsJson.status(json("""{ "state": "paused", "pause": { "scope": "all", "until": null, "openEnd": true, "by": null, "byMe": false, "remainingSeconds": null } }"""))!!
        assertEquals(DnsPause(scope = "all", openEnd = true), open.pause)

        val down = DnsJson.status(json("""{ "state": "fallback", "outage": { "since": "2026-11-12T13:31:00.000Z" }, "may": { "pause": false, "reason": "fallback", "durations": [] } }"""))!!
        assertEquals("2026-11-12T13:31:00.000Z", down.outageSince)
        assertEquals("fallback", down.may.reason)
        assertEquals(emptyList<Int>(), down.may.durations)
    }

    @Test
    fun `odd answers never throw`() {
        assertNull(DnsJson.status(json("{}")), "no state: not a status")
        val s = DnsJson.status(json("""{ "state": "active", "device": "x", "counters": [], "top": [1, { "count": 3 }, { "domain": "a.b" }], "may": { "durations": [5, "x", -1, 30] }, "today": [{ "from": "21:00" }], "manage": "yes" }"""))!!
        assertNull(s.device)
        assertEquals(DnsCounters(), s.counters)
        assertEquals(listOf(DnsTopDomain("a.b", 0)), s.top)
        assertEquals(listOf(5, 30), s.may.durations)
        assertTrue(s.today.isEmpty())
        assertFalse(s.manage)
    }

    @Test
    fun `the answer of a change carries the whole new status`() {
        val o = json("""{ "ok": true, "resumed": true, "status": $contractStatus }""")
        assertEquals("active", DnsJson.changed(o)?.state)
        assertNull(DnsJson.changed(json("""{ "ok": true }""")))
    }

    @Test
    fun `people of the contract`() {
        val p = DnsJson.people(
            json(
                """{ "ok": true,
                     "all": { "may": { "pause": true, "durations": [5,30,60,240], "maxMinutes": 10080, "forever": true }, "pause": null },
                     "people": [
                       { "userId": 2, "name": "Marc", "role": "user", "self": true, "may": { "pause": true, "durations": [5] }, "devices": [] },
                       { "userId": 4, "name": "Lea", "role": "user", "self": false,
                         "may": { "pause": true, "resume": false, "durations": [5, 30, 60], "maxMinutes": 60, "forever": false },
                         "devices": [{ "peerId": 21, "name": "Leas Handy", "state": "filtered", "suspended": true,
                                       "pause": { "scope": "peer", "until": "2026-11-12T14:12:00.000Z" }, "may": { "resume": true } },
                                     { "name": "ohne Nummer" }] } ] }""",
            ),
        )
        assertEquals(listOf(5, 30, 60, 240), p.all?.may?.durations)
        assertTrue(p.all!!.may.forever)
        assertEquals(listOf("Marc", "Lea"), p.people.map { it.name })
        assertTrue(p.people[0].self)
        val lea = p.people[1]
        assertEquals(1, lea.devices.size, "a device without an id is skipped")
        assertTrue(lea.devices[0].suspended)
        assertTrue(lea.devices[0].may.resume)
        assertEquals("2026-11-12T14:12:00.000Z", lea.devices[0].pause?.until)
        // A parent: no "all".
        assertNull(DnsJson.people(json("""{ "all": null, "people": [] }""")).all)
    }

    @Test
    fun `bodies carry exactly one duration and at most one target`() {
        assertEquals("""{"minutes":30}""", DnsJson.pauseBody(DnsDuration.Minutes(30), DnsTarget.Own).toString())
        assertEquals("""{"forever":true}""", DnsJson.pauseBody(DnsDuration.Forever, DnsTarget.Own).toString())
        assertEquals("""{"peerId":21,"minutes":5}""", DnsJson.pauseBody(DnsDuration.Minutes(5), DnsTarget.Peer(21)).toString())
        assertEquals("""{"userId":4,"minutes":60}""", DnsJson.pauseBody(DnsDuration.Minutes(60), DnsTarget.User(4)).toString())
        assertEquals("""{"all":true,"forever":true}""", DnsJson.pauseBody(DnsDuration.Forever, DnsTarget.All).toString())
        assertEquals("{}", DnsJson.targetBody(DnsTarget.Own).toString())
        assertEquals("""{"userId":4}""", DnsJson.targetBody(DnsTarget.User(4)).toString())
    }

    @Test
    fun `a refusal keeps code, sentence and reason`() {
        val f = DnsJson.failed(json("""{ "ok": false, "code": "not_allowed", "error": "Für dieses Profil gesperrt.", "reason": "profile" }"""), 403)
        assertEquals(DnsResult.Failed("not_allowed", 403, "Für dieses Profil gesperrt.", "profile"), f)
        assertEquals(DnsResult.Failed("http_502", 502), DnsJson.failed(null, 502))
        assertEquals(DnsResult.Failed("not_found", 404), DnsJson.failed(json("""{ "ok": false, "code": "not_found" }"""), 404))
    }

    @Test
    fun `permissions carry vpnDns as an object, null or not at all`() {
        val gson = Gson()
        val base = """"ok":true,"permissions":{"services":true,"traffic":false,"dns":false,"rdp":false,"pihole":true},"scopes":[]"""
        val with = gson.fromJson("""{$base,"vpnDns":{"plugin":"gatecontrol-dns","state":"filtering"}}""", PermissionsResponse::class.java)
        assertNotNull(with.vpnDns)
        assertEquals("gatecontrol-dns", with.vpnDns?.plugin)
        assertEquals("filtering", with.vpnDns?.state)
        assertNull(gson.fromJson("""{$base,"vpnDns":null}""", PermissionsResponse::class.java).vpnDns)
        assertNull(gson.fromJson("""{$base}""", PermissionsResponse::class.java).vpnDns, "server before 1.264")
    }
}
