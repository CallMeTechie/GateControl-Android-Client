package com.gatecontrol.android.media

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.gatecontrol.android.media.offline.MediaOffline
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posters and backdrops of the media pages: kept while the app runs (a few MB) and on the disk ([MediaPictureDisk],
 * the app's cache folder), from Jellyfin by URL with its access (in the size the page shows, away smaller ones), from
 * a share the picture beside the film (kept scaled down). A downloaded title's from the phone (also offline).
 */
@Singleton
class MediaPictures internal constructor(
    private val hub: MediaHub,
    private val offline: MediaOffline,
    private val disk: MediaPictureDisk?,
) {
    @Inject
    constructor(hub: MediaHub, offline: MediaOffline, @ApplicationContext context: Context) :
        this(hub, offline, MediaPictureDisk(File(context.cacheDir, DISK_DIR)))

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * The picture's key: its source, item, kind and tag (a new tag when the picture changed on the server), never an
     * access, so it stays the same across sign-ins and networks.
     */
    fun key(title: MediaTitle, backdrop: Boolean): String? = keyOf(title, backdrop)

    fun cached(title: MediaTitle, backdrop: Boolean): Bitmap? = key(title, backdrop)?.let { cache.get(it) }

    /** [title]'s poster (or backdrop); null when it has none or it cannot be loaded. */
    suspend fun load(title: MediaTitle, backdrop: Boolean): Bitmap? {
        val key = key(title, backdrop) ?: return null
        cache.get(key)?.let { return it }
        val source = hub.store.source(title.sourceId) ?: return null
        return locks.getOrPut(key) { Mutex() }.withLock {
            cache.get(key)?.let { return@withLock it }
            val away = hub.link.value.atHome != true
            val height = heightOf(backdrop, away)
            withContext(Dispatchers.IO) { offline.picture(title, backdrop) }?.let { bytes -> hub.decode(bytes, height)?.let { b -> cache.put(key, b); return@withLock b } }
            // kept on the disk: no request at all (at home a smaller one kept away is loaded again in its size)
            val kept = withContext(Dispatchers.IO) { disk?.get(key) }?.let { bytes -> hub.decode(bytes, height) }
            if (kept != null && (away || kept.height >= height * 3 / 4)) {
                cache.put(key, kept)
                return@withLock kept
            }
            val lib = hub.library(source)
            val url = lib.imageUrl(title, backdrop, height)
            val b = if (url != null) {
                // Jellyfin sends the picture in the size asked: kept as it came
                hub.pictureBytes(url, lib.headers(), source)?.let { bytes ->
                    hub.decode(bytes, height)?.also { withContext(Dispatchers.IO) { disk?.put(key, bytes) } }
                }
            } else {
                // a share's picture is the file beside the film (often large): kept scaled down
                lib.imageBytes(title, backdrop)?.let { hub.decode(it, height) }?.also { b ->
                    withContext(Dispatchers.IO) { disk?.put(key, jpeg(b)) }
                }
            }
            (b ?: kept)?.also { cache.put(key, it) }
        }.also { locks.remove(key) }
    }

    private fun jpeg(b: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        b.compress(Bitmap.CompressFormat.JPEG, SHARE_QUALITY, out)
        out.toByteArray()
    }

    internal companion object {
        const val CACHE_BYTES = 32 * 1024 * 1024
        const val DISK_DIR = "media-pictures"
        const val SHARE_QUALITY = 85

        /** The height asked for: a poster about as high as on the page, away smaller (the tunnel is slow). */
        fun heightOf(backdrop: Boolean, away: Boolean): Int = if (backdrop) (if (away) 480 else 720) else (if (away) 300 else 450)

        fun keyOf(title: MediaTitle, backdrop: Boolean): String? {
            val id = (if (backdrop) title.backdropId else title.posterId) ?: return null
            val tag = (if (backdrop) title.backdropTag else title.posterTag).orEmpty()
            return "${title.sourceId}:$id:$tag:${if (backdrop) "b" else "p"}"
        }
    }
}
