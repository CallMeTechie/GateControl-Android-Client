package com.gatecontrol.android.mail

import com.gatecontrol.android.mail.MailBulk.Action
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Several mails at once (Mail 1.13): parts, the list at once, the selection, the answers, what the server offers. */
class MailBulkTest {

    private fun s(id: Long, account: Long = 1, folder: Long? = 10, unread: Boolean = false, flagged: Boolean = false, tab: String = MSummary.TAB_RELEVANT) = MSummary(
        id = id, accountId = account, folderId = folder, threadId = null, threadCount = 1, from = MAddress("A", "a@b.de"), to = emptyList(),
        subject = "s$id", snippet = "", date = id, unread = unread, flagged = flagged, answered = false, hasAttachments = false,
        tab = tab, savedToDrive = false, aiSummary = null,
    )

    private fun folder(id: Long, account: Long = 1, role: String? = null, name: String = "f$id", parent: Long? = null) =
        MFolder(id, account, name, name, role, parent, 0, 0)

    private fun me(vararg features: String, settings: MSettings = MSettings.DEFAULT) = MMe(
        features = setOf(MMe.FEATURE_MAIL) + features, limits = MLimits(5, 0, 0, 12),
        microsoft = MMicrosoft(null, "https://login.microsoftonline.com/common", emptyList()), accounts = emptyList(),
        unreadAll = 0, unreadByAccount = emptyMap(), settings = settings,
    )

    // ─── parts of 500 ───────────────────────────────────────────────

    @Test
    fun `chunks - at most 500 per call, each id once, in order, no bad ids`() {
        val ids = (1L..1201L).toList()
        val parts = MailBulk.chunks(ids)
        assertEquals(listOf(500, 500, 201), parts.map { it.size })
        assertEquals(ids, parts.flatten())
        assertEquals(listOf(listOf(3L, 1L)), MailBulk.chunks(listOf(3L, 1L, 3L, 0L, -2L)))
        assertTrue(MailBulk.chunks(emptyList()).isEmpty())
        assertEquals(listOf(500), MailBulk.chunks((1L..500L).toList()).map { it.size })
    }

    @Test
    fun `body - ids, threads only when asked, the action's own field`() {
        val read = MailBulk.body(Action.READ, listOf(1, 2), threads = false)
        assertEquals(2, read.getAsJsonArray("ids").size())
        assertFalse(read.get("unread").asBoolean)
        assertFalse(read.has("threads"))
        assertTrue(MailBulk.body(Action.UNREAD, listOf(1), threads = true).get("unread").asBoolean)
        assertTrue(MailBulk.body(Action.ARCHIVE, listOf(1), threads = true).get("threads").asBoolean)
        assertFalse(MailBulk.body(Action.UNFLAG, listOf(1), false).get("flagged").asBoolean)
        assertEquals("other", MailBulk.body(Action.TO_OTHER, listOf(1), false).get("tab").asString)
        assertEquals(14L, MailBulk.body(Action.COPY, listOf(1), false, folderId = 14).get("folderId").asLong)
        assertFalse(MailBulk.body(Action.DELETE, listOf(1), false, folderId = 14).has("folderId"))
        assertEquals("copy", Action.COPY.path)
        assertEquals("flags", Action.FLAG.path)
        assertTrue("tab" in MailBulk.PATHS && "undo" !in MailBulk.PATHS)
    }

    // ─── the list at once ───────────────────────────────────────────

    @Test
    fun `optimistic - leaving actions take the rows out, the others change them`() {
        val list = listOf(s(1, unread = true), s(2), s(3, flagged = true))
        assertEquals(listOf(2L), MailBulk.optimistic(list, setOf(1, 3), Action.ARCHIVE, tabbed = false).map { it.id })
        assertEquals(listOf(2L, 3L), MailBulk.optimistic(list, setOf(1), Action.MOVE, tabbed = false).map { it.id })
        assertEquals(3, MailBulk.optimistic(list, setOf(1), Action.COPY, tabbed = false).size, "a copy leaves the mail where it is")
        assertFalse(MailBulk.optimistic(list, setOf(1), Action.READ, false)[0].unread)
        assertTrue(MailBulk.optimistic(list, setOf(2), Action.UNREAD, false)[1].unread)
        assertTrue(MailBulk.optimistic(list, setOf(1, 2), Action.FLAG, false).take(2).all { it.flagged })
        // Relevant / Sonstige: out of a tab of an inbox, else only the tab changes
        assertEquals(2, MailBulk.optimistic(list, setOf(1), Action.TO_OTHER, tabbed = true).size)
        assertEquals(MSummary.TAB_OTHER, MailBulk.optimistic(list, setOf(1), Action.TO_OTHER, tabbed = false)[0].tab)
        assertEquals(list, MailBulk.optimistic(list, emptySet(), Action.DELETE, false))
    }

