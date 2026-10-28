package com.gatecontrol.android.drive.backup

import com.gatecontrol.android.drive.backup.DriveFolderBackupLogic.Action
import com.gatecontrol.android.drive.backup.DriveFolderBackupLogic.Base
import com.gatecontrol.android.drive.backup.DriveFolderBackupLogic.Local
import com.gatecontrol.android.drive.backup.DriveFolderBackupLogic.Pending
import com.gatecontrol.android.drive.backup.DriveFolderBackupLogic.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DriveFolderBackupLogicTest {

    private val now = 1_800_000_000_000L
    private val key = DriveFolderBackupLogic::keyOf

    private fun local(vararg items: Local) = items.associateBy { key(it.path) }
    private fun file(path: String, size: Long = 10, mtime: Long = 100) = Local(path, false, size, mtime, "doc:$path")
    private fun dir(path: String) = Local(path, true, 0, 0, "doc:$path")
    private fun base(vararg items: Base) = items.associateBy { key(it.path) }
    private fun bFile(path: String, node: Long, size: Long = 10, mtime: Long = 100) = Base(path, false, size, mtime, node, "h")
    private fun bDir(path: String, node: Long) = Base(path, true, 0, 0, node)

    private fun plan(state: State, local: Map<String, Local>, deleteAfter: Boolean = true, unreadable: Set<String> = emptySet()) =
        DriveFolderBackupLogic.plan(state, local, unreadable, deleteAfter, now)

    @Test
    fun `new folders first, then new and changed files`() {
        val state = State(base(bDir("A", 2), bFile("A/old.jpg", 3), bFile("same.txt", 4)))
        val p = plan(state, local(dir("A"), file("A/old.jpg", size = 11), dir("A/B"), file("A/B/new.jpg"), file("same.txt"), file("Top.pdf")))
        assertEquals(
            listOf(
                Action.Mkdir("a/b", "A/B"),
                Action.Upload("a/old.jpg", "A/old.jpg", changed = true),
                Action.Upload("a/b/new.jpg", "A/B/new.jpg", changed = false),
                Action.Upload("top.pdf", "Top.pdf", changed = false),
            ).map { it.key }.sorted(),
            p.actions.map { it.key }.sorted(),
        )
        assertTrue(p.actions.first() is Action.Mkdir)
        assertTrue((p.actions.first { it.key == "a/old.jpg" } as Action.Upload).changed)
        assertEquals(0, p.deletes)
        assertEquals(2, p.files)
    }

    @Test
    fun `a time change alone is a change`() {
        val p = plan(State(base(bFile("a.txt", 3, mtime = 100))), local(file("a.txt", mtime = 200)))
        assertEquals(listOf(Action.Upload("a.txt", "a.txt", changed = true)), p.actions)
    }

    @Test
    fun `deleted on the phone is marked, a folder at once`() {
        val state = State(base(bFile("gone.jpg", 3), bDir("Old", 4), bFile("Old/x.jpg", 5), bFile("Old/y.jpg", 6), bFile("keep.jpg", 7)))
        val p = plan(state, local(file("keep.jpg")))
        val marks = p.actions.filterIsInstance<Action.Mark>()
        assertEquals(listOf("gone.jpg", "old"), marks.map { it.key }.sorted())
        val folder = marks.first { it.dir }
        assertEquals(2, folder.files)
        assertEquals(4L, folder.nodeId)
        assertEquals(setOf("old", "old/x.jpg", "old/y.jpg"), folder.keys.toSet())
        assertEquals(3, p.deletes)
        // nothing below a folder that goes as one is marked on its own
        assertFalse(marks.any { it.key == "old/x.jpg" })
    }

    @Test
    fun `without deleteAfter nothing in Drive is deleted`() {
        val p = plan(State(base(bFile("gone.jpg", 3), bDir("Old", 4), bFile("Old/x.jpg", 5))), local(), deleteAfter = false)
        assertTrue(p.actions.all { it is Action.Forget })
        assertEquals(0, p.deletes)
        assertEquals(setOf("gone.jpg", "old"), p.actions.map { it.key }.toSet())
    }

    @Test
    fun `back on the phone unchanged ends the mark, changed goes up as a version`() {
        val state = State(
            base(bFile("back.jpg", 3), bFile("changed.jpg", 4)),
            mapOf("back.jpg" to Pending(3, now - 1000, now + 1000), "changed.jpg" to Pending(4, now - 1000, now + 1000)),
        )
        val p = plan(state, local(file("back.jpg"), file("changed.jpg", size = 99)))
        assertEquals(listOf(Action.Unmark("back.jpg", 3), Action.Upload("changed.jpg", "changed.jpg", changed = true)), p.actions)
    }

    @Test
    fun `a mark still running waits, an expired one is let go`() {
        val state = State(
            base(bFile("waiting.jpg", 3), bFile("due.jpg", 4), bDir("D", 5), bFile("D/a", 6)),
            mapOf(
                "waiting.jpg" to Pending(3, now - 1000, now + 1000),
                "due.jpg" to Pending(4, now - 1000, now - 1),
                "d" to Pending(5, now - 1000, now + 1000),
            ),
        )
        val p = plan(state, local())
        assertEquals(listOf(Action.Forget("due.jpg", listOf("due.jpg"))), p.actions)
        assertEquals(0, p.deletes)
    }

    @Test
    fun `a file that reappears after its mark ran out goes up again`() {
        val gone = DriveFolderBackupLogic.forget(State(base(bFile("a.jpg", 3)), mapOf("a.jpg" to Pending(3, 0, 1))), listOf("a.jpg"), "a.jpg")
        assertTrue(gone.base.isEmpty() && gone.pending.isEmpty())
        assertEquals(listOf(Action.Upload("a.jpg", "a.jpg", changed = false)), plan(gone, local(file("a.jpg"))).actions)
    }

    @Test
    fun `below an unreadable folder nothing counts as deleted`() {
        val state = State(base(bDir("Sub", 2), bFile("Sub/a.jpg", 3), bFile("b.jpg", 4)))
        val p = plan(state, local(dir("Sub")), unreadable = setOf("sub"))
        assertEquals(listOf("b.jpg"), p.actions.filterIsInstance<Action.Mark>().map { it.key })
    }

    @Test
    fun `mass deletes wait for the person`() {
        // 3 of 4 files: < 10, no question
        assertFalse(DriveFolderBackupLogic.massDelete(3, 4))
        // 10 of 40: 25 % and at least 10
        assertTrue(DriveFolderBackupLogic.massDelete(10, 40))
        // 10 of 100: 10 %
        assertFalse(DriveFolderBackupLogic.massDelete(10, 100))
        assertTrue(DriveFolderBackupLogic.massDelete(501, 100_000))

        val files = (1..20).map { bFile("f$it.jpg", it.toLong()) }.toTypedArray()
        val p = plan(State(base(*files)), local(file("f1.jpg"), file("new.jpg")))
        assertEquals(19, p.deletes)
        assertTrue(DriveFolderBackupLogic.massDelete(p.deletes, p.files))
        val asked = DriveFolderBackupLogic.withoutMarks(p)
        assertTrue(asked.actions.none { it is Action.Mark })
        assertEquals(listOf(Action.Upload("new.jpg", "new.jpg", changed = false)), asked.actions)
        val kept = DriveFolderBackupLogic.keepInDrive(p)
        assertEquals(19, kept.actions.count { it is Action.Forget })
        assertEquals(0, kept.deletes)
    }

    @Test
    fun `a file that became a folder`() {
        val p = plan(State(base(bFile("x", 3))), local(dir("x")))
        assertEquals(listOf(Action.Forget("x", listOf("x")), Action.Mkdir("x", "x")), p.actions)
    }

    @Test
    fun `what is left out`() {
        assertEquals("hidden", DriveFolderBackupLogic.excluded(".trashed-1700000000-IMG.jpg", dir = false))
        assertEquals("hidden", DriveFolderBackupLogic.excluded(".Statuses", dir = true))
        assertEquals("hidden", DriveFolderBackupLogic.excluded(".nomedia", dir = false))
        assertEquals("temp", DriveFolderBackupLogic.excluded("~\$Brief.docx", dir = false))
        assertEquals("temp", DriveFolderBackupLogic.excluded("video.mp4.part", dir = false))
        assertEquals("temp", DriveFolderBackupLogic.excluded("Thumbs.db", dir = false))
        assertEquals("name", DriveFolderBackupLogic.excluded("a:b.jpg", dir = false))
        assertEquals("name", DriveFolderBackupLogic.excluded("ends with dot.", dir = false))
        assertNull(DriveFolderBackupLogic.excluded("IMG-20261027-WA0001.jpg", dir = false))
        assertNull(DriveFolderBackupLogic.excluded("WhatsApp Images", dir = true))
    }

    @Test
    fun `keys ignore case, overlaps of picked folders`() {
        assertEquals("whatsapp/media/a.jpg", DriveFolderBackupLogic.keyOf("WhatsApp/Media/A.jpg"))
        assertEquals("A/B", DriveFolderBackupLogic.parentOf("A/B/c.txt"))
        assertEquals("c.txt", DriveFolderBackupLogic.nameOf("A/B/c.txt"))
        assertTrue(DriveFolderBackupLogic.overlaps("primary:Documents/Rechnungen", listOf("primary:Documents")))
        assertTrue(DriveFolderBackupLogic.overlaps("primary:Documents", listOf("primary:documents/Rechnungen")))
        assertTrue(DriveFolderBackupLogic.overlaps("primary:Download/x", listOf("primary:")))
        assertFalse(DriveFolderBackupLogic.overlaps("primary:Documents", listOf("primary:Download", "primary:DocumentsOld")))
    }
}
