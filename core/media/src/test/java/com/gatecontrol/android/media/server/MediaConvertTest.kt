package com.gatecontrol.android.media.server

import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** „Videos umwandeln“ in the apps: the plugin's answers (example of `umwandeln-apps-vertrag`), the hint, the cache. */
class MediaConvertTest {

    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    private val a = "0123456789abcdef0123456789abcdef"
    private val b = "fedcba9876543210fedcba9876543210"

    @Test
    fun `item ids - 32 hex digits, dashes and capitals taken, anything else not`() {
        assertEquals(a, MediaConvertLogic.itemId("01234567-89AB-CDEF-0123-456789ABCDEF"))
        assertNull(MediaConvertLogic.itemId("m1"))
        assertNull(MediaConvertLogic.itemId("f:film.mkv"))
        assertNull(MediaConvertLogic.itemId(null))
    }

    @Test
    fun `the status of the contract - items with level and requested, states by id, unknown fields passed over`() {
        val s = MediaConvertLogic.status(
            obj(
                """
                { "ok": true, "v": 1, "available": true, "future": 1,
                  "items": [
                    { "id": "$a", "sourceId": "jf-1", "level": "convert", "reasons": ["h264_10bit"],
                      "devices": { "phone": "no", "tv": "no", "windows": "no" }, "stalled": false, "requested": true,
                      "state": "queued", "position": 2, "startsAt": "2026-11-28T00:08:00.000Z", "percent": null } ],
                  "states": {
                    "$a": { "state": "queued", "position": 2, "startsAt": "2026-11-28T00:08:00.000Z", "percent": null },
                    "${b.uppercase()}": { "state": "running", "percent": 42 },
                    "x": { "state": "done" } } }
                """.trimIndent(),
            ),
        )
        assertTrue(s.available)
        val qa = s.items.getValue(a)
        assertEquals(MediaConvertState.QUEUED, qa.state)
        assertEquals(2, qa.position)
        assertEquals(Instant.parse("2026-11-28T00:08:00Z").toEpochMilli(), qa.startsAt)
        assertTrue(qa.requested)
        assertEquals("convert", qa.level)
        val rb = s.items.getValue(b)
        assertEquals(MediaConvertState.RUNNING, rb.state)
        assertEquals(42, rb.percent)
        assertNull(rb.position)
        assertEquals(2, s.items.size)
    }

    @Test
    fun `unknown states count as none, percent kept within 0 to 100`() {
        val s = MediaConvertLogic.status(obj("""{ "states": { "$a": { "state": "paused_by_admin" }, "$b": { "state": "running", "percent": 140 } } }"""))
        assertFalse(s.available)
        assertEquals(MediaConvertState.NONE, s.items.getValue(a).state)
        assertEquals(100, s.items.getValue(b).percent)
    }

    @Test
    fun `the hint - only for a file the device does not play, by state`() {
        val item = MediaConvertItem(a, level = "convert")
        assertNull(MediaConvertLogic.hint(null, item.copy(state = MediaConvertState.QUEUED), true))
        assertNull(MediaConvertLogic.hint("H.264 10-Bit", null, true))
        assertEquals(MediaConvertHint.Offer, MediaConvertLogic.hint("H.264 10-Bit", item, true))
        // no gateway converts: no button
        assertNull(MediaConvertLogic.hint("H.264 10-Bit", item, false))
        // the plugin found nothing (level null or only info): nothing to ask for
        assertNull(MediaConvertLogic.hint("H.264 10-Bit", item.copy(level = null), true))
        assertNull(MediaConvertLogic.hint("H.264 10-Bit", item.copy(level = "info"), true))
        assertEquals(MediaConvertHint.Requested, MediaConvertLogic.hint("H.264 10-Bit", item.copy(requested = true), false))
        assertEquals(MediaConvertHint.Queued(3, 5L), MediaConvertLogic.hint("x", item.copy(state = MediaConvertState.QUEUED, position = 3, startsAt = 5L), true))
        assertEquals(MediaConvertHint.Running(42), MediaConvertLogic.hint("x", item.copy(state = MediaConvertState.RUNNING, percent = 42), false))
        assertNull(MediaConvertLogic.hint("x", item.copy(state = MediaConvertState.DONE), true))
    }

    @Test
    fun `the start as a clock - German 24 hours, past or unknown none`() {
        val zone = ZoneId.of("Europe/Berlin")
        val at = Instant.parse("2026-11-28T00:08:00Z").toEpochMilli()
        assertEquals("01:08", MediaConvertLogic.startClock(at, at - 3_600_000, zone, Locale.GERMAN))
        assertTrue(MediaConvertLogic.startClock(at, at - 3_600_000, zone, Locale.US)!!.startsWith("1:08"))
        assertNull(MediaConvertLogic.startClock(at, at + 1, zone, Locale.GERMAN))
        assertNull(MediaConvertLogic.startClock(null, 0, zone, Locale.GERMAN))
    }

