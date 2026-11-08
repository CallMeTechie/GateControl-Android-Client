package com.gatecontrol.android.drive.device

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What "Auf dem Gerät" does with the phone's files: delete (into Android's trash where MediaStore has the file, else
 * after the person said yes), move and copy, rename, unpack a ZIP (zip-slip safe, with limits), new folders. Every
 * change is told to MediaStore (the gallery and other apps see it at once). Runs off the main thread.
 */
@Singleton
class DeviceFileOps @Inject constructor(@ApplicationContext private val context: Context) {

    /** What a delete does: [trash] goes to Android's trash (restorable for 30 days, Android asks), [direct] is gone at once. */
    data class DeletePlan(val trash: List<Uri>, val direct: List<String>)

    /**
     * Splits [entries]: files MediaStore knows as a picture, video, audio or download go to the trash; folders and
     * everything else is deleted directly (the page asks first).
     */
    suspend fun planDelete(entries: List<DeviceEntry>): DeletePlan = withContext(Dispatchers.IO) {
        val files = entries.filter { !it.isDir }.map { it.path }
        val media = trashable(files)
        DeletePlan(trash = media.values.toList(), direct = entries.map { it.path }.filter { it !in media.keys })
    }

    /** Path → the MediaStore item of its kind (Images, Video, Audio, Downloads) for the trash. */
    private fun trashable(paths: List<String>): Map<String, Uri> {
        val out = HashMap<String, Uri>()
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.IS_DOWNLOAD,
        )
        for (chunk in paths.chunked(100)) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DATA} IN (${chunk.joinToString(",") { "?" }})")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, chunk.toTypedArray())
            }
            runCatching {
                context.contentResolver.query(files, cols, args, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val path = c.getString(1) ?: continue
                        val volume = c.getString(2) ?: continue
                        val base = when (c.getInt(3)) {
                            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(volume)
                            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(volume)
                            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.getContentUri(volume)
                            else -> if (c.getInt(4) == 1) MediaStore.Downloads.getContentUri(volume) else null
                        } ?: continue
                        out[path] = ContentUris.withAppendedId(base, id)
                    }
                }
            }.onFailure { Timber.d("Device: trash lookup failed (%s)", it.javaClass.simpleName) }
        }
        return out
    }

    /** Android's dialog that moves [uris] to its trash; null when it cannot be asked. */
    fun trashRequest(uris: List<Uri>): IntentSender? =
        if (uris.isEmpty()) null else runCatching { MediaStore.createTrashRequest(context.contentResolver, uris, true).intentSender }.getOrNull()

    /** Deletes [paths] (folders with everything in them); how many went. */
    suspend fun deleteDirect(paths: List<String>): Int = withContext(Dispatchers.IO) {
        var done = 0
        for (p in paths) {
            val f = File(p)
            val gone = if (f.isDirectory) f.deleteRecursively() else f.delete() || !f.exists()
            if (gone) done++
        }
        scan(paths)
        done
    }

    /** Outcome of a move or copy: how many went, how many not (a name that is taken is given a free one). */
    data class Done(val ok: Int, val failed: Int)

    /** Moves ([copy]: copies) [entries] into folder [target]. A folder never goes into itself. */
    suspend fun transfer(entries: List<DeviceEntry>, target: String, copy: Boolean): Done = withContext(Dispatchers.IO) {
        var ok = 0
        var failed = 0
        val touched = ArrayList<String>()
        val dir = File(target)
        for (e in entries) {
            currentCoroutineContext().ensureActive()
            val src = File(e.path)
            // moved to where it is already: nothing to do
            if (!copy && src.parentFile?.path == dir.path) continue
            if (DeviceFilesLogic.intoItself(src.path, dir.path)) {
                failed++
                continue
            }
            val name = DeviceFilesLogic.freeName(src.name) { File(dir, it).exists() }
            val dst = File(dir, name)
            val went = try {
                if (copy) {
                    src.copyRecursively(dst, overwrite = false)
                } else {
                    // the same volume: a rename; else copy and delete
                    src.renameTo(dst) || (src.copyRecursively(dst, overwrite = false) && src.deleteRecursively())
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.d("Device: transfer failed (%s)", ex.javaClass.simpleName)
                false
            }
            if (went) {
                ok++
                touched += dst.path
                if (!copy) touched += src.path
            } else {
                failed++
            }
        }
        scan(touched)
        Done(ok, failed)
    }

    /** Renames [entry] to [name]; the new path, or null (the name is taken or Android refused). */
    suspend fun rename(entry: DeviceEntry, name: String): String? = withContext(Dispatchers.IO) {
        val src = File(entry.path)
        val dst = File(src.parentFile ?: return@withContext null, name)
        if (dst.exists() && !dst.path.equals(src.path, ignoreCase = true)) return@withContext null
        if (!src.renameTo(dst)) return@withContext null
        scan(listOf(src.path, dst.path))
        dst.path
    }

    /** A new folder [name] in [dir]; its path, or null. */
    suspend fun mkdir(dir: String, name: String): String? = withContext(Dispatchers.IO) {
        val f = File(dir, DeviceFilesLogic.freeName(name) { File(dir, it).exists() })
        if (f.mkdir()) f.path else null
    }

    sealed interface Unzip {
        data class Ok(val folder: String, val files: Int, val skipped: Int) : Unzip
        /** More than [DeviceFilesLogic.ZIP_MAX_ENTRIES] entries. */
        data object TooMany : Unzip
        /** It would write more than its budget ([DeviceFilesLogic.zipBudget]): too large, no room, or a ZIP bomb. */
        data object TooLarge : Unzip
        /** Not a ZIP, damaged, encrypted, or the folder could not be made. */
        data object Failed : Unzip
    }

    /**
     * Unpacks [entry] into a new folder next to it (its name without `.zip`, a free one). An entry that would leave the
     * folder (zip slip) is skipped; more than the budget stops it and removes what was written.
     */
    suspend fun unzip(entry: DeviceEntry): Unzip = withContext(Dispatchers.IO) {
        val zip = File(entry.path)
        val parent = zip.parentFile ?: return@withContext Unzip.Failed
        val folder = File(parent, DeviceFilesLogic.freeName(DeviceFilesLogic.zipFolder(zip.name)) { File(parent, it).exists() })
        val budget = DeviceFilesLogic.zipBudget(zip.length(), runCatching { android.os.StatFs(parent.path).availableBytes }.getOrDefault(0L))
        var written = 0L
        var files = 0
        var skipped = 0
        val made = ArrayList<String>()
        try {
            ZipFile(zip).use { z ->
                if (z.size() > DeviceFilesLogic.ZIP_MAX_ENTRIES) return@withContext Unzip.TooMany
                if (!folder.mkdirs()) return@withContext Unzip.Failed
                val root = folder.canonicalPath
                val buf = ByteArray(64 * 1024)
                for (ze in z.entries()) {
                    currentCoroutineContext().ensureActive()
                    val rel = DeviceFilesLogic.zipTarget(ze.name)
                    val out = rel?.let { File(folder, it) }
                    if (out == null || !DeviceFilesLogic.inside(root, out.canonicalPath)) {
                        skipped++
                        continue
                    }
                    if (ze.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    z.getInputStream(ze).use { input ->
                        out.outputStream().use { o ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                written += n
                                // counted while writing: a header's sizes can lie
                                if (written > budget) throw Budget()
                                o.write(buf, 0, n)
                            }
                        }
                    }
                    files++
                    if (made.size < SCAN_MAX) made += out.path
                }
            }
        } catch (e: Budget) {
            folder.deleteRecursively()
            return@withContext Unzip.TooLarge
        } catch (e: CancellationException) {
            folder.deleteRecursively()
            throw e
        } catch (e: IOException) {
            Timber.d("Device: unzip failed (%s)", e.javaClass.simpleName)
            folder.deleteRecursively()
            return@withContext Unzip.Failed
        } catch (e: IllegalArgumentException) {
            // a name the ZIP's charset cannot read
            folder.deleteRecursively()
            return@withContext Unzip.Failed
        }
        scan(made + folder.path)
        Unzip.Ok(folder.path, files, skipped)
    }

    private class Budget : IOException("budget")

    /** Tells MediaStore that [paths] changed (gone, new, renamed). */
    fun scan(paths: List<String>) {
        if (paths.isEmpty()) return
        runCatching { MediaScannerConnection.scanFile(context, paths.distinct().take(SCAN_MAX).toTypedArray(), null, null) }
    }

    private companion object {
        const val SCAN_MAX = 2_000
    }
}
