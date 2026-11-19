package com.gatecontrol.android.dnsprotect

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What plugin gatecontrol-dns 1.1 (server 1.267+) adds to the app API (contract §7): "Internet pausiert"
 * of the family plugin, the chosen duration `pause.minutes`, `people[].pause`, "+30 Min." — and that
 * answers of plugin 1.0 still read as before.
 */
class DnsContract11Test {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val server = "2026-11-12T19:05:00.000Z"
    private val serverMs = DnsLogic.parseIso(server)!!

    /** The example of §7.1, as the plugin's `status()` answers it. */
    private val internetPaused = """
        { "ok": true, "state": "active", "enabled": true,
          "device": { "peerId": 21, "name": "Leas Handy", "platform": "android", "state": "filtered", "ownDns": null },
          "profile": { "id": "p1a2b3c4d", "name": "Kinder", "safeSearch": true },
          "pause": null,
          "internet": { "paused": true, "until": "2026-11-12T19:49:00.000Z", "remainingSeconds": 2640 },
          "outage": null,
          "counters": { "scope": "device", "queries": 3310, "blocked": 893, "percent": 27 },
          "top": null,
          "may": { "pause": false, "resume": false, "extend": false, "reason": "internet_paused", "durations": [], "maxMinutes": 60, "forever": false },
          "today": [],
          "devices": [{ "peerId": 21, "name": "Leas Handy", "platform": "android", "state": "filtered", "ownDns": null,
                        "profile": { "id": "p1a2b3c4d", "name": "Kinder" }, "suspended": false, "pause": null,
                        "internetPaused": "2026-11-12T19:49:00.000Z", "timesSuspended": null, "today": null }],
          "manage": false, "log": { "enabled": true, "visible": false },
          "serverTime": "$server" }
    """.trimIndent()

    @Test
    fun `internet paused - read, the reason, no chips`() {
        val s = DnsJson.status(json(internetPaused))!!
        assertEquals(DnsStatus.ACTIVE, s.state, "no new state: the protection keeps running")
        assertEquals(DnsInternet("2026-11-12T19:49:00.000Z", 2640), s.internet)
        assertEquals("internet_paused", s.may.reason)
        assertFalse(s.may.pause)
        assertTrue(s.may.durations.isEmpty())
        assertFalse(DnsLogic.showsPauseCard(s), "no duration chips")
        assertNull(DnsLogic.notAllowedReason(s), "not a rights reason: the internet notice says it")
    }

    @Test
    fun `internet - null, missing (plugin 1_0), not paused or odd is no pause`() {
        assertNull(DnsJson.status(json("""{ "state": "active", "internet": null }"""))!!.internet)
        assertNull(DnsJson.status(json("""{ "state": "active" }"""))!!.internet)
        assertNull(DnsJson.status(json("""{ "state": "active", "internet": { "paused": false } }"""))!!.internet)
        assertNull(DnsJson.status(json("""{ "state": "active", "internet": "yes" }"""))!!.internet)
        assertEquals(DnsInternet(null, null), DnsJson.status(json("""{ "state": "active", "internet": { "paused": true } }"""))!!.internet)
    }

    @Test
    fun `internet - the end follows the server's clock and ends`() {
        // The device's clock is half an hour behind: 44 minutes stay 44 minutes.
        val received = serverMs - 1_800_000
        val snap = DnsSnapshot(DnsJson.status(json(internetPaused))!!, received)
        assertEquals(received + 44 * 60_000, DnsLogic.internetEndsAtMs(snap))
        assertEquals(44 * 60L, DnsLogic.internetRemainingSeconds(snap, received))
        assertTrue(DnsLogic.internetPaused(snap, received + 43 * 60_000))
        assertFalse(DnsLogic.internetPaused(snap, received + 44 * 60_000), "over on this device's clock")
        // Without serverTime: the remaining seconds.
        val bySeconds = DnsSnapshot(DnsStatus(DnsStatus.ACTIVE, internet = DnsInternet("2026-11-12T19:49:00.000Z", 60)), 5_000)
        assertEquals(65_000L, DnsLogic.internetEndsAtMs(bySeconds))
        // No end at all: as the server said.
        val open = DnsSnapshot(DnsStatus(DnsStatus.ACTIVE, internet = DnsInternet()), 5_000)
        assertNull(DnsLogic.internetEndsAtMs(open))
        assertTrue(DnsLogic.internetPaused(open, Long.MAX_VALUE))
        val none = DnsSnapshot(DnsStatus(DnsStatus.ACTIVE), 5_000)
        assertFalse(DnsLogic.internetPaused(none, 0))
    }

    @Test
    fun `internet paused and a rights reason - the reason stays, the notice too`() {
        val s = DnsJson.status(json(internetPaused.replace("\"reason\": \"internet_paused\"", "\"reason\": \"profile\"")))!!
        assertEquals("profile", s.may.reason)
        assertEquals("profile", DnsLogic.notAllowedReason(s))
        assertTrue(s.internet != null)
    }

