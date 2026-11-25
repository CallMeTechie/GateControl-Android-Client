package com.gatecontrol.android.media.offline

import com.gatecontrol.android.drive.vault.DriveVaultCrypto
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTrackChoice
import com.gatecontrol.android.media.MediaTracks
import com.gatecontrol.android.media.jellyfin.JellyfinLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/** How the downloads reach a source (the hub in the app, a fake server in the tests). */
interface OfflineLink {
    fun source(id: String): MediaSource?

    /** [source]'s library after home / away was checked; null when it is not a Jellyfin server. */
    suspend fun library(source: MediaSource): JellyfinLibrary?

    /** The connection to [source] (at home straight through the Wi-Fi, away the tunnel). */
    fun http(source: MediaSource): OkHttpClient

    fun trackChoice(seriesId: String): MediaTrackChoice?
}

/**
 * Loads the waiting downloads one after the other (the worker runs it, only on Wi-Fi): asks Jellyfin whether the
 * user may download, the title's file, then the original (`Items/{id}/Download`, going on after a break with a range)
 * or the file the NAS converts as it goes (MPEG-TS, from the start after a break), encrypted as it comes; then the
 * text subtitles and the poster. Holds to the storage limit and the phone's free room. [limit]: bytes allowed in all;
 * [free]: bytes free on the phone.
 */
