package com.gatecontrol.android.familie.school

import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.FamilieStorage
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plugin 1.5: opening a message reads it at once on the device (badge and
 * card count go), then `POST …/read`; an older plugin's 404 is ignored and
 * the local mark stays; the confirmation replaces the message.
 */
class SchoolMessageReadTest {

    private val api = mockk<FamilieApi>()
    private val storage = mockk<FamilieStorage> {
        every { ownerKey() } returns "owner"
        every { read<Any?>(any(), any()) } returns null
        every { write(any(), any()) } just runs
        every { delete(any()) } just runs
    }
    private val repository = SchoolRepository(api, storage)

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    private fun answer(vararg messages: String): FamilieResult<SchoolListFetch<SchoolMessage>> {
        val raw = """{"ok":true,"messages":[${messages.joinToString(",")}]}"""
        return FamilieResult.Ok(SchoolListFetch(SchoolJson.messages(obj(raw))!!, raw))
    }

    private fun msg(id: String, isNew: Boolean, read: Boolean = !isNew, extra: String = ""","fullText":true""") =
        """{"id":"$id","studentIds":[3],"subject":"S $id","isNew":$isNew,"read":$read$extra}"""

    /** A message of plugin 1.4: no `read` / `fullText`. */
    private fun oldMsg(id: String, isNew: Boolean) = """{"id":"$id","studentIds":[3],"subject":"S $id","isNew":$isNew}"""

    private fun readAnswer(json: String) = FamilieResult.Ok(SchoolJson.messageAnswer(obj("""{"ok":true,"message":$json}""")))

    private suspend fun load(vararg messages: String) {
        coEvery { api.schoolMessages(any()) } returns answer(*messages)
        coEvery { api.schoolAbsences(any()) } returns FamilieResult.Failed("not_found", 404)
        repository.refreshInbox()
    }

    private fun newCount() = SchoolLogic.newMessages(repository.inbox.value?.messages, 3)

    @Test
    fun `opening reads at once and then on the server`() = runBlocking {
        load(msg("1", isNew = true), msg("2", isNew = true))
        assertEquals(2, newCount())
        coEvery { api.schoolMessageRead("1") } returns readAnswer(msg("1", isNew = false))

        repository.markRead("1")

        val m = repository.inbox.value!!.messages!!.first { it.id == "1" }
        assertFalse(m.isNew)
        assertTrue(m.read)
        assertEquals(1, newCount(), "the card counts only the unread one")
        coVerify(exactly = 1) { api.schoolMessageRead("1") }
    }

    @Test
    fun `an older plugin answers 404 - ignored, the message stays read on the device`() = runBlocking {
        load(oldMsg("1", isNew = true))
        coEvery { api.schoolMessageRead("1") } returns FamilieResult.Failed("http_404", 404)

        repository.markRead("1")
        assertEquals(0, newCount())

        // The old plugin still calls it new: the local mark stays, no new call.
        load(oldMsg("1", isNew = true))
        assertEquals(0, newCount())
        coVerify(exactly = 1) { api.schoolMessageRead("1") }
    }

    @Test
    fun `a read that did not get through is sent again with the next load`() = runBlocking {
        load(msg("1", isNew = true))
        coEvery { api.schoolMessageRead("1") } returns FamilieResult.Offline
        repository.markRead("1")
        assertEquals(0, newCount(), "optimistic, also when offline")

        coEvery { api.schoolMessageRead("1") } returns readAnswer(msg("1", isNew = false))
        load(msg("1", isNew = true))
        assertEquals(0, newCount())
        coVerify(exactly = 2) { api.schoolMessageRead("1") }

        // The server now knows: the mark is no longer needed, nothing more is sent.
        load(msg("1", isNew = false))
        load(msg("1", isNew = false))
        coVerify(exactly = 2) { api.schoolMessageRead("1") }
    }

    @Test
    fun `a message already read is not sent again`() = runBlocking {
        load(msg("1", isNew = false))
        repository.markRead("1")
        coVerify(exactly = 0) { api.schoolMessageRead(any()) }
    }

    @Test
    fun `an unknown message is not sent`() = runBlocking {
        load(msg("1", isNew = true))
        repository.markRead("nope")
        coVerify(exactly = 0) { api.schoolMessageRead(any()) }
        assertEquals(1, newCount())
    }

