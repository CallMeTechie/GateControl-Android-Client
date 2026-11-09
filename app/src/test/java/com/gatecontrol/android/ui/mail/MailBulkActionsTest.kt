package com.gatecontrol.android.ui.mail

import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MBulkDone
import com.gatecontrol.android.mail.MBulkUndo
import com.gatecontrol.android.mail.MFolder
import com.gatecontrol.android.mail.MIds
import com.gatecontrol.android.mail.MLimits
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MMicrosoft
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MSettings
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MUndone
import com.gatecontrol.android.mail.MailApi
import com.gatecontrol.android.mail.MailBulk.Action
import com.gatecontrol.android.mail.MailCache
import com.gatecontrol.android.mail.MailRepository
import com.google.gson.JsonObject
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The selection's actions (Mail 1.13): parts of 500, the list at once and back on refusal, the question before a
 * delete, the snackbar with "Rückgängig" (only with `undo`), the folder picker, "Alle" from the server.
 */
class MailBulkActionsTest {

    private val api: MailApi = mockk(relaxed = true)
    private val cache: MailCache = mockk(relaxed = true)
    private val repository: MailRepository = mockk(relaxed = true)
    private val me = MutableStateFlow<MMe?>(null)
    private val folders = MutableStateFlow<Map<Long, List<MFolder>>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var items = listOf<MSummary>()
    private var reloads = 0

    private fun s(id: Long, account: Long = 1, folder: Long = 10, unread: Boolean = false) = MSummary(
        id = id, accountId = account, folderId = folder, threadId = null, threadCount = 1, from = MAddress("A", "a@b.de"), to = emptyList(),
        subject = "s$id", snippet = "", date = id, unread = unread, flagged = false, answered = false, hasAttachments = false,
        tab = MSummary.TAB_RELEVANT, savedToDrive = false, aiSummary = null,
    )

    private fun me(vararg features: String, settings: MSettings = MSettings.DEFAULT) = MMe(
        features = setOf(MMe.FEATURE_MAIL) + features, limits = MLimits(5, 0, 0, 12),
        microsoft = MMicrosoft(null, "https://login.microsoftonline.com/common", emptyList()), accounts = emptyList(),
        unreadAll = 0, unreadByAccount = emptyMap(), settings = settings,
    )

    private fun done(n: Int, token: String? = null, purged: List<Long> = emptyList()) =
        MResult.Ok(MBulkDone(emptyList(), n, emptyList(), token?.let { MBulkUndo(it, "move", n, null) }, purged))

    private fun bulk(conversations: Boolean = true, query: Map<String, String>? = mapOf("box" to "all", "tab" to "relevant", "limit" to "50")) = MailBulkActions(
        scope = scope,
        repository = repository,
        items = { items },
        setItems = { f -> items = f(items) },
        tabbed = { true },
        listQuery = { query },
        conversations = { conversations },
        changed = { reloads++ },
    )

    @BeforeEach
    fun setUp() {
        every { repository.api } returns api
        every { repository.cache } returns cache
        every { repository.me } returns me
        every { repository.folders } returns folders
        me.value = me()
    }

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `1201 ticked mails go in three parts of at most 500, the rows go at once`() {
        items = (1L..1201L).map { s(it) }
        val bodies = mutableListOf<JsonObject>()
        coEvery { api.bulk("archive", capture(bodies)) } answers { done(bodies.last().getAsJsonArray("ids").size()) }
        val b = bulk()
        items.forEach { b.select(it.id) }
        b.onSelection(Action.ARCHIVE)
        assertEquals(listOf(500, 500, 201), bodies.map { it.getAsJsonArray("ids").size() })
        assertTrue(items.isEmpty())
        assertFalse(b.selection.value.active, "the selection ends")
        val snack = b.snack.value!!
        assertEquals(1201, snack.count)
        assertTrue(snack.tokens.isEmpty(), "an old server: no Rückgängig")
    }

    @Test
    fun `refused - the rows come back and the failure is said`() {
        items = listOf(s(1), s(2), s(3))
        coEvery { api.bulk("delete", any()) } returns MResult.Failed("unreachable", 409)
        val b = bulk()
        b.run(Action.DELETE, listOf(1, 3))
        assertEquals(listOf(1L, 2L, 3L), items.map { it.id })
        assertEquals("unreachable", b.message.value)
        assertNull(b.snack.value)
    }

    @Test
    fun `a later part refused - the list loads again, the done part still says what happened`() {
        items = (1L..600L).map { s(it) }
        coEvery { api.bulk("junk", any()) } returnsMany listOf(done(500), MResult.Offline)
        val b = bulk(conversations = false)
        b.run(Action.JUNK, items.map { it.id })
        assertEquals(1, reloads)
        assertEquals("offline", b.message.value)
        assertEquals(500, b.snack.value!!.count)
    }

    @Test
    fun `Rueckgaengig - with undo the tokens of every part, undo calls each and reloads`() {
        me.value = me(MMe.FEATURE_UNDO)
        items = (1L..700L).map { s(it) }
        coEvery { api.bulk("move", any()) } returnsMany listOf(done(500, "t1"), done(200, "t2"))
        coEvery { api.undo(any()) } returns MResult.Ok(MUndone("move", 700, 0, emptyList()))
        val target = MFolder(14, 1, "2026", "2026", null, null, 0, 0)
        val b = bulk(conversations = false)
        b.run(Action.MOVE, items.map { it.id }, target)
        val snack = b.snack.value!!
        assertEquals(listOf("t1", "t2"), snack.tokens)
        assertEquals(target, snack.folder)
        b.snackShown()
        b.undo(snack)
        coVerify { api.undo("t1") }
        coVerify { api.undo("t2") }
        assertEquals(1, reloads)
    }