    @Test
    fun `refusals that switch the hint off`() {
        assertTrue(MediaConvertLogic.switchesOff(MediaServerError(404, "not_found")))
        assertTrue(MediaConvertLogic.switchesOff(MediaServerError(403, "no_access")))
        assertTrue(MediaConvertLogic.switchesOff(MediaServerError(403, "license_required")))
        assertFalse(MediaConvertLogic.switchesOff(MediaServerError(0, MediaServerError.OFFLINE)))
        assertFalse(MediaConvertLogic.switchesOff(MediaServerError(429, "rate_limited")))
    }

    private class Api(var status: MediaServerResult<MediaConvertStatus>) : MediaConvertApi {
        val asked = mutableListOf<List<String>>()
        var request: MediaServerResult<MediaConvertItem> = MediaServerResult.Refused(MediaServerError(429, "rate_limited"))

        override suspend fun convertStatus(itemIds: List<String>): MediaServerResult<MediaConvertStatus> {
            asked += itemIds
            return status
        }

        override suspend fun convertRequest(itemId: String) = request
    }

    private fun ok(vararg items: MediaConvertItem, available: Boolean = true) =
        MediaServerResult.Ok(MediaConvertStatus(available, items.associateBy { it.id }))

    @Test
    fun `asked together, kept a few minutes, then asked again`() = runTest {
        var now = 1_000_000L
        val api = Api(ok(MediaConvertItem(a, MediaConvertState.RUNNING, percent = 10)))
        val convert = MediaConvert(api) { now }
        convert.prefetch(listOf(a, b, a, "m1"))
        assertEquals(listOf(listOf(a, b)), api.asked)
        assertEquals(MediaConvertHint.Running(10), convert.hint(a, "H.264 10-Bit"))
        // b was asked: no question of its own
        assertNull(convert.hint(b, "H.264 10-Bit"))
        assertEquals(1, api.asked.size)
        // no problem on this device: not asked at all
        assertNull(convert.hint(a, null))
        now += MediaConvert.TTL_MS
        api.status = ok(MediaConvertItem(a, MediaConvertState.RUNNING, percent = 60))
        assertEquals(MediaConvertHint.Running(60), convert.hint(a, "H.264 10-Bit"))
        assertEquals(listOf(a), api.asked.last())
    }

    @Test
    fun `more than 100 ids go in several questions`() = runTest {
        val api = Api(ok())
        val ids = (0 until 150).map { "%032x".format(it) }
        MediaConvert(api) { 0L }.prefetch(ids)
        assertEquals(listOf(100, 50), api.asked.map { it.size })
    }

    @Test
    fun `a plugin before 1_6_0 (404) is not asked again for a while, no hint`() = runTest {
        var now = 0L
        val api = Api(MediaServerResult.Refused(MediaServerError(404, "not_found")))
        val convert = MediaConvert(api) { now }
        assertNull(convert.hint(a, "H.264 10-Bit"))
        assertNull(convert.hint(b, "H.264 10-Bit"))
        assertEquals(1, api.asked.size)
        now += MediaConvert.OFF_MS
        api.status = ok(MediaConvertItem(b, MediaConvertState.QUEUED, position = 1))
        assertEquals(MediaConvertHint.Queued(1, null), convert.hint(b, "H.264 10-Bit"))
    }

    @Test
    fun `no connection - no hint, asked again next time`() = runTest {
        val api = Api(MediaServerResult.Refused(MediaServerError(0, MediaServerError.OFFLINE)))
        val convert = MediaConvert(api) { 0L }
        assertNull(convert.hint(a, "H.264 10-Bit"))
        api.status = ok(MediaConvertItem(a, level = "convert"))
        assertEquals(MediaConvertHint.Offer, convert.hint(a, "H.264 10-Bit"))
    }

    @Test
    fun `Fuer heute Nacht vormerken - requested, kept, refused stays null`() = runTest {
        val api = Api(ok(MediaConvertItem(a, level = "convert")))
        val convert = MediaConvert(api) { 0L }
        assertEquals(MediaConvertHint.Offer, convert.hint(a, "H.264 10-Bit"))
        assertNull(convert.request(a))
        api.request = MediaServerResult.Ok(MediaConvertLogic.requested(a, obj("""{ "ok": true, "v": 1, "requested": true, "state": "none" }""")))
        assertEquals(MediaConvertHint.Requested, convert.request(a))
        // the kept state says so too, without a new question
        assertEquals(MediaConvertHint.Requested, convert.hint(a, "H.264 10-Bit"))
        assertEquals(1, api.asked.size)
        assertNull(convert.request("not-an-id"))
    }
}
