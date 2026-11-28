package com.gatecontrol.android.drive.vault

import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DVault
import com.gatecontrol.android.drive.DVaultLocal
import com.gatecontrol.android.drive.DVaultLocalItem
import com.gatecontrol.android.drive.DVaultLocalStart
import com.gatecontrol.android.drive.DVaultLocalState
import com.gatecontrol.android.drive.DriveApi
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import retrofit2.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The jobs of "Nur auf diesem Gerät" against a faked Drive: a move fetches, checks and re-encrypts every file and only
 * then commits with the device's proof; a changed folder is fetched again; a broken file ends the move with nothing
 * deleted; the way back uploads every file with the vault key and ends with the proof.
 */
class DriveVaultLocalJobsTest {

    private val vaultId = "Vt9bJm0n2QwErTyUiOpAsD"
    private val tk = ByteArray(32) { (it + 1).toByte() }
    private val device = DriveVaultCrypto.newKeyPair()
    private val deviceId = DriveVaultCrypto.deviceId(device.publicKey)
    private val recovery = DriveVaultCrypto.recoveryKeyPair(ByteArray(32) { 9 })
    private val vault = DVault(vaultId, 1, "mine:1", null, null, 1, DriveVaultCrypto.b64u(recovery.publicKey), false, emptyList(), 0)
    private val folderNode = DNode(10, "mine:1", 1, "0".repeat(32), true, 0, null, null, 1, null, null, null, vaultId = vaultId, nameCipher = name("Ausweise"))

    private fun name(n: String) = DriveVaultCrypto.encryptName(tk, vaultId, 1, n)

    private fun cipher(plain: ByteArray): ByteArray =
        ByteArrayOutputStream().also { DriveVaultCrypto.encryptContent(tk, vaultId, 1, ByteArrayInputStream(plain), plain.size.toLong(), it, chunkSize = 1024) }.toByteArray()

    private fun sha(b: ByteArray) = DriveVaultLocalCrypt.sha256(ByteArrayInputStream(b))

    private val plainA = ByteArray(3000) { (it * 3).toByte() }
    private val plainB = ByteArray(10) { 7 }
    private val cipherA = cipher(plainA)
    private val cipherB = cipher(plainB)

    private val manifest = listOf(
        DVaultLocalItem(20, 10, true, name("Unterordner"), 1),
        DVaultLocalItem(21, 10, false, name("Personalausweis.pdf"), 1, rev = 1, sha256 = sha(cipherA), size = cipherA.size.toLong()),
        DVaultLocalItem(22, 20, false, name("Notiz.txt"), 1, rev = 3, sha256 = sha(cipherB), size = cipherB.size.toLong()),
    )

    private fun start(items: List<DVaultLocalItem>) = DResult.Ok(DVaultLocalStart(null, items, 2, 1, 100, 4, 50, 1, 1, 10))

    private fun body(b: ByteArray) = DResult.Ok(Response.success(b.toResponseBody()))

    private class Env(val api: DriveApi, val store: DriveVaultLocalStore, val jobs: DriveVaultLocalJobs, val dropped: MutableList<Long>, val kept: MutableList<Long>)

    private fun env(dir: File, now: Long = 1_000_000L): Env {
        val api = mockk<DriveApi>(relaxed = true)
        val store = DriveVaultLocalStore(File(dir, "data"), File(dir, "keys"))
        val dropped = mutableListOf<Long>()
        val kept = mutableListOf<Long>()
        val jobs = DriveVaultLocalJobs(
            api = api,
            store = store,
            session = object : DriveVaultLocalJobs.Session {
                override fun deviceId() = this@DriveVaultLocalJobsTest.deviceId
                override fun vaultKey(vaultId: String, version: Int) = if (version == 1) tk else null
                override fun keyVersion(vaultId: String) = 1
                override suspend fun <T> busy(block: suspend () -> T): T = block()
            },
            offline = object : DriveVaultLocalJobs.Offline {
                override fun cipher(node: DNode): File? = null
                override suspend fun drop(ids: List<Long>) { dropped += ids }
                override suspend fun keep(node: DNode): Boolean { kept += node.id; return true }
            },
            work = {},
            now = { now },
            tmpDir = { File(dir, "tmp") },
            serverUrl = { "srv" },
        )
        return Env(api, store, jobs, dropped, kept)
    }

