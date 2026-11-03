package com.gatecontrol.android.chat

import com.gatecontrol.android.scan.MediaScan
import com.gatecontrol.android.scan.MediaScanJson
import com.gatecontrol.android.scan.ScanBadge
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Messenger 1.11: the host's virus scan at a message's media, the send's refusals. */
class ChatScanTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun message(media: String, kind: String = "file") = ChatJson.message(
        json(
            """{"id":40,"conversationId":12,"senderId":7,"kind":"$kind","mine":false,"createdAt":"2026-10-17T10:00:00.000Z",
               "media":$media,"meta":{"fileName":"a.exe"},"reactions":[]}""",
        ),
    )!!

    @Test
    fun `media carries the scan, a blocked file has no preview`() {
        val m = message("""{"id":"0123456789abcdef0123456789abcdef","mime":"application/octet-stream","bytes":68,"thumbId":"abcdefabcdefabcdefabcdefabcdefab",
            "url":null,"scan":{"state":"infected","blocked":true,"finding":"Eicar-Test-Signature"}}""")
        assertTrue(m.media!!.blocked)
        assertNull(m.media!!.thumbId, "no preview of a blocked file")
        assertEquals(ScanBadge.BLOCKED_MALWARE, m.media!!.scan!!.badge)
        assertEquals("Eicar-Test-Signature", m.media!!.scan!!.finding)
    }

    @Test
    fun `a blocked file without its id is still a message with the mark`() {
        val m = message("""{"id":null,"mime":"image/jpeg","bytes":10,"scan":{"state":"pending","blocked":true}}""", kind = "image")
        assertEquals("", m.media!!.id)
        assertEquals(ScanBadge.CHECKING, m.media!!.scan!!.badge)
        assertNull(message("""{"id":null,"mime":"image/jpeg"}""", kind = "image").media, "without a scan as before: no media")
    }

    @Test
    fun `media without scan stays as before, marked media stays open`() {
        val old = message("""{"id":"0123456789abcdef0123456789abcdef","mime":"application/pdf","bytes":10,"thumbId":"abcdefabcdefabcdefabcdefabcdefab"}""")
        assertNull(old.media!!.scan)
        assertFalse(old.media!!.blocked)
        assertEquals("abcdefabcdefabcdefabcdefabcdefab", old.media!!.thumbId)
        val marked = message("""{"id":"0123456789abcdef0123456789abcdef","mime":"application/pdf","bytes":10,"scan":{"state":"infected","blocked":false,"finding":"GC.Office.Macro"}}""")
        assertFalse(marked.media!!.blocked)
        assertTrue(marked.media!!.scan!!.warn)
    }

    @Test
    fun `the stored message keeps its scan`() {
        listOf(
            message("""{"id":"0123456789abcdef0123456789abcdef","mime":"application/pdf","bytes":10,"scan":{"state":"unscannable","blocked":true,"finding":"encrypted"}}"""),
            message("""{"id":null,"mime":"image/jpeg","bytes":10,"scan":{"state":"pending","blocked":true}}""", kind = "image"),
        ).forEach { m -> assertEquals(m, ChatJson.message(ChatSerializer.message(m))) }
        assertEquals(MediaScan(MediaScan.UNSCANNABLE, blocked = true, finding = "encrypted"), ChatJson.message(ChatSerializer.message(
            message("""{"id":"0123456789abcdef0123456789abcdef","scan":{"state":"unscannable","blocked":true,"finding":"encrypted"}}"""),
        ))!!.media!!.scan)
    }

    @Test
    fun `a file in quarantine is not sent again, a scan that is down is`() {
        assertEquals(OutboxStep.GiveUp("infected"), ChatOutboxLogic.step(ChatResult.Failed("infected", 422), 1, true, 0))
        assertEquals(OutboxStep.GiveUp("scan_blocked"), ChatOutboxLogic.step(ChatResult.Failed("scan_blocked", 423), 1, true, 0))
        assertTrue(ChatOutboxLogic.step(ChatResult.Failed("scan_unavailable", 503), 1, false, 0) is OutboxStep.Retry)
    }

    // ─── "In Drive speichern" ───────────────────────────────────────

    private val open = """{"id":"0123456789abcdef0123456789abcdef","mime":"image/jpeg","bytes":10,"thumbId":"abcdefabcdefabcdefabcdefabcdefab"}"""

    @Test
    fun `saving to Drive refused by the scan says why`() {
        val media = message(open, kind = "image").media
        val q = ChatToolsLogic.driveRefusal(ChatResult.Failed("quarantined", 423), media)!!
        assertEquals(MediaScanJson.QUARANTINED, q.code)
        assertEquals(ScanBadge.BLOCKED_MALWARE, MediaScanJson.scanOf(q)!!.badge)
        val pending = ChatToolsLogic.driveRefusal(ChatResult.Failed("scan_pending", 423), media)!!
        assertTrue(pending.pending)
        assertEquals(ScanBadge.CHECKING, MediaScanJson.scanOf(pending)!!.badge)
        assertEquals(ScanBadge.BLOCKED_UNCHECKABLE, MediaScanJson.scanOf(ChatToolsLogic.driveRefusal(ChatResult.Failed("scan_blocked", 423), media)!!)!!.badge)
        // the host's code, should it ever come through unchanged
        assertEquals(MediaScanJson.SCAN_PENDING, ChatToolsLogic.driveRefusal(ChatResult.Failed("ERR_MEDIA_SCAN_PENDING", 409), media)!!.code)
        // Drive's own refusals and anything else keep the plugin's text
        listOf(ChatResult.Failed("no_seat", 403), ChatResult.Failed("drive_unavailable", 409), ChatResult.Failed("infected", 422), ChatResult.Offline, ChatResult.Ok("Meine Dateien"))
            .forEach { assertNull(ChatToolsLogic.driveRefusal(it, media), it.toString()) }
    }

    @Test
    fun `the find the message knows goes into the reason`() {
        val marked = message("""{"id":"0123456789abcdef0123456789abcdef","mime":"application/pdf","bytes":10,"scan":{"state":"infected","blocked":false,"finding":"GC.Office.Macro"}}""").media
        assertEquals("GC.Office.Macro", ChatToolsLogic.driveRefusal(ChatResult.Failed("quarantined", 423), marked)!!.finding)
        assertNull(ChatToolsLogic.driveRefusal(ChatResult.Failed("scan_blocked", 423), marked)!!.finding, "not checkable: no find")
        assertNull(ChatToolsLogic.driveRefusal(ChatResult.Failed("quarantined", 423), null)!!.finding)
    }

    @Test
    fun `the refused message shows its state until it is loaded again`() {
        val m = message(open, kind = "image")
        val blocked = ChatToolsLogic.withScan(m, ChatToolsLogic.driveRefusal(ChatResult.Failed("quarantined", 423), m.media)!!)!!
        assertTrue(blocked.media!!.blocked)
        assertEquals(ScanBadge.BLOCKED_MALWARE, blocked.media!!.scan!!.badge)
        assertNull(blocked.media!!.thumbId, "no preview of a blocked file")
        assertEquals(m.media!!.id, blocked.media!!.id)
        assertEquals(m.copy(media = null), blocked.copy(media = null), "only the file's state changes")
        assertEquals(blocked, ChatJson.message(ChatSerializer.message(blocked)), "stored as shown")
        // already shown so, or without a file: nothing to store
        assertNull(ChatToolsLogic.withScan(blocked, ChatToolsLogic.driveRefusal(ChatResult.Failed("quarantined", 423), blocked.media)!!))
        assertNull(ChatToolsLogic.withScan(m.copy(media = null), ChatToolsLogic.driveRefusal(ChatResult.Failed("scan_pending", 423), null)!!))
        val pending = ChatToolsLogic.withScan(m, ChatToolsLogic.driveRefusal(ChatResult.Failed("scan_pending", 423), m.media)!!)!!
        assertEquals(MediaScan(MediaScan.PENDING, blocked = true), pending.media!!.scan)
    }
}
