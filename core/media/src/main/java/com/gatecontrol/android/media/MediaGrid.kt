package com.gatecontrol.android.media

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * "Filme" ([movies]) or "Serien" as one grid over all sources (the phone's pages): the Jellyfin server shown, page by
 * page in the [order] of the chips, and each share's titles (loaded whole) in their places; a title in both once,
 * Jellyfin's. Without UI state, used from one thread (the page model's main scope) like [MediaBrowse].
 */
class MediaGrid(private val hub: MediaHub, val movies: Boolean) {

    var order: MediaOrder = MediaOrder()
        private set

    private var jellyfin: List<MediaTitle> = emptyList()
    private var jellyfinTotal = 0
    private var next: Int? = null
    private var shareTitles: List<MediaTitle> = emptyList()

    /** A new [load] makes what an older one still brings stale. */
    private var generation = 0

    /** The grid as loaded so far. */
    fun items(): List<MediaTitle> = MediaLogic.merged(jellyfin, next == null, fromShares(), order)

    /** How many titles there are in all (Jellyfin's count and the shares' ones in this order). */
    val total: Int get() = jellyfinTotal + MediaLogic.filtered(fromShares(), order).size

    /** Jellyfin has another page. */
    val hasMore: Boolean get() = next != null

    private fun fromShares() = MediaLogic.withoutDuplicates(shareTitles, jellyfin)

    /**
     * The first page in [order] of the Jellyfin server shown, and the shares' titles; the server's problem, if any (a
     * share that cannot be read just now is left out).
     */
    suspend fun load(order: MediaOrder): MediaProblem? {
        val gen = ++generation
        this.order = order
        jellyfin = emptyList()
        jellyfinTotal = 0
        next = null
        val source = hub.currentSource()
        return coroutineScope {
            val server = async { source?.let { s -> if (s.token == null) null else hub.library(s).list(movies, 0, MediaLogic.PAGE, order) to s } }
            val shares = hub.shares().map { s -> async { hub.library(s).list(movies, 0, MediaLibrary.ALL, MediaOrder()).valueOrNull()?.items.orEmpty() } }
            val r = server.await()
            val titles = shares.awaitAll().flatten()
            if (gen != generation) return@coroutineScope null
            shareTitles = titles
            when {
                source == null -> null
                r == null -> MediaProblem.SIGNED_OUT
                else -> {
                    val (result, s) = r
                    val page = result.valueOrNull()
                    if (page != null) {
                        jellyfin = page.items
                        jellyfinTotal = page.total
                        next = page.next
                    }
                    mediaProblemOf(hub, result, s)
                }
            }
        }
    }

    /** Jellyfin's next page (scrolled near the end); false when there is none or it did not come. */
    suspend fun more(): Boolean {
        val start = next ?: return false
        val source = hub.currentSource() ?: return false
        val gen = generation
        val page = hub.library(source).list(movies, start, MediaLogic.PAGE, order).valueOrNull() ?: return false
        if (gen != generation) return false
        jellyfin = (jellyfin + page.items).distinctBy { it.id }
        jellyfinTotal = page.total
        next = page.next
        return true
    }

    /** The genres of the Jellyfin server shown and the shares, A–Z (the chip "Genre"). */
    suspend fun genres(): List<String> = coroutineScope {
        val source = hub.currentSource()?.takeIf { it.token != null }
        val lists = (listOfNotNull(source) + hub.shares()).map { s -> async { hub.library(s).genres(movies).valueOrNull().orEmpty() } }
        lists.awaitAll().flatten().distinctBy { it.lowercase(java.util.Locale.ROOT) }.sortedBy { it.lowercase(java.util.Locale.ROOT) }
    }
}