    @Test
    fun `a confirmation replaces the message with the server's`() = runBlocking {
        load(msg("1", isNew = true, extra = ""","fullText":true,"confirmation":{"confirmedAt":null,"by":null}"""))
        assertTrue(repository.inbox.value!!.messages!!.single().confirmationPending)
        coEvery { api.schoolMessageConfirm("1") } returns readAnswer(
            msg("1", isNew = false, extra = ""","fullText":true,"confirmation":{"confirmedAt":"2026-10-20T07:15:00Z","by":"Anna"}"""),
        )

        val r = repository.confirm("1")

        val m = (r as FamilieResult.Ok).value!!
        assertFalse(m.confirmationPending)
        assertEquals("Anna", m.confirmation?.by)
        assertEquals(m, repository.inbox.value!!.messages!!.single())
        assertEquals(0, newCount(), "confirming also reads it")
    }

    @Test
    fun `a refused confirmation is told and changes nothing`() = runBlocking {
        load(msg("1", isNew = false, extra = ""","fullText":true,"confirmation":{"confirmedAt":null}"""))
        coEvery { api.schoolMessageConfirm("1") } returns FamilieResult.Failed("SCHULE_UNTIS_UNAVAILABLE", 503)
        val r = repository.confirm("1")
        assertEquals("SCHULE_UNTIS_UNAVAILABLE", (r as FamilieResult.Failed).code)
        assertTrue(repository.inbox.value!!.messages!!.single().confirmationPending)
    }

    // ─── pure parts ───────────────────────────────────────────────────

    @Test
    fun `local read marks and what to keep after a fresh list`() {
        val a = SchoolMessage(id = "a", studentIds = listOf(3), isNew = true, extended = true)
        val b = SchoolMessage(id = "b", studentIds = listOf(3), isNew = true)
        val c = SchoolMessage(id = "c", studentIds = listOf(3, 4), isNew = false, read = true, extended = true)
        val list = listOf(a, b, c)

        val shown = SchoolLogic.withLocalRead(list, setOf("a"))!!
        assertFalse(shown[0].isNew)
        assertTrue(shown[0].read)
        assertEquals(b, shown[1])
        assertEquals(1, SchoolLogic.newMessages(shown, 3))
        assertNull(SchoolLogic.withLocalRead(null, setOf("a")))
        assertEquals(list, SchoolLogic.withLocalRead(list, emptySet()))

        assertEquals(setOf("a", "b"), SchoolLogic.pendingLocalRead(setOf("a", "b", "c", "gone"), list), "only those the server still calls new")
        assertEquals(setOf("x"), SchoolLogic.pendingLocalRead(setOf("x"), null), "no list: kept")
        assertEquals(listOf("a"), SchoolLogic.readToRetry(setOf("a", "b"), list), "only plugins that keep the read state")
        assertEquals(1, SchoolLogic.readToRetry((1..9).map { "$it" }.toSet(), (1..9).map { a.copy(id = "$it") }, max = 1).size)
    }

    @Test
    fun `file kinds, safe names and cache folders`() {
        assertEquals(SchoolFileKind.PDF, SchoolLogic.fileKind("application/pdf", null))
        assertEquals(SchoolFileKind.PDF, SchoolLogic.fileKind(null, "Brief.PDF"))
        assertEquals(SchoolFileKind.IMAGE, SchoolLogic.fileKind("image/jpeg", "x"))
        assertEquals(SchoolFileKind.SHEET, SchoolLogic.fileKind("text/csv", null))
        assertEquals(SchoolFileKind.DOCUMENT, SchoolLogic.fileKind("application/vnd.openxmlformats-officedocument.wordprocessingml.document", null))
        assertEquals(SchoolFileKind.DOCUMENT, SchoolLogic.fileKind(null, "liste.docx"))
        assertEquals(SchoolFileKind.MEDIA, SchoolLogic.fileKind("audio/mpeg", null))
        assertEquals(SchoolFileKind.ARCHIVE, SchoolLogic.fileKind("application/zip", null))
        assertEquals(SchoolFileKind.OTHER, SchoolLogic.fileKind(null, null))

        assertEquals("Elternbrief.pdf", SchoolLogic.safeFileName("Elternbrief.pdf", "pdf"))
        assertEquals("a_b_c.pdf", SchoolLogic.safeFileName("a/b:c.pdf", "pdf"))
        assertEquals("hidden", SchoolLogic.safeFileName("..hidden", "bin"))
        assertEquals("anhang.pdf", SchoolLogic.safeFileName("  ", "pdf"))
        assertEquals("anhang.bin", SchoolLogic.safeFileName(null, "bin"))
        val long = SchoolLogic.safeFileName("x".repeat(300) + ".pdf", "pdf")
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".pdf"))

        assertEquals("3-12345_a1b2", SchoolLogic.attachmentFolder("3-12345", "a1b2"))
        assertEquals("ab_c_d", SchoolLogic.attachmentFolder("ab", "c:d"))
        assertFalse(SchoolLogic.attachmentFolder("..", "x").startsWith("."))
    }
}
