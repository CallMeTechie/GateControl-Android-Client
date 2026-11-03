package com.gatecontrol.android.gatedrop

import com.gatecontrol.android.scan.MediaScan
import com.gatecontrol.android.scan.ScanRetry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** GateDrop 1.4: the host's virus scan at the items, the send's refusals (no feature flag: the field decides). */
class GateDropScanTest {

    private fun obj(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val blocked = MediaScan(MediaScan.INFECTED, blocked = true, finding = "Eicar-Test-Signature")

    @Test
    fun `an item carries the scan, an item without it stays as before`() {
        val e = GateDropJson.entry(
            obj("""{"id":"0123456789abcdef01234567","items":[
                {"mediaId":null,"name":"a.exe","mime":"application/octet-stream","bytes":68,"available":false,
                 "scan":{"state":"infected","blocked":true,"finding":"Eicar-Test-Signature"}},
                {"mediaId":"m1234567890","name":"b.pdf","mime":"application/pdf","bytes":10}]}"""),
        )!!
        assertEquals(blocked, e.items[0].scan)
        assertFalse(e.items[0].available)
        assertNull(e.items[0].mediaId)
        assertNull(e.items[1].scan)
        assertTrue(e.items[1].available)
    }

    @Test
    fun `a blocked item is not fetched, even if the server still named its media`() {
        val send = GdEntry("7", at = 1, items = listOf(GdItem("m1234567890", "a.exe", "application/octet-stream", 10, scan = blocked)))
        val merged = GateDropLogic.mergeReceived(listOf(send), emptyList()).single().files.single()
        assertNull(merged.mediaId)
        assertEquals(blocked, merged.scan)
        val local = GdReceived("s:7", "7", null, 1, files = listOf(merged))
        assertEquals(GdPendingAction.SKIP, GateDropLogic.pendingAction(send, handled = false, local = local), "nothing to fetch again")
        val open = send.copy(items = listOf(send.items[0].copy(scan = MediaScan(MediaScan.CLEAN, blocked = false))))
        assertEquals("m1234567890", GateDropLogic.mergeReceived(listOf(open), emptyList()).single().files.single().mediaId)
    }

    @Test
    fun `the scan of a received file is kept on the device`() {
        val r = GdReceived("s:7", "7", "Anna", 5, files = listOf(GdReceivedFile("a.exe", "application/octet-stream", 68, scan = blocked), GdReceivedFile("b.pdf", "application/pdf", 1)))
        assertEquals(listOf(r), GateDropStoreJson.receivedList(GateDropStoreJson.receivedJson(listOf(r))))
    }

    @Test
    fun `423 scan_pending sends again every 5 s for at most 10 minutes`() {
        val pending = GdResult.Failed("scan_pending", 423)
        assertEquals(ScanRetry.SEND_EVERY_MS, GateDropLogic.scanWaitMs(pending, 0))
        assertEquals(ScanRetry.SEND_EVERY_MS, GateDropLogic.scanWaitMs(pending, ScanRetry.SEND_WAIT_MS - 1))
        assertNull(GateDropLogic.scanWaitMs(pending, ScanRetry.SEND_WAIT_MS), "waited long enough")
        assertNull(GateDropLogic.scanWaitMs(GdResult.Failed("scan_blocked", 423, "a.pdf"), 0))
        assertNull(GateDropLogic.scanWaitMs(GdResult.Failed("infected", 422, "a.exe"), 0))
        assertNull(GateDropLogic.scanWaitMs(GdResult.Ok(Unit), 0))
        assertNull(GateDropLogic.scanWaitMs(GdResult.Offline, 0))
    }

    @Test
    fun `a delivery the scan held back says why, other failures stay plain`() {
        val drive = GdDelivery("drive", "mine", "Mein Drive", GdDelivery.FAILED, error = "quarantined")
        val ok = GdDelivery("device", "d1", "Pixel", GdDelivery.DELIVERED)
        assertEquals("quarantined", GateDropLogic.scanFailure(listOf(ok, drive)))
        assertEquals("scan_pending", GateDropLogic.scanFailure(listOf(drive.copy(error = "scan_pending"))))
        assertEquals("scan_blocked", GateDropLogic.scanFailure(listOf(drive.copy(error = "scan_blocked"))))
        assertNull(GateDropLogic.scanFailure(listOf(drive.copy(error = "no_seat"))), "Drive's own refusal")
        assertNull(GateDropLogic.scanFailure(listOf(drive.copy(error = null))))
        // informational on a delivered one: not a failure
        assertNull(GateDropLogic.scanFailure(listOf(drive.copy(status = GdDelivery.DELIVERED))))
        assertEquals(GdDelivery.FAILED, GateDropLogic.overallStatus(listOf(ok, drive)))
    }

    @Test
    fun `a delivery's error is read as the server sends it`() {
        val d = GateDropJson.delivery(obj("""{"kind":"drive","target":"mine","label":"Mein Drive","status":"failed","error":"scan_pending"}"""))!!
        assertEquals("scan_pending", d.error)
        assertEquals("scan_pending", GateDropLogic.scanFailure(listOf(d)))
    }
}
