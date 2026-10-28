package com.gatecontrol.android.drive

import android.util.LruCache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The full node of a short entry (a photo of `GET photos` without its media id, an album's cover): fetched once with
 * `GET nodes/<id>` and kept in memory, so its preview picture can be loaded.
 */
@Singleton
class DriveNodeResolver @Inject constructor(private val api: DriveApi) {
    private val cache = LruCache<Long, DNode>(2_000)

    /** The node [id] with its media id; null when it cannot be fetched (gone, offline). */
    suspend fun resolve(id: Long): DNode? {
        cache.get(id)?.let { return it }
        return api.node(id).valueOrNull()?.also { cache.put(id, it) }
    }
}