    @Test
    fun `admins only and profile get the notice in place of the chips, other reasons none`() {
        fun active(reason: String?) = DnsStatus(DnsStatus.ACTIVE, may = DnsMay(reason = reason))
        assertEquals("admins_only", DnsLogic.notAllowedReason(active("admins_only")))
        assertEquals("profile", DnsLogic.notAllowedReason(active("profile")))
        assertNull(DnsLogic.notAllowedReason(active("not_filtered")))
        assertNull(DnsLogic.notAllowedReason(active("something_new")), "an unknown reason shows nothing")
        assertNull(DnsLogic.notAllowedReason(active(null)))
        // Only while active: suspended, the card to switch back on stands instead.
        assertNull(DnsLogic.notAllowedReason(DnsStatus(DnsStatus.PAUSED, may = DnsMay(reason = "admins_only"))))
    }

    @Test
    fun `pause minutes - read, and preferred over since and until`() {
        val p = DnsJson.pause(
            json(
                """{ "scope": "peer", "until": "2026-11-12T14:12:00.000Z", "openEnd": false, "since": "2026-11-12T13:42:00.000Z",
                     "by": { "userId": 2, "name": "Marc" }, "byMe": true, "remainingSeconds": 1450, "minutes": 60 }""",
            ),
        )
        assertEquals(60, p.minutes)
        // "+30 Min." made 30 into 60: the whole of it, though since/until of the answer say 30.
        assertEquals(60, DnsLogic.plannedMinutes(p))
        // Plugin 1.0: no minutes, computed as before.
        assertEquals(30, DnsLogic.plannedMinutes(p.copy(minutes = null)))
        // Open end: none.
        assertNull(DnsLogic.plannedMinutes(p.copy(openEnd = true)))
        assertNull(DnsJson.pause(json("""{ "scope": "peer", "openEnd": true, "minutes": null }""")).minutes)
        assertNull(DnsJson.pause(json("""{ "scope": "peer", "minutes": 0 }""")).minutes)
    }

    @Test
    fun `the chip of the whole duration counts after +30`() {
        val pause = DnsPause("peer", until = "2026-11-12T14:42:00.000Z", since = "2026-11-12T13:42:00.000Z", minutes = 60)
        val chips = DnsLogic.chips(DnsMay(resume = true, extend = true, durations = listOf(5, 30, 60), maxMinutes = 60), pause)
        assertEquals(listOf(60), chips.filter { it.selected }.map { it.minutes })
        assertTrue(chips.none { it.enabled })
    }

    @Test
    fun `+30 stands while suspended with an end and the server allows it`() {
        val pause = DnsPause("peer", until = "2026-11-12T14:12:00.000Z", since = "2026-11-12T13:42:00.000Z", minutes = 30)
        val paused = DnsStatus(DnsStatus.PAUSED, pause = pause, may = DnsMay(resume = true, extend = true))
        assertTrue(DnsLogic.showsExtend(paused))
        assertFalse(DnsLogic.showsExtend(paused.copy(may = DnsMay(resume = true, extend = false))))
        assertFalse(DnsLogic.showsExtend(paused.copy(pause = pause.copy(openEnd = true, until = null))), "no end to add to")
        assertFalse(DnsLogic.showsExtend(paused.copy(state = DnsStatus.ACTIVE, pause = null)))
        assertEquals(30, DnsLogic.EXTEND_MINUTES)
    }

    @Test
    fun `the body of +30 is extend, with the target`() {
        assertEquals(json("""{ "extend": 30 }"""), DnsJson.pauseBody(DnsDuration.Extend(30), DnsTarget.Own))
        assertEquals(json("""{ "peerId": 21, "extend": 30 }"""), DnsJson.pauseBody(DnsDuration.Extend(30), DnsTarget.Peer(21)))
    }

