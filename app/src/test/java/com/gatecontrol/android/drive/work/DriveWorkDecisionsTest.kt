package com.gatecontrol.android.drive.work

import com.gatecontrol.android.drive.DNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DriveWorkDecisionsTest {

    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)

    private fun photo(id: Long, sha: String? = null, size: Long = 2_345_678, takenAt: String? = "2026-10-29T09:12:00.000Z", name: String = "IMG_$id.jpg", parent: Long = 3) =
        DNode(id, "mine:7", parent, name, false, size, "image/jpeg", null, 1, null, null, null, sha256 = sha, takenAt = takenAt)

    private fun undecided(list: List<WorkDecision>, vararg n: DNode) = DriveWorkDecisions.undecided(list, n.toList()).first.map { it.id }

    @Test
    fun `a photo decided private or work is never open again`() {
        var list = DriveWorkDecisions.decide(emptyList(), listOf(photo(1, shaA)), work = false, at = 10)
        list = DriveWorkDecisions.decide(list, listOf(photo(2, shaB)), work = true, at = 11)
        assertEquals(listOf(3L), undecided(list, photo(1, shaA), photo(2, shaB), photo(3, "c".repeat(64), size = 1)))
        assertEquals(false, DriveWorkDecisions.of(DriveWorkDecisions.index(list), photo(1, shaA))?.work)
        assertEquals(true, DriveWorkDecisions.of(DriveWorkDecisions.index(list), photo(2, shaB))?.work)
    }

    @Test
    fun `moved or renamed keeps its id, a new upload of the same file its content`() {
        val list = DriveWorkDecisions.decide(emptyList(), listOf(photo(1, shaA)), work = true, at = 10)
        // moved into another folder and renamed: same node
        assertEquals(emptyList<Long>(), undecided(list, photo(1, shaA, name = "Baustelle.jpg", parent = 99)))
        // uploaded again by the backup into Kamera-Uploads: a new node with the same content
        assertEquals(emptyList<Long>(), undecided(list, photo(77, shaA, takenAt = "2026-11-02T10:00:00.000Z")))
        // a Drive without the content in the photo list: the same size and the same moment
        val old = DriveWorkDecisions.decide(emptyList(), listOf(photo(5, sha = null)), work = false, at = 10)
        assertEquals(emptyList<Long>(), undecided(old, photo(78, sha = null, parent = 99)))
        assertEquals(listOf(80L), undecided(old, photo(80, sha = null, size = 17)))
        // another photo stays open
        assertEquals(listOf(79L), undecided(list, photo(79, shaB, size = 999)))
    }

    @Test
    fun `size and time only decide a photo whose content is unknown`() {
        // two photos of the same size taken at the same moment, both with their content: each its own decision
        var list = DriveWorkDecisions.decide(emptyList(), listOf(photo(1, shaA)), work = true, at = 10)
        list = DriveWorkDecisions.decide(list, listOf(photo(2, shaB)), work = false, at = 11)
        assertEquals(2, list.size)
        assertEquals(listOf(3L), undecided(list, photo(3, "c".repeat(64))))
        // a decision made with the content: its copy in an older Drive's list (no content) is known by size and time
        assertEquals(emptyList<Long>(), undecided(list, photo(4, sha = null)))
    }

    @Test
    fun `a later answer replaces the earlier one`() {
        var list = DriveWorkDecisions.decide(emptyList(), listOf(photo(1, shaA)), work = false, at = 10)
        list = DriveWorkDecisions.decide(list, listOf(photo(1, shaA)), work = true, at = 20)
        assertEquals(1, list.size)
        assertTrue(list.single().work)
    }

    @Test
    fun `taken back, it is open again`() {
        val list = DriveWorkDecisions.decide(emptyList(), listOf(photo(1, shaA), photo(2, shaB, size = 5)), work = true, at = 10)
        val after = DriveWorkDecisions.forget(list, listOf(1L))
        assertEquals(listOf(1L), undecided(after, photo(1, shaA), photo(2, shaB, size = 5)))
    }

    @Test
    fun `decisions of the app up to 1_129 come from the examples the person gave`() {
        val f = mapOf(1 to 0.9f)
        val examples = listOf(
            WorkExample("n:1", false, WorkExample.Source.CLEANUP, f, 10),
            WorkExample("n:2", true, WorkExample.Source.CLEANUP, f, 11),
            WorkExample("n:3", true, WorkExample.Source.ASSIGN, f, 12),
            WorkExample("n:4", false, WorkExample.Source.MOVE, f, 13),
            WorkExample("m:$shaB", true, WorkExample.Source.ASSIGN, f, 14),
            // what the phone sorted alone is no decision of the person
            WorkExample("n:5", false, WorkExample.Source.WIFI, f, 15),
            WorkExample("n:6", true, WorkExample.Source.PLACE, f, 16),
            WorkExample("n:7", true, WorkExample.Source.FOLDER, f, 17),
            WorkExample("n:x", true, WorkExample.Source.CLEANUP, f, 18),
        )
        val list = DriveWorkDecisions.fromExamples(examples)
        assertEquals(listOf(setOf("n:1"), setOf("n:2"), setOf("n:3"), setOf("n:4"), setOf("h:$shaB")), list.map { it.keys })
        assertEquals(listOf(5L, 6L, 7L), undecided(list, *(1L..7L).map { photo(it, size = it) }.toTypedArray()))
        // a decision kept by id alone learns the content when the photo is seen: a later copy of it is known too
        val (open, grown) = DriveWorkDecisions.undecided(list, listOf(photo(2, shaA)))
        assertTrue(open.isEmpty())
        assertTrue("h:$shaA" in grown.first { "n:2" in it.keys }.keys)
        assertEquals(emptyList<Long>(), undecided(grown, photo(500, shaA, size = 1)))
    }

    @Test
    fun `nothing new learned keeps the same list`() {
        val list = DriveWorkDecisions.decide(emptyList(), listOf(photo(1, shaA)), work = true, at = 10)
        assertSame(list, DriveWorkDecisions.undecided(list, listOf(photo(1, shaA))).second)
        assertSame(list, DriveWorkDecisions.undecided(list, listOf(photo(9, shaB, size = 3))).second)
    }

    @Test
    fun `kept and read back, anything broken reads as nothing`() {
        val list = DriveWorkDecisions.decide(emptyList(), listOf(photo(1, shaA)), work = true, at = 10)
        assertEquals(list, DriveWorkDecisions.decode(DriveWorkDecisions.encode(list)))
        assertNull(DriveWorkDecisions.decode(null))
        assertEquals(emptyList<WorkDecision>(), DriveWorkDecisions.decode("{broken"))
        assertEquals(emptyList<WorkDecision>(), DriveWorkDecisions.decode("""{"v":1,"d":[{"k":[],"w":true},{"w":true},{"k":["n:1"]}]}"""))
    }

    @Test
    fun `the oldest go beyond the limit`() {
        var list = emptyList<WorkDecision>()
        list = DriveWorkDecisions.decide(list, (1L..DriveWorkDecisions.MAX.toLong()).map { photo(it, size = 0) }, work = false, at = 1)
        list = DriveWorkDecisions.decide(list, listOf(photo(DriveWorkDecisions.MAX + 1L, size = 0)), work = true, at = 2)
        assertEquals(DriveWorkDecisions.MAX, list.size)
        assertTrue(list.last().work)
    }
}
