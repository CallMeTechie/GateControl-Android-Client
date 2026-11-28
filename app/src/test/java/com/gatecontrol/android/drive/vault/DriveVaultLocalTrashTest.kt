package com.gatecontrol.android.drive.vault

import android.net.Uri
import com.gatecontrol.android.drive.DResult
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * The trash of a folder kept only here (30 days): deleting moves the entry and everything below it there — still
 * encrypted with LK, no plain name on the disk —, restore goes back (or to the top; a taken name gets "(2)"), delete for
 * good and "Papierkorb leeren" remove the files, after 30 days they go on their own; an export and the way back leave
 * the trash out.
 */
class DriveVaultLocalTrashTest {

    private val vaultId = "Vt9bJm0n2QwErTyUiOpAsD"
    private val device = DriveVaultCrypto.newKeyPair()
    private val bytes = ByteArray(5000) { (it % 251).toByte() }
    private val uri = mockk<Uri>()
    private val day = 24 * 60 * 60_000L
    private var now = 5000L

    private fun setup(dir: File): Pair<DriveVaultLocalStore, DriveVaultLocalEdit> {
        val store = DriveVaultLocalStore(File(dir, "data"), File(dir, "keys"))
        store.put(DriveVaultLocalFolder("srv", vaultId, 10, 1, "mine:1", null, "dev", DriveVaultLocalFolder.State.LOCAL, since = 1000, changedAt = 1000))
        store.newKey(vaultId, 10, device.publicKey, null)
        val opener = object : DriveVaultLocalEdit.Opener {
            override fun describe(uri: Uri) = "Scan.pdf" to bytes.size.toLong()
            override fun open(uri: Uri): InputStream = ByteArrayInputStream(bytes)
        }
        return store to DriveVaultLocalEdit(store, opener) { now }
    }

    private fun name(store: DriveVaultLocalStore, cipher: String) =
        DriveVaultCrypto.decryptName({ if (it == 0) store.key(10) else null }, vaultId, cipher)

    private fun names(store: DriveVaultLocalStore, parent: Long) =
        DriveVaultLocalLogic.children(store.get(10)!!, parent).map { name(store, it.name) }.sorted()

    private fun gcdv(dir: File) = File(dir, "data").walkTopDown().count { it.name.endsWith(".gcdv") }

    @Test
    fun `deleting puts the entry and everything below into the trash, files and names stay encrypted`(@TempDir dir: File) {
        val (store, edit) = setup(dir)
        val sub = (edit.newFolder(10, 10, "Belege") as DResult.Ok).value
        val a = (runBlocking { edit.add(10, sub, uri, "Quittung.pdf") } as DResult.Ok).value
        runBlocking { edit.add(10, 10, uri, "Brief.pdf") }
        now = 9000
        assertTrue(edit.delete(10, sub) is DResult.Ok)
        val f = store.get(10)!!
        assertEquals(1, f.items.size)
        val e = f.trash.single()
        assertEquals(sub, e.id)
        assertEquals(10L, e.parentId)
        assertEquals(9000L, e.deletedAt)
        assertEquals(setOf(sub, a), e.items.map { it.id }.toSet())
        assertEquals(1, e.files)
        assertEquals(5000L, f.trashBytes)
        assertEquals(10_000L, f.allBytes)
        assertEquals(9000L, f.changedAt)
        // both files still on the disk, LK ciphertext; no plain name anywhere
        assertEquals(2, gcdv(dir))
        assertNotNull(store.fileOf(f, a)?.takeIf { it.isFile })
        val disk = dir.walkTopDown().filter { it.isFile }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse(disk.contains("Quittung") || disk.contains("Belege"))
        // the index keeps the trash
        val again = DriveVaultLocalStore(File(dir, "data"), File(dir, "keys"))
        assertEquals(f.trash, again.get(10)!!.trash)
    }

    @Test
    fun `restore goes back where it was, a taken name gets a free one`(@TempDir dir: File) {
        val (store, edit) = setup(dir)
        val sub = (edit.newFolder(10, 10, "Belege") as DResult.Ok).value
        val a = (runBlocking { edit.add(10, sub, uri, "Quittung.pdf") } as DResult.Ok).value
        edit.delete(10, a)
        runBlocking { edit.add(10, sub, uri, "Quittung.pdf") }
        val r = edit.restore(10, a)
        assertEquals("Quittung (2).pdf", (r as DResult.Ok).value)
        assertEquals(listOf("Quittung (2).pdf", "Quittung.pdf"), names(store, sub))
        assertTrue(store.get(10)!!.trash.isEmpty())
        assertEquals(sub, store.get(10)!!.items.first { it.id == a }.parentId)
        // the content is still the same file, readable with LK
        assertTrue(store.fileOf(store.get(10)!!, a)!!.isFile)
    }

    @Test
    fun `restore of an entry whose folder is gone lands at the top, with what was below it`(@TempDir dir: File) {
        val (store, edit) = setup(dir)
        val sub = (edit.newFolder(10, 10, "Belege") as DResult.Ok).value
        val inner = (edit.newFolder(10, sub, "2025") as DResult.Ok).value
        val a = (runBlocking { edit.add(10, inner, uri, "Quittung.pdf") } as DResult.Ok).value
        edit.delete(10, inner)
        edit.delete(10, sub)
        // "2025" was in "Belege", which is in the trash itself: back at the top
        assertEquals(10L, DriveVaultLocalLogic.restoreParent(store.get(10)!!, store.get(10)!!.trash.first { it.id == inner }))
        assertEquals(10L, DriveVaultLocalLogic.restoreParent(store.get(10)!!, store.get(10)!!.trash.first { it.id == sub }))
        assertEquals("2025", (edit.restore(10, inner) as DResult.Ok).value)
        val f = store.get(10)!!
        assertEquals(10L, f.items.first { it.id == inner }.parentId)
        assertEquals(inner, f.items.first { it.id == a }.parentId)
        assertEquals(listOf(sub), f.trash.map { it.id })
    }

