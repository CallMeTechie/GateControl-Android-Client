package com.gatecontrol.android.drive.vault

import com.gatecontrol.android.drive.DCrumb
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DVault
import com.gatecontrol.android.drive.DVaultMyDevice
import com.gatecontrol.android.drive.DVaultWrap
import com.gatecontrol.android.drive.DriveOfflineCopy
import com.gatecontrol.android.drive.DriveOfflineStoreLogic
import com.gatecontrol.android.drive.DriveOfflineWant
import com.gatecontrol.android.drive.vault.DriveVaultOfflineLogic.Mark
import com.gatecontrol.android.drive.vault.DriveVaultOfflineLogic.RowState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * "Tresor-Dateien offline": which vault files are offline (a file, a folder, the whole vault), what a run plans for
 * them (ciphertext only, new versions load again), what goes when the device is no longer approved, the page without
 * a connection, and what this phone keeps for unlocking without one (wrapped keys only).
 */
class DriveVaultOfflineTest {
    private val vaultId = "AAAAAAAAAAAAAAAAAAAAAA"
    private val otherVault = "BBBBBBBBBBBBBBBBBBBBBB"
    private val deviceId = "CCCCCCCCCCCCCCCCCCCCCC"
    private val cipher = "A".repeat(88)
    private val wrap = "B".repeat(108)
    private val mb = 1024L * 1024

    // the vault's folder 10 in the area's top folder 1; folders 20 (in 10) and 30 (in 20); files below
    private val root = 10L

    private fun node(id: Long, parent: Long, dir: Boolean = false, vault: String? = vaultId, rev: Int = 1, size: Long = mb, sha: String? = null) = DNode(
        id = id, areaId = "mine", parentId = parent, name = "%032x".format(id), isDir = dir, size = size, mime = "application/octet-stream",
        mediaId = "m$id", rev = rev, mtime = null, updatedAt = null, updatedBy = null, sha256 = sha,
        vaultId = vault, nameCipher = vault?.let { cipher }, keyVersion = vault?.let { 1 },
    )

    private fun copy(id: Long, parents: List<Long>, stored: Boolean = true, vault: String? = vaultId, roots: Set<Long> = setOf(id), size: Long = mb) = DriveOfflineCopy(
        id = id, name = "%032x".format(id), mime = null, size = size, rev = 1, sha256 = null, path = null, roots = roots,
        state = if (stored) DriveOfflineCopy.State.STORED else DriveOfflineCopy.State.PENDING,
        file = if (stored) "%032x".format(id) else null, storedRev = if (stored) 1 else null, storedAt = if (stored) 1L else null,
        vaultId = vault, nameCipher = vault?.let { cipher }, parents = parents,
    )

    private fun vault(keyVersion: Int = 1) = DVault(
        id = vaultId, nodeId = root, areaId = "mine", parentId = 1L, nameCipher = cipher, keyVersion = keyVersion, recoveryPub = null,
        needsRotation = false, myDevices = listOf(DVaultMyDevice(deviceId, "approved", "Pixel")), pending = 2,
    )

    private fun folder(id: Long, crumbs: List<Long>, items: List<DNode>) = DFolder(
        node = node(id, crumbs.lastOrNull() ?: 1L, dir = true),
        crumbs = crumbs.map { DCrumb(it, "%032x".format(it)) },
        role = "write", items = items, total = items.size, next = null,
    )

    // ─── which files are offline ──────────────────────────────────────

    @Test
    fun `a file is offline by its own mark, a folder above it or the whole vault`() {
        val above = listOf(1L, root, 20L, 30L)
        assertEquals(Mark.NONE, DriveVaultOfflineLogic.markOf(40L, above, root, emptySet()))
        assertEquals(Mark.SELF, DriveVaultOfflineLogic.markOf(40L, above, root, setOf(40L)))
        assertEquals(Mark.FOLDER, DriveVaultOfflineLogic.markOf(40L, above, root, setOf(20L)))
        assertEquals(Mark.FOLDER, DriveVaultOfflineLogic.markOf(40L, above, root, setOf(30L)))
        assertEquals(Mark.VAULT, DriveVaultOfflineLogic.markOf(40L, above, root, setOf(root, 20L)))
        // the vault's own folder: "Ganzen Tresor offline halten"
        assertEquals(Mark.VAULT, DriveVaultOfflineLogic.markOf(root, listOf(1L), root, setOf(root)))
        assertEquals(Mark.NONE, DriveVaultOfflineLogic.markOf(root, listOf(1L), root, setOf(1L)))
    }

