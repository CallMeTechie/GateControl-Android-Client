package com.gatecontrol.android.drive.vault

import com.gatecontrol.android.drive.DVaultLocalItem
import com.gatecontrol.android.drive.vault.DriveVaultLocalFolder.State
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DriveVaultLocalLogicTest {

    private val vaultId = "Vt9bJm0n2QwErTyUiOpAsD"
    private val day = 24 * 60 * 60_000L

    private fun folder(items: List<DriveVaultLocalItem> = emptyList(), state: State = State.LOCAL, since: Long = 1000, changedAt: Long = since, purgeAt: Long? = since + day, lastExport: Long? = null) =
        DriveVaultLocalFolder("srv", vaultId, 10, 1, "mine:1", "cipher", "dev", state, since, purgeAt, lastExport, changedAt, items)

    private fun file(id: Long, parent: Long = 10, rev: Int = 1, sha: String? = "a".repeat(64), size: Long = 100) =
        DriveVaultLocalItem(id, parent, false, "n$id", size, 0, rev, sha)

    private fun m(id: Long, parent: Long = 10, rev: Int = 1, sha: String? = "a".repeat(64), dir: Boolean = false) =
        DVaultLocalItem(id, parent, dir, "c", 1, rev, sha, 100)

    @Test
    fun `room for a move is the content and a tenth and 500 MB`() {
        val mb = 1_048_576L
        assertEquals(500 * mb, DriveVaultLocalLogic.roomNeeded(0))
        assertEquals(1100 * mb + 500 * mb, DriveVaultLocalLogic.roomNeeded(1000 * mb))
        assertTrue(DriveVaultLocalLogic.enoughRoom(100 * mb, 700 * mb))
        assertFalse(DriveVaultLocalLogic.enoughRoom(100 * mb, 600 * mb))
    }

    @Test
    fun `a move that goes on fetches only what changed and drops what is gone`() {
        val have = listOf(file(1), file(2, rev = 1), file(3), DriveVaultLocalItem(4, 10, true, "d", 0, 0))
        val manifest = listOf(m(1), m(2, rev = 2), m(5), m(4, dir = true))
        val p = DriveVaultLocalLogic.plan(manifest, have)
        assertEquals(listOf(2L, 5L), p.fetch.map { it.id })
        assertEquals(listOf(1L, 4L), p.keep.map { it.id }.sorted())
        assertEquals(listOf(3L), p.drop.map { it.id })
        assertEquals(listOf(4L), p.dirs.map { it.id })
        // another SHA-256 of the same revision is fetched again too
        assertEquals(listOf(1L), DriveVaultLocalLogic.plan(listOf(m(1, sha = "b".repeat(64))), have).fetch.map { it.id })
    }

    @Test
    fun `complete only when every file and folder of the manifest is here`() {
        val manifest = listOf(m(1), m(4, dir = true))
        assertFalse(DriveVaultLocalLogic.complete(manifest, listOf(file(1))))
        assertTrue(DriveVaultLocalLogic.complete(manifest, listOf(file(1), DriveVaultLocalItem(4, 10, true, "d"))))
    }

    @Test
    fun `the commit's manifest has folders as ids, files with rev and sha256`() {
        val c = DriveVaultLocalLogic.commitManifest(listOf(file(1, rev = 3), DriveVaultLocalItem(4, 10, true, "d")))
        assertEquals(3, c[0].rev)
        assertEquals("a".repeat(64), c[0].sha256)
        assertTrue(c[1].isDir)
    }

    @Test
    fun `a 409 changed is merged into the manifest`() {
        val merged = DriveVaultLocalLogic.merge(listOf(m(1), m(2), m(3)), changed = listOf(m(2, rev = 9)), added = listOf(m(7)), removed = listOf(3))
        assertEquals(listOf(1L, 2L, 7L), merged.map { it.id })
        assertEquals(9, merged.first { it.id == 2L }.rev)
    }

    @Test
    fun `undo only within the grace time, only while nothing changed here`() {
        val f = folder()
        assertTrue(DriveVaultLocalLogic.canUndo(f, 2000))
        assertFalse(DriveVaultLocalLogic.canUndo(f, 1000 + day + 1))
        assertFalse(DriveVaultLocalLogic.canUndo(f.copy(changedAt = 1500), 2000))
        assertFalse(DriveVaultLocalLogic.canUndo(f.copy(state = State.MOVING), 2000))
        assertFalse(DriveVaultLocalLogic.canUndo(f.copy(revoked = true), 2000))
        assertFalse(DriveVaultLocalLogic.canUndo(f.copy(purgeAt = null), 2000))
    }

    @Test
    fun `a backup is due without one, after a month, or after a change`() {
        val month = DriveVaultLocalLogic.REMIND_EVERY_MS
        assertTrue(DriveVaultLocalLogic.needsBackup(folder(), 2000))
        val saved = folder(lastExport = 5000)
        assertFalse(DriveVaultLocalLogic.needsBackup(saved, 6000))
        assertTrue(DriveVaultLocalLogic.needsBackup(saved, 5000 + month))
        assertTrue(DriveVaultLocalLogic.needsBackup(saved.copy(changedAt = 5001), 6000))
        assertFalse(DriveVaultLocalLogic.needsBackup(folder(state = State.MOVING), 2000))
        // the reminder itself: at most once a month
        assertFalse(DriveVaultLocalLogic.remindNow(folder(), 2000))
        assertTrue(DriveVaultLocalLogic.remindNow(folder(), 1000 + month))
        assertFalse(DriveVaultLocalLogic.remindNow(folder().copy(remindedAt = 1000 + month), 1000 + month + 10))
    }

    @Test
    fun `subtree, path and what is reachable`() {
        val items = listOf(
            DriveVaultLocalItem(20, 10, true, "a"),
            DriveVaultLocalItem(21, 20, true, "b"),
            file(22, parent = 21),
            file(23),
            file(99, parent = 1234),
        )
        val f = folder(items)
        assertEquals(setOf(20L, 21L, 22L), DriveVaultLocalLogic.subtree(f, 20))
        assertEquals(listOf(20L, 21L, 22L), DriveVaultLocalLogic.path(f, 22).map { it.id })
        assertEquals(listOf(20L, 21L, 22L, 23L), DriveVaultLocalLogic.reachable(f).map { it.id })
        assertEquals(listOf(20L, 23L), DriveVaultLocalLogic.children(f, 10).map { it.id })
    }

    @Test
    fun `new ids are negative and free`() {
        repeat(50) {
            val id = DriveVaultLocalLogic.newId(setOf(-1L))
            assertTrue(id < 0)
        }
    }

    @Test
    fun `an entry as a node is a vault node of key version 0 with an opaque name`() {
        val f = folder()
        val n = DriveVaultLocalLogic.asNode(f, file(-5))
        assertEquals(0, n.keyVersion)
        assertEquals(vaultId, n.vaultId)
        assertEquals(32, n.name.length)
        assertTrue(n.encrypted)
    }

    @Test
    fun `the index file reads back`() {
        val f = folder(listOf(file(1), DriveVaultLocalItem(-2, 10, true, "d", up = 77)), lastExport = 9).copy(revoked = true, remindedAt = 3)
        assertEquals(f, DriveVaultLocalLogic.fromJson(DriveVaultLocalLogic.toJson(f)))
        assertNull(DriveVaultLocalLogic.fromJson("{\"v\":2}"))
        assertNull(DriveVaultLocalLogic.fromJson("nonsense"))
    }

    @Test
    fun `the store keeps folders and keys, LK opens with the device key and the recovery key`(@TempDir dir: File) {
        val store = DriveVaultLocalStore(File(dir, "data"), File(dir, "keys"))
        val device = DriveVaultCrypto.newKeyPair()
        val recovery = DriveVaultCrypto.recoveryKeyPair(ByteArray(32) { it.toByte() })
        val f = folder(listOf(file(1)))
        store.put(f)
        val lk = store.newKey(vaultId, 10, device.publicKey, DriveVaultCrypto.b64u(recovery.publicKey))
        assertArrayEquals(lk, store.key(10))
        // nothing of LK in the clear on the disk
        val onDisk = File(dir, "keys").walkTopDown().filter { it.isFile }.map { it.readBytes() }.toList()
        assertTrue(onDisk.isNotEmpty())
        onDisk.forEach { b -> assertFalse(String(b, Charsets.ISO_8859_1).contains(String(lk, Charsets.ISO_8859_1))) }

        store.lockKeys()
        assertNull(store.key(10))
        // another run of the app: read from the disk, the key opens with the device key
        val again = DriveVaultLocalStore(File(dir, "data"), File(dir, "keys"))
        assertEquals(f, again.get(10))
        again.unlock(vaultId, device)
        assertArrayEquals(lk, again.key(10))
        assertArrayEquals(lk, again.keyOfItem(1))
        // and with the recovery key (the export)
        val sealed = again.recoverySealed(f)
        assertNotNull(sealed)
        assertArrayEquals(lk, DriveVaultCrypto.openLocalKey(sealed!!, recovery, DriveVaultCrypto.localKeyInfo(vaultId, 10)))
        // another device key does not open it
        val third = DriveVaultLocalStore(File(dir, "data"), File(dir, "keys"))
        third.unlock(vaultId, DriveVaultCrypto.newKeyPair())
        assertNull(third.key(10))
    }

    @Test
    fun `revoked marks every folder of the vault, remove takes files and key`(@TempDir dir: File) {
        val store = DriveVaultLocalStore(File(dir, "data"), File(dir, "keys"))
        val device = DriveVaultCrypto.newKeyPair()
        store.put(folder(listOf(file(1))))
        store.newKey(vaultId, 10, device.publicKey, null)
        val tmp = store.tempFile(store.get(10)!!).apply { writeText("x") }
        store.place(store.get(10)!!, 1, tmp)
        assertTrue(store.fileOf(store.get(10)!!, 1)!!.isFile)
        store.setRevoked(vaultId, true)
        assertTrue(store.get(10)!!.revoked)
        store.setRevoked(vaultId, false)
        assertFalse(store.get(10)!!.revoked)
        store.remove(10)
        assertNull(store.get(10))
        assertNull(store.key(10))
        assertFalse(File(dir, "keys").walkTopDown().any { it.isFile })
        assertFalse(File(dir, "data").walkTopDown().any { it.isFile })
    }

    @Test
    fun `tidy removes files of no entry`(@TempDir dir: File) {
        val store = DriveVaultLocalStore(File(dir, "data"), File(dir, "keys"))
        val f = folder(listOf(file(1)))
        store.put(f)
        store.place(f, 1, store.tempFile(f).apply { writeText("a") })
        store.place(f, 2, store.tempFile(f).apply { writeText("b") })
        store.tempFile(f)
        store.tidy(f)
        assertTrue(store.fileOf(f, 1)!!.isFile)
        assertFalse(store.fileOf(f, 2)!!.exists())
    }
}
