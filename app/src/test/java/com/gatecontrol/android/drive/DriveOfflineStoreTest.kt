package com.gatecontrol.android.drive

import com.gatecontrol.android.scan.MediaScan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** "Offline verfügbar" on this phone: the plan of a run, taking a mark off, the states and the index. */
class DriveOfflineStoreTest {

    private val mb = 1024L * 1024

    private fun node(id: Long, size: Long = mb, rev: Int = 1, sha: String? = null, dir: Boolean = false, vault: String? = null, scan: MediaScan? = null) =
        DNode(
            id = id, areaId = "a", parentId = 1, name = "f$id.pdf", isDir = dir, size = size, mime = "application/pdf", mediaId = "m$id",
            rev = rev, mtime = null, updatedAt = null, updatedBy = null, sha256 = sha, vaultId = vault, scan = scan,
        )

    private fun want(n: DNode, vararg roots: Long) = DriveOfflineWant(n, if (roots.isEmpty()) setOf(n.id) else roots.toSet(), null)

    private fun stored(n: DNode, rev: Int = n.rev, sha: String? = n.sha256, roots: Set<Long> = setOf(n.id)) = DriveOfflineCopy(
        id = n.id, name = n.name, mime = n.mime, size = n.size, rev = rev, sha256 = sha, path = null, roots = roots,
        state = DriveOfflineCopy.State.STORED, file = n.name, storedRev = rev, storedSha256 = sha, storedAt = 1L,
    )

    @Test
    fun `new marks load, the same version stays, a changed one is outdated and loads again`() {
        val a = node(1)
        val b = node(2, rev = 3)
        val c = node(3, rev = 2, sha = "a".repeat(64))
        val have = mapOf(2L to stored(b), 3L to stored(c, rev = 1, sha = "b".repeat(64)))
        val p = DriveOfflineStoreLogic.plan(listOf(want(a), want(b), want(c)), have, 100 * mb)
        assertEquals(listOf(1L, 3L), p.download)
        assertEquals(DriveOfflineCopy.State.PENDING, p.copies[1L]?.state)
        assertEquals(DriveOfflineCopy.State.STORED, p.copies[2L]?.state)
        assertEquals(DriveOfflineCopy.State.OUTDATED, p.copies[3L]?.state)
        // the older copy still opens until the new one is there
        assertEquals(1, p.copies[3L]?.storedRev)
        assertTrue(p.remove.isEmpty())
    }

    @Test
    fun `same revision with another content is outdated too`() {
        val n = node(1, rev = 2, sha = "a".repeat(64))
        val p = DriveOfflineStoreLogic.plan(listOf(want(n)), mapOf(1L to stored(n, sha = "c".repeat(64))), 100 * mb)
        assertEquals(DriveOfflineCopy.State.OUTDATED, p.copies[1L]?.state)
        assertEquals(listOf(1L), p.download)
    }

    @Test
    fun `no longer marked goes`() {
        val gone = node(9)
        val p = DriveOfflineStoreLogic.plan(emptyList(), mapOf(9L to stored(gone)), 100 * mb)
        assertEquals(listOf(9L), p.remove.map { it.id })
        assertTrue(p.copies.isEmpty())
    }

    @Test
    fun `the limit is filled in order, what does not fit waits and its copy goes`() {
        val a = node(1, size = 60 * mb)
        val b = node(2, size = 50 * mb)
        val c = node(3, size = 30 * mb)
        val p = DriveOfflineStoreLogic.plan(listOf(want(a), want(b), want(c)), mapOf(2L to stored(b)), 100 * mb)
        assertEquals(listOf(1L, 3L), p.download)
        assertEquals(DriveOfflineStoreLogic.WAIT_SPACE, p.copies[2L]?.reason)
        assertNull(p.copies[2L]?.file)
        assertEquals(listOf(2L), p.remove.map { it.id })
    }

    @Test
    fun `folders are never kept, a vault file is planned as its ciphertext, a blocked file loses its copy`() {
        val vault = node(1, vault = "AAAAAAAAAAAAAAAAAAAAAA")
        val dir = node(2, dir = true)
        val bad = node(3, scan = MediaScan(state = MediaScan.INFECTED, blocked = true))
        val p = DriveOfflineStoreLogic.plan(listOf(want(vault), want(dir), want(bad)), mapOf(3L to stored(bad)), 100 * mb)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA", p.copies[1L]?.vaultId)
        assertNull(p.copies[2L])
        assertEquals(DriveOfflineCopy.State.FAILED, p.copies[3L]?.state)
        assertNull(p.copies[3L]?.file)
        assertEquals(listOf(3L), p.remove.map { it.id })
        assertEquals(listOf(1L), p.download)
    }