    @Test
    fun `a mark outside the vault never keeps its content`() {
        // the area's top folder 1 is marked: the vault inside is not offline by it
        assertEquals(Mark.NONE, DriveVaultOfflineLogic.markOf(40L, listOf(1L, root, 20L), root, setOf(1L)))
        // without the vault's folder among the folders above, only the node's own mark counts
        assertEquals(Mark.NONE, DriveVaultOfflineLogic.markOf(40L, listOf(20L), root, setOf(20L)))
        val plainMark = node(1, 0, dir = true, vault = null)
        assertFalse(DriveVaultOfflineLogic.include(plainMark, node(40, 20)))
        assertTrue(DriveVaultOfflineLogic.include(plainMark, node(41, 1, vault = null)))
        val vaultMark = node(root, 1, dir = true)
        assertTrue(DriveVaultOfflineLogic.include(vaultMark, node(40, 20)))
        assertFalse(DriveVaultOfflineLogic.include(vaultMark, node(42, 20, vault = otherVault)))
        assertFalse(DriveVaultOfflineLogic.include(vaultMark, node(43, 20, vault = null)))
    }

    @Test
    fun `the folders of a page run from the top to the folder itself`() {
        assertEquals(listOf(1L, root, 20L), DriveVaultOfflineLogic.ancestors(folder(20L, listOf(1L, root), emptyList())))
    }

    @Test
    fun `a row says offline, loading, waiting or failed`() {
        assertEquals(RowState.NONE, DriveVaultOfflineLogic.rowState(null))
        assertEquals(RowState.OFFLINE, DriveVaultOfflineLogic.rowState(copy(40, listOf(root))))
        assertEquals(RowState.LOADING, DriveVaultOfflineLogic.rowState(copy(40, listOf(root), stored = false)))
        assertEquals(RowState.WAITING, DriveVaultOfflineLogic.rowState(copy(40, listOf(root), stored = false).copy(reason = DriveOfflineStoreLogic.WAIT_WIFI)))
        assertEquals(RowState.FAILED, DriveVaultOfflineLogic.rowState(copy(40, listOf(root), stored = false).copy(state = DriveOfflineCopy.State.FAILED, reason = "not_found")))
        // an older version still opens while the new one loads
        assertEquals(RowState.OFFLINE, DriveVaultOfflineLogic.rowState(copy(40, listOf(root)).copy(state = DriveOfflineCopy.State.OUTDATED)))
    }

    @Test
    fun `the summary counts the files on this phone and their ciphertext`() {
        val copies = listOf(copy(40, listOf(root)), copy(41, listOf(root, 20L), size = 3 * mb), copy(42, listOf(root), stored = false))
        assertEquals(DriveVaultOfflineLogic.Summary(stored = 2, total = 3, bytes = 4 * mb), DriveVaultOfflineLogic.summary(copies))
        assertEquals(listOf(41L), DriveVaultOfflineLogic.below(copies, vaultId, root, 20L).map { it.id })
        assertEquals(3, DriveVaultOfflineLogic.below(copies, vaultId, root, root).size)
    }

    // ─── what a run plans ─────────────────────────────────────────────

