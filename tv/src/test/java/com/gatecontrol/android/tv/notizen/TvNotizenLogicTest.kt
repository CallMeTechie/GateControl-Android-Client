package com.gatecontrol.android.tv.notizen

import com.gatecontrol.android.notizen.NArea
import com.gatecontrol.android.notizen.NItem
import com.gatecontrol.android.notizen.NList
import com.gatecontrol.android.notizen.NNav
import com.gatecontrol.android.notizen.NNote
import com.gatecontrol.android.notizen.NNoteSummary
import com.gatecontrol.android.notizen.NNotebook
import com.gatecontrol.android.notizen.NSection
import com.gatecontrol.android.notizen.NSubtask
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.notizen.NTaskDetail
import com.gatecontrol.android.notizen.NVaultRef
import com.gatecontrol.android.notizen.NView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class TvNotizenLogicTest {

    private val today = LocalDate.parse("2026-11-24")

    private fun list(id: Long, name: String, area: String = NArea.PRIVATE, position: Long = id) =
        NList(id, name, area, null, false, null, null, null, position, false, emptyList(), open = 2, ext = null)

    private fun book(id: Long, name: String?, area: String = NArea.PRIVATE, vault: NVaultRef? = null) =
        NNotebook(id, name, area, null, false, null, null, emptyList(), false, vault, null, count = 1, position = id)

    private fun nav() = NNav(
        area = NArea.PRIVATE,
        smart = mapOf("myday" to 4, "planned" to 9, "important" to 1, "assigned" to 0),
        sources = emptyList(),
        lists = listOf(list(3, "Garten"), list(2, "Haushalt"), list(9, "Projekt", area = NArea.WORK)),
        groups = emptyList(),
        notesTotal = 3,
        notebooks = listOf(book(5, "Rezepte"), book(6, null, vault = NVaultRef("AAAAAAAAAAAAAAAAAAAAAA", "x", 1, false)), book(7, "Büro", area = NArea.WORK)),
        tags = emptyList(),
    )

    private fun task(id: Long, title: String, done: Boolean = false, overdue: Boolean = false, parentId: Long? = null, canEdit: Boolean = true) = NTask(
        id = id, listId = 2, parentId = parentId, title = title, hasNote = false, important = false, done = done, doneAt = null, due = null,
        overdue = overdue, remindAt = null, repeat = null, repeatText = null, assignee = null, myDay = true, progress = null, attachments = 0,
        source = null, noteId = null, position = id, updatedAt = 0, canEdit = canEdit,
    )

    private fun item(id: Long, title: String, overdue: Boolean = false, canComplete: Boolean = true) = NItem(
        id, "waste", title, null, "gatecontrol-alltag", "Alltag", null, null, null, true, null, overdue, NArea.PRIVATE, false, false, null,
        canComplete, null, null, null, null, emptyMap(), 0,
    )

    private fun view(vararg sections: NSection) = NView("myday", "Mein Tag", sections.toList())

    @Test
    fun `only the area Privat and never a Tresor`() {
        val n = TvNotizenLogic.nav(nav())
        assertEquals(4, n.myDay)
        assertEquals(9, n.planned)
        assertEquals(listOf("Haushalt", "Garten"), n.lists.map { it.name })
        assertEquals(listOf("Rezepte"), n.notebooks.map { it.name })
    }

    @Test
    fun `a place that went away falls back to Mein Tag`() {
        val n = TvNotizenLogic.nav(nav())
        assertEquals(TvNotesPlace.List(2), TvNotizenLogic.valid(TvNotesPlace.List(2), n))
        assertEquals(TvNotesPlace.MyDay, TvNotizenLogic.valid(TvNotesPlace.List(9), n))
        assertEquals(TvNotesPlace.MyDay, TvNotizenLogic.valid(TvNotesPlace.Notebook(6), n))
        assertEquals(TvNotesPlace.Planned, TvNotizenLogic.valid(TvNotesPlace.Planned, n))
        assertEquals("list:2", TvNotizenLogic.viewName(TvNotesPlace.List(2)))
        assertNull(TvNotizenLogic.viewName(TvNotesPlace.Notebook(5)))
    }

    @Test
    fun `rows once each, sub tasks left out, the open ones first`() {
        val a = task(1, "Altglas wegbringen", done = true)
        val b = task(2, "Winterreifen abholen")
        val c = task(3, "Heizungsableser bestätigen", overdue = true)
        val sub = task(4, "Geschenkpapier", parentId = 2)
        val v = view(NSection("overdue", "Überfällig", 1, listOf(c), false), NSection("today", "Heute", 3, listOf(a, b, c, sub), false))
        assertEquals(listOf("t:3", "t:2", "t:1"), TvNotizenLogic.entries(v).map { it.uid })
        assertEquals(TvTasksToday(open = 2, overdue = 1), TvNotizenLogic.today(v))
        assertNull(TvNotizenLogic.today(null))
    }

    @Test
    fun `items of other plugins count and tick too`() {
        val v = view(NSection("today", "Heute", 2, listOf(item(7, "Gelbe Tonne", overdue = true), task(2, "Blumen gießen")), false))
        assertEquals(TvTasksToday(2, 1), TvNotizenLogic.today(v))
        assertTrue(TvNotizenLogic.canTick(item(7, "Gelbe Tonne")))
        assertFalse(TvNotizenLogic.canTick(item(8, "Brief", canComplete = false)))
        assertFalse(TvNotizenLogic.canMove(item(7, "Gelbe Tonne")))
    }

    @Test
    fun `ticking shows at once in the loaded view`() {
        val v = view(NSection("today", "Heute", 2, listOf(task(2, "Blumen gießen"), item(7, "Gelbe Tonne")), false))
        val ticked = TvNotizenLogic.withDone(v, "t:2", true)
        assertEquals(listOf("s:7", "t:2"), TvNotizenLogic.entries(ticked).map { it.uid })
        assertTrue(TvNotizenLogic.entries(TvNotizenLogic.withDone(v, "s:7", true)).last().done)
    }

    @Test
    fun `move only own open tasks I may change`() {
        assertTrue(TvNotizenLogic.canMove(task(2, "Blumen gießen")))
        assertFalse(TvNotizenLogic.canMove(task(2, "Blumen gießen", done = true)))
        assertFalse(TvNotizenLogic.canMove(task(2, "Blumen gießen", canEdit = false)))
        // made offline on a phone: not on the server yet
        assertFalse(TvNotizenLogic.canMove(task(-1, "Neu")))
    }

    @Test
    fun `sub tasks tick in the details`() {
        val d = NTaskDetail(task(2, "Geschenk für Jonas"), null, listOf(NSubtask(10, "Idee", true), NSubtask(11, "Kaufen", false)), "Haushalt", NArea.PRIVATE, emptyList(), null)
        val after = TvNotizenLogic.withSub(d, 11, true)
        assertEquals(2 to 2, TvNotizenLogic.progress(after.subtasks))
        assertEquals(1 to 2, TvNotizenLogic.progress(d.subtasks))
    }

    @Test
    fun `notes of a Tresor never show`() {
        fun summary(id: Long, encrypted: Boolean = false, pinned: Boolean = false, updated: Long = id) =
            NNoteSummary(id, 5, "Notiz $id", null, if (encrypted) "c" else null, null, encrypted, pinned, null, null, null, null, 0, 0, emptyList(), null, 0, updated, 1)
        assertEquals(listOf(3L, 2L), TvNotizenLogic.notes(listOf(summary(1, encrypted = true), summary(2), summary(3, pinned = true, updated = 0))).map { it.id })
        val note = NNote(summary(2), "# Zählerstände", null, "Wohnung", false, emptyList(), emptyList())
        assertEquals(note, TvNotizenLogic.readable(note))
        assertNull(TvNotizenLogic.readable(note.copy(notebookVault = true)))
        assertNull(TvNotizenLogic.readable(note.copy(cipher = "x")))
    }

    @Test
    fun `a new task lands in Mein Tag without a day or with today's`() {
        assertTrue(TvNotizenLogic.inMyDay(null, today))
        assertTrue(TvNotizenLogic.inMyDay("2026-11-24", today))
        assertFalse(TvNotizenLogic.inMyDay("2026-11-25", today))
        assertEquals("Winterreifen abholen morgen 17 Uhr", TvNotizenLogic.line("  Winterreifen abholen\nmorgen   17 Uhr "))
        assertNull(TvNotizenLogic.line("   "))
        assertEquals(TvNotizenLogic.TEXT_MAX, TvNotizenLogic.line("x".repeat(900))?.length)
    }
}
