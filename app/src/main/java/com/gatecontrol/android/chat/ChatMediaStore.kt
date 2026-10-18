package com.gatecontrol.android.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
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

/**
 * A file copied into the outbox; [thumb] a preview picture (a video's
 * frame, a PDF's first page) and [video] a video's length and size — both
 * only when the server takes them (`file_thumbs`, `video_meta`).
 */
data class PreparedFile(
    val file: File,
    val mime: String,
    val name: String,
    val bytes: Long,
    val thumb: File? = null,
    val video: ChatVideo? = null,
)

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

    /** A file just uploaded as [mediaId]: kept as its download (no round trip to show it). */
    fun prime(mediaId: String, source: File) {
        val id = ChatJson.mediaId(mediaId) ?: return
        runCatching {
            val tmp = File(cacheDir, "$id.part")
            source.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(File(cacheDir, id))) tmp.delete()
        }
    }

    // ─── camera ─────────────────────────────────────────────────────

    /**
     * A new file for a photo taken with the camera app (`cache/camera`,
     * shared through the FileProvider only for the capture) and its
     * content URI. Left-overs older than a day are removed.
     */
    fun newCameraTarget(): Pair<File, Uri> {
        val dir = File(context.cacheDir, CAMERA_DIR).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        return file to FileProvider.getUriForFile(context, context.packageName + ".provider", file)
    }

    /** A camera photo of [newCameraTarget] by its path (nothing outside `cache/camera`). */
    fun cameraFile(path: String?): File? {
        val f = path?.let { File(it) } ?: return null
        val dir = File(context.cacheDir, CAMERA_DIR)
        return f.takeIf { it.isFile && it.length() > 0 && it.canonicalPath.startsWith(dir.canonicalPath + File.separator) }
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

    // ─── previews of files (Messenger 1.3.0) ────────────────────────

    /**
     * [prepared] with what the server takes beyond the file: a video's
     * length and size ([videoMeta]) and a preview picture of a video or a
     * PDF ([thumbs], WebP ≤ [thumbMax] bytes, ≤ [maxSide] px — the
     * server's `limits.thumbMaxSide`). Never fails: what cannot be read is
     * left out.
     */
    suspend fun withPreview(
        prepared: PreparedFile,
        clientId: String,
        thumbs: Boolean,
        videoMeta: Boolean,
        thumbMax: Int,
        maxSide: Int = ChatPreview.THUMB_MAX_SIDE,
    ): PreparedFile =
        withContext(Dispatchers.IO) {
            val mime = prepared.mime
            val thumbFile = File(outboxDir, "$clientId.thumb.webp")
            when {
                ChatPreview.isVideo(mime) && (thumbs || videoMeta) -> {
                    val (frame, video) = readVideo(prepared.file, thumbs, maxSide)
                    val thumb = frame?.let { bmp ->
                        try {
                            encodeThumb(bmp, thumbMax, maxSide)?.let { thumbFile.apply { writeBytes(it) } }
                        } finally {
                            bmp.recycle()
                        }
                    }
                    prepared.copy(thumb = thumb, video = video.takeIf { videoMeta })
                }
                ChatPreview.isPdf(mime) && thumbs -> {
                    val page = renderPdf(prepared.file, maxSide)
                    val thumb = page?.let { bmp ->
                        try {
                            encodeThumb(bmp, thumbMax, maxSide)?.let { thumbFile.apply { writeBytes(it) } }
                        } finally {
                            bmp.recycle()
                        }
                    }
                    prepared.copy(thumb = thumb)
                }
                else -> prepared
            }
        }

    /** A frame at about one second (when [frame]) plus length and picture size of the video [file]. */
    private fun readVideo(file: File, frame: Boolean, maxSide: Int): Pair<Bitmap?, ChatVideo?> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val (w, h) = ChatPreview.orientedSize(
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull(),
            )
            val video = ChatPreview.video(duration, w, h)
            val bmp = if (!frame) null else runCatching {
                val at = ChatPreview.frameTimeUs(duration)
                r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide)
                    ?: r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull()
            bmp to video
        } catch (e: Exception) {
            Timber.d("Chats: video not read (%s)", e.javaClass.simpleName)
            null to null
        } finally {
            runCatching { r.release() }
        }
    }

    /** The first page of the PDF [file] on white, ≤ [maxSide] px (null: encrypted, broken). */
    private fun renderPdf(file: File, maxSide: Int): Bitmap? = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                if (renderer.pageCount < 1) return@use null
                val page = renderer.openPage(0)
                try {
                    val (w, h) = ChatPreview.fit(page.width, page.height, maxSide, upscale = true)
                    val bmp = createBitmap(w, h)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        }
    } catch (e: Exception) {
        Timber.d("Chats: PDF page not rendered (%s)", e.javaClass.simpleName)
        null
    }

    /** WebP within [maxBytes] and [maxSide]: lower quality first, then smaller. */
    private fun encodeThumb(bmp: Bitmap, maxBytes: Int, maxSide: Int): ByteArray? {
        for (side in ChatPreview.thumbSides(maxSide)) {
            val (w, h) = ChatPreview.fit(bmp.width, bmp.height, side)
            val scaled = if (w == bmp.width && h == bmp.height) bmp else bmp.scale(w, h)
            try {
                encodeWithin(scaled, Bitmap.CompressFormat.WEBP_LOSSY, listOf(78, 64, 50, 36), maxBytes)?.let { return it }
            } finally {
                if (scaled !== bmp) scaled.recycle()
            }
        }
        return null
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
        const val CAMERA_DIR = "camera"

        private fun bucket(px: Int): Int = when {
            px <= 160 -> 160
            px <= 480 -> 480
            px <= 1080 -> 1080
            else -> 2048
        }
    }
}

