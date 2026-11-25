package com.gatecontrol.android.media

import com.gatecontrol.android.media.share.ShareEntry
import com.gatecontrol.android.media.share.ShareFs
import com.gatecontrol.android.media.share.ShareProbe
import com.gatecontrol.android.media.share.ShareProbeResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the videos of the page "Ordner" hold (length, sound, picture: [ShareProbe]), looked into two at a time and
 * kept while the app runs, by share, path, size and date (a file written again is looked into again).
 */
@Singleton
class MediaShareProbes(private val io: CoroutineDispatcher) {
    @Inject constructor() : this(Dispatchers.IO)

    private val cache = object : LinkedHashMap<String, Found>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Found>?): Boolean = size > CACHE
    }
    private val gate = Semaphore(PARALLEL)

    /** A result, or none found (not asked again). */
    private data class Found(val result: ShareProbeResult?)

    private fun key(sourceId: String, e: ShareEntry) = "$sourceId|${e.path}|${e.size}|${e.modifiedAt}"

    /** Known already: the result (null inside: nothing found); null: not yet looked into. */
    fun cached(sourceId: String, e: ShareEntry): Result<ShareProbeResult?>? = synchronized(cache) { cache[key(sourceId, e)] }?.let { Result.success(it.result) }

    /** [e] of share [sourceId] through [fs] (blocking reads on the IO threads). */
    suspend fun probe(sourceId: String, fs: ShareFs, e: ShareEntry): ShareProbeResult? {
        cached(sourceId, e)?.let { return it.getOrNull() }
        return gate.withPermit {
            synchronized(cache) { cache[key(sourceId, e)] }?.let { return@withPermit it.result }
            val r = withContext(io) { ShareProbe.probe(fs, e.path, e.name) }
            synchronized(cache) { cache[key(sourceId, e)] = Found(r) }
            r
        }
    }

    private companion object {
        const val CACHE = 2_000
        const val PARALLEL = 2
    }
}
