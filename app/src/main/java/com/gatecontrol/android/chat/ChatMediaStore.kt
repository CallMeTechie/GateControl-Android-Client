package com.gatecontrol.android.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/** A picture ready to upload: the scaled file, its preview and its size. */
data class PreparedImage(val file: File, val mime: String, val thumb: File?, val width: Int, val height: Int)

/** A file copied into the outbox. */
data class PreparedFile(val file: File, val mime: String, val name: String, val bytes: Long)

/** Why a picked file cannot be sent. */
sealed interface PrepareError {
    data object Unreadable : PrepareError
    data class TooLarge(val maxBytes: Long) : PrepareError
    data object TypeNotAllowed : PrepareError
}

/**
 * Files of the chats: downloads (`GET …/media/<id>` with the device token,
 * kept in `cache/chat-media`, oldest pruned beyond [MAX_CACHE_BYTES]),
 * decoded pictures in memory, and the outbox copies of what the person
 * sends (`files/chat-outbox`: a picked file stays sendable after a restart
 * even though the picker's permission is gone). Pictures are scaled before
 * the upload (long side 2048 px, WebP) with a small preview (≤ 64 KB).
 */
@Singleton
class ChatMediaStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ChatApi,
) {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<File?>>()

    private val cacheDir: File get() = File(context.cacheDir, "chat-media").apply { mkdirs() }
    val outboxDir: File get() = File(context.filesDir, "chat-outbox").apply { mkdirs() }

    /** Already downloaded (no I/O beyond a stat). */
    fun cached(mediaId: String): File? = ChatJson.mediaId(mediaId)?.let { File(cacheDir, it) }?.takeIf { it.isFile && it.length() > 0 }

    /** The file [mediaId], downloaded once; null when it cannot be fetched (offline, gone, no right). */
    suspend fun file(mediaId: String, maxBytes: Long = MAX_DOWNLOAD_BYTES): File? {
        val id = ChatJson.mediaId(mediaId) ?: return null
        cached(id)?.let { it.setLastModified(System.currentTimeMillis()); return it }
        val mine = CompletableDeferred<File?>()
        val running = inFlight.putIfAbsent(id, mine)
        if (running != null) return running.await()
        return try {
            val f = withContext(Dispatchers.IO) { download(id, maxBytes) }
            mine.complete(f)
            f
        } catch (e: CancellationException) {
            mine.complete(null)
            throw e
        } catch (e: Exception) {
            Timber.d("Chats: file not loaded (%s)", e.javaClass.simpleName)
            mine.complete(null)
            null
        } finally {
            inFlight.remove(id, mine)
        }
    }

    private suspend fun download(id: String, maxBytes: Long): File? {
        val response = api.download(id) ?: return null
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            response.errorBody()?.close()
            body?.close()
            return null
        }
        val target = File(cacheDir, id)
        val tmp = File(cacheDir, "$id.part")
        body.use { b ->
            if (b.contentLength() > maxBytes) return null
            b.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) {
                            tmp.delete()
                            return null
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return null
        }
        prune()
        return target
    }

    private fun prune() {
        val files = cacheDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    /** Memory-cached picture of [mediaId] (no I/O). */
    fun peekBitmap(mediaId: String, maxSidePx: Int): Bitmap? = memory.get("$mediaId#${bucket(maxSidePx)}")

    /** The picture [mediaId] decoded to about [maxSidePx] on its longer side. */
    suspend fun bitmap(mediaId: String, maxSidePx: Int): Bitmap? {
        val key = "$mediaId#${bucket(maxSidePx)}"
        memory.get(key)?.let { return it }
        val file = file(mediaId) ?: return null
        val bmp = withContext(Dispatchers.IO) { decodeFile(file, bucket(maxSidePx)) } ?: return null
        memory.put(key, bmp)
        return bmp
    }

    /** A local picture (outbox copy) decoded small. */
    suspend fun localBitmap(path: String, maxSidePx: Int): Bitmap? {
        val key = "local:$path#${bucket(maxSidePx)}"
        memory.get(key)?.let { return it }
        val f = File(path).takeIf { it.isFile && it.canonicalPath.startsWith(outboxDir.canonicalPath) } ?: return null
        val bmp = withContext(Dispatchers.IO) { decodeFile(f, bucket(maxSidePx)) } ?: return null
        memory.put(key, bmp)
        return bmp
    }

    // ─── outgoing ───────────────────────────────────────────────────

    /** Display name and size of a picked [uri] (SAF / photo picker). */
    fun describe(uri: Uri): Pair<String?, Long?> = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null to null
            val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
            val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
            name to size
        } ?: (null to null)
    }.getOrDefault(null to null)

    /** Picture → scaled WebP (long side [maxSide]) plus a preview ≤ [thumbMax] bytes, in the outbox. */
    suspend fun prepareImage(uri: Uri, clientId: String, maxSide: Int, thumbMax: Int): PreparedImage? = withContext(Dispatchers.IO) {
        val bmp = decodeUri(uri, maxSide) ?: return@withContext null
        try {
            val main = File(outboxDir, "$clientId.webp")
            val bytes = encodeWithin(bmp, Bitmap.CompressFormat.WEBP_LOSSY, listOf(82, 74, 66), Int.MAX_VALUE) ?: return@withContext null
            main.writeBytes(bytes)
            val thumbBmp = scaleTo(bmp, THUMB_SIDE)
            val thumbBytes = encodeWithin(thumbBmp, Bitmap.CompressFormat.WEBP_LOSSY, listOf(70, 55, 40, 25), thumbMax)
            if (thumbBmp !== bmp) thumbBmp.recycle()
            val thumb = thumbBytes?.let { File(outboxDir, "$clientId.thumb.webp").apply { writeBytes(it) } }
            PreparedImage(main, "image/webp", thumb, bmp.width, bmp.height)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Any file → copy in the outbox. Refused when it is larger than
     * [maxBytes] or its type is not one of [allowed] (empty = unknown, all).
     */
    suspend fun prepareFile(uri: Uri, clientId: String, maxBytes: Long?, allowed: List<String>): Result<PreparedFile> = withContext(Dispatchers.IO) {
        val (name, size) = describe(uri)
        if (maxBytes != null && size != null && size > maxBytes) return@withContext Result.failure(PrepareException(PrepareError.TooLarge(maxBytes)))
        val rawMime = context.contentResolver.getType(uri)?.lowercase()
            ?: name?.substringAfterLast('.', "")?.lowercase()?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
        val mime = ChatMime.sendable(rawMime, allowed) ?: return@withContext Result.failure(PrepareException(PrepareError.TypeNotAllowed))
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val target = File(outboxDir, "$clientId.$ext")
        val limit = maxBytes ?: MAX_DOWNLOAD_BYTES
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > limit) throw PrepareException(PrepareError.TooLarge(limit))
                        out.write(buf, 0, n)
                    }
                    total
                }
            }
        }
        val bytes = copied.getOrElse { e ->
            target.delete()
            return@withContext Result.failure(if (e is PrepareException) e else PrepareException(PrepareError.Unreadable))
        } ?: return@withContext Result.failure(PrepareException(PrepareError.Unreadable))
        Result.success(PreparedFile(target, mime, ChatMime.safeName(name, ext), bytes))
    }

    /** Removes the outbox copies of [clientId]. */
    fun dropOutboxFiles(vararg paths: String?) {
        for (p in paths) {
            val f = p?.let { File(it) } ?: continue
            if (f.canonicalPath.startsWith(outboxDir.canonicalPath)) f.delete()
        }
    }

    // ─── decoding ───────────────────────────────────────────────────

    private fun decodeUri(uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longer = max(w, h)
            if (longer > maxSide) {
                val f = maxSide.toFloat() / longer
                decoder.setTargetSize((w * f).roundToInt().coerceAtLeast(1), (h * f).roundToInt().coerceAtLeast(1))
            }
        }
    }.getOrNull()

    private fun decodeFile(file: File, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun scaleTo(bmp: Bitmap, side: Int): Bitmap {
        val longer = max(bmp.width, bmp.height)
        if (longer <= side) return bmp
        val f = side.toFloat() / longer
        return bmp.scale((bmp.width * f).roundToInt().coerceAtLeast(1), (bmp.height * f).roundToInt().coerceAtLeast(1))
    }

    private fun encodeWithin(bmp: Bitmap, format: Bitmap.CompressFormat, qualities: List<Int>, maxBytes: Int): ByteArray? {
        for (q in qualities) {
            val out = ByteArrayOutputStream(64 * 1024)
            if (!bmp.compress(format, q, out)) continue
            val bytes = out.toByteArray()
            if (bytes.isNotEmpty() && bytes.size <= maxBytes) return bytes
        }
        return null
    }

    companion object {
        private const val MEMORY_BYTES = 24 * 1024 * 1024
        const val MAX_CACHE_BYTES = 200L * 1024 * 1024
        const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
        private const val THUMB_SIDE = 320

        private fun bucket(px: Int): Int = when {
            px <= 160 -> 160
            px <= 480 -> 480
            px <= 1080 -> 1080
            else -> 2048
        }
    }
}

