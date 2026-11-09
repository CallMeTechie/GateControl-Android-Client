package com.gatecontrol.android.notizen

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The outbox of plugin 1.1.0 (`offlineEdits`): creates, changes and deletes made offline, sent in order, shown meanwhile. */
class NotizenOutboxTest {
    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject
    private val O = NotizenOutbox

    private fun create(what: String, id: Long, body: String, n: Int = 1) = NotizenOutbox.Op(O.CREATE, id, false, n.toLong(), what, json(body), "c0ffee00-0000-4000-8000-00000000000$n", uid = "u$id")
    private fun patch(what: String, id: Long, body: String, uid: String = "p$id") = NotizenOutbox.Op(O.PATCH, id, false, 5, what, json(body), uid = uid)
    private fun delete(what: String, id: Long) = NotizenOutbox.Op(O.DELETE, id, false, 9, what, JsonObject(), uid = "d$id")

    private fun task(id: Long, title: String = "A", listId: Long = 1) =
        NTask(id, listId, null, title, false, false, false, null, null, false, null, null, null, null, false, null, 0, null, null, 0, 0)

    @Test
    fun `a change of something made offline goes into its create, a delete takes it and what was made in it away`() {
        var ops = emptyList<NotizenOutbox.Op>()
        ops = O.add(ops, create(O.LIST, -1, """{"name":"Reise","area":"private","clientId":"x"}"""))
        ops = O.add(ops, create(O.TASK, -2, """{"title":"Koffer","listId":-1}""", 2))
        ops = O.add(ops, create(O.TASK, -3, """{"title":"Pass","parentId":-2}""", 3))
        ops = O.add(ops, patch(O.TASK, -2, """{"title":"Koffer packen","base":{"title":"Koffer"}}"""))
        assertEquals(3, ops.size, "no patch of its own")
        assertEquals("Koffer packen", ops[1].body!!.get("title").asString)
        assertFalse(ops[1].body!!.has("base"))
        ops = O.add(ops, NotizenOutbox.Op(O.TASK, -3, true, 6, uid = "t3"))
        // deleting the task made offline: it, its sub task and the tick go; nothing to send
        val after = O.add(ops, delete(O.TASK, -2))
        assertEquals(listOf(O.LIST to -1L), after.map { O.objectOf(it) })
        // deleting the list: everything made in it
        assertTrue(O.add(ops, delete(O.LIST, -1)).isEmpty())
        // a delete of a server task: its waiting change and tick go, the delete waits
        val s = O.add(O.add(listOf(patch(O.TASK, 7, """{"title":"x"}""")), NotizenOutbox.Op(O.TASK, 7, true, 1, uid = "t7")), delete(O.TASK, 7))
        assertEquals(listOf(O.DELETE), s.map { it.kind })
        // what is being sent right now stays as it is
        val frozen = O.add(ops, patch(O.TASK, -2, """{"important":true}"""), frozen = "u-2")
        assertEquals(O.PATCH, frozen.last().kind)
        assertFalse(frozen[1].body!!.has("important"))
    }

    @Test
    fun `changes of the same thing join, keeping what the phone saw first`() {
        var ops = O.add(emptyList(), patch(O.TASK, 5, """{"title":"B","base":{"title":"A"}}"""))
        ops = O.add(ops, NotizenOutbox.Op(O.TASK, 9, true, 1, uid = "t9"))
        ops = O.add(ops, patch(O.TASK, 5, """{"title":"C","due":{"date":"2026-11-10"},"base":{"title":"B","due":null}}""", uid = "q"))
        assertEquals(2, ops.size)
        val b = ops[0].body!!
        assertEquals("C", b.get("title").asString)
        assertEquals("A", b.getAsJsonObject("base").get("title").asString, "the first value seen")
        assertTrue(b.getAsJsonObject("base").get("due").isJsonNull)
        var notes = O.add(emptyList(), patch(O.NOTE, 3, """{"body":"x","rev":4}"""))
        notes = O.add(notes, patch(O.NOTE, 3, """{"body":"xy","rev":5}""", uid = "q"))
        assertEquals(4, notes.single().body!!.get("rev").asInt, "the rev it was edited on")
        assertEquals("xy", notes.single().body!!.get("body").asString)
        // a refused change is not joined: the new one waits behind it
        val c = O.add(listOf(patch(O.TASK, 5, """{"title":"B"}""").copy(conflict = true)), patch(O.TASK, 5, """{"important":true}""", uid = "q"))
        assertEquals(2, c.size)
    }

