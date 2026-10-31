package com.gatecontrol.android.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The menu's folders as a tree ([MailLogic.folderTree], [MailLogic.folderRows]): order, folding, open parents, sums. */
class MailFolderTreeTest {

    private fun f(id: Long, name: String, parent: Long? = null, role: String? = null, unread: Int = 0) =
        MFolder(id, 3, name, name, role, parent, unread, 0)

    private val list = listOf(
        f(10, "Projekte"),
        f(11, "Alpha", 10, unread = 2),
        f(12, "2025", 11, unread = 3),
        f(13, "beta", 10),
        f(14, "Kunden", 1, unread = 1),
        f(1, "INBOX", role = MFolder.ROLE_INBOX, unread = 4),
        f(2, "Gesendet", role = MFolder.ROLE_SENT),
        f(3, "Papierkorb", role = MFolder.ROLE_TRASH),
        f(4, "Entwürfe", 99, role = MFolder.ROLE_DRAFTS),
        f(15, "Archiv alt", 77),
        f(5, "Zeug"),
    )

    private fun ids(rows: List<MailLogic.FolderRow>) = rows.map { it.folder.id }

    @Test
    fun `tree - the roles on top as before, the own ones by name between archive and junk, children by name`() {
        val tree = MailLogic.folderTree(list)
        assertEquals(
            listOf(1L to 0, 14L to 1, 2L to 0, 4L to 0, 15L to 0, 10L to 0, 11L to 1, 12L to 2, 13L to 1, 5L to 0, 3L to 0),
            tree.nodes.map { it.folder.id to it.depth },
        )
        assertNull(tree.byId.getValue(15).parent, "its parent was not synced: on top")
        assertNull(tree.byId.getValue(4).parent, "a folder with a role is always on top")
        assertEquals(listOf(11L, 13L), tree.byId.getValue(10).kids)
        assertEquals(5, tree.byId.getValue(10).unreadBelow, "everything below, not only the children")
        assertEquals(1, tree.byId.getValue(1).unreadBelow)
    }

    @Test
    fun `tree - a loop of parents and a folder its own parent stay, on top`() {
        val tree = MailLogic.folderTree(listOf(f(1, "a", 2), f(2, "b", 1), f(3, "c", 3)))
        assertEquals(setOf(1L, 2L, 3L), tree.nodes.map { it.folder.id }.toSet())
        assertNull(tree.byId.getValue(3).parent)
        assertEquals(3, tree.nodes.size)
    }

    @Test
    fun `rows - closed by default, the arrow opens and closes, the sum below a closed parent`() {
        val tree = MailLogic.folderTree(list)
        var rows = MailLogic.folderRows(tree, emptyMap(), null)
        assertEquals(listOf(1L, 2L, 4L, 15L, 10L, 5L, 3L), ids(rows))
        val p = rows.first { it.folder.id == 10L }
        assertTrue(p.hasKids)
        assertFalse(p.open)
        assertEquals(5, p.unreadBelow)
        assertEquals(1, rows.first { it.folder.id == 1L }.unreadBelow, "the inbox with its children")
        assertFalse(rows.first { it.folder.id == 5L }.hasKids)

        var folds = MailLogic.toggleFolder(tree, emptyMap(), 10, null)
        rows = MailLogic.folderRows(tree, folds, null)
        assertEquals(listOf(10L, 11L, 13L, 5L), ids(rows).subList(4, 8))
        assertEquals(0, rows.first { it.folder.id == 10L }.unreadBelow, "open: no sum")
        assertEquals(3, rows.first { it.folder.id == 11L }.unreadBelow)
        folds = MailLogic.toggleFolder(tree, folds, 11, null)
        assertEquals(listOf(10L, 11L, 12L, 13L), ids(MailLogic.folderRows(tree, folds, null)).subList(4, 8))
        folds = MailLogic.toggleFolder(tree, folds, 10, null)
        assertEquals(listOf(10L, 5L), ids(MailLogic.folderRows(tree, folds, null)).subList(4, 6), "closed again: all below hidden")
        assertEquals(true, folds[11], "the choice below stays")
    }

    @Test
    fun `rows - the indent stops at level 4`() {
        val deep = (1L..7L).map { f(it, "l$it", if (it == 1L) null else it - 1) }
        val tree = MailLogic.folderTree(deep)
        val open = deep.associate { it.id to true }
        assertEquals(listOf(0, 1, 2, 3, 4, 4, 4), MailLogic.folderRows(tree, open, null).map { it.indent })
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), MailLogic.folderRows(tree, open, null).map { it.depth })
    }

    @Test
    fun `the folder shown - its parents open without a choice, reveal opens them over a choice`() {
        val tree = MailLogic.folderTree(list)
        assertEquals(listOf(11L, 10L), MailLogic.folderAncestors(tree, 12))
        assertTrue(MailLogic.folderOpen(tree, emptyMap(), 10, 12))
        assertEquals(listOf(10L, 11L, 12L, 13L), ids(MailLogic.folderRows(tree, emptyMap(), 12)).subList(4, 8))
        val closed = MailLogic.toggleFolder(tree, emptyMap(), 10, 12)
        assertEquals(false, closed[10])
        assertEquals(listOf(10L, 5L), ids(MailLogic.folderRows(tree, closed, 12)).subList(4, 6))
        val shown = MailLogic.revealFolder(tree, closed, 12)
        assertEquals(true, shown[10])
        assertEquals(true, shown[11])
        assertSame(shown, MailLogic.revealFolder(tree, shown, 12), "nothing to open: the same map")
        assertTrue(MailLogic.folderAncestors(tree, null).isEmpty())
    }
}
