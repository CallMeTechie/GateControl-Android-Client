package com.gatecontrol.android.drive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DriveBulkTest {

    private fun node(
        id: Long,
        name: String = "f$id.pdf",
        dir: Boolean = false,
        size: Long = 100,
        parent: Long = 1,
        mime: String? = null,
        sha: String? = null,
        vault: String? = null,
        area: String = "u:1",
    ) = DNode(
        id = id, areaId = area, parentId = parent, name = name, isDir = dir, size = size, mime = mime, mediaId = null, rev = 1,
        mtime = "2026-10-01T10:00:00Z", updatedAt = null, updatedBy = null, sha256 = sha, vaultId = vault,
    )

    private fun me(vararg features: String) = DMe(
        userId = 1, name = "Ma", seat = "family", licenceValid = true, tier = null, areas = emptyList(), latest = 0, trashDays = 30,
        maxBytes = null, people = emptyMap(), features = features.toSet(),
    )

    private val writable = DriveBulk.Where(writable = { true })
    private val readOnly = DriveBulk.Where(writable = { false })
    private val child = DriveBulk.Where(writable = { false }, child = { true })

    // ─── the selection ──────────────────────────────────────────────

    @Test
    fun `toggle ticks and unticks`() {
        val s = DriveBulk.toggle(emptySet(), 3)
        assertEquals(setOf(3L), s)
        assertEquals(emptySet<Long>(), DriveBulk.toggle(s, 3))
    }

    @Test
    fun `all or none turns all on, then off, and keeps others`() {
        val all = DriveBulk.allOrNone(setOf(9), listOf(1, 2, 3))
        assertEquals(setOf(9L, 1L, 2L, 3L), all)
        assertTrue(DriveBulk.allSelected(all, listOf(1, 2, 3)))
        assertEquals(setOf(9L), DriveBulk.allOrNone(all, listOf(1, 2, 3)))
        assertFalse(DriveBulk.allSelected(emptySet(), emptyList()))
    }

    @Test
    fun `after a reload only present items stay ticked`() {
        assertEquals(setOf(2L), DriveBulk.keep(setOf(1, 2), listOf(2, 3)))
    }

    @Test
    fun `swiping ticks the range from the first photo and undoes it when going back`() {
        val ids = listOf(10L, 11L, 12L, 13L, 14L)
        val base = setOf(20L)
        assertEquals(setOf(20L, 11L, 12L, 13L), DriveBulk.dragRange(base, ids, 1, 3, select = true))
        // back to 2: 13 is no longer covered
        assertEquals(setOf(20L, 11L, 12L), DriveBulk.dragRange(base, ids, 1, 2, select = true))
        // upwards works the same
        assertEquals(setOf(20L, 10L, 11L), DriveBulk.dragRange(base, ids, 1, 0, select = true))
        // starting on a ticked photo unticks
        assertEquals(setOf(10L), DriveBulk.dragRange(setOf(10L, 11L, 12L), ids, 1, 2, select = false))
        // out of range is clamped
        assertEquals(ids.toSet(), DriveBulk.dragRange(emptySet(), ids, 0, 99, select = true))
    }

    // ─── the summary ────────────────────────────────────────────────

    @Test
    fun `summary counts files, folders and the bytes of the files`() {
        val s = DriveBulk.summary(listOf(node(1, size = 1000), node(2, size = 600), node(3, dir = true, size = 99_999)))
        assertEquals(2, s.files)
        assertEquals(1, s.folders)
        assertEquals(1600L, s.bytes)
        assertEquals(3, s.count)
    }

    // ─── which action ───────────────────────────────────────────────

    @Test
    fun `move and delete need the write right, the reason says which`() {
        val ns = listOf(node(1))
        assertTrue(DriveBulk.can(DriveBulk.Action.MOVE, ns, writable, me()).ok)
        assertEquals(DriveBulk.Why.READ_ONLY, DriveBulk.can(DriveBulk.Action.DELETE, ns, readOnly, me()).why)
        assertEquals(DriveBulk.Why.CHILD, DriveBulk.can(DriveBulk.Action.MOVE, ns, child, me()).why)
        // one of several not writable is enough
        val mixed = DriveBulk.Where(writable = { it.id != 2L })
        assertFalse(DriveBulk.can(DriveBulk.Action.DELETE, listOf(node(1), node(2)), mixed, me()).ok)
    }

    @Test
    fun `copy, zip and one link for several need Drive 1_12, zip and link also the public links`() {
        val ns = listOf(node(1), node(2))
        assertEquals(DriveBulk.Why.NEEDS_BULK, DriveBulk.can(DriveBulk.Action.COPY, ns, writable, me()).why)
        // a copy needs the read right only
        assertTrue(DriveBulk.can(DriveBulk.Action.COPY, ns, readOnly, me(DMe.FEATURE_BULK)).ok)
        assertEquals(DriveBulk.Why.NEEDS_BULK, DriveBulk.can(DriveBulk.Action.ZIP, ns, writable, me()).why)
        assertEquals(DriveBulk.Why.LINKS_OFF, DriveBulk.can(DriveBulk.Action.ZIP, ns, writable, me(DMe.FEATURE_BULK)).why)
        assertTrue(DriveBulk.can(DriveBulk.Action.ZIP, ns, readOnly, me(DMe.FEATURE_BULK, DMe.FEATURE_ZIP)).ok)
        assertEquals(DriveBulk.Why.NEEDS_BULK, DriveBulk.can(DriveBulk.Action.LINK, ns, writable, me()).why)
        assertEquals(DriveBulk.Why.LINKS_OFF, DriveBulk.can(DriveBulk.Action.LINK, ns, writable, me(DMe.FEATURE_BULK)).why)
        assertTrue(DriveBulk.can(DriveBulk.Action.LINK, ns, writable, me(DMe.FEATURE_BULK, DMe.FEATURE_MULTI_LINK)).ok)
        // one item: the link of before
        assertTrue(DriveBulk.can(DriveBulk.Action.LINK, listOf(node(1)), writable, me()).ok)
    }

    @Test
    fun `one link for several takes 2 to 20 files only`() {
        val all = me(DMe.FEATURE_BULK, DMe.FEATURE_MULTI_LINK)
        assertEquals(DriveBulk.Why.LINK_FILES_ONLY, DriveBulk.can(DriveBulk.Action.LINK, listOf(node(1), node(2, dir = true)), writable, all).why)
        assertEquals(DriveBulk.Why.TOO_MANY, DriveBulk.can(DriveBulk.Action.LINK, (1L..21L).map { node(it) }, writable, all).why)
        assertTrue(DriveBulk.can(DriveBulk.Action.LINK, (1L..20L).map { node(it) }, writable, all).ok)
    }

    @Test
    fun `a zip is of one folder or of up to 20 files`() {
        val all = me(DMe.FEATURE_BULK, DMe.FEATURE_ZIP)
        assertTrue(DriveBulk.can(DriveBulk.Action.ZIP, listOf(node(1, dir = true)), readOnly, all).ok)
        assertTrue(DriveBulk.can(DriveBulk.Action.ZIP, listOf(node(1)), readOnly, all).ok)
        assertEquals(DriveBulk.Why.ZIP_SHAPE, DriveBulk.can(DriveBulk.Action.ZIP, listOf(node(1, dir = true), node(2, dir = true)), readOnly, all).why)
        assertEquals(DriveBulk.Why.ZIP_SHAPE, DriveBulk.can(DriveBulk.Action.ZIP, listOf(node(1, dir = true), node(2)), readOnly, all).why)
        assertEquals(DriveBulk.Why.TOO_MANY, DriveBulk.can(DriveBulk.Action.ZIP, (1L..21L).map { node(it) }, readOnly, all).why)
    }

    @Test
    fun `send and save take folders only as a zip`() {
        val files = listOf(node(1), node(2))
        val withDir = files + node(3, dir = true)
        val zip = me(DMe.FEATURE_BULK, DMe.FEATURE_ZIP)
        assertTrue(DriveBulk.can(DriveBulk.Action.SEND, files, readOnly, me()).ok)
        assertTrue(DriveBulk.can(DriveBulk.Action.SAVE, files, readOnly, me()).ok)
        assertEquals(DriveBulk.Why.FOLDERS_NEED_ZIP, DriveBulk.can(DriveBulk.Action.SEND, withDir, readOnly, me()).why)
        assertEquals(DriveBulk.Why.FOLDERS_NEED_ZIP, DriveBulk.can(DriveBulk.Action.SAVE, withDir, readOnly, me()).why)
        assertEquals(DriveBulk.Why.LINKS_OFF, DriveBulk.can(DriveBulk.Action.SAVE, withDir, readOnly, me(DMe.FEATURE_BULK)).why)
        // sending a folder: alone, as its ZIP; saving: files one by one, each folder as a ZIP
        assertTrue(DriveBulk.can(DriveBulk.Action.SEND, listOf(node(3, dir = true)), readOnly, zip).ok)
        assertEquals(DriveBulk.Why.ZIP_SHAPE, DriveBulk.can(DriveBulk.Action.SEND, withDir, readOnly, zip).why)
        assertTrue(DriveBulk.can(DriveBulk.Action.SAVE, withDir + node(4, dir = true), readOnly, zip).ok)
    }

    @Test
    fun `the actions for one item are greyed out with several`() {
        val two = listOf(node(1, name = "a.docx"), node(2))
        for (a in listOf(DriveBulk.Action.RENAME, DriveBulk.Action.DETAILS, DriveBulk.Action.OFFICE)) {
            assertEquals(DriveBulk.Why.ONLY_ONE, DriveBulk.can(a, two, writable, me(DMe.FEATURE_OFFICE)).why, a.name)
        }
        assertTrue(DriveBulk.can(DriveBulk.Action.RENAME, listOf(node(1)), writable, me()).ok)
        assertEquals(DriveBulk.Why.ONLY_FILES, DriveBulk.can(DriveBulk.Action.DETAILS, listOf(node(1, dir = true)), writable, me()).why)
        assertTrue(DriveBulk.can(DriveBulk.Action.OFFICE, listOf(node(1, name = "a.docx")), writable, me(DMe.FEATURE_OFFICE)).ok)
        assertEquals(DriveBulk.Why.NO_OFFICE, DriveBulk.can(DriveBulk.Action.OFFICE, listOf(node(1, name = "a.pdf")), writable, me(DMe.FEATURE_OFFICE)).why)
    }

    @Test
    fun `an album only when all are photos or videos`() {
        val photos = listOf(node(1, name = "a.jpg", mime = "image/jpeg"), node(2, name = "b.mp4", mime = "video/mp4"))
        assertTrue(DriveBulk.can(DriveBulk.Action.ALBUM, photos, readOnly, me(DMe.FEATURE_ALBUMS)).ok)
        assertEquals(DriveBulk.Why.NOT_MEDIA, DriveBulk.can(DriveBulk.Action.ALBUM, photos + node(3), readOnly, me(DMe.FEATURE_ALBUMS)).why)
        assertEquals(DriveBulk.Why.NO_ALBUMS, DriveBulk.can(DriveBulk.Action.ALBUM, photos, readOnly, me()).why)
    }

    @Test
    fun `nothing leaves a vault, moving and deleting inside it are fine`() {
        val v = "a".repeat(22)
        val ns = listOf(node(1, vault = v), node(2, vault = v))
        val inVault = DriveBulk.Where(writable = { true }, vault = true)
        val all = me(DMe.FEATURE_BULK, DMe.FEATURE_ZIP, DMe.FEATURE_MULTI_LINK, DMe.FEATURE_ALBUMS)
        for (a in listOf(DriveBulk.Action.SEND, DriveBulk.Action.SAVE, DriveBulk.Action.ZIP, DriveBulk.Action.LINK, DriveBulk.Action.ALBUM)) {
            assertEquals(DriveBulk.Why.VAULT, DriveBulk.can(a, ns, inVault, all).why, a.name)
        }
        assertTrue(DriveBulk.can(DriveBulk.Action.MOVE, ns, inVault, all).ok)
        assertTrue(DriveBulk.can(DriveBulk.Action.DELETE, ns, inVault, all).ok)
        assertTrue(DriveBulk.can(DriveBulk.Action.COPY, ns, inVault, all).ok)
    }

    // ─── the target ─────────────────────────────────────────────────

    private fun folder(id: Long, crumbs: List<Long> = listOf(1), role: String = "write", vault: String? = null) = DFolder(
        node = node(id, name = "d$id", dir = true, parent = crumbs.lastOrNull() ?: 0, vault = vault),
        crumbs = crumbs.map { DCrumb(it, "c$it") },
        role = role,
        items = emptyList(),
        total = 0,
        next = null,
    )

    @Test
    fun `not into a ticked folder, nor below it`() {
        val sel = setOf(5L, 6L)
        assertEquals(DriveBulk.Block.INTO_ITSELF, DriveBulk.targetBlock(folder(5), sel, setOf(1L), DriveBulk.Mode.MOVE))
        assertEquals(DriveBulk.Block.INTO_ITSELF, DriveBulk.targetBlock(folder(9, crumbs = listOf(1, 5)), sel, setOf(1L), DriveBulk.Mode.COPY))
        assertNull(DriveBulk.targetBlock(folder(9, crumbs = listOf(1, 7)), sel, setOf(1L), DriveBulk.Mode.MOVE))
        assertEquals(DriveBulk.Block.INTO_ITSELF, DriveBulk.rowBlock(node(5, dir = true), sel))
        assertNull(DriveBulk.rowBlock(node(7, dir = true), sel))
    }

    @Test
    fun `a move needs another folder and the write right, a copy may stay`() {
        assertEquals(DriveBulk.Block.SAME_FOLDER, DriveBulk.targetBlock(folder(1, crumbs = emptyList()), setOf(5L), setOf(1L), DriveBulk.Mode.MOVE))
        assertNull(DriveBulk.targetBlock(folder(1, crumbs = emptyList()), setOf(5L), setOf(1L), DriveBulk.Mode.COPY))
        // from two folders: one of them is a fine target
        assertNull(DriveBulk.targetBlock(folder(1, crumbs = emptyList()), setOf(5L), setOf(1L, 2L), DriveBulk.Mode.MOVE))
        assertEquals(DriveBulk.Block.READ_ONLY, DriveBulk.targetBlock(folder(8, role = "read"), setOf(5L), setOf(1L), DriveBulk.Mode.COPY))
    }

    @Test
    fun `never over the border of a vault`() {
        val v = "a".repeat(22)
        val w = "b".repeat(22)
        assertEquals(DriveBulk.Block.VAULT, DriveBulk.targetBlock(folder(8, vault = v), setOf(5L), setOf(1L), DriveBulk.Mode.MOVE))
        assertNull(DriveBulk.targetBlock(folder(8, vault = v), setOf(5L), setOf(1L), DriveBulk.Mode.MOVE, vaultId = v))
        assertEquals(DriveBulk.Block.VAULT, DriveBulk.targetBlock(folder(8, vault = w), setOf(5L), setOf(1L), DriveBulk.Mode.MOVE, vaultId = v))
        assertEquals(DriveBulk.Block.VAULT, DriveBulk.targetBlock(folder(8), setOf(5L), setOf(1L), DriveBulk.Mode.MOVE, vaultId = v))
        // the list: folders only, vaults left out (inside a vault: only its own)
        val items = listOf(node(1, dir = true), node(2), node(3, dir = true, vault = v), node(4, dir = true, vault = w))
        assertEquals(listOf(1L), DriveBulk.targetRows(items, null).map { it.id })
        assertEquals(listOf(3L), DriveBulk.targetRows(items, v).map { it.id })
    }

    @Test
    fun `read-only areas are greyed out`() {
        val a = DArea("u:1", "mine", "Meine Dateien", "manage", "owner", 1, false)
        assertTrue(DriveBulk.areaEnabled(a))
        assertFalse(DriveBulk.areaEnabled(a.copy(role = "read")))
        assertFalse(DriveBulk.areaEnabled(a.copy(archived = true)))
    }

    @Test
    fun `recent folders – newest first, each once, at most four, kept as text`() {
        var list = emptyList<DriveBulk.Recent>()
        for (i in 1L..6L) list = DriveBulk.pushRecent(list, DriveBulk.Recent(i, "Ordner $i"))
        list = DriveBulk.pushRecent(list, DriveBulk.Recent(4, "Ordner 4"))
        assertEquals(listOf(4L, 6L, 5L, 3L), list.map { it.id })
        val back = DriveBulk.decodeRecent(DriveBulk.encodeRecent(list + DriveBulk.Recent(9, "a\tb\nc")))
        assertEquals(list, back)
        assertEquals(emptyList<DriveBulk.Recent>(), DriveBulk.decodeRecent("x\ty\n0\tnull\n\n"))
        assertEquals(listOf(DriveBulk.Recent(2, "Steuern › 2025")), DriveBulk.decodeRecent("2\tSteuern › 2025"))
    }

    // ─── names taken ────────────────────────────────────────────────

    private fun check(vararg c: DOpConflict, blocked: List<DOpBlocked> = emptyList()) = DOpCheck(2, 0, 200, c.toList(), blocked)

    @Test
    fun `only names with another content ask, same content is done anyway`() {
        val same = DOpConflict(1, "Scan.pdf", 11, 1200, "2026-10-12T08:00:00Z", same = true)
        val other = DOpConflict(2, "Rechnung.pdf", 12, 412, "2026-10-03T08:00:00Z", same = false)
        assertFalse(DriveBulk.needsQuestion(check(same)))
        assertTrue(DriveBulk.needsQuestion(check(same, other)))
        assertEquals(listOf("Scan.pdf"), DriveBulk.sameNames(check(same, other)))
        val rows = DriveBulk.conflictRows(check(same, other), mapOf(2L to node(2, size = 380)))
        // the ones that ask first, then the same ones
        assertEquals(listOf("Rechnung.pdf", "Scan.pdf"), rows.map { it.name })
        assertEquals(412L, rows[0].thereSize)
        assertEquals(380L, rows[0].newSize)
        assertTrue(rows[1].same)
    }

    @Test
    fun `blocked items are left out, the server's items count when it names them`() {
        val c = check(blocked = listOf(DOpBlocked(2, "forbidden")))
        assertEquals(listOf(1L, 3L), DriveBulk.allowed(listOf(1, 2, 3), c))
        // a node inside a ticked folder is not an item of its own
        assertEquals(listOf(1L), DriveBulk.allowed(listOf(1, 2, 3), c.copy(items = listOf(1))))
    }

    @Test
    fun `too little space only when both are known`() {
        assertFalse(DriveBulk.quotaShort(check()))
        assertFalse(DriveBulk.quotaShort(check().copy(quotaNeeded = 100, quotaFree = null)))
        assertTrue(DriveBulk.quotaShort(check().copy(quotaNeeded = 100, quotaFree = 99)))
        assertFalse(DriveBulk.quotaShort(check().copy(quotaNeeded = 100, quotaFree = 100)))
    }

    @Test
    fun `a file inside a merged folder shows by its path, a folder has no sizes`() {
        val c = check(
            DOpConflict(1, "a.pdf", 11, 5, null, same = false, path = "Ordner/a.pdf", size = 7, mtime = "2026-10-02T00:00:00Z"),
            DOpConflict(2, "Ordner", 12, 0, null, same = false, isDir = true, existingIsDir = true),
        )
        val rows = DriveBulk.conflictRows(c, emptyMap())
        assertEquals("Ordner/a.pdf", rows[0].name)
        assertEquals(7L, rows[0].newSize)
        assertEquals("2026-10-02T00:00:00Z", rows[0].newTime)
        assertNull(rows[1].newSize)
        assertNull(rows[1].thereSize)
    }

    @Test
    fun `without Drive 1_12 the app finds taken names itself`() {
        val sha = "a".repeat(64)
        val nodes = listOf(node(1, name = "A.pdf", sha = sha, size = 5), node(2, name = "b.pdf", size = 7), node(3, name = "c.pdf"), node(4, name = "Ordner", dir = true))
        val there = listOf(node(11, name = "a.PDF", sha = sha, size = 5, parent = 9), node(12, name = "B.pdf", size = 9, parent = 9), node(13, name = "ordner", dir = true, parent = 9))
        val c = DriveBulk.localCheck(nodes, there)
        assertEquals(listOf(1L, 2L, 4L), c.conflicts.map { it.id })
        assertTrue(c.conflicts[0].same)
        assertFalse(c.conflicts[1].same)
        assertEquals(9L, c.conflicts[1].existingSize)
        assertNull(c.conflicts[2].existingSize)
        assertEquals(3, c.files)
        assertEquals(1, c.folders)
    }

    @Test
    fun `without Drive 1_12 a move keeps both or skips, never merges a folder`() {
        val sha = "a".repeat(64)
        val nodes = listOf(node(1, name = "A.pdf", sha = sha, size = 5), node(2, name = "b.pdf", size = 7), node(3, name = "c.pdf"), node(4, name = "Ordner", dir = true))
        val there = listOf(node(11, name = "A.pdf", sha = sha, size = 5), node(12, name = "b.pdf", size = 9), node(13, name = "b (2).pdf"), node(14, name = "Ordner", dir = true))
        val c = DriveBulk.localCheck(nodes, there)
        val keep = DriveBulk.fallbackPlan(nodes, c, there, DriveBulk.ON_RENAME)
        assertEquals(DriveBulk.ON_SKIP, keep[1L])
        assertEquals("b (3).pdf", keep[2L])
        assertNull(keep[3L])
        assertEquals(DriveBulk.ON_SKIP, keep[4L])
        val skip = DriveBulk.fallbackPlan(nodes, c, there, DriveBulk.ON_SKIP)
        assertEquals(DriveBulk.ON_SKIP, skip[2L])
        assertNull(skip[3L])
    }

    // ─── jobs ───────────────────────────────────────────────────────

    private fun op(kind: String = "move", state: String = "done", undo: String? = "2026-10-29T10:15:00Z") =
        DOp("op1", kind, state, total = 10, done = 4, bytesTotal = 0, bytesDone = 0, undoUntil = undo)

    @Test
    fun `progress by bytes when known, else by items`() {
        assertEquals(0.4f, DriveBulk.progress(op()), 0.001f)
        assertEquals(0.25f, DriveBulk.progress(op().copy(bytesTotal = 400, bytesDone = 100)), 0.001f)
        assertEquals(0f, DriveBulk.progress(op().copy(total = 0, done = 0)), 0.001f)
    }

    @Test
    fun `undo while it is not too late, for a finished move, copy or trash`() {
        val now = java.time.Instant.parse("2026-10-29T10:00:00Z").toEpochMilli()
        assertTrue(DriveBulk.canUndo(op(), now))
        assertFalse(DriveBulk.canUndo(op(undo = "2026-10-29T09:59:00Z"), now))
        assertFalse(DriveBulk.canUndo(op(undo = null), now))
        assertFalse(DriveBulk.canUndo(op(state = "running"), now))
        // stopped: what was done can be undone
        assertTrue(DriveBulk.canUndo(op(state = "cancelled"), now))
        assertFalse(DriveBulk.canUndo(op(state = "failed"), now))
        assertFalse(DriveBulk.canUndo(op(kind = "purge"), now))
        assertTrue(DriveBulk.canUndo(op(kind = "trash", undo = "2026-10-29T12:15:00+02:00"), now))
    }

    @Test
    fun `the last part of a target path`() {
        assertEquals("Rechnungen", DriveBulk.lastPart("Meine Dateien › Dokumente › Rechnungen"))
        assertEquals("b", DriveBulk.lastPart("/a/b/"))
        assertNull(DriveBulk.lastPart(null))
    }
}