    @Test
    fun `a file under two marks is kept once`() {
        val n = node(5)
        val p = DriveOfflineStoreLogic.plan(listOf(DriveOfflineWant(n, setOf(10L, 11L), "Ordner")), emptyMap(), 100 * mb)
        assertEquals(setOf(10L, 11L), p.copies[5L]?.roots)
        assertEquals("Ordner", p.copies[5L]?.path)
    }

    @Test
    fun `taking a mark off drops only what no other mark holds`() {
        val one = stored(node(1), roots = setOf(10L))
        val both = stored(node(2), roots = setOf(10L, 11L))
        val other = stored(node(3), roots = setOf(11L))
        val file = stored(node(4))
        val (keep, gone) = DriveOfflineStoreLogic.without(mapOf(1L to one, 2L to both, 3L to other, 4L to file), 10L)
        assertEquals(listOf(1L), gone.map { it.id })
        assertEquals(setOf(11L), keep[2L]?.roots)
        assertNotNull(keep[3L])
        val (keep2, gone2) = DriveOfflineStoreLogic.without(keep, 4L)
        assertEquals(listOf(4L), gone2.map { it.id })
        assertNull(keep2[4L])
    }

    @Test
    fun `wifi only and the room on the phone`() {
        assertTrue(DriveOfflineStoreLogic.mayLoad(wifiOnly = false, unmetered = false))
        assertFalse(DriveOfflineStoreLogic.mayLoad(wifiOnly = true, unmetered = false))
        assertTrue(DriveOfflineStoreLogic.mayLoad(wifiOnly = true, unmetered = true))
        assertTrue(DriveOfflineStoreLogic.deviceRoom(usable = 2_000 * mb, size = 100 * mb))
        assertFalse(DriveOfflineStoreLogic.deviceRoom(usable = 550 * mb, size = 100 * mb))
    }

    @Test
    fun `a folder mark counts its files`() {
        val copies = listOf(
            stored(node(1), roots = setOf(10L)),
            stored(node(2), roots = setOf(10L)).copy(state = DriveOfflineCopy.State.PENDING, reason = DriveOfflineStoreLogic.WAIT_WIFI, file = null),
            stored(node(3), roots = setOf(11L)),
        )
        val m = DriveOfflineStoreLogic.markState(10L, copies)!!
        assertEquals(2, m.total)
        assertEquals(1, m.stored)
        assertEquals(1, m.pending)
        assertEquals(DriveOfflineStoreLogic.WAIT_WIFI, m.reason)
        assertNull(DriveOfflineStoreLogic.markState(99L, copies))
        assertEquals(2 * mb, DriveOfflineStoreLogic.used(copies))
    }

    @Test
    fun `the index reads back, a strange file name is not trusted`() {
        val c = stored(node(1, sha = "a".repeat(64)), roots = setOf(1L, 7L)).copy(path = "Fotos/2026")
        val json = DriveOfflineStoreLogic.toJson("https://gc.example", listOf(c))
        val (server, list) = DriveOfflineStoreLogic.fromJson(json)!!
        assertEquals("https://gc.example", server)
        assertEquals(listOf(c), list)
        val evil = json.replace("\"file\":\"f1.pdf\"", "\"file\":\"../../shared_prefs/x.xml\"")
        val back = DriveOfflineStoreLogic.fromJson(evil)!!.second.single()
        assertNull(back.file)
        assertNull(back.storedRev)
        assertNull(DriveOfflineStoreLogic.fromJson("not json"))
        assertNull(DriveOfflineStoreLogic.fromJson("""{"v":2,"server":"x","copies":[]}"""))
    }

    @Test
    fun `a stored limit that is no choice becomes the default`() {
        assertEquals(5_000, DriveSettingsLogic.offlineLimit(5_000))
        assertEquals(DriveSettingsLogic.DEFAULT_OFFLINE_LIMIT_MB, DriveSettingsLogic.offlineLimit(123))
    }
}
