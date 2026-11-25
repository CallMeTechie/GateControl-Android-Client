package com.gatecontrol.android.media.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.gatecontrol.android.familie.emergency.AesGcmCipher
import com.gatecontrol.android.media.MediaAddress
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaPhoneSettings
import com.gatecontrol.android.media.MediaPlan
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaTrackChoice
import com.gatecontrol.android.media.MediaVersion
import com.gatecontrol.android.media.PlayMethod
import com.gatecontrol.android.media.jellyfin.JellyfinLibrary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Whether Jellyfin lets the user download, as the detail page shows it. */
enum class OfflineAllowed { YES, NO, UNKNOWN }

/**
 * Download for offline (question 22 a) for the whole app: Jellyfin only, the original or converted before, only on
 * Wi-Fi ([MediaOfflineWork]), up to the storage limit of Medien › Quellen, encrypted in the app's own storage
 * ([MediaOfflineStore]). The player plays a ready download from the phone ([localPlan], [MediaOfflineDataSource]);
 * what is watched there is kept ([record]) and told to Jellyfin at the next contact ([sync]).
 */
@Singleton
class MediaOffline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hub: MediaHub,
    private val settings: MediaPhoneSettings,
) {
    val store: MediaOfflineStore by lazy {
        MediaOfflineStore(File(context.noBackupFilesDir, DIR), AesGcmCipher { AesGcmCipher.keystoreKey(KEY_ALIAS) })
    }

    val items: StateFlow<List<OfflineItem>> get() = store.items

    private val _wifi = MutableStateFlow(true)

    /** On Wi-Fi now (a waiting download says "wartet auf WLAN" without). */
    val wifi: StateFlow<Boolean> = _wifi.asStateFlow()

    private val allowed = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    init {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching
            _wifi.value = unmetered(cm)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _wifi.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }

                override fun onLost(network: Network) {
                    _wifi.value = unmetered(cm)
                }
            })
        }.onFailure { Timber.d("Medien offline: network not watched (%s)", it.javaClass.simpleName) }
    }

    private fun unmetered(cm: ConnectivityManager): Boolean =
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    fun limitBytes(): Long = MediaOfflineLogic.gb(settings.offlineLimitGb.value)

    fun usedBytes(): Long = store.used()

    private fun freeBytes(): Long = runCatching { context.noBackupFilesDir.usableSpace }.getOrDefault(-1)

    fun item(sourceId: String, itemId: String): OfflineItem? = store.find(sourceId, itemId)

    /** The ready download of [title]; null: none. */
    fun ready(sourceId: String, itemId: String): OfflineItem? = item(sourceId, itemId)?.takeIf { it.ready }

    /**
     * Whether Jellyfin lets [source]'s user download (asked at most every few minutes). A share never: only Jellyfin
     * is loaded for offline.
     */
    suspend fun canDownload(source: MediaSource): OfflineAllowed {
        if (source.kind != MediaSourceKind.JELLYFIN || source.token == null) return OfflineAllowed.NO
        val now = System.currentTimeMillis()
        allowed[source.id]?.let { (at, yes) -> if (now - at < ASK_EVERY_MS) return if (yes) OfflineAllowed.YES else OfflineAllowed.NO }
        val lib = hub.library(source) as? JellyfinLibrary ?: return OfflineAllowed.NO
        return when (val r = lib.canDownload()) {
            is MediaResult.Ok -> {
                allowed[source.id] = now to r.value
                if (r.value) OfflineAllowed.YES else OfflineAllowed.NO
            }
            else -> OfflineAllowed.UNKNOWN
        }
    }

    /** [titles] (a film, an episode, the episodes of a season) to be loaded in [quality]; the worker starts on Wi-Fi. */
    fun enqueue(titles: List<MediaTitle>, quality: OfflineQuality, audioIndex: Int? = null) {
        titles.filter { it.playable }.forEach { t ->
            val item = store.add(t, quality)
            if (audioIndex != null && titles.size == 1 && item.state == OfflineState.QUEUED) store.update(item.id) { it.copy(audioIndex = audioIndex) }
        }
        MediaOfflineWork.download(context)
    }

    /** "Abbrechen" / "Löschen": the download and its files go (a running one stops at its next block). */
    fun delete(id: String) {
        store.remove(id)
    }

    /** "Nochmal" of a failed one. */
    fun retry(id: String) {
        val item = store.get(id) ?: return
        store.add(item.title, item.quality)
        MediaOfflineWork.download(context)
    }

    /** A source was removed in Medien › Quellen: its downloads go with it. */
    fun removeSource(sourceId: String) = store.removeSource(sourceId)

    /** Waiting downloads still have their worker (after a restart of the phone or the app). */
    fun ensure() {
        if (store.items.value.any { it.active }) MediaOfflineWork.download(context)
        if (store.items.value.any { it.dirty }) MediaOfflineWork.sync(context)
    }

    /**
     * How the player plays ready download [item]: the file on the phone as it is (all its own tracks), the subtitles
     * beside it only offered.
     */
    fun localPlan(item: OfflineItem): MediaPlan = MediaPlan(
        itemId = item.itemId,
        url = MediaOfflineLogic.uri(item.id, MediaOfflineLogic.VIDEO),
        method = PlayMethod.DIRECT_PLAY,
        version = item.version ?: MediaVersion(item.itemId, supportsDirectPlay = true),
        playSessionId = null,
        hls = false,
        audioIndex = null,
        subtitleIndex = null,
        sideSubtitles = MediaOfflineLogic.sideOf(item),
    )

    /** Where [item] was left (offline or, when newer, as Jellyfin had it when it was listed). */
    fun startAt(item: OfflineItem): Long? = item.titleNow().takeIf { it.resumable }?.positionMs

    /** The progress of a download watched on the phone: kept, sent to Jellyfin at the next contact. */
    fun record(id: String, positionMs: Long, durationMs: Long, stopped: Boolean) {
        val (pos, played) = MediaOfflineLogic.watched(positionMs, durationMs)
        val now = System.currentTimeMillis()
        store.update(id) { it.copy(positionMs = pos, played = played || it.played && pos == 0L, playedAt = now, dirty = true) }
        if (stopped) MediaOfflineWork.sync(context)
    }

    /**
     * The progress watched offline to Jellyfin (unless the server has a newer one). False when a server could not be
     * reached (the worker tries again).
     */
    suspend fun sync(): Boolean {
        var allDone = true
        for (item in store.items.value.filter { it.dirty }) {
            val source = hub.store.source(item.sourceId)
            if (source == null || source.token == null) {
                store.update(item.id) { it.copy(dirty = false) }
                continue
            }
            hub.check(source)
            val lib = hub.library(source) as? JellyfinLibrary ?: continue
            val server = when (val d = lib.detail(item.itemId)) {
                is MediaResult.Ok -> d.value.title
                MediaResult.Offline -> {
                    allDone = false
                    continue
                }
                else -> {
                    store.update(item.id) { it.copy(dirty = false) }
                    continue
                }
            }
            if (!MediaOfflineLogic.shouldSend(item, server.playedAt)) {
                store.update(item.id) { it.copy(dirty = false, title = it.title.copy(positionMs = server.positionMs, played = server.played, playedAt = server.playedAt), positionMs = server.positionMs, played = server.played, playedAt = server.playedAt ?: it.playedAt) }
                continue
            }
            when (lib.saveUserData(item.itemId, item.positionMs, item.played, item.playedAt)) {
                is MediaResult.Ok -> store.update(item.id) { if (it.playedAt == item.playedAt) it.copy(dirty = false) else it }
                MediaResult.Offline -> allDone = false
                else -> store.update(item.id) { it.copy(dirty = false) }
            }
        }
        return allDone
    }

    /** The loader of the worker. */
    fun downloader(): MediaOfflineDownloader = MediaOfflineDownloader(store, link, limit = ::limitBytes, free = ::freeBytes)

    private val link = object : OfflineLink {
        override fun source(id: String): MediaSource? = hub.store.source(id)

        override suspend fun library(source: MediaSource): JellyfinLibrary? {
            hub.check(source)
            return hub.library(source) as? JellyfinLibrary
        }

        override fun http(source: MediaSource): OkHttpClient = hub.http(MediaAddress.host(source.address))

        override fun trackChoice(seriesId: String): MediaTrackChoice? = hub.store.trackChoice(seriesId)
    }

    /** The poster (or backdrop) of a download, decrypted; null: none kept. */
    fun picture(title: MediaTitle, backdrop: Boolean): ByteArray? {
        val item = store.find(title.sourceId, title.id) ?: return null
        return store.readSmall(item.id, if (backdrop) MediaOfflineStore.BACKDROP else MediaOfflineStore.POSTER)
    }

    private companion object {
        const val DIR = "media-offline"
        const val KEY_ALIAS = "gc_media_offline_v1"
        const val ASK_EVERY_MS = 10 * 60_000L
    }
}
