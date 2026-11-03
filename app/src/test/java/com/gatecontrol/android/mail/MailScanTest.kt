package com.gatecontrol.android.mail

import com.gatecontrol.android.scan.MediaScanJson
import com.gatecontrol.android.scan.ScanBadge
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    // ─── "In Drive speichern" and the live event of a draft ─────────

    @Test
    fun `saving to Drive refused by the scan says why`() {
        val q = MailLogic.driveRefusal(MResult.Failed("quarantined", 423, obj("""{"code":"quarantined","message":"gesperrt"}""")))!!
        assertEquals("quarantined", q.code)
        assertEquals(ScanBadge.BLOCKED_MALWARE, MediaScanJson.scanOf(q)!!.badge)
        val pending = MailLogic.driveRefusal(MResult.Failed("scan_pending", 423, null))!!
        assertTrue(pending.pending)
        assertEquals(ScanBadge.CHECKING, MediaScanJson.scanOf(pending)!!.badge)
        assertEquals(ScanBadge.BLOCKED_UNCHECKABLE, MediaScanJson.scanOf(MailLogic.driveRefusal(MResult.Failed("scan_blocked", 423, null))!!)!!.badge)
        // Drive's refusal passed on with the host's code
        val passed = MailLogic.driveRefusal(MResult.Failed("drive_unavailable", 409, obj("""{"code":"drive_unavailable","reason":"ERR_MEDIA_QUARANTINED"}""")))!!
        assertEquals("quarantined", passed.code)
        val removed = MailLogic.driveRefusal(MResult.Failed("attachment_removed", 410, obj("""{"code":"attachment_removed","scan":{"state":"infected","blocked":true,"finding":"Eicar"}}""")))!!
        assertEquals(ScanBadge.REMOVED, MediaScanJson.scanOf(removed)!!.badge)
        assertEquals("Eicar", removed.finding)
        // anything else keeps the plugin's text
        assertNull(MailLogic.driveRefusal(MResult.Failed("no_folder", 409, null)))
        assertNull(MailLogic.driveRefusal(MResult.Failed("drive_unavailable", 409, obj("""{"reason":"handover"}"""))))
        // "Alle in Drive": failed[].code
        val all = MailJson.saveAll(obj("""{"saved":[],"failed":[{"attachmentId":7,"code":"scan_pending"},{"attachmentId":8,"code":"forbidden"}]}"""))
        assertEquals(listOf("scan_pending", null), all.failed.map { MediaScanJson.heldBack(it.second) })
    }

    @Test
    fun `the live event names the draft`() {
        val c = MailJson.changed(obj("""{"mail":{"kind":"changed","draftId":4}}"""))!!
        assertEquals(4L, c.draftId)
        assertTrue(MailLogic.concernsDraft(c, 4))
        assertFalse(MailLogic.concernsDraft(c, 5))
        assertFalse(MailLogic.concernsDraft(c, null))
        assertFalse(MailLogic.concernsDraft(MailJson.changed(obj("""{"kind":"changed","messageId":4,"attachmentId":2}"""))!!, 4))
    }

    @Test
    fun `a draft read again takes only the scan, what was written stays`() {
        val blocked = com.gatecontrol.android.scan.MediaScan("infected", blocked = true, finding = "Eicar")
        val kept = listOf(
            MDraftAttachment(21, "a.docm", 10, "x/y", asLink = false, url = null, scan = com.gatecontrol.android.scan.MediaScan("pending", blocked = true)),
            MDraftAttachment(22, "b.pdf", 10, "application/pdf", asLink = false, url = null),
            MDraftAttachment(23, "c.txt", 1, "text/plain", asLink = false, url = null),
        )
        val fresh = listOf(kept[0].copy(name = "server.docm", scan = blocked), kept[1].copy(scan = com.gatecontrol.android.scan.MediaScan("clean", blocked = false)))
        val merged = MailLogic.withDraftScans(kept, fresh)
        assertEquals(blocked, merged[0].scan)
        assertEquals("a.docm", merged[0].name, "only the scan changes")
        assertEquals("clean", merged[1].scan!!.state)
        assertEquals(kept[2], merged[2], "unknown to the server: as it was")
        val warnings = MailLogic.withDraftScanWarnings(
            listOf(MWarning("no_subject", "x"), MWarning("attachment_blocked", "alt")),
            listOf(MWarning("too_large", "y"), MWarning("attachment_blocked", "neu")),
        )
        assertEquals(listOf(MWarning("no_subject", "x"), MWarning("attachment_blocked", "neu")), warnings)
        assertEquals(listOf(MWarning("no_subject", "x")), MailLogic.withDraftScanWarnings(warnings, emptyList()), "released: the warning goes")
    }
}