    @Test
    fun `Rueckgaengig too late - one sentence`() {
        me.value = me(MMe.FEATURE_UNDO)
        coEvery { api.undo(any()) } returns MResult.Failed("undo_expired", 410)
        val b = bulk()
        b.undo(MailSnack(1, Action.DELETE, 3, tokens = listOf("t")))
        assertEquals(MailBulkActions.UNDO_TOO_LATE, b.message.value)
    }

    @Test
    fun `flags keep the selection, the read call sets unread false`() {
        items = listOf(s(1, unread = true), s(2, unread = true))
        val body = slot<JsonObject>()
        coEvery { api.bulk("flags", capture(body)) } returns done(2)
        val b = bulk(conversations = false)
        b.select(1)
        b.select(2)
        b.onSelection(Action.READ)
        assertFalse(body.captured.get("unread").asBoolean)
        assertTrue(items.none { it.unread })
        assertTrue(b.selection.value.active)
        assertNull(b.snack.value, "read and unread need no snackbar")
    }

    @Test
    fun `threads - with conversations and threadScope the calls carry threads true and the list reloads`() {
        me.value = me(MMe.FEATURE_THREAD_SCOPE)
        items = listOf(s(1))
        val body = slot<JsonObject>()
        coEvery { api.bulk("archive", capture(body)) } returns done(4)
        bulk().run(Action.ARCHIVE, listOf(1))
        assertTrue(body.captured.get("threads").asBoolean)
        assertEquals(1, reloads)
        // without the flag: none
        me.value = me()
        bulk().run(Action.ARCHIVE, listOf(1))
        assertFalse(body.captured.has("threads"))
    }

    @Test
    fun `delete - asks when the setting says so, Nicht mehr fragen switches it off`() {
        me.value = me(MMe.FEATURE_SWIPE_V2, settings = MSettings.DEFAULT.copy(confirmDelete = true))
        items = listOf(s(1), s(2))
        coEvery { api.bulk("delete", any()) } returns done(2)
        coEvery { repository.putSettings(any(), any(), any(), any()) } returns null
        val b = bulk()
        b.select(1)
        b.select(2)
        b.onSelection(Action.DELETE)
        val ask = b.ask.value!!
        assertEquals(2, ask.count)
        assertFalse(ask.purge)
        assertEquals(2, items.size, "nothing deleted before the answer")
        b.deleteConfirmed(dontAsk = true)
        assertTrue(items.isEmpty())
        assertFalse(b.selection.value.active)
        coVerify { repository.putSettings(confirmDelete = false) }
    }

    @Test
    fun `delete - without the question at once, in the trash always asked`() {
        items = listOf(s(1), s(2, folder = 99))
        coEvery { api.bulk("delete", any()) } returns done(1)
        val b = bulk()
        b.delete(listOf(1))
        assertNull(b.ask.value)
        assertEquals(listOf(2L), items.map { it.id })
        folders.value = mapOf(1L to listOf(MFolder(99, 1, "Trash", "Trash", MFolder.ROLE_TRASH, null, 0, 0)))
        b.delete(listOf(2))
        assertTrue(b.ask.value!!.purge)
        b.cancelDelete()
        assertNull(b.ask.value)
        assertEquals(listOf(2L), items.map { it.id })
    }

    @Test
    fun `picker - copy only with the feature, a pick moves there and goes into Zuletzt`() {
        items = listOf(s(1), s(2))
        coEvery { api.bulk("move", any()) } returns done(2)
        val b = bulk()
        b.select(1)
        b.select(2)
        b.onSelection(Action.COPY)
        val p = b.picker.value!!
        assertEquals(Action.MOVE, p.mode, "an old server cannot copy")
        assertFalse(p.copy)
        assertEquals(10L, p.current)
        assertEquals(setOf(1L), p.accounts)
        val target = MFolder(14, 1, "2026", "2026", null, null, 0, 0)
        b.pick(target)
        verify { repository.useFolder(14) }
        assertTrue(items.isEmpty())
        assertNull(b.picker.value)

        me.value = me(MMe.FEATURE_COPY)
        items = listOf(s(3))
        b.openPicker(Action.COPY, listOf(3))
        assertEquals(Action.COPY, b.picker.value!!.mode)
        b.setPickerMode(Action.MOVE)
        assertEquals(Action.MOVE, b.picker.value!!.mode)
    }

    @Test
    fun `Alle - the whole list from the server with bulkAll, else the loaded mails`() {
        items = listOf(s(1), s(2))
        val b = bulk()
        b.select(1)
        b.allOrNone()
        assertEquals(setOf(1L, 2L), b.selection.value.ids)
        assertNull(b.selection.value.all)
        b.allOrNone()
        assertFalse(b.selection.value.active, "everything ticked: Keine ends the selection")

        me.value = me(MMe.FEATURE_BULK_ALL)
        val query = slot<Map<String, String>>()
        coEvery { api.messageIds(capture(query)) } returns MResult.Ok(MIds((1L..48L).toList(), 48, 2000, false))
        b.select(1)
        b.allOrNone()
        assertEquals(48, b.selection.value.count)
        assertNotNull(b.selection.value.all)
        assertEquals("relevant", query.captured["tab"])
        assertFalse(b.loadingAll.value)
    }
}
