package com.gatecontrol.android.drive.vault

import android.net.Uri
import com.gatecontrol.android.drive.DResult
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Working in a folder kept only here: files encrypted with LK (checked), folders, names, deleting — all on this phone. */
class DriveVaultLocalEditTest {

    private val vaultId = "Vt9bJm0n2QwErTyUiOpAsD"
    private val device = DriveVaultCrypto.newKeyPair()
    private val bytes = ByteArray(5000) { (it % 251).toByte() }
    private val uri = mockk<Uri>()

    private fun setup(dir: File, now: Long = 5000): Pair<DriveVaultLocalStore, DriveVaultLocalEdit> {
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

    @Test
    fun `a new file is encrypted with LK, checked, and marks the folder changed`(@TempDir dir: File) {
        val (store, edit) = setup(dir)
        val id = (runBlocking { edit.add(10, 10, uri) } as DResult.Ok).value
        assertTrue(id < 0)
        val f = store.get(10)!!
        val item = f.items.single()
        assertEquals("Scan.pdf", name(store, item.name))
        assertEquals(5000L, item.size)
        assertEquals(5000L, f.changedAt)
        val file = store.fileOf(f, id)!!
        assertEquals(0, DriveVaultCrypto.contentVersion(file.readBytes()))
        val out = ByteArrayOutputStream()
        file.inputStream().use { DriveVaultCrypto.decryptContent({ if (it == 0) store.key(10) else null }, vaultId, it, out) }
        assertArrayEquals(bytes, out.toByteArray())
        // no plaintext of the file on the disk
        assertFalse(dir.walkTopDown().filter { it.isFile }.any { String(it.readBytes(), Charsets.ISO_8859_1).contains(String(bytes.copyOfRange(100, 140), Charsets.ISO_8859_1)) })
    }

    @Test
    fun `folders, names and deleting with everything below`(@TempDir dir: File) {
        val (store, edit) = setup(dir)
        val sub = (edit.newFolder(10, 10, "Belege") as DResult.Ok).value
        val file = (runBlocking { edit.add(10, sub, uri, "Quittung.pdf") } as DResult.Ok).value
        assertEquals(sub, store.get(10)!!.items.first { it.id == file }.parentId)
        assertTrue(edit.rename(10, sub, "Belege 2025") is DResult.Ok)
        assertEquals("Belege 2025", name(store, store.get(10)!!.items.first { it.id == sub }.name))
        assertTrue(edit.delete(10, sub) is DResult.Ok)
        assertTrue(store.get(10)!!.items.isEmpty())
        // into the folder's trash (30 days): the encrypted file stays until the trash is emptied
        assertEquals(listOf(sub), store.get(10)!!.trash.map { it.id })
        assertTrue(File(dir, "data").walkTopDown().any { it.name.endsWith(".gcdv") })
        assertTrue(edit.emptyTrash(10) is DResult.Ok)
        assertFalse(File(dir, "data").walkTopDown().any { it.name.endsWith(".gcdv") })
    }

    @Test
    fun `a locked or revoked folder takes nothing`(@TempDir dir: File) {
        val (store, edit) = setup(dir)
        store.update(10) { it.copy(revoked = true) }
        assertEquals("local_locked", (edit.newFolder(10, 10, "x") as DResult.Failed).code)
        store.update(10) { it.copy(revoked = false) }
        store.lockKeys()
        assertEquals("locked", (edit.newFolder(10, 10, "x") as DResult.Failed).code)
    }
}