    @Test
    fun `sending - ids of things made offline are replaced, a create's answer maps them, refusals`() {
        val sub = create(O.TASK, -3, """{"title":"Pass","parentId":-2,"clientId":"c"}""", 3)
        assertNull(O.prepared(sub, emptyMap()), "its task is not on the server yet")
        assertEquals(12, O.prepared(sub, mapOf(-2L to 12L))!!.get("parentId").asLong)
        assertEquals("c", O.prepared(sub, mapOf(-2L to 12L))!!.get("clientId").asString)
        assertNull(O.target(patch(O.TASK, -2, "{}"), emptyMap()))
        assertEquals(12L, O.target(patch(O.TASK, -2, "{}"), mapOf(-2L to 12L)))
        val s = O.mapped(NotizenOutbox.State(listOf(sub, patch(O.TASK, -2, """{"title":"x"}""")), emptyMap(), -4), -2, 12)
        assertEquals(12, s.ops[0].body!!.get("parentId").asLong)
        assertEquals(12, s.ops[1].id)
        assertEquals(mapOf(-2L to 12L), s.ids)
        assertTrue(O.conflictOf(patch(O.TASK, 5, "{}"), 409, "conflict"))
        assertTrue(O.conflictOf(patch(O.NOTE, 5, "{}"), 409, "stale_key"))
        assertFalse(O.conflictOf(sub, 409, "unknown_client_id"), "a create is never a conflict")
        assertFalse(O.conflictOf(patch(O.TASK, 5, "{}"), 404, "not_found"))
        val mine = O.keepMine(patch(O.NOTE, 5, """{"body":"x","rev":3,"base":{}}""").copy(conflict = true))
        assertFalse(mine.conflict)
        assertFalse(mine.body!!.has("rev"))
        assertFalse(mine.body!!.has("base"))
    }

    @Test
    fun `kept on the phone - the new ops and the outbox of 1_0 without ids`() {
        val st = NotizenOutbox.State(
            listOf(create(O.NOTE, -5, """{"title":"T","notebookId":-4}"""), patch(O.TASK, 3, """{"due":null}"""), delete(O.NOTE, 8), NotizenOutbox.Op(O.ITEM, 2, true, 1, uid = "i")),
            mapOf(-1L to 40L), -6,
        )
        assertEquals(st, O.stateFromJson(json(O.stateToJson(st).toString())), "as written to the file (nulls kept)")
        val old = O.stateFromJson(json("""{"ops":[{"kind":"task","id":5,"done":true,"at":1}]}"""))
        assertEquals(listOf(NotizenOutbox.Op(O.TASK, 5, true, 1)), old.ops)
        assertEquals(-1L, old.next)
        // broken entries are left out
        assertNull(O.fromJson(json("""{"kind":"create","id":3,"what":"task","body":{},"clientId":"c0ffee00-0000"}""")), "a create needs a local id")
        assertNull(O.fromJson(json("""{"kind":"patch","id":3,"what":"vault","body":{}}""")))
        assertEquals(-9L, O.stateFromJson(json("""{"ops":[],"ids":{"-8":3},"next":-2}""")).next, "never an id given before")
    }

    @Test
    fun `shown meanwhile - in its list, in Mein Tag, a change and a delete laid over, a conflict marked`() {
        val ops = listOf(
            create(O.TASK, -2, """{"title":"Koffer","listId":1,"due":{"date":"2026-11-08","time":"09:00"},"remindAt":{"date":"2026-11-08","time":"08:00"}}"""),
            create(O.TASK, -3, """{"title":"Pass","parentId":-2}""", 3),
            patch(O.TASK, 5, """{"title":"Neu","important":true}"""),
            patch(O.TASK, 6, """{"title":"Anders"}""").copy(conflict = true),
            delete(O.TASK, 7),
        )
        val view = NView("list:1", "Haus", listOf(NSection("open", "", 3, listOf(task(5), task(6), task(7)), false), NSection("done", "", 0, emptyList(), true)))
        val v = O.overlay(view, ops, "2026-11-08")
        val open = v.sections[0].items.map { it as NTask }
        assertEquals(listOf(-2L, 5L, 6L), open.map { it.id })
        assertTrue(open[0].pending)
        assertEquals("2026-11-08T08:00:00", open[0].remindAt)
        assertEquals(listOf("Neu", true, true), listOf(open[1].title, open[1].important, open[1].pending))
        assertTrue(open[2].conflict)
        val day = O.overlay(NView("myday", "Mein Tag", listOf(NSection("today", "Heute", 0, emptyList(), false))), ops, "2026-11-08")
        assertEquals(listOf(-2L), day.sections[0].items.map { (it as NTask).id }, "due today; its sub task not on its own")
        assertTrue(O.overlay(NView("list:9", "x", listOf(NSection("open", "", 0, emptyList(), false))), ops).sections[0].items.isEmpty())
        // the page of the task made offline, with its sub task
        val d = O.localDetail(-2, ops)!!
        assertEquals("Koffer", d.task.title)
        assertEquals(listOf("Pass"), d.subtasks.map { it.title })
        assertTrue(d.subtasks[0].pending)
        assertNull(O.localDetail(-9, ops))
        // a server task's page: sub tasks made, ticked, deleted offline
        val det = NTaskDetail(task(5), "n", listOf(NSubtask(10, "a", false), NSubtask(11, "b", false)), "Haus", "private", emptyList(), null)
        val dd = O.overlayDetail(det, ops + NotizenOutbox.Op(O.TASK, 10, true, 1, uid = "t10") + delete(O.TASK, 11) + create(O.TASK, -4, """{"title":"c","parentId":5}""", 4))
        assertEquals(listOf(10L to true, -4L to false), dd.subtasks.map { it.id to it.done })
        assertEquals("Neu", dd.task.title)
    }

