package com.gatecontrol.android.mail

import com.gatecontrol.android.scan.MediaScanJson
import com.gatecontrol.android.scan.ScanBadge
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Mail 1.8: the host's virus scan at attachments and drafts, the refusals of fetching and sending. */
class MailScanTest {

    private fun obj(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    @Test
    fun `an attachment carries the scan, removed ones say so`() {
        val a = MailJson.attachment(obj("""{"id":7,"name":"Rechnung.docm","mime":"application/octet-stream","size":10,"scan":{"state":"infected","blocked":true,"finding":"GC.Office.Macro"}}"""))!!
        assertEquals(ScanBadge.BLOCKED_MALWARE, a.scan!!.badge)
        val gone = MailJson.attachment(obj("""{"id":8,"name":"x.exe","size":10,"removed":true,"scan":{"state":"infected","blocked":true,"finding":"Eicar"}}"""))!!
        assertTrue(gone.scan!!.removed)
        assertEquals(ScanBadge.REMOVED, gone.scan!!.badge)
        assertNull(MailJson.attachment(obj("""{"id":9,"name":"a.pdf","size":10}"""))!!.scan, "an older Mail: nothing")
    }

    @Test
    fun `a draft's forwarded attachment goes along marked`() {
        val d = MailJson.draft(
            obj("""{"id":4,"accountId":1,"to":[],"subject":"Fwd","html":"","attachments":[
                {"id":21,"name":"a.docm","size":10,"mime":"application/octet-stream","scan":{"state":"infected","blocked":true}},
                {"id":22,"name":"b.pdf","size":10,"mime":"application/pdf"}],
                "warnings":[{"kind":"attachment_blocked","text":""}]}"""),
        )!!
        assertTrue(d.attachments[0].scan!!.blocked)
        assertNull(d.attachments[1].scan)
        assertEquals("attachment_blocked", d.warnings.single().kind)
    }

    @Test
    fun `the copy's answer carries the scan`() {
        val m = MailJson.attachmentMedia(obj("""{"mediaId":"abc123","name":"a.docx","mime":"x/y","size":1,"scan":{"state":"infected","blocked":false,"finding":"GC.Office.Macro"}}"""))!!
        assertTrue(m.scan!!.warn)
        assertNull(MailJson.attachmentMedia(obj("""{"mediaId":"abc123"}"""))!!.scan)
    }

    @Test
    fun `refusals keep the scan's codes`() {
        val q = obj("""{"error":{"code":"quarantined","message":"gesperrt","scan":{"state":"infected","blocked":true,"finding":"Eicar"}}}""")
        assertEquals("quarantined", MailJson.failureCode(423, q))
        assertEquals("attachment_removed", MailJson.failureCode(410, obj("""{"error":{"code":"attachment_removed","message":"x"}}""")))
        val blocked = obj("""{"error":{"code":"attachment_blocked","attachmentId":21,"name":"a.docm","scan":{"state":"infected","blocked":true}}}""")
        assertEquals("attachment_blocked", MailJson.failureCode(409, blocked))
        // MailApi hands on the error object: what the send dialog reads from it
        val r = MediaScanJson.refusal(409, blocked.getAsJsonObject("error"))!!
        assertEquals(21L, r.attachmentId)
        assertEquals("a.docm", r.name)
        assertEquals(ScanBadge.BLOCKED_MALWARE, MediaScanJson.scanOf(MediaScanJson.refusal(423, q.getAsJsonObject("error"))!!)!!.badge)
    }
}