class MediaOfflineDownloader(
    private val store: MediaOfflineStore,
    private val link: OfflineLink,
    private val limit: () -> Long,
    private val free: () -> Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { DONE, RETRY }

    private sealed interface Step {
        data object Next : Step
        data object Retry : Step
    }

    /** All waiting downloads; [RETRY] when the server cannot be reached now (the worker tries again later). */
    suspend fun runAll(onProgress: (OfflineItem) -> Unit = {}): Outcome {
        val tried = HashSet<String>()
        while (true) {
            val next = store.items.value.filter { it.active && it.id !in tried }.minByOrNull { it.addedAt } ?: return Outcome.DONE
            tried += next.id
            when (one(next, onProgress)) {
                Step.Retry -> return Outcome.RETRY
                Step.Next -> Unit
            }
        }
    }

    private fun fail(item: OfflineItem, why: OfflineFailure): Step {
        Timber.d("Medien offline: download stopped (%s)", why.wire)
        store.update(item.id) { it.copy(state = OfflineState.FAILED, failure = why) }
        return Step.Next
    }

    private suspend fun one(start: OfflineItem, onProgress: (OfflineItem) -> Unit): Step {
        val source = link.source(start.sourceId) ?: return fail(start, OfflineFailure.GONE)
        if (source.kind != MediaSourceKind.JELLYFIN) return fail(start, OfflineFailure.GONE)
        if (source.token == null) return fail(start, OfflineFailure.SIGNED_OUT)
        val lib = link.library(source) ?: return fail(start, OfflineFailure.FAILED)
        when (val allowed = lib.canDownload()) {
            is MediaResult.Ok -> if (!allowed.value) return fail(start, OfflineFailure.NOT_ALLOWED)
            MediaResult.Offline -> return Step.Retry
            MediaResult.Unauthorized -> return fail(start, OfflineFailure.SIGNED_OUT)
            else -> Unit
        }
        val detail = when (val d = lib.detail(start.itemId)) {
            is MediaResult.Ok -> d.value
            MediaResult.Offline -> return Step.Retry
            MediaResult.NotFound -> return fail(start, OfflineFailure.GONE)
            MediaResult.Unauthorized -> return fail(start, OfflineFailure.SIGNED_OUT)
            is MediaResult.Failed -> return fail(start, OfflineFailure.FAILED)
        }
        val version = detail.version ?: return fail(start, OfflineFailure.GONE)
        val quality = if (start.quality.converted && !MediaOfflineLogic.convertGains(start.quality, version)) OfflineQuality.ORIGINAL else start.quality
        val choice = detail.title.seriesId?.let(link::trackChoice)
        val audioIndex = start.audioIndex ?: MediaTracks.pickAudio(version, choice)
        val burn = if (quality.converted) MediaTracks.pickSubtitle(version, choice)?.takeIf { i -> version.subtitles.any { it.index == i && it.picture } } else null
        val runtime = detail.title.runtimeMs ?: version.runtimeMs
        val expected = MediaOfflineLogic.estimate(quality, runtime, version) ?: 0L
        MediaOfflineLogic.fits((expected - start.loaded).coerceAtLeast(0), store.used(except = start.id), limit(), free())?.let { return fail(start, it) }
        val subs = MediaOfflineLogic.sideSubtitles(version, quality, burn)
        val kept = if (quality.converted) {
            MediaOfflineLogic.convertedVersion(version, quality, version.audio.firstOrNull { it.index == audioIndex }, subs, runtime)
        } else {
            version
        }
        // the title as the server has it now, the progress as it was kept here
        val title = detail.title.copy(positionMs = start.positionMs, played = start.played, playedAt = start.playedAt.takeIf { it > 0 } ?: detail.title.playedAt)
        var item = store.update(start.id) {
            it.copy(state = OfflineState.LOADING, failure = null, quality = quality, expected = expected, version = kept, subtitles = subs, audioIndex = audioIndex, title = title)
        } ?: return Step.Next
        onProgress(item)

        val url = if (quality.converted) {
            lib.convertedUrl(item.itemId, version.id, quality.videoBps!!, quality.maxWidth, quality.maxHeight, audioIndex, burn)
        } else {
            lib.downloadUrl(item.itemId, version.id)
        }
        val http = link.http(source)
        val headers = lib.headers()
        try {
            when (val r = video(item, url, http, headers, resumable = !quality.converted, onProgress)) {
                null -> Unit
                else -> return r
            }
        } catch (e: CancellationException) {
            // the worker was stopped (no Wi-Fi any more): it waits again, a cut-off original goes on later
            withContext(NonCancellable) {
                store.update(item.id) { if (it.state == OfflineState.LOADING) it.copy(state = OfflineState.QUEUED) else it }
            }
            throw e
        }
        item = store.get(item.id) ?: return Step.Next
        // the text subtitles (WebVTT) and the poster; one that cannot be loaded is left out
        val loadedSubs = item.subtitles.filter { s ->
            val bytes = small(http, lib.subtitleUrl(item.itemId, version.id, s.index), headers) ?: return@filter false
            store.writeSmall(item.id, MediaOfflineLogic.subPart(s.index), bytes)
            true
        }
        lib.imageUrl(item.title, backdrop = false, maxHeight = 450)?.let { u -> small(http, u, headers)?.let { store.writeSmall(item.id, MediaOfflineStore.POSTER, it) } }
        lib.imageUrl(item.title, backdrop = true, maxHeight = 720)?.let { u -> small(http, u, headers)?.let { store.writeSmall(item.id, MediaOfflineStore.BACKDROP, it) } }
        val size = store.bytesOf(item.id)
        val done = store.update(item.id) {
            val v = if (quality.converted) it.version?.copy(streams = it.version.streams.filter { s -> s.type != com.gatecontrol.android.media.MediaStream.Type.SUBTITLE || loadedSubs.any { l -> l.index == s.index } }) else it.version
            it.copy(state = OfflineState.READY, subtitles = loadedSubs, size = size, loaded = size, expected = size, version = v)
        }
        done?.let(onProgress)
        return Step.Next
    }

    /** The video into its encrypted file; null when it is complete, else what to do next. */
    private suspend fun video(
        item: OfflineItem,
        url: String,
        http: OkHttpClient,
        headers: Map<String, String>,
        resumable: Boolean,
        onProgress: (OfflineItem) -> Unit,
    ): Step? = withContext(Dispatchers.IO) {
        val file = store.file(item.id, MediaOfflineLogic.VIDEO)
        file.parentFile?.mkdirs()
        val step = DriveVaultCrypto.CHUNK.toLong() + DriveVaultCrypto.TAG
        var chunks = if (resumable && file.isFile) DriveVaultCrypto.ContentWriter.chunksIn(file.length()) else 0L
        if (chunks > 0) RandomAccessFile(file, "rw").use { it.setLength(DriveVaultCrypto.HEADER + chunks * step) } else file.delete()
        val from = chunks * DriveVaultCrypto.CHUNK
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (from > 0) header("Range", "bytes=$from-")
        }.build()
        val call = http.newBuilder().readTimeout(READ_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request)
        val job = currentCoroutineContext().job
        val stop = job.invokeOnCompletion { if (job.isCancelled) call.cancel() }
        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                return@withContext Step.Retry
            }
            response.use { r ->
                when {
                    r.code == 401 -> return@withContext fail(item, OfflineFailure.SIGNED_OUT)
                    r.code == 403 -> return@withContext fail(item, OfflineFailure.NOT_ALLOWED)
                    r.code == 404 -> return@withContext fail(item, OfflineFailure.GONE)
                    !r.isSuccessful -> return@withContext fail(item, OfflineFailure.FAILED)
                }
                val resumed = from > 0 && r.code == 206
                if (!resumed) {
                    chunks = 0
                    file.delete()
                }
                val total = r.body.contentLength().takeIf { it > 0 }?.let { if (resumed) it + from else it }
                val expected = total ?: item.expected
                if (total != null && total != item.expected) {
                    MediaOfflineLogic.fits(total, store.used(except = item.id), limit(), free())?.let { return@withContext fail(item, it) }
                }
                val out = FileOutputStream(file, resumed)
                out.buffered(BUFFER).use { o ->
                    val writer = if (resumed) {
                        val head = ByteArray(DriveVaultCrypto.HEADER)
                        RandomAccessFile(file, "r").use { it.readFully(head) }
                        DriveVaultCrypto.ContentWriter.resume(item.key, item.id, head, chunks, o, MediaOfflineLogic.PREFIX)
                    } else {
                        DriveVaultCrypto.ContentWriter.start(item.key, item.id, 1, o, MediaOfflineLogic.PREFIX)
                    }
                    writer.use { w ->
                        val buf = ByteArray(BUFFER)
                        val input = r.body.byteStream()
                        var lastSaved = now()
                        try {
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                w.write(buf, 0, n)
                                currentCoroutineContext().ensureActive()
                                val t = now()
                                if (t - lastSaved >= SAVE_EVERY_MS) {
                                    lastSaved = t
                                    // removed meanwhile ("Abbrechen"): its folder is gone already
                                    val u = store.update(item.id) { it.copy(loaded = w.written, expected = maxOf(expected, w.written)) } ?: return@withContext Step.Next
                                    onProgress(u)
                                    if (total == null && store.used(except = item.id) + w.written > limit()) return@withContext fail(item, OfflineFailure.LIMIT)
                                }
                            }
                        } catch (e: IOException) {
                            currentCoroutineContext().ensureActive()
                            store.update(item.id) { it.copy(state = OfflineState.QUEUED, loaded = if (resumable) w.written else 0) }
                            if (!resumable) file.delete()
                            return@withContext Step.Retry
                        }
                        if (total != null && w.written < total) {
                            store.update(item.id) { it.copy(state = OfflineState.QUEUED, loaded = w.written) }
                            return@withContext Step.Retry
                        }
                        w.finish()
                        store.update(item.id) { it.copy(loaded = w.written, expected = w.written) }
                    }
                }
                null
            }
        } finally {
            stop.dispose()
        }
    }

    /** A subtitle or a picture (at most a few MB); null when it could not be loaded. */
    private suspend fun small(http: OkHttpClient, url: String, headers: Map<String, String>): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val len = r.body.contentLength()
                if (len > MAX_SMALL) return@withContext null
                r.body.bytes().takeIf { it.isNotEmpty() && it.size <= MAX_SMALL }
            }
        } catch (e: IOException) {
            null
        }
    }

    private companion object {
        const val BUFFER = 256 * 1024
        const val SAVE_EVERY_MS = 2_000L
        const val READ_TIMEOUT_S = 120L
        const val MAX_SMALL = 5L * 1024 * 1024
    }
}
