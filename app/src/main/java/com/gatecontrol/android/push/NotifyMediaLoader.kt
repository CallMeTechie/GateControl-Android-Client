package com.gatecontrol.android.push

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.notify.PushEventParser
import com.gatecontrol.android.profile.AvatarUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Where the picture of a push message may be fetched from. Pure, unit-tested. */
object NotifyMediaUrls {

    /**
     * The absolute address of a message picture ([path] = `imageUrl`) on the
     * server at [serverUrl]: only a `/notify-media/<id>.<ext>` path, or an
     * absolute URL with the same scheme, host and port pointing there. Any
     * other host or path → null, so a message can never make the app fetch
     * something from elsewhere.
     */
    fun resolve(serverUrl: String, path: String?): String? {
        if (path.isNullOrBlank()) return null
        val p = path.trim()
        val mediaPath = if (p.startsWith("/")) p else runCatching { URI(p).rawPath }.getOrNull() ?: return null
        if (!PushEventParser.IMAGE_PATH_RE.matches(mediaPath)) return null
        if (!p.startsWith("/") && runCatching { URI(p).rawQuery != null || URI(p).rawFragment != null }.getOrDefault(true)) return null
        return AvatarUrls.resolve(serverUrl, p)
    }
}

/**
 * Loads the pictures of push messages (core 1.169+, e.g. the doorbell
 * snapshot) from the own server's `/notify-media/…` over the client API's
 * OkHttp, like [com.gatecontrol.android.profile.AvatarLoader] does for
 * avatars — but kept in memory only: these pictures may show people at
 * the door and expire on the server (≤ 7 days), so nothing is written to
 * disk. Only image answers (`image` content types) up to [MAX_BYTES] are decoded.
 */
@Singleton
class NotifyMediaLoader @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

    fun resolve(path: String?): String? = NotifyMediaUrls.resolve(setupRepository.getServerUrl(), path)

    /** Already in memory (no I/O). */
    fun peek(path: String?, maxSidePx: Int): Bitmap? = resolve(path)?.let { memory.get(key(it, maxSidePx)) }

    /** The picture at [path], its longer side scaled down to about [maxSidePx]; null when unusable. */
    suspend fun load(path: String?, maxSidePx: Int = NOTIFICATION_SIDE_PX): Bitmap? {
        val abs = resolve(path) ?: return null
        val k = key(abs, maxSidePx)
        memory.get(k)?.let { return it }
        val mine = CompletableDeferred<Bitmap?>()
        val running = inFlight.putIfAbsent(k, mine)
        if (running != null) return running.await()
        return try {
            val bmp = withContext(Dispatchers.IO) { download(abs, maxSidePx) }
            if (bmp != null) memory.put(k, bmp)
            mine.complete(bmp)
            bmp
        } catch (e: CancellationException) {
            mine.complete(null)
            throw e
        } catch (e: Exception) {
            Timber.d("Push picture not loaded (%s)", e.javaClass.simpleName)
            mine.complete(null)
            null
        } finally {
            inFlight.remove(k, mine)
        }
    }

    private suspend fun download(abs: String, maxSidePx: Int): Bitmap? {
        val server = setupRepository.getServerUrl().takeIf { it.isNotBlank() } ?: return null
        val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(server).fetchNotifyMedia(abs) } ?: return null
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            response.errorBody()?.close()
            body?.close()
            return null
        }
        val bytes = body.use { b ->
            val type = b.contentType()
            if (type == null || type.type != "image") return null
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
        return decode(bytes, maxSidePx)
    }

    companion object {
        /** Big picture of a notification: Android scales it to the shade's width anyway. */
        const val NOTIFICATION_SIDE_PX = 1024
        const val THUMB_SIDE_PX = 256
        const val MAX_BYTES = 1024 * 1024
        private const val MEMORY_BYTES = 8 * 1024 * 1024
        private const val TIMEOUT_MS = 15_000L

        private fun key(abs: String, side: Int) = "$abs#$side"

        /** Decodes [bytes] with its longer side at most about [maxSidePx]; null when not a picture. */
        fun decode(bytes: ByteArray, maxSidePx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), maxSidePx) }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }

        /** Largest power of two that keeps the longer side ≥ [target] (pure). */
        fun sampleSize(longSide: Int, target: Int): Int {
            var sample = 1
            val want = target.coerceAtLeast(1)
            while (longSide / (sample * 2) >= want) sample *= 2
            return sample
        }
    }
}
