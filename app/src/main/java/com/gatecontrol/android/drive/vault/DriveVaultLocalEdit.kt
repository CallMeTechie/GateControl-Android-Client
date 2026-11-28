package com.gatecontrol.android.drive.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveLogic
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Working in a folder kept only on this phone (concept 1.9): new files are encrypted here with the folder's key LK —
 * nothing goes to the server —, new folders, a new name, deleting into the folder's own trash (30 days; restore,
 * delete for good, empty it). Every change is written to the index at once and marks the folder changed (the reminder
 * of a new export, no "Rückgängig" any more).
 */
@Singleton
class DriveVaultLocalEdit internal constructor(
    private val store: DriveVaultLocalStore,
    private val opener: Opener,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(@ApplicationContext context: Context, store: DriveVaultLocalStore) : this(store, AndroidOpener(context))

    /** A picked file: its name and size, and its bytes. */
    interface Opener {
        fun describe(uri: Uri): Pair<String, Long>?
        fun open(uri: Uri): InputStream?
    }

    private fun lkOf(folder: DriveVaultLocalFolder): ByteArray? = store.key(folder.nodeId)

    private fun writable(folderId: Long): DriveVaultLocalFolder? = store.get(folderId)?.takeIf { it.state == DriveVaultLocalFolder.State.LOCAL && !it.revoked }

    /** A new folder [name] in [parentId] of the local folder [folderId]. */
    fun newFolder(folderId: Long, parentId: Long, name: String): DResult<Long> {
        val f = writable(folderId) ?: return DResult.Failed("local_locked", 0)
        val lk = lkOf(f) ?: return DResult.Failed("locked", 0)
        val clean = name.trim().ifEmpty { return DResult.Failed("invalid", 0) }
        val id = DriveVaultLocalLogic.newId(f.ids)
        val item = DriveVaultLocalItem(id, parentId, true, DriveVaultCrypto.encryptName(lk, f.vaultId, DriveVaultCrypto.LOCAL_KEY_VERSION, clean), mtime = now())
        store.update(folderId) { it.copy(items = it.items + item, changedAt = now()) }
        return DResult.Ok(id)
    }

    /** A new name for the entry [itemId]. */
    fun rename(folderId: Long, itemId: Long, name: String): DResult<Unit> {
        val f = writable(folderId) ?: return DResult.Failed("local_locked", 0)
        val lk = lkOf(f) ?: return DResult.Failed("locked", 0)
        val clean = name.trim().ifEmpty { return DResult.Failed("invalid", 0) }
        val cipher = DriveVaultCrypto.encryptName(lk, f.vaultId, DriveVaultCrypto.LOCAL_KEY_VERSION, clean)
        store.update(folderId) { x -> x.copy(items = x.items.map { if (it.id == itemId) it.copy(name = cipher, mtime = now()) else it }, changedAt = now()) }
        return DResult.Ok(Unit)
    }

    /**
     * The entry [itemId] and everything below it go into the trash of the folder kept here (30 days, still encrypted
     * with LK where they are — there is no copy anywhere else).
     */
    fun delete(folderId: Long, itemId: Long): DResult<Unit> {
        writable(folderId) ?: return DResult.Failed("local_locked", 0)
        store.update(folderId) { x -> DriveVaultLocalLogic.toTrash(x, itemId, now()) } ?: return DResult.Failed("failed", 0)
        return DResult.Ok(Unit)
    }

    /**
     * The trash entry [entryId] back where it was (or at the top, when that folder is gone); a name taken there by now
     * gets a free one, "Name (2)" (as Drive's trash does). Returns the name it has now.
     */
    fun restore(folderId: Long, entryId: Long): DResult<String> {
        val f = writable(folderId) ?: return DResult.Failed("local_locked", 0)
        val lk = lkOf(f) ?: return DResult.Failed("locked", 0)
        val e = f.trash.firstOrNull { it.id == entryId } ?: return DResult.Failed("not_found", 0)
        val top = e.top ?: return DResult.Failed("not_found", 0)
        val keys: (Int) -> ByteArray? = { v -> if (v == DriveVaultCrypto.LOCAL_KEY_VERSION) lk else null }
        val name = runCatching { DriveVaultCrypto.decryptName(keys, f.vaultId, top.name) }.getOrNull() ?: return DResult.Failed(DriveVaultSession.CODE_VERIFY, 0)
        var restored = name
        val next = store.update(folderId) { x ->
            val parent = DriveVaultLocalLogic.restoreParent(x, e)
            val taken = DriveVaultLocalLogic.children(x, parent).mapNotNull { runCatching { DriveVaultCrypto.decryptName(keys, x.vaultId, it.name) }.getOrNull() }
            restored = DriveLogic.freeName(name, taken)
            val cipher = if (restored == name) null else DriveVaultCrypto.encryptName(lk, x.vaultId, DriveVaultCrypto.LOCAL_KEY_VERSION, restored)
            DriveVaultLocalLogic.restore(x, entryId, cipher, now()) ?: x
        } ?: return DResult.Failed("failed", 0)
        if (next.trash.any { it.id == entryId }) return DResult.Failed("not_found", 0)
        return DResult.Ok(restored)
    }

    /** The trash entry [entryId] gone for good: its files and its entries (a revoked folder may do this too). */
    fun purge(folderId: Long, entryId: Long): DResult<Unit> {
        val f = store.get(folderId)?.takeIf { it.state == DriveVaultLocalFolder.State.LOCAL } ?: return DResult.Failed("local_locked", 0)
        if (f.trash.none { it.id == entryId }) return DResult.Failed("not_found", 0)
        store.dropTrash(folderId, setOf(entryId)) ?: return DResult.Failed("failed", 0)
        return DResult.Ok(Unit)
    }

    /** "Papierkorb leeren": everything in the trash of the folder kept here gone for good. */
    fun emptyTrash(folderId: Long): DResult<Int> {
        val f = store.get(folderId)?.takeIf { it.state == DriveVaultLocalFolder.State.LOCAL } ?: return DResult.Failed("local_locked", 0)
        val all = f.trash.mapTo(HashSet()) { it.id }
        store.dropTrash(folderId, all) ?: return DResult.Failed("failed", 0)
        return DResult.Ok(all.size)
    }

    /**
     * The picked [uri] encrypted with LK into the folder [parentId] of the local folder [folderId] (as [name], else the
     * file's own), checked (decrypted again, the same plaintext) — only then an entry. [onProgress] 0..1.
     */
    suspend fun add(folderId: Long, parentId: Long, uri: Uri, name: String? = null, onProgress: (Float) -> Unit = {}): DResult<Long> = withContext(Dispatchers.IO) {
        val f = writable(folderId) ?: return@withContext DResult.Failed("local_locked", 0)
        val lk = lkOf(f) ?: return@withContext DResult.Failed("locked", 0)
        val (picked, size) = opener.describe(uri) ?: return@withContext DResult.Failed("read", 0)
        if (store.usableSpace() in 1 until size + 64L * 1_048_576) return@withContext DResult.Failed("no_space", 0)
        val clean = name?.let { DriveLogic.safeName(it) } ?: picked
        val tmp = store.tempFile(f)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                val stream = opener.open(uri) ?: return@withContext DResult.Failed("read", 0)
                DigestInputStream(stream, digest).use { inp ->
                    tmp.outputStream().buffered(256 * 1024).use { out ->
                        DriveVaultCrypto.encryptContent(lk, f.vaultId, DriveVaultCrypto.LOCAL_KEY_VERSION, inp, size, out, onProgress = { done ->
                            if (size > 0) onProgress(done.toFloat() / size)
                        })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return@withContext DResult.Failed("read", 0)
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val back = try {
                tmp.inputStream().buffered(256 * 1024).use { DriveVaultLocalCrypt.digest({ v -> if (v == DriveVaultCrypto.LOCAL_KEY_VERSION) lk else null }, f.vaultId, it) }
            } catch (e: DriveVaultCrypto.DriveVaultException) {
                return@withContext DResult.Failed(DriveVaultSession.CODE_VERIFY, 0)
            }
            if (back.sha256 != sha || back.size != size) return@withContext DResult.Failed(DriveVaultSession.CODE_VERIFY, 0)
            val cur = store.get(folderId) ?: return@withContext DResult.Failed("failed", 0)
            val id = DriveVaultLocalLogic.newId(cur.ids)
            store.place(cur, id, tmp)
            val item = DriveVaultLocalItem(id, parentId, false, DriveVaultCrypto.encryptName(lk, f.vaultId, DriveVaultCrypto.LOCAL_KEY_VERSION, clean), size = size, mtime = now())
            store.update(folderId) { it.copy(items = it.items + item, changedAt = now()) }
            DResult.Ok(id)
        } finally {
            tmp.delete()
        }
    }

    /** The phone's side: the content resolver. */
    class AndroidOpener(private val context: Context) : Opener {
        override fun describe(uri: Uri): Pair<String, Long>? = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(0)?.let { DriveLogic.safeName(it) } ?: "file"
                val size = if (c.isNull(1)) -1L else c.getLong(1)
                name to size
            }
        }.getOrNull()?.let { (name, size) ->
            if (size >= 0) name to size
            else runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it >= 0 }?.let { name to it }
        }

        override fun open(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)
    }
}
