package com.gatecontrol.android.drive.vault

import android.net.Uri
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DVault
import com.gatecontrol.android.drive.DriveApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * "Sicherung exportieren / importieren" (`.gctresor`): the export holds only LK ciphertext and LK sealed to the recovery
 * key; only that key opens it, a changed file is found, and the import brings every file back with the vault key.
 */
class DriveVaultLocalBackupTest {

    private val vaultId = "Vt9bJm0n2QwErTyUiOpAsD"
    private val tk = ByteArray(32) { (it + 3).toByte() }
    private val recoveryBytes = ByteArray(32) { (it * 5).toByte() }
    private val recovery = DriveVaultCrypto.recoveryKeyPair(recoveryBytes)
    private val recoveryText = DriveVaultCrypto.formatRecoveryKey(recoveryBytes)
    private val device = DriveVaultCrypto.newKeyPair()
    private val vault = DVault(vaultId, 1, "mine:1", null, null, 2, DriveVaultCrypto.b64u(recovery.publicKey), false, emptyList(), 0)
    private val plainA = ByteArray(4000) { (it * 11).toByte() }
    private val plainB = ByteArray(17) { 4 }
    private val uri = mockk<Uri>()

    private class Mem : DriveVaultLocalBackup.Streams {
        var bytes = ByteArray(0)
        override fun output(uri: Uri): OutputStream = object : ByteArrayOutputStream() {
            override fun close() { super.close(); bytes = toByteArray() }
        }
        override fun input(uri: Uri): InputStream = ByteArrayInputStream(bytes)
        override fun delete(uri: Uri) { bytes = ByteArray(0) }
    }

    private class Env(val store: DriveVaultLocalStore, val api: DriveApi, val mem: Mem, val backup: DriveVaultLocalBackup, val lk: ByteArray)

    private fun env(dir: File): Env {
        val store = DriveVaultLocalStore(File(dir, "data"), File(dir, "keys"))
        store.put(DriveVaultLocalFolder("srv", vaultId, 10, 1, "mine:1", null, "dev", DriveVaultLocalFolder.State.LOCAL, since = 1000, changedAt = 2000))
        val lk = store.newKey(vaultId, 10, device.publicKey, vault.recoveryPub)
        val edit = DriveVaultLocalEdit(store, object : DriveVaultLocalEdit.Opener {
            override fun describe(uri: Uri) = if (uri.toString() == "b") "b.txt" to plainB.size.toLong() else "a.pdf" to plainA.size.toLong()
            override fun open(uri: Uri): InputStream = ByteArrayInputStream(if (uri.toString() == "b") plainB else plainA)
        })
        val a = mockk<Uri> { io.mockk.every { this@mockk.toString() } returns "a" }
        val b = mockk<Uri> { io.mockk.every { this@mockk.toString() } returns "b" }
        val sub = (edit.newFolder(10, 10, "Belege") as DResult.Ok).value
        runBlocking { edit.add(10, 10, a, "Personalausweis.pdf") }
        runBlocking { edit.add(10, sub, b, "Notiz.txt") }
        val api = mockk<DriveApi>(relaxed = true)
        val mem = Mem()
        val backup = DriveVaultLocalBackup(api, store, { v, k -> if (v == vaultId && k == 2) tk else null }, { 2 }, mem, { File(dir, "tmp") }, { 9000 })
        return Env(store, api, mem, backup, lk)
    }