    @Test
    fun `restore - the mails as they were at their places, what changed meanwhile stays`() {
        val before = listOf(s(1), s(2), s(3))
        val now = listOf(s(2, unread = true), s(9))
        val back = MailBulk.restore(now, before, setOf(1, 3))
        assertEquals(listOf(1L, 2L, 3L, 9L), back.map { it.id })
        assertTrue(back[1].unread, "a row not part of the action keeps its new state")
        // a row gone meanwhile (not of the action) stays gone
        assertEquals(listOf(1L, 3L), MailBulk.restore(emptyList(), before, setOf(1, 3)).map { it.id })
    }

    // ─── answers ────────────────────────────────────────────────────

    @Test
    fun `combine - the parts as one, the tokens of each part`() {
        val a = MBulkDone(listOf(s(1)), 500, emptyList(), MBulkUndo("t1", "move", 500, null), emptyList())
        val b = MBulkDone(listOf(s(1), s(2)), 12, emptyList(), MBulkUndo("t2", "move", 12, null), listOf(7))
        val c = MBulkDone(emptyList(), 3, emptyList(), null, emptyList())
        val d = MailBulk.combine(listOf(a, b, c))
        assertEquals(515, d.count)
        assertEquals(listOf("t1", "t2"), d.tokens)
        assertEquals(listOf(1L, 2L), d.items.map { it.id })
        assertEquals(setOf(7L), d.purged)
    }

    // ─── the selection ──────────────────────────────────────────────

    @Test
    fun `selection - a long press starts it, taps toggle, the last one off ends it`() {
        var sel = MailBulk.select(MailBulk.Selection(), 5)
        assertTrue(sel.active)
        sel = MailBulk.select(sel, 5)
        assertEquals(1, sel.count, "ticking a ticked mail again changes nothing")
        sel = MailBulk.toggle(sel, 7)
        assertEquals(setOf(5L, 7L), sel.ids)
        sel = MailBulk.toggle(MailBulk.toggle(sel, 5), 7)
        assertFalse(sel.active)
    }

    @Test
    fun `selection - Alle with the loaded mails or the whole list, a tap takes Alle back`() {
        val loaded = listOf(1L, 2L, 3L)
        val some = MailBulk.Selection(setOf(2L))
        assertFalse(MailBulk.allSelected(some, loaded))
        val all = MailBulk.selectLoaded(some, loaded)
        assertTrue(MailBulk.allSelected(all, loaded))
        assertNull(all.all)

        val everything = MailBulk.selectEverything(loaded, MIds(listOf(3L, 4L, 5L), total = 5, max = 2000, truncated = false))
        assertEquals(5, everything.count)
        assertEquals(MailBulk.All(5, truncated = false), everything.all)
        assertTrue(MailBulk.allSelected(everything, loaded))
        // more than the server's limit: only the newest, and it says so
        val many = MailBulk.selectEverything(emptyList(), MIds(listOf(1L, 2L), total = 4000, max = 2, truncated = true))
        assertTrue(many.all!!.truncated)
        // one taken out: no longer "Alle"
        val less = MailBulk.toggle(everything, 4)
        assertNull(less.all)
        assertEquals(4, less.count)
    }

    @Test
    fun `selection - mails gone from the list are no longer ticked, but with Alle`() {
        val sel = MailBulk.Selection(setOf(1L, 2L, 3L))
        assertEquals(setOf(1L, 3L), MailBulk.prune(sel, listOf(1L, 3L, 9L)).ids)
        assertEquals(sel, MailBulk.prune(sel, listOf(1L, 2L, 3L)))
        val all = MailBulk.Selection(setOf(1L, 2L, 3L), MailBulk.All(3, false))
        assertEquals(all, MailBulk.prune(all, listOf(1L)))
    }

    @Test
    fun `bar - read or unread, flag or unflag, Sonstige or Relevant by what is ticked`() {
        assertEquals(Action.READ, MailBulk.readAction(listOf(s(1), s(2, unread = true))))
        assertEquals(Action.UNREAD, MailBulk.readAction(listOf(s(1), s(2))))
        assertEquals(Action.FLAG, MailBulk.flagAction(listOf(s(1, flagged = true), s(2))))
        assertEquals(Action.UNFLAG, MailBulk.flagAction(listOf(s(1, flagged = true))))
        assertEquals(Action.TO_OTHER, MailBulk.tabAction(listOf(s(1), s(2, tab = MSummary.TAB_OTHER))))
        assertEquals(Action.TO_RELEVANT, MailBulk.tabAction(listOf(s(2, tab = MSummary.TAB_OTHER))))
        assertEquals(setOf(1L, 2L), MailBulk.accounts(listOf(s(1, account = 1), s(2, account = 2), s(3, account = 1))))
    }