    @Test
    fun `lists, notebooks and notes made offline show, a change of a note is laid over`() {
        val ops = listOf(
            create(O.LIST, -1, """{"name":"Reise","area":"private"}"""),
            create(O.NOTEBOOK, -2, """{"name":"Ideen","area":"work"}""", 2),
            create(O.TASK, -3, """{"title":"Koffer","listId":-1}""", 3),
            create(O.NOTE, -4, """{"notebookId":-2,"area":"work","title":"Plan","body":"erste\nzweite"}""", 4),
            patch(O.NOTE, 8, """{"title":"Neu","body":"text","rev":2}"""),
            delete(O.NOTE, 9),
        )
        val nav = NNav("private", emptyMap(), emptyList(), emptyList(), emptyList(), 0, emptyList(), emptyList())
        val n = O.overlayNav(nav, ops)
        assertEquals(listOf("Reise"), n.lists.map { it.name })
        assertEquals(1, n.lists[0].open)
        assertTrue(n.lists[0].pending)
        assertTrue(n.notebooks.isEmpty(), "the notebook is in Arbeit")
        assertEquals(listOf("Ideen"), O.overlayNav(nav.copy(area = "work"), ops).notebooks.map { it.name })
        val page = O.localView("list:-1", ops)!!
        assertEquals("Reise", page.title)
        assertEquals(listOf("Koffer"), page.sections[0].items.map { it.title })
        assertNull(O.localView("list:-7", ops))
        val summary = { id: Long -> NNoteSummary(id, 1, "T$id", "s", null, null, false, false, null, null, null, null, 0, 0, emptyList(), null, 0, 0, 2) }
        val shown = O.overlayNotes(listOf(summary(8), summary(9)), ops, "work", null, null, false)
        assertEquals(listOf(-4L, 8L), shown.map { it.id })
        assertEquals("erste · zweite", shown[0].snippet)
        assertEquals("Neu", shown[1].title)
        assertTrue(shown.all { it.pending })
        assertEquals(listOf(-4L), O.overlayNotes(emptyList(), ops, "work", -2, null, false).map { it.id })
        assertTrue(O.overlayNotes(emptyList(), ops, "private", null, null, false).isEmpty())
        val note = NNote(summary(8), "alt", null, null, false, emptyList(), emptyList())
        assertEquals("text", O.overlayNote(note, ops).body)
        assertNotNull(O.noteOf(ops[3]))
        assertEquals("erste\nzweite", O.noteOf(ops[3])!!.body)
    }

    @Test
    fun `base - the values the phone saw of the fields it changes`() {
        val t = task(5, "Zahnarzt").copy(due = NDue("2026-11-09", "09:00"), remindAt = "2026-11-08T14:00:00+01:00", important = true)
        val b = O.taskBase(NTaskDetail(t, null, emptyList(), null, null, emptyList(), null), listOf("title", "due", "remindAt", "note", "assigneeId", "myDay", "repeat"))
        assertEquals(
            json("""{"title":"Zahnarzt","due":{"date":"2026-11-09","time":"09:00"},"remindAt":"2026-11-08T14:00:00+01:00","note":"","assigneeId":null,"myDay":false,"repeat":null}"""),
            b,
        )
    }
}
