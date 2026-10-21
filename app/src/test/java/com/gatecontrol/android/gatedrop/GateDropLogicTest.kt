package com.gatecontrol.android.gatedrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GateDropLogicTest {

    private val pc = GdDevice("pc", "Büro-PC", "windows", online = true, direct = true)
    private val laptop = GdDevice("lap", "Laptop", "windows", online = false, direct = false)
    private val phone = GdDevice("ph", "Handy", "android", online = true, direct = false)
    private val targets = GdTargets(
        me = GdMe(canLink = true, canNas = true),
        people = listOf(GdPerson("family", "Familie", kind = GdPerson.KIND_FAMILY), GdPerson("7", "Anna")),
        devices = listOf(pc, laptop, phone),
        nas = listOf(GdNas("f1", "Fotos", "/Familie/Fotos")),
        linksEnabled = true,
    )

    @Test
    fun `device states`() {
        assertEquals(GdDeviceState.DIRECT, GateDropLogic.deviceState(pc))
        assertEquals(GdDeviceState.OFFLINE, GateDropLogic.deviceState(laptop))
        assertEquals(GdDeviceState.ONLINE, GateDropLogic.deviceState(phone))
        // "direct" without being online is not direct.
        assertEquals(GdDeviceState.OFFLINE, GateDropLogic.deviceState(pc.copy(online = false)))
    }

    @Test
    fun `selection becomes recipients limited to what is offered`() {
        val sel = GdSelection().togglePerson("family").togglePerson("7").togglePerson("gone").toggleDevice("pc").toggleNas("f1")
            .copy(link = GdLinkRequest(GdExpiry.WEEK, pin = true))
        val to = GateDropLogic.recipients(sel, targets)
        assertEquals(listOf("family", "7"), to.people)
        assertEquals(listOf("pc"), to.devices)
        assertEquals(listOf(GdNasRef("f1", "/Familie/Fotos")), to.nas)
        assertEquals(GdExpiry.WEEK, to.link!!.expiry)

        // No right for NAS / links (or links switched off): dropped.
        val limited = targets.copy(me = GdMe(canLink = false, canNas = false))
        val to2 = GateDropLogic.recipients(sel, limited)
        assertTrue(to2.nas.isEmpty())
        assertNull(to2.link)
        assertNull(GateDropLogic.recipients(sel, targets.copy(linksEnabled = false)).link)

        assertEquals(listOf("Familie", "Anna", "Büro-PC", "Fotos"), GateDropLogic.chosenNames(sel, targets))
        // Toggling twice removes again.
        assertTrue(GdSelection().toggleDevice("pc").toggleDevice("pc").isEmpty)
    }

    @Test
    fun `can send`() {
        val none = GdSelection()
        assertFalse(GateDropLogic.canSend(none, targets, 1, false))
        assertFalse(GateDropLogic.canSend(none.toggleDevice("pc"), targets, 0, false))
        assertTrue(GateDropLogic.canSend(none.toggleDevice("pc"), targets, 0, true))
        assertTrue(GateDropLogic.canSend(none.toggleDevice("pc"), targets, 20, false))
        assertFalse(GateDropLogic.canSend(none.toggleDevice("pc"), targets, 21, false))
        // A link alone needs files.
        val linkOnly = none.copy(link = GdLinkRequest())
        assertFalse(GateDropLogic.canSend(linkOnly, targets, 0, true))
        assertTrue(GateDropLogic.canSend(linkOnly, targets, 2, false))
        // Only unknown targets.
        assertFalse(GateDropLogic.canSend(none.toggleDevice("gone"), targets, 1, false))
    }

    @Test
    fun `plan - only direct devices go local, everything else uploads first`() {
        val devices = targets.devices
        val directOnly = GateDropLogic.plan(2, GdRecipients(devices = listOf("pc")), devices)
        assertFalse(directOnly.uploadFirst)
        assertTrue(directOnly.localItems)

        assertTrue(GateDropLogic.plan(2, GdRecipients(devices = listOf("pc", "lap")), devices).uploadFirst)
        assertTrue(GateDropLogic.plan(2, GdRecipients(devices = listOf("ph")), devices).uploadFirst)
        assertTrue(GateDropLogic.plan(2, GdRecipients(people = listOf("7"), devices = listOf("pc")), devices).uploadFirst)
        assertTrue(GateDropLogic.plan(2, GdRecipients(nas = listOf(GdNasRef("f1")), devices = listOf("pc")), devices).uploadFirst)
        assertTrue(GateDropLogic.plan(2, GdRecipients(devices = listOf("pc"), link = GdLinkRequest()), devices).uploadFirst)
        // Unknown device: not known to be direct.
        assertTrue(GateDropLogic.plan(1, GdRecipients(devices = listOf("gone")), devices).uploadFirst)
        // Text only: nothing to upload.
        val text = GateDropLogic.plan(0, GdRecipients(people = listOf("7")), devices)
        assertFalse(text.uploadFirst)
        assertFalse(text.localItems)
    }

    @Test
    fun `fallback decision after direct transfers`() {
        val upload = GdTransferAnswer("upload", listOf(1))
        // Uploaded first: the server delivers anyway.
        assertTrue(GateDropLogic.missingAfterDirect(false, 3, listOf(false to upload)).isEmpty())
        // Every device reached directly.
        assertTrue(GateDropLogic.missingAfterDirect(true, 3, listOf(true to GdTransferAnswer())).isEmpty())
        // The plugin asks for the missing files.
        assertEquals(listOf(1), GateDropLogic.missingAfterDirect(true, 3, listOf(false to upload)))
        assertEquals(listOf(0, 1, 2), GateDropLogic.missingAfterDirect(true, 3, listOf(false to GdTransferAnswer("upload", null))))
        // `server`: the files are there already.
        assertTrue(GateDropLogic.missingAfterDirect(true, 3, listOf(false to GdTransferAnswer("server"))).isEmpty())
        // The failure did not reach the plugin: everything up.
        assertEquals(listOf(0, 1), GateDropLogic.missingAfterDirect(true, 2, listOf(false to null)))
        // Two devices: the union.
        assertEquals(listOf(0, 1), GateDropLogic.missingAfterDirect(true, 2, listOf(false to GdTransferAnswer("upload", listOf(0)), false to GdTransferAnswer("upload", listOf(1, 7)))))
        assertTrue(GateDropLogic.uploadRequired("upload_required", 409))
        assertFalse(GateDropLogic.uploadRequired("media_invalid", 409))
        assertTrue(GateDropLogic.serverWantsUpload("upload"))
        assertFalse(GateDropLogic.serverWantsUpload(null))
        assertEquals(2_000L, GateDropLogic.CONNECT_TIMEOUT_MS)
    }

    @Test
    fun `shared text as link or text`() {
        assertEquals("https://bahn.example/ticket?a=1", GateDropLogic.asLink(" https://bahn.example/ticket?a=1\n".trim()))
        assertNull(GateDropLogic.asLink("Schau mal https://x.example"))
        assertNull(GateDropLogic.asLink("WLAN: geheim"))
        assertNull(GateDropLogic.asLink(null))
    }

    @Test
    fun `subnets and candidate order`() {
        assertTrue(GateDropLogic.inSubnet("192.168.178.20", "192.168.178.5", 24))
        assertFalse(GateDropLogic.inSubnet("192.168.179.20", "192.168.178.5", 24))
        assertTrue(GateDropLogic.inSubnet("10.1.2.3", "10.9.9.9", 8))
        assertTrue(GateDropLogic.inSubnet("1.2.3.4", "9.9.9.9", 0))
        assertFalse(GateDropLogic.inSubnet("x", "10.0.0.1", 8))
        val c = listOf(GdCandidate("10.0.0.7", 47731), GdCandidate("192.168.1.20", 47731))
        assertEquals(listOf(GdCandidate("192.168.1.20", 47731), GdCandidate("10.0.0.7", 47731)), GateDropLogic.orderCandidates(c, listOf("192.168.1.5" to 24)))
        assertEquals(c, GateDropLogic.orderCandidates(c, emptyList()))
    }

    @Test
    fun `auto save and overall status`() {
        assertTrue(GateDropLogic.autoSave(1_000_000_000, unmetered = true))
        assertTrue(GateDropLogic.autoSave(1_000_000, unmetered = false))
        assertFalse(GateDropLogic.autoSave(100_000_000, unmetered = false))
        assertNull(GateDropLogic.overallStatus(emptyList()))
        val d = { s: String -> GdDelivery("device", "x", status = s) }
        assertEquals(GdDelivery.PENDING, GateDropLogic.overallStatus(listOf(d(GdDelivery.DELIVERED), d(GdDelivery.PENDING))))
        assertEquals(GdDelivery.FAILED, GateDropLogic.overallStatus(listOf(d(GdDelivery.FAILED), d(GdDelivery.OPENED))))
        assertEquals(GdDelivery.DELIVERED, GateDropLogic.overallStatus(listOf(d(GdDelivery.DELIVERED), d(GdDelivery.OPENED))))
    }

    @Test
    fun `received lists merge server and direct copies`() {
        val server = listOf(
            GdEntry("5", at = 200, from = "Büro-PC", items = listOf(GdItem("m1234567890", "a.pdf", "application/pdf", 12))),
            GdEntry("6", at = 300, from = "Laptop", items = listOf(GdItem(null, "b.jpg", "image/jpeg", 34))),
        )
        val local = listOf(
            GdReceived("s:5", "5", "Büro-PC", 200, files = listOf(GdReceivedFile("a.pdf", "application/pdf", 12, "m1234567890", "content://dl/1"))),
            GdReceived("t:tr1", "6", "Laptop", 300, files = listOf(GdReceivedFile("b.jpg", "image/jpeg", 34, null, "content://dl/2"))),
            GdReceived("t:tr2", null, "Handy", 100, text = "nur hier"),
        )
        val merged = GateDropLogic.mergeReceived(server, local)
        assertEquals(listOf("s:6", "s:5", "t:tr2"), merged.map { it.key })
        assertEquals("content://dl/2", merged[0].files.single().uri)
        assertEquals("content://dl/1", merged[1].files.single().uri)
        // Offline: what this device knows.
        assertEquals(listOf("t:tr1", "s:5", "t:tr2"), GateDropLogic.mergeReceived(null, local).map { it.key })
    }
}
