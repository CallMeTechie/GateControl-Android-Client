package com.gatecontrol.android.media

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posters and backdrops of the media pages, kept while the app runs (a few MB): from Jellyfin by URL with its access,
 * from a share the picture beside the film. Away smaller ones.
 */
@Singleton
class MediaPictures @Inject constructor(private val hub: MediaHub) {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun key(title: MediaTitle, backdrop: Boolean): String? {
        val id = (if (backdrop) title.backdropId else title.posterId) ?: return null
        return "${title.sourceId}:$id:${if (backdrop) "b" else "p"}"
    }

    fun cached(title: MediaTitle, backdrop: Boolean): Bitmap? = key(title, backdrop)?.let { cache.get(it) }

    /** [title]'s poster (or backdrop); null when it has none or it cannot be loaded. */
    suspend fun load(title: MediaTitle, backdrop: Boolean): Bitmap? {
        val key = key(title, backdrop) ?: return null
        cache.get(key)?.let { return it }
        val source = hub.store.source(title.sourceId) ?: return null
        return locks.getOrPut(key) { Mutex() }.withLock {
            cache.get(key)?.let { return@withLock it }
            val lib = hub.library(source)
            val away = hub.link.value.atHome != true
            val height = if (backdrop) (if (away) 480 else 720) else (if (away) 300 else 450)
            val url = lib.imageUrl(title, backdrop, height)
            val b = if (url != null) {
                hub.picture(url, lib.headers(), MediaAddress.host(source.address))
            } else {
                lib.imageBytes(title, backdrop)?.let { hub.decode(it, height) }
            }
            b?.also { cache.put(key, it) }
        }.also { locks.remove(key) }
    }

    private companion object {
        const val CACHE_BYTES = 32 * 1024 * 1024
    }
}
