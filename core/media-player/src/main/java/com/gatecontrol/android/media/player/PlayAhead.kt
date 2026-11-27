package com.gatecontrol.android.media.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.AesCipherDataSink
import androidx.media3.datasource.AesCipherDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import com.gatecontrol.android.media.MediaPlayAhead
import com.gatecontrol.android.media.MediaPlayAheadState
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.security.SecureRandom
import java.util.UUID

/**
 * „Beim Abspielen mitladen“ (part 2, stage 1, option C): for one playback the player's file is loaded further ahead
 * than its memory holds, into a space of its own on the device ([limitBytes]), and read from there; what was seen
 * stays for jumping back while there is room. One writer loads ahead of the place the player reads ([follow]), in
 * pieces, so the player and the loading never fetch the same bytes twice (each piece is locked while it loads; the
 * player reads its own pieces through the same space). Kept encrypted with a key that lives only in memory, and
 * deleted with [release] (after the end, a stop, the next title): nothing outlives the playback. The downloads for
 * offline (only on Wi-Fi, encrypted) are something else and stay as they are.
 *
 * Only a file played as it is (Jellyfin's original, a share's file) is followed; anything else (Jellyfin converting,
 * subtitles, pictures) goes straight through. Thread-safe; [release] from any thread.
 */
@OptIn(UnstableApi::class)
class PlayAhead(
    root: File,
    private val upstream: DataSource.Factory,
    /** The space for this playback ([MediaPlayAhead.limitBytes]). */
    val limitBytes: Long,
) {
    private val dir: File = File(root, UUID.randomUUID().toString())
    private val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val evictor = PlayheadEvictor(limitBytes) { position() }

    /** What is followed now; null: nothing (at home, converted, a download). */
    private data class Follow(val key: String, val uri: Uri, val aheadLimit: Long)

    @Volatile
    private var follow: Follow? = null

    /** The byte the player reads at, of [follow]'s key. */
    @Volatile
    private var head: Long = C.POSITION_UNSET.toLong()

    @Volatile
    private var headKey: String? = null

    @Volatile
    private var closed = false

    @Volatile
    private var writer: CacheWriter? = null

    @Volatile
    private var snapshot = MediaPlayAheadState()

    /** Loading speed of the last pieces (bit/s), smoothed. */
    private var rateBps = 0.0
    private var piece = FIRST_PIECE
    private val signal = Object()

    /** The key of a file no longer followed: its bytes go (on the loader's thread). */
    private var dropKey: String? = null

    private val cache: SimpleCache = run {
        synchronized(active) {
            // what an earlier run left (the app was stopped during a playback): it goes, it cannot be read anyway
            sweepLocked(root)
            active += dir
        }
        dir.mkdirs()
        SimpleCache(dir, evictor, null, null, false, true)
    }

    private val reading: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory { Measured(upstream.createDataSource()) }
        .setCacheReadDataSourceFactory { AesCipherDataSource(secret, FileDataSource()) }
        .setCacheWriteDataSinkFactory { AesCipherDataSink(secret, CacheDataSink(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE), ByteArray(SCRATCH)) }
        // a piece another reader is loading is waited for (never loaded twice)
        .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)

    /** The player's data source: the followed file through the space, everything else straight through. */
    val factory: DataSource.Factory = DataSource.Factory { Source() }

    private val thread: Thread = Thread({ loop() }, "media-ahead").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY - 1
        start()
    }

    /**
     * Follows the file at [url] from now on ([aheadLimit]: how far ahead at most, [MediaPlayAhead.aheadLimit]); null:
     * nothing (the next title converts, came home). What another file left goes (the title before is over).
     */
    fun follow(url: String?, aheadLimit: Long = (limitBytes * MediaPlayAhead.AHEAD_SHARE).toLong()) {
        val next = url?.let { Follow(MediaPlayAhead.cacheKey(it), Uri.parse(it), aheadLimit.coerceAtLeast(MIN_PIECE)) }
        val before = follow
        if (before?.key == next?.key && before?.aheadLimit == next?.aheadLimit) return
        follow = next
        if (before != null && before.key != next?.key) {
            writer?.cancel()
            headKey = null
            snapshot = MediaPlayAheadState()
            synchronized(signal) { dropKey = before.key }
        }
        wake()
    }

    /** Where it is now (for the player's card); empty when nothing is followed. */
    fun state(): MediaPlayAheadState = snapshot

    /** Whether something is followed now. */
    val following: Boolean get() = follow != null

    /** Ends it: the loading stops, the space and its key go (on the loader's thread). Call after the player is released. */
    fun release() {
        if (closed) return
        closed = true
        writer?.cancel()
        wake()
    }

    private fun wake() = synchronized(signal) { signal.notifyAll() }

    private fun position(): Pair<String?, Long> = headKey to head

    /** The player opened [key] at [position]: the loading goes on from there. */
    private fun reads(key: String, position: Long) {
        val jumped = headKey != key || position < head || position > head + (follow?.aheadLimit ?: 0)
        headKey = key
        head = position
        // jumped elsewhere (spooled, a new title): the piece loading now is not needed first
        if (jumped) writer?.cancel()
        wake()
    }

    private fun loop() {
        try {
            while (!closed) {
                synchronized(signal) {
                    dropKey?.let { k ->
                        runCatching { cache.removeResource(k) }
                        dropKey = null
                    }
                }
                val f = follow
                val key = headKey
                val at = head
                if (f == null || key != f.key || at < 0) {
                    idle(IDLE_MS)
                    continue
                }
                val total = ContentMetadata.getContentLength(cache.getContentMetadata(f.key))
                // the player's own piece is its to load (it may hold it open, paused): the loading goes on after it
                val own = cache.getCachedLength(f.key, at, PLAYER_PIECE).coerceAtLeast(0)
                val after = at + PLAYER_PIECE
                val beyond = if (total != C.LENGTH_UNSET.toLong() && after >= total) 0 else cache.getCachedLength(f.key, after, Long.MAX_VALUE).coerceAtLeast(0)
                val end = after + beyond
                val cached = own + beyond
                val complete = total != C.LENGTH_UNSET.toLong() && end >= total
                val full = cached >= f.aheadLimit
                snapshot = MediaPlayAheadState(cached, total.coerceAtLeast(0), at, rateBps.toLong(), complete, full && !complete)
                if (complete || full) {
                    idle(IDLE_MS)
                    continue
                }
                val length = if (total == C.LENGTH_UNSET.toLong()) piece else minOf(piece, total - end)
                load(f, end, length)
            }
        } finally {
            writer = null
            runCatching { cache.release() }
            dir.deleteRecursively()
            synchronized(active) { active -= dir }
        }
    }

    /** Loads [length] bytes of [f] from [from] into the space (skipping what is there), measuring the speed. */
    private fun load(f: Follow, from: Long, length: Long) {
        val spec = DataSpec.Builder().setUri(f.uri).setKey(f.key).setPosition(from).setLength(length).build()
        var fresh = 0L
        val w = CacheWriter(reading.createDataSource(), spec, null) { _, _, newBytes -> fresh += newBytes }
        writer = w
        val started = System.nanoTime()
        try {
            if (closed) return
            w.cache()
            // less than asked and no error: the file ends there
            val got = cache.getCachedLength(f.key, from, length)
            if (got in 0 until length) total(f.key, from + got)
            val seconds = (System.nanoTime() - started) / 1e9
            if (fresh > 0 && seconds > 0.05) {
                val bps = fresh * 8 / seconds
                rateBps = if (rateBps <= 0) bps else rateBps * 0.7 + bps * 0.3
                // pieces of about two seconds: the player waits at most that long for one it needs
                piece = (rateBps / 8 * PIECE_SECONDS).toLong().coerceIn(MIN_PIECE, MAX_PIECE)
            }
        } catch (e: InterruptedIOException) {
            // cancelled: jumped elsewhere, another title, the end
        } catch (e: IOException) {
            // the line went (a dead spot): again in a moment, the player says itself when it cannot go on
            idle(RETRY_MS)
        } catch (e: IllegalStateException) {
            // the space was released meanwhile
        } finally {
            writer = null
        }
    }

    /** The file's size, once known (from the server's `Content-Range`, or where it ended). */
    private fun total(key: String, bytes: Long) {
        if (bytes <= 0) return
        runCatching {
            if (ContentMetadata.getContentLength(cache.getContentMetadata(key)) == C.LENGTH_UNSET.toLong()) {
                cache.applyContentMetadataMutations(key, ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, bytes) })
            }
        }
    }

    /** The upstream of the space: learns the file's size from the answer to the first piece. */
    private inner class Measured(private val d: DataSource) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = d.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            val n = d.open(dataSpec)
            dataSpec.key?.let { key -> PlayAheadHeaders.total(d.responseHeaders)?.let { total(key, it) } }
            return n
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = d.read(buffer, offset, length)

        override fun getUri(): Uri? = d.uri

        override fun getResponseHeaders(): Map<String, List<String>> = d.responseHeaders

        override fun close() = d.close()
    }

    private fun idle(ms: Long) {
        synchronized(signal) {
            if (!closed) signal.wait(ms)
        }
    }

    /**
     * The player's source of one request: the followed file in pieces of [PLAYER_PIECE] (the loader keeps no lock
     * beyond the piece it reads, so the loading can go on ahead of it), else straight through.
     */
    private inner class Source : DataSource {
        private val listeners = ArrayList<TransferListener>()
        private var inner: DataSource? = null
        private var spec: DataSpec? = null
        private var chunked = false
        private var position = 0L
        private var remaining = 0L
        private var pieceLeft = 0L
        private var last = false

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            val f = follow
            val key = f?.takeIf { !closed && MediaPlayAhead.cacheKey(dataSpec.uri.toString()) == it.key }?.key
            if (key == null) {
                chunked = false
                val ds = upstream.createDataSource().also { d -> listeners.forEach(d::addTransferListener) }
                inner = ds
                return ds.open(dataSpec)
            }
            chunked = true
            val ds = reading.createDataSource().also { d -> listeners.forEach(d::addTransferListener) }
            inner = ds
            spec = dataSpec.buildUpon().setKey(key).build()
            position = dataSpec.position
            remaining = dataSpec.length
            last = false
            openPiece()
            val total = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            return when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                last -> pieceLeft
                total != C.LENGTH_UNSET.toLong() -> (total - dataSpec.position).coerceAtLeast(0)
                else -> C.LENGTH_UNSET.toLong()
            }
        }

        private fun openPiece() {
            val s = spec!!
            val want = if (remaining == C.LENGTH_UNSET.toLong()) PLAYER_PIECE else minOf(remaining, PLAYER_PIECE)
            reads(s.key!!, position)
            val got = inner!!.open(s.buildUpon().setPosition(position).setLength(want).build())
            pieceLeft = if (got == C.LENGTH_UNSET.toLong()) want else got
            // shorter than asked: the file ends here
            if (pieceLeft < want) {
                last = true
                total(s.key!!, position + pieceLeft)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!chunked) return inner!!.read(buffer, offset, length)
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            if (pieceLeft == 0L) {
                if (last) return C.RESULT_END_OF_INPUT
                inner!!.close()
                openPiece()
                if (pieceLeft == 0L) return C.RESULT_END_OF_INPUT
            }
            val n = inner!!.read(buffer, offset, minOf(length.toLong(), pieceLeft).toInt())
            if (n == C.RESULT_END_OF_INPUT) {
                last = true
                pieceLeft = 0
                return C.RESULT_END_OF_INPUT
            }
            position += n
            pieceLeft -= n
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
            return n
        }

        override fun getUri(): Uri? = inner?.uri ?: spec?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = inner?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                inner?.close()
            } finally {
                inner = null
                spec = null
            }
        }
    }

    companion object {
        /** The player's pieces of the followed file. */
        const val PLAYER_PIECE = 2L shl 20

        /** The loading's pieces: at first, at least, at most. */
        const val FIRST_PIECE = 1L shl 20
        const val MIN_PIECE = 512L shl 10
        const val MAX_PIECE = 8L shl 20
        const val PIECE_SECONDS = 2.0

        private const val SCRATCH = 64 * 1024
        private const val IDLE_MS = 500L
        private const val RETRY_MS = 2_000L

        /** The spaces of the playbacks running now (in this process). */
        private val active = HashSet<File>()

        /** The folder of the spaces (in the app's cache: Android may clear it, nothing is lost). */
        fun root(cacheDir: File): File = File(cacheDir, "media-ahead")

        /**
         * Deletes what earlier runs left in [root] (the process ended during a playback: its key went with it, so
         * the pieces cannot be read anyway); the spaces of playbacks running now stay. At the app's start and before
         * each new space. Returns how many spaces went.
         */
        fun sweep(root: File): Int = synchronized(active) { sweepLocked(root) }

        private fun sweepLocked(root: File): Int {
            val left = root.listFiles()?.filter { it !in active }.orEmpty()
            left.forEach { it.deleteRecursively() }
            return left.size
        }
    }
}