    @Test
    fun `a vault file is planned as ciphertext with its folders, a new version replaces it`() {
        val n = node(40, 20, rev = 2, sha = "a".repeat(64))
        val have = mapOf(40L to copy(40, listOf(root, 20L), roots = setOf(root)).copy(storedSha256 = "b".repeat(64)))
        val p = DriveOfflineStoreLogic.plan(listOf(DriveOfflineWant(n, setOf(root), null, listOf(1L, root, 20L))), have, 100 * mb)
        val c = p.copies.getValue(40L)
        assertEquals(DriveOfflineCopy.State.OUTDATED, c.state)
        assertEquals(listOf(40L), p.download)
        assertEquals(vaultId, c.vaultId)
        assertEquals(cipher, c.nameCipher)
        assertEquals(listOf(1L, root, 20L), c.parents)
        // the opaque name only: nothing of the real name is on the disk
        assertEquals("%032x".format(40), c.name)
        // the file moved out of the marked folder (or was deleted): it is no longer wanted and goes
        val gone = DriveOfflineStoreLogic.plan(emptyList(), mapOf(40L to c.copy(file = "x")), 100 * mb)
        assertEquals(listOf(40L), gone.remove.map { it.id })
    }

    @Test
    fun `the index keeps the vault fields and drops a vault id that is not one`() {
        val c = copy(40, listOf(root, 20L))
        val json = DriveOfflineStoreLogic.toJson("https://gc.example", listOf(c))
        val back = DriveOfflineStoreLogic.fromJson(json)!!.second.single()
        assertEquals(c, back)
        val evil = json.replace(vaultId, "../../etc")
        assertTrue(DriveOfflineStoreLogic.fromJson(evil)!!.second.isEmpty())
    }

    // ─── no longer approved, logout ───────────────────────────────────

    @Test
    fun `revoked - the vault's copies go, the others stay`() {
        val have = listOf(copy(40, listOf(root)), copy(41, listOf(root)), copy(50, emptyList(), vault = otherVault), copy(60, emptyList(), vault = null))
            .associateBy { it.id }
        val (keep, gone) = DriveVaultOfflineLogic.withoutVault(have, vaultId)
        assertEquals(setOf(40L, 41L), gone.map { it.id }.toSet())
        assertEquals(setOf(50L, 60L), keep.keys)
        assertEquals(setOf(otherVault), DriveVaultOfflineLogic.keep(keep.values))
        assertTrue(DriveVaultOfflineLogic.revoked("revoked"))
        assertTrue(DriveVaultOfflineLogic.revoked("pending"))
        assertTrue(DriveVaultOfflineLogic.revoked(null))
        assertFalse(DriveVaultOfflineLogic.revoked("approved"))
    }

