package com.gatecontrol.android.mail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The accounts' pictures (`Account.avatar`, Microsoft only): the plugin's media file, kept by its media id — in memory
 * and in the offline copy ([MailCache], gone with it). A new picture comes with a new media id (the live event `mail`
 * makes `GET me` load again), so a kept one is never stale.
 */
@Singleton
class MailAvatars @Inject constructor(
    private val api: MailApi,
    private val cache: MailCache,
) {
    private val memory = LruCache<String, Bitmap>(MAX_KEPT)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

    /** Already decoded (no I/O). */
    fun peek(mediaId: String): Bitmap? = memory.get(mediaId)

    /** The picture [mediaId], from the copy or the server; null when it cannot be had (offline, gone, no picture). */
    suspend fun bitmap(mediaId: String): Bitmap? {
        memory.get(mediaId)?.let { return it }
        inFlight[mediaId]?.let { return it.await() }
        val mine = CompletableDeferred<Bitmap?>()
        val other = inFlight.putIfAbsent(mediaId, mine)
        if (other != null) return other.await()
        return try {
            val kept = cache.avatar(mediaId)
            val bytes = kept ?: api.avatar(mediaId)
            val bmp = bytes?.let { withContext(Dispatchers.IO) { decode(it) } }
            if (bmp != null) {
                memory.put(mediaId, bmp)
                if (kept == null) cache.putAvatar(mediaId, bytes)
            }
            mine.complete(bmp)
            bmp
        } catch (e: CancellationException) {
            mine.complete(null)
            throw e
        } catch (e: Exception) {
            mine.complete(null)
            null
        } finally {
            inFlight.remove(mediaId, mine)
        }
    }

    /** Small enough for a round avatar (at most [SIDE_PX] on the longer side). */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        const val MAX_KEPT = 16
        const val SIDE_PX = 192
    }
}