class PrepareException(val error: PrepareError) : Exception()

/** Previews of video and PDF files (Messenger 1.3.0). Pure, unit-tested. */
object ChatPreview {
    /** The server takes previews up to 640 px (`limits.thumbMaxSide`). */
    const val THUMB_MAX_SIDE = 640
    private const val FRAME_AT_US = 1_000_000L

    fun isVideo(mime: String?): Boolean = mime?.startsWith("video/") == true

    fun isPdf(mime: String?): Boolean = mime == "application/pdf"

    /** Whether a file of type [mime] gets a preview picture: videos and PDFs, on servers with `file_thumbs`. */
    fun wantsThumb(mime: String?, features: ChatFeatures?): Boolean = features?.fileThumbs == true && (isVideo(mime) || isPdf(mime))

    /** [w] × [h] within [max] on the longer side (aspect kept; larger only with [upscale]). */
    fun fit(w: Int, h: Int, max: Int, upscale: Boolean = false): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return max to max
        val longer = maxOf(w, h)
        if (longer <= max && !upscale) return w to h
        val f = max.toDouble() / longer
        return (w * f).roundToInt().coerceAtLeast(1) to (h * f).roundToInt().coerceAtLeast(1)
    }

    /** Sizes tried for a preview until it fits the byte limit: [maxSide], then ¾ and ½ of it. */
    fun thumbSides(maxSide: Int): List<Int> = listOf(maxSide, maxSide * 3 / 4, maxSide / 2).map { it.coerceAtLeast(32) }.distinct()

    /** The picture size as shown: width and height swapped for a video recorded upright (90° / 270°). */
    fun orientedSize(w: Int?, h: Int?, rotation: Int?): Pair<Int?, Int?> {
        val r = ((rotation ?: 0) % 360 + 360) % 360
        return if (r == 90 || r == 270) h to w else w to h
    }

    /** Where the preview frame is taken: at one second, in the middle of shorter videos. */
    fun frameTimeUs(durationMs: Long?): Long = when {
        durationMs == null || durationMs <= 0 -> FRAME_AT_US
        durationMs * 1000 < FRAME_AT_US * 2 -> durationMs * 1000 / 2
        else -> FRAME_AT_US
    }

    /** `video` of the send body from what the file says; null when it says nothing usable. */
    fun video(durationMs: Long?, w: Int?, h: Int?): ChatVideo? {
        val v = ChatVideo(
            durationMs = durationMs?.takeIf { it in 1..24 * 3_600_000L },
            width = w?.takeIf { it in 1..20_000 },
            height = h?.takeIf { it in 1..20_000 },
        )
        return v.takeIf { it.durationMs != null || it.width != null || it.height != null }
    }

    /**
     * The outbox keeps a video's length and size in its `meta`; the send
     * body carries them as `video` (kind stays `file`) and `meta` keeps the
     * file name only.
     */
    fun sendFields(kind: String, mime: String?, meta: ChatMeta?): Pair<ChatMeta?, ChatVideo?> {
        if (kind != ChatMessage.KIND_FILE || !isVideo(mime) || meta == null) return meta to null
        val video = video(meta.durationMs, meta.w, meta.h)
        return meta.copy(durationMs = null, w = null, h = null) to video
    }
}

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