/** The size of a file from an answer to a part of it. Pure, unit-tested. */
object PlayAheadHeaders {
    /** `Content-Range: bytes 0-1023/52428800` → 52428800; null without (or `*`). */
    fun total(headers: Map<String, List<String>>): Long? {
        val value = headers.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value?.firstOrNull() ?: return null
        return value.substringAfterLast('/', "").trim().toLongOrNull()?.takeIf { it > 0 }
    }
}

/**
 * Makes room in a [PlayAhead]'s space: first what belongs to another file (the title before), then what lies furthest
 * behind the place the player reads (seen long ago), only then what lies furthest ahead. Never the pieces right at
 * that place. Not by age (LRU): a piece loaded ahead long ago would go before the one just seen.
 */
@OptIn(UnstableApi::class)
class PlayheadEvictor(
    private val maxBytes: Long,
    /** The key and byte the player reads at now. */
    private val position: () -> Pair<String?, Long>,
) : CacheEvictor {
    private val spans = HashSet<CacheSpan>()
    private var size = 0L

    override fun requiresCacheSpanTouches(): Boolean = false

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans += span
        size += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (spans.remove(span)) size -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evict(cache: Cache, needed: Long) {
        while (size + needed > maxBytes) {
            val (key, at) = position()
            val v = victim(spans, key, at) ?: return
            // removing calls onSpanRemoved
            cache.removeSpan(v)
            if (spans.remove(v)) size -= v.length
        }
    }

    companion object {
        /** Left around the place read: the piece read now and the next one. */
        private const val KEEP = 2 * PlayAhead.MAX_PIECE

        /** The span to remove first; null: only what is right at [at] is left. */
        fun victim(spans: Collection<CacheSpan>, key: String?, at: Long): CacheSpan? {
            spans.firstOrNull { it.key != key }?.let { return it }
            if (at < 0) return spans.minByOrNull { it.position }
            val behind = spans.filter { it.position + it.length <= at - KEEP }.minByOrNull { it.position }
            if (behind != null) return behind
            return spans.filter { it.position >= at + KEEP }.maxByOrNull { it.position }
        }
    }
}
