package com.gatecontrol.android.drive

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
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
 * The copies of a Drive file on this phone: the original in the gallery (a photo the backup sent, found by size and
 * SHA-256) and an opened download in the cache. "Delete only on this device" removes them; "everywhere" also asks the
 * user's other devices to remove theirs ([DWipe]), which this class processes on this side.
 */
@Singleton
class DriveLocal @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
) {
    /** What this phone has of a file: gallery entries (content URIs) and cache copies. */
    data class Copies(val gallery: List<Uri> = emptyList(), val cache: List<File> = emptyList())

    /** The phone's copies of [node] (nothing for folders). */
    suspend fun copies(node: DNode): Copies = withContext(Dispatchers.IO) {
        if (node.isDir) return@withContext Copies()
        Copies(gallery(node.sha256, node.size, node.mime, node.name), cache(node.id))
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
                val found = if (w.size >= 0) gallery(w.sha256, w.size, w.mime, w.name) else emptyList()
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
    }
}