    @Test
    fun `delete for good and empty the trash remove the files, nothing else`(@TempDir dir: File) {
        val (store, edit) = setup(dir)
        val a = (runBlocking { edit.add(10, 10, uri, "A.pdf") } as DResult.Ok).value
        val b = (runBlocking { edit.add(10, 10, uri, "B.pdf") } as DResult.Ok).value
        val c = (runBlocking { edit.add(10, 10, uri, "C.pdf") } as DResult.Ok).value
        edit.delete(10, a)
        edit.delete(10, b)
        assertTrue(edit.purge(10, a) is DResult.Ok)
        assertEquals(listOf(b), store.get(10)!!.trash.map { it.id })
        assertEquals(2, gcdv(dir))
        assertEquals("not_found", (edit.purge(10, a) as DResult.Failed).code)
        assertEquals(1, (edit.emptyTrash(10) as DResult.Ok).value)
        assertTrue(store.get(10)!!.trash.isEmpty())
        assertEquals(1, gcdv(dir))
        assertEquals(listOf(c), store.get(10)!!.items.map { it.id })
    }

    @Test
    fun `after 30 days the trash empties itself, without a key`(@TempDir dir: File) {
        val (store, edit) = setup(dir)
        val a = (runBlocking { edit.add(10, 10, uri, "A.pdf") } as DResult.Ok).value
        val b = (runBlocking { edit.add(10, 10, uri, "B.pdf") } as DResult.Ok).value
        now = 1_000_000
        edit.delete(10, a)
        now = 1_000_000 + 10 * day
        edit.delete(10, b)
        val entry = store.get(10)!!.trash.first { it.id == a }
        assertEquals(1_000_000 + 30 * day, DriveVaultLocalLogic.purgeAt(entry))
        store.lockKeys()
        assertEquals(0, store.purgeExpiredAll(1_000_000 + 30 * day - 1))
        assertEquals(1, store.purgeExpiredAll(1_000_000 + 30 * day))
        assertEquals(listOf(b), store.get(10)!!.trash.map { it.id })
        assertEquals(1, gcdv(dir))
        assertEquals(1, store.purgeExpiredAll(1_000_000 + 40 * day))
        assertEquals(0, gcdv(dir))
    }

    @Test
    fun `a restore needs the open vault and a writable folder, deleting for good works on a locked one`(@TempDir dir: File) {
        val (store, edit) = setup(dir)
        val a = (runBlocking { edit.add(10, 10, uri, "A.pdf") } as DResult.Ok).value
        edit.delete(10, a)
        store.lockKeys()
        assertEquals("locked", (edit.restore(10, a) as DResult.Failed).code)
        store.update(10) { it.copy(revoked = true) }
        assertEquals("local_locked", (edit.restore(10, a) as DResult.Failed).code)
        assertTrue(edit.purge(10, a) is DResult.Ok)
    }

    @Test
    fun `new ids never take one of the trash, tidy keeps the trash's files`(@TempDir dir: File) {
        val (store, edit) = setup(dir)
        val a = (runBlocking { edit.add(10, 10, uri, "A.pdf") } as DResult.Ok).value
        edit.delete(10, a)
        val f = store.get(10)!!
        assertTrue(a in f.ids)
        store.tidy(f)
        assertTrue(store.fileOf(f, a)!!.isFile)
        // the entry is found for its key and its file (the trash's rows show its name)
        assertEquals(10L, store.folderOfItem(a)?.nodeId)
    }

    @Test
    fun `an export and the way back take only what is not in the trash`(@TempDir dir: File) {
        val (store, edit) = setup(dir)
        val keep = (runBlocking { edit.add(10, 10, uri, "A.pdf") } as DResult.Ok).value
        val gone = (runBlocking { edit.add(10, 10, uri, "B.pdf") } as DResult.Ok).value
        edit.delete(10, gone)
        val f = store.get(10)!!
        assertEquals(listOf(keep), DriveVaultBackupFormat.order(f).map { it.id })
        assertEquals(listOf(keep), DriveVaultLocalLogic.reachable(f).map { it.id })
        assertEquals(listOf(keep), f.files.map { it.id })
        assertNull(f.items.firstOrNull { it.id == gone })
    }

    @Test
    fun `the trash survives the index, an old index without it reads as empty`() {
        val item = DriveVaultLocalItem(-5, 10, false, "n", size = 7, mtime = 3)
        val inner = DriveVaultLocalItem(-6, -7, false, "m", size = 8)
        val dirItem = DriveVaultLocalItem(-7, 10, true, "d")
        val f = DriveVaultLocalFolder(
            "srv", vaultId, 10, 1, "mine:1", null, "dev", DriveVaultLocalFolder.State.LOCAL, since = 1000,
            trash = listOf(DriveVaultLocalTrashEntry(-5, 10, 2000, listOf(item)), DriveVaultLocalTrashEntry(-7, 10, 3000, listOf(dirItem, inner))),
        )
        val back = DriveVaultLocalLogic.fromJson(DriveVaultLocalLogic.toJson(f))!!
        assertEquals(f.trash, back.trash)
        assertEquals(listOf(-7L, -5L), DriveVaultLocalLogic.trashSorted(back).map { it.id })
        val old = DriveVaultLocalLogic.toJson(f.copy(trash = emptyList()))
        assertFalse(old.contains("\"trash\""))
        assertTrue(DriveVaultLocalLogic.fromJson(old)!!.trash.isEmpty())
    }
}