class PrepareException(val error: PrepareError) : Exception()

/** Content types of chat files. Pure, unit-tested. */
object ChatMime {
    private val NAME_BAD_RE = Regex("[\\u0000-\\u001F\\u007F/\\\\:*?\"<>|]")

    /**
     * The type to upload [mime] as: itself when the server allows it, else
     * `application/octet-stream` when that is allowed (any file), else null.
     * [allowed] empty = the server did not say (all its usual types).
     */
    fun sendable(mime: String, allowed: List<String>): String? {
        val m = mime.lowercase().substringBefore(';').trim()
        if (allowed.isEmpty()) return m.ifBlank { "application/octet-stream" }
        if (m in allowed) return m
        // Common aliases of the same thing.
        val alias = when (m) {
            "image/jpg" -> "image/jpeg"
            "audio/x-m4a", "audio/m4a" -> "audio/mp4"
            "text/x-vcard" -> "text/vcard"
            else -> null
        }
        if (alias != null && alias in allowed) return alias
        return "application/octet-stream".takeIf { it in allowed }
    }

    /** Kind of message for a file of type [mime]. */
    fun kindOf(mime: String?): String = when {
        mime == null -> ChatMessage.KIND_FILE
        mime.startsWith("image/") && mime != "image/gif" -> ChatMessage.KIND_IMAGE
        else -> ChatMessage.KIND_FILE
    }

    /** A file name safe to show and to save under. */
    fun safeName(name: String?, ext: String): String {
        val n = name?.let { NAME_BAD_RE.replace(it, "_").trim().take(200) }?.takeIf { it.isNotBlank() && it != "." && it != ".." }
        return n ?: "datei.$ext"
    }

    fun isImage(mime: String?): Boolean = mime?.startsWith("image/") == true
}
