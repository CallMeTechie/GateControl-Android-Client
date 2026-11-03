package com.gatecontrol.android.drive

import com.gatecontrol.android.drive.backup.DriveBackupLogic
import com.gatecontrol.android.scan.MediaScan
import com.gatecontrol.android.scan.ScanBadge
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drive 1.19: the host's virus scan at the nodes, the refusals and what the app leaves out for a blocked file. */
class DriveScanTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun node(id: Long, name: String = "f$id.docx", scan: MediaScan? = null, dir: Boolean = false) = DNode(
        id = id, areaId = "u:1", parentId = 1, name = name, isDir = dir, size = 100, mime = null, mediaId = null, rev = 1,
        mtime = null, updatedAt = null, updatedBy = null, scan = scan,
    )

    private fun me(vararg features: String) = DMe(
        userId = 1, name = "Ma", seat = "family", licenceValid = true, tier = null, areas = emptyList(), latest = 0, trashDays = 30,
        maxBytes = null, people = emptyMap(), features = features.toSet(),
    )

    private val writable = DriveBulk.Where(writable = { true })
    private val blocked = MediaScan(MediaScan.INFECTED, blocked = true, finding = "Eicar-Test-Signature")
    private val marked = MediaScan(MediaScan.INFECTED, blocked = false, finding = "GC.Office.Macro")

    @Test
    fun `a node carries the scan, a node without it stays as before`() {
        val n = DriveJson.node(
            json("""{"id":5,"areaId":"u:1","name":"a.exe","size":68,"rev":1,"preview":null,
                "scan":{"state":"infected","blocked":true,"finding":"Eicar-Test-Signature"}}"""),
        )!!
        assertTrue(n.blocked)
        assertEquals(ScanBadge.BLOCKED_MALWARE, n.scan!!.badge)
        assertFalse(n.preview, "no preview for a blocked file")
        val old = DriveJson.node(json("""{"id":6,"areaId":"u:1","name":"a.pdf","size":1,"rev":1}"""))!!
        assertNull(old.scan)
        assertFalse(old.blocked)
        val pending = DriveJson.node(json("""{"id":7,"areaId":"u:1","name":"v.mp4","rev":1,"scan":{"state":"pending","blocked":true,"finding":null}}"""))!!
        assertEquals(ScanBadge.CHECKING, pending.scan!!.badge)
    }

    @Test
    fun `the change log tolerates the scan op and keeps it out of Zuletzt`() {
        val c = DriveJson.changes(
            json("""{"changes":[{"seq":3,"op":"scan","areaId":"u:1","nodeId":5,"node":{"id":5,"areaId":"u:1","name":"a.exe","rev":2,
                "scan":{"state":"infected","blocked":true}}},
                {"seq":2,"op":"create","areaId":"u:1","nodeId":6,"node":{"id":6,"areaId":"u:1","name":"b.pdf","rev":1}}],"next":3,"more":false}"""),
        )!!
        assertEquals("scan", c.changes[0].op)
        assertTrue(c.changes[0].node!!.blocked)
        assertEquals(listOf(6L), DriveLogic.recent(c.changes).map { it.id }, "no content change")
    }

    @Test
    fun `blocked files are not sent, saved, linked or opened in the office`() {
        val me = me(DMe.FEATURE_BULK, DMe.FEATURE_ZIP, DMe.FEATURE_MULTI_LINK, DMe.FEATURE_OFFICE)
        val bad = node(1, scan = blocked)
        val good = node(2)
        listOf(DriveBulk.Action.SEND, DriveBulk.Action.SAVE, DriveBulk.Action.LINK).forEach { a ->
            assertEquals(DriveBulk.Why.SCAN_BLOCKED, DriveBulk.can(a, listOf(bad, good), writable, me).why, a.name)
            assertTrue(DriveBulk.can(a, listOf(good), writable, me).ok, a.name)
        }
        // the server leaves blocked files out of a ZIP (and says so); only blocked ones make none
        assertTrue(DriveBulk.can(DriveBulk.Action.ZIP, listOf(bad, good), writable, me).ok)
        assertEquals(DriveBulk.Why.SCAN_BLOCKED, DriveBulk.can(DriveBulk.Action.ZIP, listOf(bad), writable, me).why)
        assertFalse(DriveLogic.officeAllowed(me, bad))
        assertTrue(DriveLogic.officeAllowed(me, good))
        // marked, not blocked: everything stays (the app asks before opening)
        assertTrue(DriveBulk.can(DriveBulk.Action.SEND, listOf(node(3, scan = marked)), writable, me).ok)
        assertTrue(DriveLogic.officeAllowed(me, node(3, scan = marked)))
        // moving and deleting are not the scan's business
        assertTrue(DriveBulk.can(DriveBulk.Action.MOVE, listOf(bad), writable, me).ok)
        assertTrue(DriveBulk.can(DriveBulk.Action.DELETE, listOf(bad), writable, me).ok)
    }

    @Test
    fun `a ZIP says how many blocked files it left out`() {
        val z = DriveJson.zip(json("""{"zip":{"mediaId":"abcdefgh12","name":"Fotos"},"skipped":[{"id":1,"reason":"blocked"},{"id":2,"reason":"blocked"},{"id":3,"reason":"gone"}]}"""))!!
        assertEquals(2, z.blocked)
        assertEquals(0, DriveJson.zip(json("""{"zip":{"mediaId":"abcdefgh12","name":"Fotos"}}"""))!!.blocked)
    }

    @Test
    fun `the refusals keep their code (423 is not always the ransomware protection)`() {
        assertEquals("quarantined", DriveJson.failureCode(423, json("""{"ok":false,"code":"quarantined","error":"x"}""")))
        assertEquals("scan_pending", DriveJson.failureCode(423, json("""{"ok":false,"code":"scan_pending"}""")))
        assertEquals(DriveJson.DEVICE_LOCKED, DriveJson.failureCode(423, null))
        assertEquals("infected", DriveJson.failureCode(422, json("""{"ok":false,"code":"infected","finding":"Eicar"}""")))
    }

    @Test
    fun `the photo backup skips a rejected photo and waits for the scan`() {
        assertEquals(DriveBackupLogic.Next.SKIP, DriveBackupLogic.next("infected"))
        assertEquals(DriveBackupLogic.Next.RETRY, DriveBackupLogic.next("scan_unavailable"))
    }
}
