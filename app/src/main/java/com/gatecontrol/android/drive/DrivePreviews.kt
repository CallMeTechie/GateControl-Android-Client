package com.gatecontrol.android.drive

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.util.Size
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preview pictures of Drive files, made by the server (core 1.208+, Drive 1.1): small JPEGs fetched once per media id
 * and kept in memory and on disk. A media id never changes its content (a new version has a new one), so a cached
 * picture never goes stale; old ones are pruned by count. For HEIC photos and videos, which the server cannot read,
 * the uploader sends a picture it made itself ([uploadOwn]).
 */
@Singleton
class DrivePreviews @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()
    private val dir: File get() = File(context.cacheDir, "drive-previews").apply { mkdirs() }

    /** Already in memory (no I/O): for the first frame. */
    fun peek(node: DNode, large: Boolean = false): Bitmap? = key(node, large)?.let { memory.get(it) }

    /** The preview of [node], or null (none, not yet, offline). */
    suspend fun load(node: DNode, large: Boolean = false): Bitmap? {
        val key = key(node, large) ?: return null
        memory.get(key)?.let { return it }
        val mine = CompletableDeferred<Bitmap?>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) return running.await()
        return try {
            val bmp = withContext(Dispatchers.IO) { fromDisk(key) ?: download(node.mediaId!!, large, key) }
            if (bmp != null) memory.put(key, bmp)
            mine.complete(bmp)
            bmp
        } catch (e: CancellationException) {
            mine.complete(null)
            throw e
        } catch (e: Exception) {
            Timber.d("Drive preview not loaded (%s)", e.javaClass.simpleName)
            mine.complete(null)
            null
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private fun key(node: DNode, large: Boolean): String? =
        node.mediaId?.takeIf { node.preview && !node.isDir }?.let { if (large) "$it-l" else it }

    private fun fromDisk(key: String): Bitmap? {
        val file = File(dir, "$key.jpg")
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return BitmapFactory.decodeFile(file.path)
    }

    private suspend fun download(mediaId: String, large: Boolean, key: String): Bitmap? {
        val server = setupRepository.getServerUrl().takeIf { it.isNotBlank() } ?: return null
        val client = apiClientProvider.mediaClient(server)
        repeat(PENDING_TRIES) { attempt ->
            val response = withTimeoutOrNull(TIMEOUT_MS) {
                client.pluginMediaPreview(DRIVE_PLUGIN, mediaId, if (large) "large" else null)
            } ?: return null
            if (response.code() == 503 && attempt < PENDING_TRIES - 1) {
                // Being made right now (just uploaded): wait as long as the server says, a few seconds at most.
                val wait = response.headers()["Retry-After"]?.trim()?.toLongOrNull()?.coerceIn(1, 5) ?: 2
                response.errorBody()?.close()
                delay(wait * 1_000)
                return@repeat
            }
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                response.errorBody()?.close()
                body?.close()
                return null
            }
            val bytes = body.use { b ->
                if (b.contentLength() > MAX_BYTES) return null
                b.byteStream().use { input -> readCapped(input) } ?: return null
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            runCatching { write(key, bytes) }
            return bmp
        }
        return null
    }

    private fun readCapped(input: java.io.InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8_192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun write(key: String, bytes: ByteArray) {
        val target = File(dir, "$key.jpg")
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) tmp.delete()
        prune()
    }

    private fun prune() {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    /**
     * For a file the server cannot make a preview of (HEIC, video): a picture made on the phone from [uri], sent
     * before the commit so the new node has it at once. Best effort: false when it did not work (the file is in
     * Drive all the same, just without a picture).
     */
    suspend fun uploadOwn(mediaId: String, uri: Uri, mime: String?): Boolean {
        if (!DriveLogic.needsOwnPreview(mime)) return false
        val server = setupRepository.getServerUrl().takeIf { it.isNotBlank() } ?: return false
        return try {
            val jpeg = withContext(Dispatchers.IO) { thumbnail(context.contentResolver, uri) } ?: return false
            val response = withTimeoutOrNull(TIMEOUT_MS) {
                apiClientProvider.mediaClient(server).pluginMediaPreviewPut(DRIVE_PLUGIN, mediaId, jpeg.toRequestBody(JPEG))
            }
            response?.errorBody()?.close()
            response?.isSuccessful == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Drive: own preview not sent (%s)", e.javaClass.simpleName)
            false
        }
    }

    /** A JPEG of about [OWN_SIDE] px from the media store's (or the file's) thumbnail, ≤ [OWN_MAX_BYTES]; null when none. */
    private fun thumbnail(resolver: ContentResolver, uri: Uri): ByteArray? {
        val bmp = runCatching { resolver.loadThumbnail(uri, Size(OWN_SIDE, OWN_SIDE), null) }.getOrNull() ?: return null
        try {
            for (quality in intArrayOf(82, 70, 55)) {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= OWN_MAX_BYTES) return out.toByteArray()
            }
            return null
        } finally {
            bmp.recycle()
        }
    }

    private companion object {
        const val MEMORY_BYTES = 8 * 1024 * 1024
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_FILES = 600
        const val TIMEOUT_MS = 20_000L
        const val PENDING_TRIES = 3
        const val OWN_SIDE = 640
        const val OWN_MAX_BYTES = 500 * 1024
        val JPEG = "image/jpeg".toMediaType()
    }
}