    private fun challenge(secret: ByteArray) = DResult.Ok(DriveVaultCrypto.sealProof(secret, device.publicKey, vaultId, deviceId))

    private fun localState(state: String = DVaultLocal.STATE_LOCAL) =
        DVaultLocal(10, vaultId, deviceId, "Pixel 8", "android", "approved", state, "2026-11-27T10:00:00Z", true, true, purgeAt = "2026-11-28T10:00:00Z", undo = true)

    private fun plainOf(e: Env, id: Long): ByteArray {
        val f = e.store.get(10)!!
        val lk = e.store.key(10)!!
        val out = ByteArrayOutputStream()
        e.store.fileOf(f, id)!!.inputStream().use { DriveVaultCrypto.decryptContent({ if (it == 0) lk else null }, vaultId, it, out) }
        return out.toByteArray()
    }

    private fun moved(dir: File): Env {
        val e = env(dir)
        val secret = ByteArray(32) { 5 }
        coEvery { e.api.vaultLocalStart(vaultId, 10, deviceId) } returns start(manifest)
        coEvery { e.api.download(21, any(), any(), any()) } returns body(cipherA)
        coEvery { e.api.download(22, any(), any(), any()) } returns body(cipherB)
        coEvery { e.api.vaultLocalChallenge(vaultId, deviceId) } returns challenge(secret)
        coEvery { e.api.vaultLocalCommit(vaultId, 10, deviceId, DriveVaultCrypto.b64u(secret), any()) } returns DResult.Ok(DVaultLocalState(localState()))
        e.jobs.move(vault, folderNode, "Ausweise", DriveVaultCrypto.keyFromSeed(device.privateKey))
        runBlocking { e.jobs.drain() }
        return e
    }

    @Test
    fun `a move fetches, re-encrypts with LK and commits with the device's proof`(@TempDir dir: File) {
        val e = moved(dir)
        val f = e.store.get(10)
        assertNotNull(f)
        assertEquals(DriveVaultLocalFolder.State.LOCAL, f!!.state)
        assertEquals("srv", f.server)
        assertEquals(setOf(20L, 21L, 22L), f.items.map { it.id }.toSet())
        assertArrayEquals(plainA, plainOf(e, 21))
        assertArrayEquals(plainB, plainOf(e, 22))
        // names with LK (version 0), sizes of the plaintext
        val lk = e.store.key(10)!!
        assertEquals("Notiz.txt", DriveVaultCrypto.decryptName({ if (it == 0) lk else null }, vaultId, f.items.first { it.id == 22L }.name))
        assertEquals(10L, f.items.first { it.id == 22L }.size)
        assertEquals(20L, f.items.first { it.id == 22L }.parentId)
        // the commit named every node with what was fetched
        val sent = slot<List<DVaultLocalItem>>()
        coVerify { e.api.vaultLocalCommit(vaultId, 10, deviceId, any(), capture(sent)) }
        assertEquals(setOf(20L, 21L, 22L), sent.captured.map { it.id }.toSet())
        assertEquals(3, sent.captured.first { it.id == 22L }.rev)
        assertEquals(manifest[1].sha256, sent.captured.first { it.id == 21L }.sha256)
        // the "zusätzlich offline" copies of the files go
        assertEquals(listOf(21L, 22L), e.dropped.sorted())
        assertTrue(e.jobs.progress.value[10]!!.finished)
        // LK is sealed to the recovery key too (the export)
        assertArrayEquals(lk, DriveVaultCrypto.openLocalKey(e.store.recoverySealed(f)!!, recovery, DriveVaultCrypto.localKeyInfo(vaultId, 10)))
    }

