package com.gatecontrol.android.tv.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.gatecontrol.android.homecache.HomeCacheLan
import com.gatecontrol.android.media.MediaAddress
import com.gatecontrol.android.media.MediaClientInfo
import com.gatecontrol.android.media.MediaLibrary
import com.gatecontrol.android.media.MediaQualitySetting
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaSourceStore
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import com.gatecontrol.android.media.jellyfin.JellyfinDiscovery
import com.gatecontrol.android.media.jellyfin.JellyfinLibrary
import com.gatecontrol.android.media.jellyfin.JellyfinServerHint
import com.gatecontrol.android.media.jellyfin.JellyfinSignIn
import com.gatecontrol.android.tv.BuildConfig
import com.gatecontrol.android.tv.TvSettings
import com.gatecontrol.android.tv.pair.TvPairingApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the TV is for its media: at home (the server's home-network check, as the home cache) or away, and the
 * bandwidth from the NAS measured just now ("Unterwegs · 18 Mbit/s"). [atHome] null: not known yet.
 */
data class TvMediaLink(val atHome: Boolean? = null, val bps: Long? = null, val measuredAt: Long = 0)

/**
 * The media of GateControl TV (stage 2, "Medien"): the stored sources, one [MediaLibrary] per source, the connection
 * (at home straight through the Wi-Fi, also while the tunnel is up; away through the tunnel), the bandwidth, what the
 * HDMI sink plays, the posters and the progress reports (which outlive the player). Shared by the page, the start
 * page's "Weiterschauen" and the player.
 */
@Singleton
class TvMedia @Inject constructor(
    @ApplicationContext private val context: Context,
    val store: MediaSourceStore,
    private val homeLan: HomeCacheLan,
    private val settings: TvSettings,
    private val pairing: TvPairingApi,
) {
    /** Reports and other work that must finish after a page or the player is gone. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    private val clients = ConcurrentHashMap<String, OkHttpClient>()
    private val libraries = ConcurrentHashMap<String, Pair<String, MediaLibrary>>()

    private val _link = MutableStateFlow(TvMediaLink())
    val link: StateFlow<TvMediaLink> = _link.asStateFlow()

    private val _current = MutableStateFlow<String?>(null)

    /** The source the page shows ("▲ Quelle wechseln"); the first one when none was chosen. */
    val current: StateFlow<String?> = _current.asStateFlow()

    val quality: StateFlow<MediaQualitySetting> get() = settings.mediaQuality

    fun setQuality(value: MediaQualitySetting) = settings.setMediaQuality(value)

    fun currentSource(): MediaSource? {
        val list = store.sources.value
        return list.firstOrNull { it.id == _current.value } ?: list.firstOrNull { it.token != null } ?: list.firstOrNull()
    }

    fun select(id: String) {
        _current.value = id
    }

    val info: MediaClientInfo by lazy {
        MediaClientInfo(client = "GateControl TV", device = pairing.deviceName(), deviceId = store.deviceId, version = BuildConfig.VERSION_NAME)
    }

    /** OkHttp to [host]: at home bound to the Wi-Fi (straight through the LAN), away the system's way (the tunnel). */
    fun http(host: String?): OkHttpClient {
        val home = _link.value.atHome == true && host != null && MediaAddress.isHomeHost(host)
        val factory = if (home) homeLan.lanSocketFactory(host) else null
        if (factory == null) return base
        return clients.getOrPut("lan:$host") { base.newBuilder().socketFactory(factory).build() }
    }

    /** The library of [source] (made again when its access or the network changed). */
    fun library(source: MediaSource): MediaLibrary {
        val key = "${source.token.hashCode()}:${source.address}:${_link.value.atHome}"
        libraries[source.id]?.let { (k, lib) -> if (k == key) return lib }
        val lib: MediaLibrary = when (source.kind) {
            // SMB and WebDAV follow in a later version behind the same interface
            MediaSourceKind.JELLYFIN, MediaSourceKind.SMB, MediaSourceKind.WEBDAV ->
                JellyfinLibrary(source, http(MediaAddress.host(source.address)), info, ::caps)
        }
        libraries[source.id] = key to lib
        return lib
    }

    fun signIn(address: String): JellyfinSignIn = JellyfinSignIn(address, http(MediaAddress.host(address)), info)

    private val checking = Mutex()

    /**
     * Home or away now, and away the bandwidth from [source] (at most every [MEASURE_EVERY_MS] unless [force]).
     * Never throws except for cancellation.
     */
    suspend fun check(source: MediaSource? = currentSource(), force: Boolean = false) = checking.withLock {
        val home = try {
            homeLan.localHeader() != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        val now = System.currentTimeMillis()
        val old = _link.value
        if (home) {
            _link.value = TvMediaLink(atHome = true, bps = null, measuredAt = now)
            return@withLock
        }
        val fresh = old.atHome == false && old.bps != null && now - old.measuredAt < MEASURE_EVERY_MS
        if (fresh && !force) return@withLock
        _link.value = old.copy(atHome = false)
        val bps = source?.takeIf { it.token != null }?.let { library(it).measure() }
        _link.value = TvMediaLink(atHome = false, bps = bps ?: old.bps.takeIf { old.atHome == false }, measuredAt = now)
    }

    /** What the TV plays itself: the HDMI sink's sound formats (DTS and Dolby to the AV receiver). */
    @OptIn(UnstableApi::class)
    fun caps(): JellyfinDeviceCaps {
        val audio = runCatching { AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null) }.getOrNull()
        val pass = buildSet {
            if (audio?.supportsEncoding(C.ENCODING_AC3) == true) add("ac3")
            if (audio?.supportsEncoding(C.ENCODING_E_AC3) == true) add("eac3")
            if (audio?.supportsEncoding(C.ENCODING_DTS) == true) add("dts")
            if (audio?.supportsEncoding(C.ENCODING_DOLBY_TRUEHD) == true) add("truehd")
        }
        val channels = audio?.maxChannelCount?.takeIf { it > 0 }?.coerceAtMost(8) ?: 6
        return JellyfinDeviceCaps(passthrough = pass, maxAudioChannels = channels.coerceAtLeast(2))
    }

    /** Jellyfin servers that answer in the home network (the Wi-Fi's broadcast, not through the tunnel). */
    suspend fun discover(): List<JellyfinServerHint> {
        val links = homeLan.lanLinks()
        val targets = links.mapNotNull { JellyfinDiscovery.broadcastOf(it.address, it.prefix) } + "255.255.255.255"
        val network = links.firstOrNull()?.network
        return JellyfinDiscovery.discover(targets, bind = { socket -> network?.bindSocket(socket) })
    }

    /** A poster or backdrop of [url] (with [headers]); null when it cannot be loaded. 16-bit colour: memory of a stick. */
    suspend fun picture(url: String, headers: Map<String, String>, host: String?): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            http(host).newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val bytes = r.body.bytes()
                if (bytes.size > MAX_PICTURE_BYTES) return@withContext null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Medien: picture not loaded (%s)", e.javaClass.simpleName)
            null
        }
    }

    /** Fire and forget (a progress report), on [scope]. */
    fun later(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    /** Unpaired: the sources are gone with the encrypted storage. */
    fun clear() {
        store.forgetAll()
        libraries.clear()
        _current.value = null
    }

    companion object {
        /** Away the bandwidth is measured again after this long. */
        const val MEASURE_EVERY_MS = 5 * 60_000L
        private const val MAX_PICTURE_BYTES = 4 * 1024 * 1024
    }
}