    @Test
    fun `people - the person's pause and internet of plugin 1_1, devices with internetPaused`() {
        val people = DnsJson.people(
            json(
                """{ "ok": true, "all": null, "people": [
                     { "userId": 4, "name": "Lea", "role": "user", "self": false,
                       "pause": { "scope": "user", "until": "2026-11-12T14:12:00.000Z", "openEnd": false, "since": "2026-11-12T13:42:00.000Z",
                                  "by": { "userId": 2, "name": "Marc" }, "byMe": true, "remainingSeconds": 1450, "minutes": 30 },
                       "internet": null,
                       "may": { "pause": false, "resume": true, "extend": true, "reason": null, "durations": [5, 30, 60], "maxMinutes": 60, "forever": false },
                       "devices": [
                         { "peerId": 21, "name": "Leas Handy", "state": "filtered", "suspended": true, "pause": null, "internetPaused": null, "may": {} },
                         { "peerId": 22, "name": "Leas Tablet", "state": "filtered", "suspended": false, "pause": null, "may": {} } ] },
                     { "userId": 5, "name": "Tim", "role": "user", "self": false, "pause": null,
                       "internet": { "paused": true, "until": "2026-11-12T19:49:00.000Z", "remainingSeconds": 2640 },
                       "may": { "pause": false, "reason": "internet_paused", "durations": [] },
                       "devices": [{ "peerId": 30, "name": "Tims Tablet", "state": "filtered", "suspended": true,
                                     "pause": { "scope": "peer", "until": "2026-11-12T14:00:00.000Z" },
                                     "internetPaused": "2026-11-12T19:49:00.000Z", "may": {} }] } ] }""",
            ),
        )
        val (lea, tim) = people.people
        assertEquals(30, lea.pause?.minutes)
        assertEquals("user", lea.pause?.scope)
        assertNull(lea.internet)
        assertNull(lea.devices[0].internetPaused)
        // Plugin 1.1 says "no common pause" (null) — not derived from the device even though it is suspended.
        assertNull(tim.pause)
        assertEquals(DnsInternet("2026-11-12T19:49:00.000Z", 2640), tim.internet)
        assertEquals("2026-11-12T19:49:00.000Z", tim.devices[0].internetPaused)
        assertEquals("internet_paused", tim.may.reason)
    }

    @Test
    fun `people of plugin 1_0 - the person's pause derived from the devices`() {
        val people = DnsJson.people(
            json(
                """{ "people": [
                     { "userId": 4, "name": "Lea", "devices": [
                         { "peerId": 21, "name": "Leas Handy", "suspended": true, "pause": { "scope": "user", "until": "2026-11-12T14:12:00.000Z" } },
                         { "peerId": 22, "name": "Leas Tablet", "suspended": true, "pause": { "scope": "user", "until": "2026-11-12T14:12:00.000Z" } } ] },
                     { "userId": 5, "name": "Tim", "devices": [
                         { "peerId": 30, "name": "Tims Handy", "suspended": true, "pause": { "scope": "peer", "until": "2026-11-12T14:00:00.000Z" } },
                         { "peerId": 31, "name": "Tims PC", "suspended": false } ] },
                     { "userId": 6, "name": "Ben", "devices": [] } ] }""",
            ),
        )
        assertEquals("2026-11-12T14:12:00.000Z", people.people[0].pause?.until)
        assertNull(people.people[1].pause, "only some devices suspended: no common pause")
        assertNull(people.people[2].pause)
        assertNull(people.people[0].internet)
    }

    // --- Plugin 1.2 / server 1.301 (contract §8) ---

    @Test
    fun `profile home - true, false, missing (older plugin), no profile`() {
        fun home(profile: String) = DnsJson.status(json("""{ "state": "active", "profile": $profile }"""))!!
        assertTrue(home("""{ "id": "p1", "name": "Kinder", "safeSearch": true, "home": true }""").profileHome)
        assertFalse(home("""{ "id": "p1", "name": "Kinder", "safeSearch": true, "home": false }""").profileHome)
        assertFalse(home("""{ "id": "p1", "name": "Kinder", "safeSearch": true }""").profileHome)
        assertFalse(home("""{ "id": "p1", "name": "Kinder", "home": "yes" }""").profileHome)
        val none = home("null")
        assertFalse(none.profileHome)
        assertNull(none.profileName)
    }

    @Test
    fun `404 - the core's plugin_unavailable is gone, the plugin's own is stale, an older core's is gone`() {
        fun kind(body: String) = DnsLogic.failure(DnsJson.failed(json(body), 404))
        // Server 1.301+: the core marks it (even should a text come along).
        assertEquals(DnsFailure.GONE, kind("""{ "ok": false, "code": "not_found", "reason": "plugin_unavailable" }"""))
        assertEquals(DnsFailure.GONE, kind("""{ "ok": false, "code": "not_found", "reason": "plugin_unavailable", "error": "Nicht gefunden." }"""))
        // The plugin's 404 (device or person unknown): reload, not gone.
        assertEquals(DnsFailure.STALE, kind("""{ "ok": false, "code": "not_found", "error": "Gerät nicht gefunden." }"""))
        // A core before 1.301: neither reason nor text.
        assertEquals(DnsFailure.GONE, kind("""{ "ok": false, "code": "not_found" }"""))
        // Without a body at all.
        assertEquals(DnsFailure.GONE, DnsLogic.failure(DnsJson.failed(null, 404)))
        // Another reason on another status stays what it was.
        assertEquals(DnsFailure.REFUSED, DnsLogic.failure(DnsJson.failed(json("""{ "code": "not_allowed", "reason": "plugin_unavailable", "error": "x" }"""), 403)))
    }
}
