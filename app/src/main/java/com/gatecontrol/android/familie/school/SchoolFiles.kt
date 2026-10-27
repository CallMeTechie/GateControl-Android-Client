package com.gatecontrol.android.familie.school

import android.content.Context
import android.webkit.MimeTypeMap
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A school attachment on the device, ready to open: [file] under its own [name], of type [mime]. */
data class SchoolLocalFile(val file: File, val name: String, val mime: String)

/**
 * Attachments of school messages (plugin 1.5+): the plugin fetches the
 * file into its media store (`POST app/messages/<id>/attachments/<attId>`),
 * the app downloads it (`GET …/plugins/gatecontrol-schule/media/<mediaId>`)
 * into `cache/school/<message>_<attachment>/<name>` — shared with other apps
 * through the FileProvider (`res/xml/file_paths.xml`, path `school/`).
 * A file already there opens without a network call; folders not used for
 * [MAX_AGE_MS] are removed.
 */
@Singleton
class SchoolFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: FamilieApi,
) {
    private val root: File get() = File(context.cacheDir, DIR).apply { mkdirs() }

    /** Schule 1.7: [attachment] of message [messageId] into the Drive folder [folderId] → where it went. */
    suspend fun toDrive(messageId: String, attachment: SchoolAttachment, folderId: Long): FamilieResult<String> =
        api.schoolAttachmentToDrive(messageId, attachment.id, folderId)

    /** The file of [attachment] of message [messageId]; a failure carries the code to tell. */
    suspend fun load(messageId: String, attachment: SchoolAttachment): FamilieResult<SchoolLocalFile> = withContext(Dispatchers.IO) {
        prune()
        val folder = File(root, SchoolLogic.attachmentFolder(messageId, attachment.id))
        cached(folder, attachment)?.let { return@withContext FamilieResult.Ok(it) }
        val info = when (val r = api.schoolAttachment(messageId, attachment.id)) {
            is FamilieResult.Ok -> r.value
            is FamilieResult.Failed -> return@withContext r
            FamilieResult.Offline -> return@withContext FamilieResult.Offline
            FamilieResult.NotConfigured -> return@withContext FamilieResult.NotConfigured
        }
        try {
            download(info, attachment, folder)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Schule: attachment not saved (%s)", e.javaClass.simpleName)
            folder.deleteRecursively()
            FamilieResult.Failed(CODE_DOWNLOAD, 0)
        }
    }

    private fun cached(folder: File, attachment: SchoolAttachment): SchoolLocalFile? {
        val f = folder.listFiles()?.firstOrNull { it.isFile && !it.name.endsWith(PART) && it.length() > 0 } ?: return null
        folder.setLastModified(System.currentTimeMillis())
        return SchoolLocalFile(f, f.name, typeOf(attachment.mime, f.name))
    }

    private suspend fun download(info: SchoolAttachmentFile, attachment: SchoolAttachment, folder: File): FamilieResult<SchoolLocalFile> {
        val response = api.schoolMedia(info.mediaId) ?: return FamilieResult.Offline
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            response.errorBody()?.close()
            body?.close()
            return FamilieResult.Failed(if (response.code() == 404) CODE_GONE else CODE_DOWNLOAD, response.code())
        }
        val headerType = body.contentType()?.let { "${it.type}/${it.subtype}" }
        val mime = SchoolJson.mime(info.mime) ?: attachment.mime ?: SchoolJson.mime(headerType)
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"
        val name = SchoolLogic.safeFileName(info.name ?: attachment.name.ifBlank { null }, ext)
        folder.deleteRecursively()
        folder.mkdirs()
        val target = File(folder, name)
        val tmp = File(folder, name + PART)
        body.use { b ->
            if (b.contentLength() > MAX_BYTES) return FamilieResult.Failed(CODE_TOO_LARGE, 413)
            b.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) {
                            tmp.delete()
                            return FamilieResult.Failed(CODE_TOO_LARGE, 413)
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        if (tmp.length() == 0L || !tmp.renameTo(target)) {
            tmp.delete()
            return FamilieResult.Failed(CODE_DOWNLOAD, 0)
        }
        return FamilieResult.Ok(SchoolLocalFile(target, name, typeOf(mime, name)))
    }

    /** The type to open a file as: the one the server told, else from the name's extension. */
    private fun typeOf(mime: String?, name: String): String =
        mime ?: name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"

    /** Removes folders not used for [MAX_AGE_MS]. */
    private fun prune() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    companion object {
        private const val DIR = "school"
        private const val PART = ".part"

        /** The plugin refuses more than 25 MB; a little room for its rounding. */
        private const val MAX_BYTES = 26L * 1024 * 1024
        private const val MAX_AGE_MS = 7L * 24 * 3600_000L

        /** The file could not be downloaded or saved (app-side code). */
        const val CODE_DOWNLOAD = "school_download_failed"

        /** The plugin's file is gone (told like the plugin's own 404). */
        const val CODE_GONE = "SCHULE_ATTACHMENT_NOT_FOUND"

        /** Larger than the plugin allows (told like the plugin's own 413). */
        const val CODE_TOO_LARGE = "SCHULE_ATTACHMENT_TOO_LARGE"
    }
}
