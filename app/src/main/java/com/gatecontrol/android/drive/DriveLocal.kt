package com.gatecontrol.android.drive

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The copies of a Drive file on this phone: the file it was uploaded from (picked in the Drive tab: remembered with a
 * lasting permission, any type), the original in the gallery (a photo the backup sent, found by size and SHA-256) and
 * an opened download in the cache. "Delete only on this device" removes them; "everywhere" also asks the
 * user's other devices to remove theirs ([DWipe]), which this class processes on this side.
 */
@Singleton
class DriveLocal @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
) {
    /**
     * What this phone has of a file: picked files it was uploaded from ([docs], deleted directly), gallery entries
     * ([gallery], Android asks first) and cache copies.
     */
    data class Copies(val gallery: List<Uri> = emptyList(), val cache: List<File> = emptyList(), val docs: List<Uri> = emptyList()) {
        val any: Boolean get() = gallery.isNotEmpty() || docs.isNotEmpty()
    }

    private val uploads = context.getSharedPreferences("drive_local", Context.MODE_PRIVATE)

    /** The phone's copies of [node] (nothing for folders). */
    suspend fun copies(node: DNode): Copies = withContext(Dispatchers.IO) {
        if (node.isDir) return@withContext Copies()
        val docs = uploaded(node.id, node.sha256)
        // A picked file that is a photo is in the gallery as well: one copy, deleted through the picked one.
        val gallery = if (docs.isEmpty()) gallery(node.sha256, node.size, node.mime, node.name) else emptyList()
        Copies(gallery, cache(node.id), docs)
    }

    /** Keeps the right to read and delete a picked file later (called when it is picked, while the grant holds). */
    fun hold(uri: Uri) {
        val r = context.contentResolver
        runCatching { r.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .onFailure { runCatching { r.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    }

    /** The picked [uri] went up as [nodeId]: "only on this device" and "everywhere" can delete it later. */
    fun remember(nodeId: Long, sha256: String?, uri: Uri) {
        uploads.edit().putString("n$nodeId", "${System.currentTimeMillis()}|${sha256.orEmpty()}|$uri").apply()
        trim()
    }

    /** Deletes picked files directly (the lasting permission allows it); the ones that could not go come back. */
    suspend fun deleteDocs(uris: List<Uri>): List<Uri> = withContext(Dispatchers.IO) {
        uris.filterNot { uri ->
            val gone = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
            if (gone) forget(uri)
            gone
        }
    }

    /** A picked file that would not go directly as its media store entry, for [trashRequest]; null if it has none. */
    fun mediaUri(uri: Uri): Uri? = runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()

    private fun uploaded(nodeId: Long, sha256: String?): List<Uri> {
        val hits = uploads.all.entries.mapNotNull { (key, value) ->
            val parts = (value as? String)?.split('|', limit = 3) ?: return@mapNotNull null
            if (parts.size < 3) return@mapNotNull null
            val match = key == "n$nodeId" || (sha256 != null && parts[1] == sha256)
            if (match) Uri.parse(parts[2]) else null
        }.distinct()
        return hits.filter { uri ->
            val there = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } == true
            }.getOrDefault(false)
            if (!there) forget(uri)
            there
        }
    }

    private fun forget(uri: Uri) {
        val s = uri.toString()
        val keys = uploads.all.filter { (_, v) -> (v as? String)?.endsWith("|$s") == true }.keys
        if (keys.isEmpty()) return
        uploads.edit().apply { keys.forEach { remove(it) } }.apply()
        release(uri)
    }

    /** Only the newest [MAX_REMEMBERED]: Android limits lasting permissions per app. */
    private fun trim() {
        val all = uploads.all.mapNotNull { (k, v) -> (v as? String)?.split('|', limit = 3)?.takeIf { it.size == 3 }?.let { Triple(k, it[0].toLongOrNull() ?: 0L, it[2]) } }
        if (all.size <= MAX_REMEMBERED) return
        val old = all.sortedBy { it.second }.take(all.size - MAX_REMEMBERED)
        uploads.edit().apply { old.forEach { remove(it.first) } }.apply()
        old.forEach { o -> if (all.none { it.third == o.third && it !in old }) release(Uri.parse(o.third)) }
    }

    private fun release(uri: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .onFailure { runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    }

    /** Removes the cache copies at once; the gallery needs the user's yes ([trashRequest]). */
    fun dropCache(copies: Copies) {
        copies.cache.forEach { it.parentFile?.deleteRecursively() }
    }

    /** The system dialog that moves [uris] to the phone's trash (Android asks; restorable for 30 days). */
    fun trashRequest(uris: List<Uri>): IntentSender? =
        if (uris.isEmpty()) null else runCatching { MediaStore.createTrashRequest(context.contentResolver, uris, true).intentSender }.getOrNull()

    /** Pending "deleted everywhere" files with what this phone has of them. */
    data class PendingWipes(val ids: List<Long>, val gallery: List<Uri>, val names: List<String>)

    /**
     * Fetches the files deleted everywhere on other devices: those this phone has no gallery copy of are reported done
     * at once (cache copies go silently); the rest come back for the user to confirm. Null when nothing is pending.
     */
    suspend fun pendingWipes(): PendingWipes? {
        val wipes = api.wipes().valueOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val quiet = ArrayList<Long>()
        val ask = ArrayList<Long>()
        val uris = ArrayList<Uri>()
        val names = ArrayList<String>()
        withContext(Dispatchers.IO) {
            for (w in wipes) {
                w.nodeId?.let { cache(it) }?.forEach { it.parentFile?.deleteRecursively() }
                // Picked files it was uploaded from go at once; what would not go is asked like a gallery copy.
                val docs = uploaded(w.nodeId ?: -1L, w.sha256)
                val left = deleteDocs(docs).mapNotNull { mediaUri(it) }
                val found = left + if (docs.isEmpty() && w.size >= 0) gallery(w.sha256, w.size, w.mime, w.name) else emptyList()
                if (found.isEmpty()) {
                    quiet += w.id
                } else {
                    ask += w.id
                    uris += found
                    names += w.name
                }
            }
        }
        if (quiet.isNotEmpty()) api.wipesDone(quiet)
        return if (ask.isEmpty()) null else PendingWipes(ask, uris, names)
    }

    /** The user moved the gallery copies to the trash: the wipes are done here. */
    suspend fun wipesDone(ids: List<Long>) {
        if (ids.isNotEmpty()) api.wipesDone(ids)
    }

    private fun cache(nodeId: Long): List<File> =
        File(context.cacheDir, "drive-open").listFiles { f -> f.isDirectory && f.name.startsWith("$nodeId-") }
            ?.flatMap { d -> d.listFiles()?.filter { it.isFile }.orEmpty() }.orEmpty()

    private fun canReadMedia(): Boolean = listOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.READ_EXTERNAL_STORAGE,
    ).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Gallery entries with the same content: same size, then the same SHA-256 (without a hash from the server: the same
     * name too). Only photos and videos, at most a few candidates are hashed.
     */
    private fun gallery(sha256: String?, size: Long, mime: String?, name: String): List<Uri> {
        if (size <= 0 || !canReadMedia()) return emptyList()
        val kind = DriveLogic.kind(mime, name)
        if (kind != DriveLogic.Kind.IMAGE && kind != DriveLogic.Kind.VIDEO) return emptyList()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        val selection = "${MediaStore.MediaColumns.SIZE} = ? AND $type IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val out = ArrayList<Uri>()
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                selection,
                arrayOf(size.toString()),
                null,
            )?.use { c ->
                var checked = 0
                while (c.moveToNext() && checked < MAX_CANDIDATES) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                    val same = if (sha256 != null) {
                        checked++
                        hash(uri) == sha256
                    } else {
                        c.getString(1) == name
                    }
                    if (same) out += uri
                }
            }
        }.onFailure { Timber.d("Drive: gallery lookup failed (%s)", it.javaClass.simpleName) }
        return out
    }

    private fun hash(uri: Uri): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private companion object {
        const val MAX_CANDIDATES = 8
        const val MAX_REMEMBERED = 400
    }
}