    @Test
    fun `a folder changed meanwhile is fetched again and committed once more`(@TempDir dir: File) {
        val e = env(dir)
        val newA = ByteArray(500) { 1 }
        val newCipherA = cipher(newA)
        coEvery { e.api.vaultLocalStart(vaultId, 10, deviceId) } returns start(manifest)
        coEvery { e.api.download(21, any(), any(), any()) } returnsMany listOf(body(cipherA), body(newCipherA))
        coEvery { e.api.download(22, any(), any(), any()) } returns body(cipherB)
        coEvery { e.api.vaultLocalChallenge(vaultId, deviceId) } returns challenge(ByteArray(32) { 1 })
        val conflict = JsonObject().apply {
            addProperty("reason", "changed")
            add("changed", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("id", 21); addProperty("parentId", 10); addProperty("isDir", false); addProperty("nameCipher", name("Personalausweis.pdf"))
                    addProperty("keyVersion", 1); addProperty("rev", 2); addProperty("sha256", sha(newCipherA)); addProperty("size", newCipherA.size)
                })
            })
            add("added", JsonArray())
            add("removed", JsonArray())
        }
        coEvery { e.api.vaultLocalCommit(vaultId, 10, deviceId, any(), any()) } returnsMany listOf(DResult.Failed("conflict", 409, conflict), DResult.Ok(DVaultLocalState(localState())))
        e.jobs.move(vault, folderNode, "Ausweise", DriveVaultCrypto.keyFromSeed(device.privateKey))
        runBlocking { e.jobs.drain() }
        assertEquals(DriveVaultLocalFolder.State.LOCAL, e.store.get(10)!!.state)
        assertArrayEquals(newA, plainOf(e, 21))
        assertEquals(2, e.store.get(10)!!.items.first { it.id == 21L }.srcRev)
        coVerify(exactly = 2) { e.api.vaultLocalCommit(vaultId, 10, deviceId, any(), any()) }
    }

    @Test
    fun `a file that is not what the server says ends the move, nothing is deleted`(@TempDir dir: File) {
        val e = env(dir)
        coEvery { e.api.vaultLocalStart(vaultId, 10, deviceId) } returns start(manifest)
        coEvery { e.api.download(21, any(), any(), any()) } returns body(cipherA.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        coEvery { e.api.download(22, any(), any(), any()) } returns body(cipherB)
        e.jobs.move(vault, folderNode, "Ausweise", DriveVaultCrypto.keyFromSeed(device.privateKey))
        runBlocking { e.jobs.drain() }
        val p = e.jobs.progress.value[10]!!
        assertEquals("verify", p.error)
        assertEquals("Personalausweis.pdf", p.file)
        assertNull(e.store.get(10))
        coVerify(exactly = 0) { e.api.vaultLocalCommit(any(), any(), any(), any(), any()) }
        coVerify { e.api.vaultLocalCancel(vaultId, 10, deviceId) }
    }

    @Test
    fun `without a connection the move stops and goes on with what it has`(@TempDir dir: File) {
        val e = env(dir)
        coEvery { e.api.vaultLocalStart(vaultId, 10, deviceId) } returns start(manifest)
        coEvery { e.api.download(21, any(), any(), any()) } returns body(cipherA)
        coEvery { e.api.download(22, any(), any(), any()) } returns DResult.Offline
        e.jobs.move(vault, folderNode, "Ausweise", DriveVaultCrypto.keyFromSeed(device.privateKey))
        runBlocking { e.jobs.drain() }
        assertEquals("offline", e.jobs.progress.value[10]!!.error)
        val kept = e.store.get(10)!!
        assertEquals(DriveVaultLocalFolder.State.MOVING, kept.state)
        assertTrue(kept.items.any { it.id == 21L })
        coVerify(exactly = 0) { e.api.vaultLocalCancel(any(), any(), any()) }
        // again: 21 is not fetched a second time
        coEvery { e.api.download(22, any(), any(), any()) } returns body(cipherB)
        coEvery { e.api.vaultLocalChallenge(vaultId, deviceId) } returns challenge(ByteArray(32) { 2 })
        coEvery { e.api.vaultLocalCommit(vaultId, 10, deviceId, any(), any()) } returns DResult.Ok(DVaultLocalState(localState()))
        e.jobs.dismiss(10)
        e.jobs.move(vault, folderNode, "Ausweise", DriveVaultCrypto.keyFromSeed(device.privateKey))
        runBlocking { e.jobs.drain() }
        assertEquals(DriveVaultLocalFolder.State.LOCAL, e.store.get(10)!!.state)
        coVerify(exactly = 1) { e.api.download(21, any(), any(), any()) }
    }

    @Test
    fun `back to the server uploads every file with the vault key and ends with the proof`(@TempDir dir: File) {
        val e = moved(dir)
        val uploaded = mutableMapOf<String, ByteArray>()
        var n = 0
        coEvery { e.api.vaultLocalReturn(vaultId, 10, deviceId) } returns DResult.Ok(DVaultLocalState(localState(DVaultLocal.STATE_RETURNING)))
        coEvery { e.api.createVaultFolder(10, any(), 1) } returns DResult.Ok(folderNode.copy(id = 200, parentId = 10))
        coEvery { e.api.upload(any<File>(), any(), any()) } answers {
            val id = "m${++n}"
            uploaded[id] = firstArg<File>().readBytes()
            DResult.Ok(id)
        }
        coEvery { e.api.commitVault(any(), any(), any(), 1, any(), any()) } answers {
            val bytes = uploaded[firstArg<String>()]!!
            DResult.Ok(folderNode.copy(id = 300L + n, isDir = false, size = bytes.size.toLong(), sha256 = sha(bytes)) to true)
        }
        val secret = ByteArray(32) { 3 }
        coEvery { e.api.vaultLocalChallenge(vaultId, deviceId) } returns challenge(secret)
        coEvery { e.api.vaultLocalReturnCommit(vaultId, 10, deviceId, DriveVaultCrypto.b64u(secret), 2) } returns DResult.Ok(DVaultLocalState(null))
        e.jobs.dismiss(10)
        e.jobs.back(vault, folderNode, "Ausweise", DriveVaultCrypto.keyFromSeed(device.privateKey), keepOffline = true)
        runBlocking { e.jobs.drain() }
        assertTrue(e.jobs.progress.value[10]!!.finished, e.jobs.progress.value[10]!!.error)
        assertNull(e.store.get(10))
        // what went up is the vault's format with key version 1 and the same plaintext
        val plains = uploaded.values.map { c ->
            assertEquals(1, DriveVaultCrypto.contentVersion(c))
            ByteArrayOutputStream().also { DriveVaultCrypto.decryptContent({ if (it == 1) tk else null }, vaultId, ByteArrayInputStream(c), it) }.toByteArray()
        }
        assertTrue(plains.any { it.contentEquals(plainA) })
        assertTrue(plains.any { it.contentEquals(plainB) })
        // the file of the subfolder went into the new folder on the server
        coVerify { e.api.commitVault(any(), 200, any(), 1, any(), any()) }
        assertEquals(listOf(10L), e.kept)
    }

    @Test
    fun `undo only while nothing changed here`(@TempDir dir: File) {
        val e = moved(dir)
        coEvery { e.api.vaultLocalUndo(vaultId, 10, deviceId) } returns DResult.Ok(DVaultLocalState(null))
        e.store.update(10) { it.copy(changedAt = it.since + 1) }
        assertEquals("changed_here", (runBlocking { e.jobs.undo(10) } as DResult.Failed).code)
        e.store.update(10) { it.copy(changedAt = it.since) }
        assertTrue(runBlocking { e.jobs.undo(10) } is DResult.Ok)
        assertNull(e.store.get(10))
    }

    @Test
    fun `deleting here needs the proof first`(@TempDir dir: File) {
        val e = moved(dir)
        val secret = ByteArray(32) { 8 }
        coEvery { e.api.vaultLocalChallenge(vaultId, deviceId) } returns challenge(secret)
        coEvery { e.api.vaultLocalForget(vaultId, 10, deviceId, DriveVaultCrypto.b64u(secret)) } returns DResult.Ok(Unit)
        assertTrue(runBlocking { e.jobs.deleteHere(10, DriveVaultCrypto.keyFromSeed(device.privateKey)) } is DResult.Ok)
        assertNull(e.store.get(10))
    }
}
