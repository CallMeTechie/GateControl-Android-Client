package com.gatecontrol.android.chat

import com.gatecontrol.android.scan.MediaScan
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
}