    @Test
    fun `the export holds no plaintext, notes the time and opens only with the recovery key`(@TempDir dir: File) {
        val e = env(dir)
        assertTrue(runBlocking { e.backup.export(10, "Ausweise", uri) } is DResult.Ok)
        assertEquals(9000L, e.store.get(10)!!.lastExport)
        val bytes = e.mem.bytes
        assertEquals("GCTRESOR", String(bytes, 0, 8, Charsets.US_ASCII))
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("Personalausweis"))
        assertFalse(text.contains("Ausweise"))
        assertFalse(text.contains(String(plainA.copyOfRange(10, 60), Charsets.ISO_8859_1)))
        val opened = DriveVaultBackupFormat.open(ByteArrayInputStream(bytes))
        val lk = DriveVaultCrypto.openLocalKey(opened.header.sealed, recovery, DriveVaultCrypto.localKeyInfo(vaultId, 10))
        assertArrayEquals(e.lk, lk)
        assertTrue(opened.check(lk))
        assertEquals(listOf(true, false, false), opened.header.items.map { it.isDir })
        assertTrue(runCatching { DriveVaultCrypto.openLocalKey(opened.header.sealed, device, DriveVaultCrypto.localKeyInfo(vaultId, 10)) }.isFailure)
    }

    @Test
    fun `the import brings every file back with the vault key`(@TempDir dir: File) {
        val e = env(dir)
        runBlocking { e.backup.export(10, "Ausweise", uri) }
        val uploaded = mutableMapOf<String, ByteArray>()
        var n = 0
        var folders = 500L
        coEvery { e.api.createVaultFolder(any(), any(), 2) } answers { DResult.Ok(node(++folders, true, 0, null)) }
        coEvery { e.api.upload(any<File>(), any(), any()) } answers { val id = "m${++n}"; uploaded[id] = firstArg<File>().readBytes(); DResult.Ok(id) }
        coEvery { e.api.commitVault(any(), any(), any(), 2, any(), any()) } answers {
            val b = uploaded[firstArg<String>()]!!
            DResult.Ok(node(900L + n, false, b.size.toLong(), DriveVaultLocalCrypt.sha256(ByteArrayInputStream(b))) to true)
        }
        val r = runBlocking { e.backup.import(vault, 1, uri, recoveryText, taken = listOf("Ausweise")) }
        val ok = (r as? DResult.Ok)?.value
        assertNotNull(ok, r.toString())
        assertEquals(com.gatecontrol.android.drive.DriveLogic.freeName("Ausweise", listOf("Ausweise")), ok!!.name)
        assertEquals(2, ok.files)
        val plains = uploaded.values.map { c -> ByteArrayOutputStream().also { DriveVaultCrypto.decryptContent({ v -> if (v == 2) tk else null }, vaultId, ByteArrayInputStream(c), it) }.toByteArray() }
        assertTrue(plains.any { it.contentEquals(plainA) })
        assertTrue(plains.any { it.contentEquals(plainB) })
        // the top folder in folder 1, "Belege" inside it
        coVerify { e.api.createVaultFolder(1, any(), 2) }
        coVerify { e.api.createVaultFolder(501, any(), 2) }
    }

    private fun node(id: Long, dir: Boolean, size: Long, sha: String?) =
        DNode(id, "mine:1", 1, "x", dir, size, null, null, 1, null, null, null, sha256 = sha, vaultId = vaultId)

    @Test
    fun `a wrong recovery key, another vault, a changed file`(@TempDir dir: File) {
        val e = env(dir)
        runBlocking { e.backup.export(10, "Ausweise", uri) }
        val other = DriveVaultCrypto.formatRecoveryKey(ByteArray(32) { 1 })
        assertEquals("recovery_key", (runBlocking { e.backup.import(vault, 1, uri, other, emptyList()) } as DResult.Failed).code)
        assertEquals("recovery_key", (runBlocking { e.backup.import(vault, 1, uri, "nonsense", emptyList()) } as DResult.Failed).code)
        assertEquals("backup_other_vault", (runBlocking { e.backup.import(vault.copy(id = "Xt9bJm0n2QwErTyUiOpAsD"), 1, uri, recoveryText, emptyList()) } as DResult.Failed).code)
        // one byte of a file changed: found before anything goes up
        val good = e.mem.bytes
        val firstFile = 13 + java.nio.ByteBuffer.wrap(good, 9, 4).int + 32 + 100
        e.mem.bytes = good.copyOf().also { it[firstFile] = (it[firstFile] + 1).toByte() }
        coEvery { e.api.createVaultFolder(any(), any(), 2) } returns DResult.Ok(node(501, true, 0, null))
        assertEquals("backup_damaged", (runBlocking { e.backup.import(vault, 1, uri, recoveryText, emptyList()) } as DResult.Failed).code)
        coVerify(exactly = 0) { e.api.commitVault(any(), any(), any(), any(), any(), any()) }
        // the header changed: the check with LK finds it
        val headerAt = good.indexOf('"'.code.toByte())
        e.mem.bytes = good.copyOf().also { it[headerAt + 2] = (it[headerAt + 2] + 1).toByte() }
        val r = runBlocking { e.backup.import(vault, 1, uri, recoveryText, emptyList()) } as DResult.Failed
        assertTrue(r.code == "backup_damaged" || r.code == "backup_invalid" || r.code == "recovery_key", r.code)
        // not an export at all
        e.mem.bytes = "hello".toByteArray()
        assertEquals("backup_invalid", (runBlocking { e.backup.import(vault, 1, uri, recoveryText, emptyList()) } as DResult.Failed).code)
    }

    @Test
    fun `without a recovery key of the vault there is no export`(@TempDir dir: File) {
        val store = DriveVaultLocalStore(File(dir, "data"), File(dir, "keys"))
        store.put(DriveVaultLocalFolder("srv", vaultId, 10, 1, "mine:1", null, "dev", DriveVaultLocalFolder.State.LOCAL, since = 1000))
        store.newKey(vaultId, 10, device.publicKey, null)
        val backup = DriveVaultLocalBackup(mockk(relaxed = true), store, { _, _ -> null }, { null }, Mem(), { File(dir, "tmp") })
        assertEquals("no_recovery", (runBlocking { backup.export(10, "x", uri) } as DResult.Failed).code)
    }
}