    @Test
    fun `the cache file goes with forget, keep and forgetAll`() {
        val dir = Files.createTempDirectory("vault-offline").toFile()
        try {
            val cache = DriveVaultOfflineCache(dir)
            cache.putVault("https://gc.example", deviceId, vault(), listOf(DVaultWrap(1, wrap)), now = 5L)
            cache.putVault("https://gc.example", deviceId, vault().copy(id = otherVault), listOf(DVaultWrap(1, wrap)), now = 5L)
            assertEquals(setOf(vaultId, otherVault), cache.vaultIds())
            cache.keep(setOf(otherVault))
            assertNull(cache.get(vaultId))
            assertEquals(setOf(otherVault), cache.vaultIds())
            cache.forget(otherVault)
            assertTrue(cache.vaultIds().isEmpty())
            cache.putVault("https://gc.example", deviceId, vault(), listOf(DVaultWrap(1, wrap)), now = 5L)
            cache.forgetAll()
            assertTrue(cache.vaultIds().isEmpty())
            assertNull(cache.get(vaultId))
            assertFalse(dir.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ─── without a connection ─────────────────────────────────────────

    @Test
    fun `without a connection the files here open, the others of the folder are greyed out`() {
        val copies = listOf(copy(40, listOf(1L, root)), copy(41, listOf(1L, root, 20L)), copy(42, listOf(1L, root), stored = false), copy(50, listOf(1L), vault = otherVault))
        val seen = listOf(node(20, root, dir = true), node(40, root), node(42, root), node(43, root))
        val page = DriveVaultOfflineLogic.offlinePage(vaultId, root, root, seen, copies)
        // the vault's page: every file on this phone (also those in folders), never another vault's
        assertEquals(setOf(40L, 41L), page.ready.map { it.id }.toSet())
        // the others: files only (folders are not opened without a connection), a file still to come too
        assertEquals(listOf(42L, 43L), page.netOnly.map { it.id })
        val sub = DriveVaultOfflineLogic.offlinePage(vaultId, root, 20L, null, copies)
        assertEquals(listOf(41L), sub.ready.map { it.id })
        assertTrue(sub.netOnly.isEmpty())
    }

    @Test
    fun `unlocking without a connection takes only this device's entry of this server`() {
        val e = DriveVaultOfflineEntry("https://gc.example", deviceId, vault(), listOf(DVaultWrap(1, wrap)))
        assertTrue(DriveVaultOfflineLogic.usable(e, "https://gc.example", deviceId))
        assertFalse(DriveVaultOfflineLogic.usable(e, "https://other.example", deviceId))
        assertFalse(DriveVaultOfflineLogic.usable(e, "https://gc.example", "DDDDDDDDDDDDDDDDDDDDDD"))
        assertFalse(DriveVaultOfflineLogic.usable(e, "https://gc.example", null))
        assertFalse(DriveVaultOfflineLogic.usable(e.copy(keys = emptyList()), "https://gc.example", deviceId))
        assertFalse(DriveVaultOfflineLogic.usable(null, "https://gc.example", deviceId))
    }

    @Test
    fun `the entry keeps wraps of every key version, the vault and its listings - nothing else`() {
        val listing = folder(20L, listOf(1L, root), listOf(node(40, 20), node(41, 20, vault = otherVault)))
        val e = DriveVaultOfflineLogic.withFolder(
            DriveVaultOfflineEntry("https://gc.example", deviceId, vault(keyVersion = 2), listOf(DVaultWrap(1, wrap), DVaultWrap(2, "C".repeat(108))), at = 7L),
            listing,
        )
        val json = DriveVaultOfflineLogic.toJson(e)
        val back = DriveVaultOfflineLogic.fromJson(json, vaultId)
        assertNotNull(back)
        back!!
        assertEquals(listOf(1, 2), back.keys.map { it.keyVersion })
        assertEquals(e.keys, back.keys)
        assertEquals(2, back.vault.keyVersion)
        assertEquals(vaultId, back.vault.id)
        assertEquals(cipher, back.vault.nameCipher)
        assertEquals(listOf(DVaultMyDevice(deviceId, "approved", "Pixel")), back.vault.myDevices)
        // the requests of other devices are not kept (they need the server)
        assertEquals(0, back.vault.pending)
        val f = back.folders.getValue(20L)
        assertEquals(listOf(1L, root), f.crumbs.map { it.id })
        // another vault's node in a listing is never kept
        assertEquals(listOf(40L), f.items.map { it.id })
        assertEquals(cipher, f.items.single().nameCipher)
        assertEquals(cipher, DriveVaultOfflineLogic.folderCipher(back, 20L))
        assertEquals(cipher, DriveVaultOfflineLogic.folderCipher(back, root))
        assertNull(DriveVaultOfflineLogic.folderCipher(back, 99L))
        // the file of another vault, or one that is not readable, is not taken
        assertNull(DriveVaultOfflineLogic.fromJson(json, otherVault))
        assertNull(DriveVaultOfflineLogic.fromJson("{", vaultId))
        assertNull(DriveVaultOfflineLogic.fromJson(json.replace("\"v\":1", "\"v\":2"), vaultId))
    }

    @Test
    fun `at most so many listings are kept, the newest`() {
        var e = DriveVaultOfflineEntry("s", deviceId, vault(), listOf(DVaultWrap(1, wrap)))
        for (i in 1..(DriveVaultOfflineLogic.MAX_FOLDERS + 5)) e = DriveVaultOfflineLogic.withFolder(e, folder(1000L + i, listOf(root), emptyList()))
        assertEquals(DriveVaultOfflineLogic.MAX_FOLDERS, e.folders.size)
        assertFalse(e.folders.containsKey(1001L))
        assertTrue(e.folders.containsKey(1000L + DriveVaultOfflineLogic.MAX_FOLDERS + 5))
    }
}