    @Test
    fun `purges - mails in the trash are deleted for good, the trash shown too`() {
        val folders = mapOf(1L to listOf(folder(10, role = MFolder.ROLE_INBOX), folder(99, role = MFolder.ROLE_TRASH)))
        assertFalse(MailBulk.purges(listOf(s(1, folder = 10)), folders))
        assertTrue(MailBulk.purges(listOf(s(1, folder = 10), s(2, folder = 99)), folders))
        assertTrue(MailBulk.purges(emptyList(), folders, here = folder(99, role = MFolder.ROLE_TRASH)))
        assertEquals(10L, MailBulk.commonFolder(listOf(s(1, folder = 10), s(2, folder = 10))))
        assertNull(MailBulk.commonFolder(listOf(s(1, folder = 10), s(2, folder = 11))))
        assertNull(MailBulk.commonFolder(listOf(s(1, folder = 10), s(2, folder = null))))
    }

    // ─── the folder picker ──────────────────────────────────────────

    @Test
    fun `recent - the newest in front, each once, at most four, only folders that exist`() {
        var r = emptyList<Long>()
        listOf(1L, 2L, 3L, 2L, 4L, 5L).forEach { r = MailBulk.recent(r, it) }
        assertEquals(listOf(5L, 4L, 2L, 3L), r)
        assertEquals(listOf(folder(4), folder(3)), MailBulk.recentFolders(listOf(9L, 4L, 3L), listOf(folder(3), folder(4))))
    }

    @Test
    fun `search - folders by name in the tree's order, any case`() {
        val folders = listOf(folder(1, role = MFolder.ROLE_INBOX, name = "INBOX"), folder(2, name = "Rechnungen"), folder(3, name = "2026", parent = 2), folder(4, name = "Verein"))
        assertEquals(listOf(2L), MailBulk.search(folders, "rech").map { it.id })
        assertEquals(listOf(3L), MailBulk.search(folders, " 2026 ").map { it.id })
        assertEquals(listOf(1L), MailBulk.search(folders, "posteingang") { if (it.role == MFolder.ROLE_INBOX) "Posteingang" else it.name }.map { it.id })
        assertEquals(4, MailBulk.search(folders, "").size)
    }

    // ─── what the server offers ─────────────────────────────────────

    @Test
    fun `features - copy, undo, Alle, conversations and the seven swipes only with Mail 1-13's flags`() {
        val old = me()
        assertFalse(MailBulk.canCopy(old))
        assertFalse(MailBulk.canSelectAll(old))
        assertFalse(MailBulk.undoable(Action.DELETE, old))
        assertFalse(MailBulk.threads(old, conversations = true))
        assertEquals(MSettings.SWIPES, MailBulk.swipeOptions(old))
        assertFalse(MailBulk.confirmDelete(me(settings = MSettings.DEFAULT.copy(confirmDelete = true))), "an old server keeps no question")

        val new = me(MMe.FEATURE_COPY, MMe.FEATURE_UNDO, MMe.FEATURE_BULK_ALL, MMe.FEATURE_THREAD_SCOPE, MMe.FEATURE_SWIPE_V2, settings = MSettings.DEFAULT.copy(confirmDelete = true))
        assertTrue(MailBulk.canCopy(new))
        assertTrue(MailBulk.canSelectAll(new))
        assertTrue(MailBulk.undoable(Action.DELETE, new))
        assertTrue(MailBulk.undoable(Action.COPY, new))
        assertFalse(MailBulk.undoable(Action.READ, new), "flags are not taken back by token")
        assertTrue(MailBulk.threads(new, conversations = true))
        assertFalse(MailBulk.threads(new, conversations = false), "the search's mails stay single")
        assertFalse(MailBulk.threads(new.copy(settings = new.settings.copy(threads = false)), conversations = true))
        assertEquals(MSettings.SWIPES_V2, MailBulk.swipeOptions(new))
        assertTrue(MailBulk.confirmDelete(new))
        assertNull(MailBulk.swipe("none"))
        assertEquals("move", MailBulk.swipe("move"))
    }

    @Test
    fun `leaves - which actions take mails out of the list`() {
        assertTrue(MailBulk.leaves(Action.DELETE, false))
        assertTrue(MailBulk.leaves(Action.JUNK, false))
        assertFalse(MailBulk.leaves(Action.COPY, true))
        assertFalse(MailBulk.leaves(Action.READ, true))
        assertTrue(MailBulk.leaves(Action.TO_RELEVANT, true))
        assertFalse(MailBulk.leaves(Action.TO_RELEVANT, false))
        assertTrue(MailBulk.needsFolder(Action.MOVE) && MailBulk.needsFolder(Action.COPY) && !MailBulk.needsFolder(Action.ARCHIVE))
    }

    @Test
    fun `json - the body is what the contract reads`() {
        val o = JsonParser.parseString(MailBulk.body(Action.MOVE, listOf(5, 6), threads = true, folderId = 14).toString()).asJsonObject
        assertEquals("""{"ids":[5,6],"threads":true,"folderId":14}""", o.toString())
    }
}
