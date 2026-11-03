package com.gatecontrol.android.scan

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response

class MediaScanTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun refused(code: Int, body: String, retryAfter: String? = null): Response<ResponseBody> {
        val raw = okhttp3.Response.Builder()
            .code(code)
            .message("refused")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("http://localhost/api/v1/client/plugins/x/media/abc").build())
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .build()
        return Response.error(body.toResponseBody("application/json".toMediaType()), raw)
    }

    private fun ok(): Response<ResponseBody> = Response.success("data".toResponseBody(null))

    // ─── reading `scan` ─────────────────────────────────────────────

    @Test
    fun `no scan field means nothing to show (older host or plugin)`() {
        assertNull(MediaScanJson.of(json("""{"id":1,"name":"a.pdf"}""")))
        assertNull(MediaScanJson.of(null))
        assertNull(MediaScanJson.of(json("""{"scan":null}""")))
        assertNull(MediaScanJson.of(json("""{"scan":"infected"}""")), "not an object")
        assertNull(MediaScanJson.of(json("""{"scan":{"blocked":true}}""")), "no state")
        assertNull(MediaScanJson.of(json("""{"scan":{"state":"<b>x</b>","blocked":true}}""")), "a state that is no token")
    }

    @Test
    fun `scan with state, blocked and finding`() {
        val s = MediaScanJson.of(json("""{"scan":{"state":"infected","blocked":true,"finding":"Eicar-Test-Signature"}}"""))!!
        assertEquals(MediaScan.INFECTED, s.state)
        assertTrue(s.blocked)
        assertEquals("Eicar-Test-Signature", s.finding)
        assertFalse(s.removed)
        val open = MediaScanJson.of(json("""{"scan":{"state":"clean","blocked":false,"finding":null}}"""))!!
        assertFalse(open.blocked)
        assertNull(open.finding)
        assertEquals("a b", MediaScanJson.finding(" a\nb "), "one line")
        assertEquals(200, MediaScanJson.finding("x".repeat(500))!!.length)
    }

    @Test
    fun `blocked decides, not the state`() {
        assertEquals(ScanBadge.CHECKING, MediaScan(MediaScan.PENDING, blocked = true).badge)
        assertNull(MediaScan(MediaScan.PENDING, blocked = false).badge, "pending but open: nothing")
        assertEquals(ScanBadge.BLOCKED_MALWARE, MediaScan(MediaScan.INFECTED, blocked = true).badge)
        assertEquals(ScanBadge.WARN_MALWARE, MediaScan(MediaScan.INFECTED, blocked = false).badge, "only marked")
        assertEquals(ScanBadge.BLOCKED_UNCHECKABLE, MediaScan(MediaScan.UNSCANNABLE, blocked = true).badge)
        assertEquals(ScanBadge.NOT_CHECKABLE, MediaScan(MediaScan.UNSCANNABLE, blocked = false).badge)
        assertNull(MediaScan(MediaScan.CLEAN, blocked = false).badge)
        assertNull(MediaScan(MediaScan.UNCHECKED, blocked = false).badge)
        assertEquals(ScanBadge.BLOCKED_UNCHECKABLE, MediaScan("later", blocked = true).badge, "an unknown state that is blocked")
        assertEquals(ScanBadge.REMOVED, MediaScan(MediaScan.INFECTED, blocked = true, removed = true).badge)
        assertTrue(MediaScan(MediaScan.INFECTED, blocked = false).warn)
        assertFalse(MediaScan(MediaScan.INFECTED, blocked = true).warn)
        assertTrue(MediaScan(MediaScan.PENDING, blocked = true).closed)
        assertFalse(MediaScan(MediaScan.UNSCANNABLE, blocked = false).closed)
    }

    @Test
    fun `a blocked file says the code its routes answer with`() {
        assertEquals(MediaScanJson.SCAN_PENDING, MediaScan(MediaScan.PENDING, blocked = true).refusalCode)
        assertEquals(MediaScanJson.QUARANTINED, MediaScan(MediaScan.INFECTED, blocked = true).refusalCode)
        assertEquals(MediaScanJson.SCAN_BLOCKED, MediaScan(MediaScan.UNSCANNABLE, blocked = true).refusalCode)
        assertEquals(MediaScanJson.ATTACHMENT_REMOVED, MediaScan(MediaScan.INFECTED, blocked = true, removed = true).refusalCode)
        assertNull(MediaScan(MediaScan.INFECTED, blocked = false).refusalCode, "marked files open")
    }

    @Test
    fun `the gravest mark speaks for several files`() {
        val warn = MediaScan(MediaScan.INFECTED, blocked = false)
        val bad = MediaScan(MediaScan.INFECTED, blocked = true)
        val quiet = MediaScan(MediaScan.UNSCANNABLE, blocked = false)
        assertEquals(bad, MediaScan.worst(listOf(quiet, warn, null, bad)))
        assertEquals(warn, MediaScan.worst(listOf(quiet, warn, MediaScan(MediaScan.CLEAN, false))))
        assertNull(MediaScan.worst(listOf(null, MediaScan(MediaScan.CLEAN, false))))
    }

    @Test
    fun `stored scan reads back the same`() {
        val s = MediaScan(MediaScan.INFECTED, blocked = true, finding = "Win.Trojan.Agent-1", removed = true)
        assertEquals(s, MediaScanJson.scan(MediaScanJson.toJson(s)))
        val t = MediaScan(MediaScan.PENDING, blocked = true)
        assertEquals(t, MediaScanJson.scan(MediaScanJson.toJson(t)))
    }

    @Test
    fun `Mail - removed without a scan still means removed`() {
        val s = MediaScanJson.attachment(json("""{"id":3,"removed":true}"""))!!
        assertTrue(s.removed)
        assertEquals(ScanBadge.REMOVED, s.badge)
        assertTrue(MediaScanJson.attachment(json("""{"id":3,"removed":true,"scan":{"state":"infected","blocked":true}}"""))!!.removed)
        assertNull(MediaScanJson.attachment(json("""{"id":3}""")))
    }

    // ─── refusals ───────────────────────────────────────────────────

    @Test
    fun `refusals of the scan, flat and as Mail's error object`() {
        val q = MediaScanJson.refusal(423, json("""{"ok":false,"code":"quarantined","error":"gesperrt","scan":{"state":"infected","blocked":true,"finding":"Eicar"}}"""))!!
        assertEquals(MediaScanJson.QUARANTINED, q.code)
        assertEquals("Eicar", q.finding, "from the scan")
        assertTrue(q.scan!!.blocked)
        val g = MediaScanJson.refusal(422, json("""{"ok":false,"code":"infected","error":"x","name":"rechnung.exe","finding":"Win.Trojan"}"""))!!
        assertEquals("rechnung.exe", g.name)
        assertEquals("Win.Trojan", g.finding)
        val m = MediaScanJson.refusal(409, json("""{"error":{"code":"attachment_blocked","message":"x","attachmentId":12,"name":"a.docm","scan":{"state":"infected","blocked":true}}}"""))!!
        assertEquals(MediaScanJson.ATTACHMENT_BLOCKED, m.code)
        assertEquals(12L, m.attachmentId)
        assertEquals("a.docm", m.name)
        assertTrue(MediaScanJson.refusal(423, json("""{"ok":false,"code":"scan_pending"}"""))!!.pending)
        assertNull(MediaScanJson.refusal(423, json("""{"ok":false,"code":"device_locked"}""")), "Drive's ransomware protection is not the scan's")
        assertNull(MediaScanJson.refusal(404, null))
    }

    @Test
    fun `the state a refusal tells about its file`() {
        assertEquals(ScanBadge.CHECKING, MediaScanJson.scanOf(ScanRefusal(MediaScanJson.SCAN_PENDING, 423))!!.badge)
        assertEquals(ScanBadge.BLOCKED_MALWARE, MediaScanJson.scanOf(ScanRefusal(MediaScanJson.QUARANTINED, 423, finding = "E"))!!.badge)
        assertEquals(ScanBadge.BLOCKED_UNCHECKABLE, MediaScanJson.scanOf(ScanRefusal(MediaScanJson.SCAN_BLOCKED, 423))!!.badge)
        assertEquals(ScanBadge.REMOVED, MediaScanJson.scanOf(ScanRefusal(MediaScanJson.ATTACHMENT_REMOVED, 410))!!.badge)
        val withScan = ScanRefusal(MediaScanJson.ATTACHMENT_REMOVED, 410, scan = MediaScan(MediaScan.INFECTED, blocked = true))
        assertTrue(MediaScanJson.scanOf(withScan)!!.removed)
        assertNull(MediaScanJson.scanOf(ScanRefusal(MediaScanJson.SCAN_UNAVAILABLE, 503)))
    }

    @Test
    fun `Retry-After in seconds, bounded`() {
        assertEquals(5_000L, ScanRetry.delayMs("5"))
        assertEquals(5_000L, ScanRetry.delayMs(null))
        assertEquals(5_000L, ScanRetry.delayMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(1_000L, ScanRetry.delayMs("0"))
        assertEquals(30_000L, ScanRetry.delayMs("600"))
    }

    // ─── the media routes ───────────────────────────────────────────

    @Test
    fun `423 scan_pending is asked again after Retry-After`() = runTest {
        val waits = ArrayList<Long>()
        val answers = ArrayDeque(listOf(refused(423, """{"ok":false,"code":"scan_pending"}""", "7"), ok()))
        val a = MediaScanHttp.get(wait = { waits += it }) { answers.removeFirst() }!!
        assertTrue(a.response.isSuccessful)
        assertNull(a.refusal)
        assertEquals(listOf(7_000L), waits)
    }

    @Test
    fun `scan_pending stops after the tries and says so`() = runTest {
        var calls = 0
        val a = MediaScanHttp.get(tries = 2, wait = { }) { calls++; refused(423, """{"ok":false,"code":"scan_pending"}""", "5") }!!
        assertEquals(3, calls, "once and two more")
        assertEquals(MediaScanJson.SCAN_PENDING, a.code)
    }

    @Test
    fun `a blocked file is not asked again`() = runTest {
        var calls = 0
        val a = MediaScanHttp.get(wait = { error("no wait") }) { calls++; refused(423, """{"ok":false,"code":"quarantined","finding":"Eicar"}""") }!!
        assertEquals(1, calls)
        assertEquals(MediaScanJson.QUARANTINED, a.code)
        assertEquals("Eicar", a.refusal!!.finding)
        val b = MediaScanHttp.get(wait = { error("no wait") }) { refused(423, """{"ok":false,"code":"scan_blocked"}""") }!!
        assertEquals(MediaScanJson.SCAN_BLOCKED, b.code)
    }

    @Test
    fun `other failures keep their status code, no answer stays null`() = runTest {
        assertEquals("http_404", MediaScanHttp.get { refused(404, """{"ok":false,"code":"not_found"}""") }!!.code)
        assertEquals("http_423", MediaScanHttp.get { refused(423, "not json") }!!.code)
        assertNull(MediaScanHttp.get<ResponseBody> { null })
    }

    // ─── hand-overs held back ───────────────────────────────────────

    @Test
    fun `a hand-over held back by the scan names why, other failures do not`() {
        assertEquals(MediaScanJson.QUARANTINED, MediaScanJson.heldBack("quarantined"))
        assertEquals(MediaScanJson.SCAN_PENDING, MediaScanJson.heldBack("scan_pending"))
        assertEquals(MediaScanJson.SCAN_BLOCKED, MediaScanJson.heldBack("scan_blocked"))
        assertEquals(MediaScanJson.ATTACHMENT_REMOVED, MediaScanJson.heldBack("attachment_removed"))
        // the host's own codes, when a plugin passes them on (Mail: drive_unavailable { reason })
        assertEquals(MediaScanJson.QUARANTINED, MediaScanJson.heldBack("ERR_MEDIA_QUARANTINED"))
        assertEquals(MediaScanJson.SCAN_PENDING, MediaScanJson.heldBack("drive_unavailable", "ERR_MEDIA_SCAN_PENDING"))
        assertEquals(MediaScanJson.SCAN_BLOCKED, MediaScanJson.heldBack("drive_unavailable", "ERR_MEDIA_BLOCKED"))
        // a refused upload or anything else is not a held-back file
        assertNull(MediaScanJson.heldBack("infected"))
        assertNull(MediaScanJson.heldBack("scan_unavailable"))
        assertNull(MediaScanJson.heldBack("drive_unavailable", "handover"))
        assertNull(MediaScanJson.heldBack("no_seat"))
        assertNull(MediaScanJson.heldBack(null))
    }
}
