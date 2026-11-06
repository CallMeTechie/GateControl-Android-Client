package com.gatecontrol.android.print

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrintLogicTest {

    private fun printer(online: Boolean = true, state: String = "idle", problem: String? = null, color: Boolean = true, duplex: Boolean = true, copiesMax: Int = 99) =
        PPrinter("u:1", "HP", null, online, problem, state, color, duplex, copiesMax)

    private fun job(state: String, error: String? = null) = PJob("pj_1", "x", null, state, error, null, 1, 1)

    @Test
    fun `uploads follow the plugin's media types`() {
        assertEquals(PrintLogic.Upload.AS_IS, PrintLogic.upload("application/pdf"))
        assertEquals(PrintLogic.Upload.AS_IS, PrintLogic.upload("image/JPEG"))
        assertEquals(PrintLogic.Upload.AS_IS, PrintLogic.upload("image/jpg"))
        assertEquals(PrintLogic.Upload.AS_IS, PrintLogic.upload("image/heic"))
        assertEquals(PrintLogic.Upload.AS_IS, PrintLogic.upload("image/png; charset=binary"))
        // pictures the plugin does not take go up as a JPEG made here
        assertEquals(PrintLogic.Upload.AS_JPEG, PrintLogic.upload("image/gif"))
        assertEquals(PrintLogic.Upload.AS_JPEG, PrintLogic.upload("image/bmp"))
        assertNull(PrintLogic.upload("image/svg+xml"))
        assertNull(PrintLogic.upload("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertNull(PrintLogic.upload("text/plain"))
        assertNull(PrintLogic.upload(null))
        assertEquals("image/jpeg", PrintLogic.uploadType("image/jpg"))
        assertEquals("image/jpeg", PrintLogic.uploadType("image/gif"))
        assertEquals("application/pdf", PrintLogic.uploadType("application/pdf"))
    }

    @Test
    fun `colour needs a colour printer and no black-and-white rule`() {
        assertTrue(PrintLogic.colorAllowed(printer(), PRights(true, 0, false)))
        assertFalse(PrintLogic.colorAllowed(printer(), PRights(true, 20, true)))
        assertFalse(PrintLogic.colorAllowed(printer(color = false), PRights(true, 0, false)))
        assertEquals(1..5, PrintLogic.copiesRange(printer(copiesMax = 5)))
    }

    @Test
    fun `a printer that is off still takes jobs`() {
        assertEquals(PrintLogic.Availability.IDLE, PrintLogic.availability(printer()))
        assertEquals(PrintLogic.Availability.BUSY, PrintLogic.availability(printer(online = false)))
        assertEquals(PrintLogic.Availability.BUSY, PrintLogic.availability(printer(state = "processing")))
        assertEquals(PrintLogic.Availability.BUSY, PrintLogic.availability(printer(problem = "cert_changed")))
        assertNull(PrintLogic.note(printer()))
        assertEquals(PrintLogic.Note.OFF, PrintLogic.note(printer(online = false)))
        assertEquals(PrintLogic.Note.OFF, PrintLogic.note(printer(state = "offline")))
        assertEquals(PrintLogic.Note.STOPPED, PrintLogic.note(printer(state = "stopped")))
        assertEquals(PrintLogic.Note.BUSY, PrintLogic.note(printer(state = "processing")))
        assertEquals(PrintLogic.Note.CERT, PrintLogic.note(printer(online = false, problem = "cert_changed")))
    }

    @Test
    fun `job states map to the print framework`() {
        for (s in listOf("queued", "sending", "printing", "something_new")) assertEquals(PrintLogic.Phase.RUNNING, PrintLogic.phase(s))
        assertEquals(PrintLogic.Phase.WAITING, PrintLogic.phase("waiting"))
        assertEquals(PrintLogic.Phase.DONE, PrintLogic.phase("done"))
        assertEquals(PrintLogic.Phase.FAILED, PrintLogic.phase("failed"))
        assertEquals(PrintLogic.Phase.FAILED, PrintLogic.phase("expired"))
        assertEquals(PrintLogic.Phase.CANCELED, PrintLogic.phase("canceled"))
        assertTrue(PrintLogic.isFinal(PrintLogic.Phase.DONE))
        assertFalse(PrintLogic.isFinal(PrintLogic.Phase.WAITING))
        assertEquals("expired", PrintLogic.failReason(job("expired")))
        assertEquals("format_unsupported", PrintLogic.failReason(job("failed", "format_unsupported")))
        assertEquals("failed", PrintLogic.failReason(job("failed")))
    }

    @Test
    fun `polling slows down and a waiting job is asked every two minutes`() {
        assertEquals(5_000L, PrintLogic.pollDelayMs(10_000L, PrintLogic.Phase.RUNNING))
        assertEquals(20_000L, PrintLogic.pollDelayMs(5 * 60_000L, PrintLogic.Phase.RUNNING))
        assertEquals(60_000L, PrintLogic.pollDelayMs(60 * 60_000L, PrintLogic.Phase.RUNNING))
        assertEquals(120_000L, PrintLogic.pollDelayMs(10_000L, PrintLogic.Phase.WAITING))
    }

    @Test
    fun `job names are plain and short`() {
        assertEquals("GateControl", PrintLogic.jobName(null))
        assertEquals("GateControl", PrintLogic.jobName("  \n "))
        assertEquals("Bordkarte Mallorca.pdf", PrintLogic.jobName("Bordkarte\nMallorca.pdf"))
        assertEquals(120, PrintLogic.jobName("x".repeat(500)).length)
    }
}
