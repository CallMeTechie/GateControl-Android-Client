package com.gatecontrol.android.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads avatar pictures (the app has no image library): over the client
 * API's OkHttp (same DNS, same server) from `/avatars/<id>.<ext>`, kept in
 * memory and on disk keyed by the URL. Avatar URLs change with every new
 * picture (capability URL, immutable), so a cached file never goes stale;
 * old ones are pruned by count.
 */
@Singleton
class AvatarLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()
    private val dir: File get() = File(context.cacheDir, "avatars").apply { mkdirs() }

    /** The absolute address of [url] on the configured server, null when unusable. */
    fun resolve(url: String?): String? = AvatarUrls.resolve(setupRepository.getServerUrl(), url)

    /** Already in memory (no I/O): for the first frame. */
    fun peek(url: String?, sizePx: Int): Bitmap? = resolve(url)?.let { memory.get(memKey(it, sizePx)) }

    /** The picture at [url] decoded for about [sizePx] square, or null (none, offline, broken). */
    suspend fun load(url: String?, sizePx: Int): Bitmap? {
        val abs = resolve(url) ?: return null
        val key = memKey(abs, sizePx)
        memory.get(key)?.let { return it }
        val mine = CompletableDeferred<Bitmap?>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) return running.await()
        return try {
            val bmp = withContext(Dispatchers.IO) { fromDisk(abs, sizePx) ?: download(abs, sizePx) }
            if (bmp != null) memory.put(key, bmp)
            mine.complete(bmp)
            bmp
        } catch (e: CancellationException) {
            mine.complete(null)
            throw e
        } catch (e: Exception) {
            Timber.d("Avatar not loaded (%s)", e.javaClass.simpleName)
            mine.complete(null)
            null
        } finally {
            inFlight.remove(key, mine)
        }
    }

    /** Stores a picture just uploaded under its new address (no download needed). */
    suspend fun prime(absoluteUrl: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        runCatching { write(absoluteUrl, bytes) }
    }

    private fun fromDisk(abs: String, sizePx: Int): Bitmap? {
        val file = File(dir, fileName(abs))
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        file.setLastModified(System.currentTimeMillis())
        return decode(bytes, sizePx)
    }

    private suspend fun download(abs: String, sizePx: Int): Bitmap? {
        val server = setupRepository.getServerUrl().takeIf { it.isNotBlank() } ?: return null
        val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(server).fetchAvatar(abs) } ?: return null
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            response.errorBody()?.close()
            body?.close()
            return null
        }
        val bytes = body.use { b ->
            val type = b.contentType()
            if (type != null && type.type != "image") return null
            if (b.contentLength() > MAX_BYTES) return null
            b.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8_192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }
        val bmp = decode(bytes, sizePx) ?: return null
        runCatching { write(abs, bytes) }
        return bmp
    }

    private fun write(abs: String, bytes: ByteArray) {
        val target = File(dir, fileName(abs))
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) tmp.delete()
        prune()
    }

    private fun prune() {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun decode(bytes: ByteArray, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(minOf(bounds.outWidth, bounds.outHeight), sizePx) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    companion object {
        private const val MEMORY_BYTES = 4 * 1024 * 1024
        private const val MAX_BYTES = 1024 * 1024
        private const val MAX_FILES = 64
        private const val TIMEOUT_MS = 15_000L

        private fun memKey(abs: String, sizePx: Int) = "$abs#${bucket(sizePx)}"

        /** Sizes are rounded up to a few buckets so close sizes share one bitmap. */
        private fun bucket(sizePx: Int): Int = when {
            sizePx <= 96 -> 96
            sizePx <= 192 -> 192
            else -> 512
        }

        /** Largest power of two that keeps the decoded side ≥ [target] (pure). */
        fun sampleSize(side: Int, target: Int): Int {
            var sample = 1
            val want = bucket(target.coerceAtLeast(1))
            while (side / (sample * 2) >= want) sample *= 2
            return sample
        }

        fun fileName(abs: String): String =
            MessageDigest.getInstance("SHA-256").digest(abs.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }
}
