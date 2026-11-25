package com.gatecontrol.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gatecontrol.android.homecache.HomeCacheLan
import com.gatecontrol.android.media.jellyfin.JellyfinAuthorize
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import com.gatecontrol.android.media.jellyfin.JellyfinDiscovery
import com.gatecontrol.android.media.jellyfin.JellyfinLibrary
import com.gatecontrol.android.media.jellyfin.JellyfinServerHint
import com.gatecontrol.android.media.jellyfin.JellyfinSignIn
import com.gatecontrol.android.media.share.ShareFs
import com.gatecontrol.android.media.share.ShareLibrary
import com.gatecontrol.android.media.share.SmbFs
import com.gatecontrol.android.media.share.WebDavFs
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
 * Where the device is for its media: at home (the server's home-network check, as the home cache) or away, and the
 * bandwidth from the NAS measured just now ("Unterwegs · 18 Mbit/s"). [atHome] null: not known yet.
 */
data class MediaLink(val atHome: Boolean? = null, val bps: Long? = null, val measuredAt: Long = 0)

/**
 * The media of the own NAS for an app (GateControl TV, later the phone app): the stored sources, one [MediaLibrary]
 * per source (a share set up as SMB and as WebDAV is one: at home SMB, away WebDAV), the connection (at home straight
 * through the Wi-Fi, also while the tunnel is up; away through the tunnel), the bandwidth, what the device plays
 * ([MediaDevice.caps]), the posters and the progress reports (which outlive the player). Shared by the pages, the
 * start page's "Weiterschauen" and the player. No UI state: the pages keep theirs ([MediaBrowse], [MediaSetup]).
 */
@Singleton
class MediaHub @Inject constructor(
    val store: MediaSourceStore,
    private val homeLan: HomeCacheLan,
    private val device: MediaDevice,
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

    /** Per source (a pair under its SMB source): the libraries by their access and network, made once each. */
    private val libraries = ConcurrentHashMap<String, Pair<String, MediaLibrary>>()

    private val _link = MutableStateFlow(MediaLink())
    val link: StateFlow<MediaLink> = _link.asStateFlow()

    private val _current = MutableStateFlow<String?>(null)

    /** The Jellyfin server the page shows ("▲ Quelle wechseln"); the first one when none was chosen. Shares show always. */
    val current: StateFlow<String?> = _current.asStateFlow()

    val quality: StateFlow<MediaQualitySetting> get() = device.quality

    fun setQuality(value: MediaQualitySetting) = device.setQuality(value)

    fun currentSource(): MediaSource? {
        val list = store.sources.value.filter { it.kind == MediaSourceKind.JELLYFIN }
        return list.firstOrNull { it.id == _current.value } ?: list.firstOrNull { it.token != null } ?: list.firstOrNull()
    }

    /** The shares the page shows, a pair once (as its SMB source). */
    fun shares(): List<MediaSource> = MediaLogic.shares(store.sources.value)

    fun select(id: String) {
        _current.value = id
    }

    val info: MediaClientInfo by lazy {
        MediaClientInfo(client = device.clientName, device = device.deviceName, deviceId = store.deviceId, version = device.version)
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
        if (source.kind.share) return shareLibrary(source)
        val key = "${source.token.hashCode()}:${source.address}:${_link.value.atHome}"
        libraries[source.id]?.let { (k, lib) -> if (k == key) return lib }
        val lib: MediaLibrary = JellyfinLibrary(source, http(MediaAddress.host(source.address)), info, ::caps)
        libraries.put(source.id, key to lib)?.second?.close()
        return lib
    }

    /**
     * A share's library: of a pair at home the SMB source, away the WebDAV one (decision 9), the titles always under
     * the SMB source's id and its progress. The old one stays open for a player still reading from it until the next
     * change.
     */
    private fun shareLibrary(source: MediaSource): MediaLibrary {
        val list = store.sources.value
        val partner = source.pairWith?.let { id -> list.firstOrNull { it.id == id && it.pairWith == source.id } }
        val smb = listOfNotNull(source, partner).firstOrNull { it.kind == MediaSourceKind.SMB }
        val webdav = listOfNotNull(source, partner).firstOrNull { it.kind == MediaSourceKind.WEBDAV }
        val primary = smb ?: source
        val atHome = _link.value.atHome
        val use = MediaLogic.shareFor(smb, webdav, atHome) ?: source
        val key = "${use.id}:${use.address}:${use.userName}:${use.password.hashCode()}:$atHome"
        val group = "share:${primary.id}"
        libraries[group]?.let { (k, lib) -> if (k == key) return lib }
        val lib = ShareLibrary(primary, shareFs(use), store, primary.id, seasonName = device::seasonName)
        libraries.put(group, key to lib)?.second?.let { old -> retired.put(group, old)?.let { gone -> scope.launch { gone.close() } } }
        return lib
    }

    /** The library a share had before the network changed (a running player may still read from it). */
    private val retired = ConcurrentHashMap<String, MediaLibrary>()

    /** The files of share [s]: SMB at home bound to the Wi-Fi; WebDAV through [http]. */
    private fun shareFs(s: MediaSource): ShareFs = when (s.kind) {
        MediaSourceKind.SMB -> {
            val a = MediaAddress.parseSmb(s.address) ?: error("not an SMB address")
            val factory = if (_link.value.atHome == true) homeLan.lanSocketFactory(a.host) else null
            SmbFs(a, s.userName, s.password, factory)
        }
        else -> WebDavFs(s.address, http(MediaAddress.host(s.address)), s.userName, s.password)
    }

    /** "Verbinden" of a new share: its root can be read with this access. */
    suspend fun tryShare(kind: MediaSourceKind, address: String, user: String?, password: String?): MediaResult<Unit> {
        val s = MediaSource("try", kind, "", address, userName = user, password = password)
        val fs = try {
            shareFs(s)
        } catch (e: IllegalStateException) {
            return MediaResult.NotFound
        }
        return try {
            when (val r = fs.list("")) {
                is MediaResult.Ok -> MediaResult.Ok(Unit)
                is MediaResult.Offline -> r
                is MediaResult.Unauthorized -> r
                is MediaResult.NotFound -> r
                is MediaResult.Failed -> r
            }
        } finally {
            withContext(Dispatchers.IO) { fs.close() }
        }
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
            _link.value = MediaLink(atHome = true, bps = null, measuredAt = now)
            return@withLock
        }
        val fresh = old.atHome == false && old.bps != null && now - old.measuredAt < MEASURE_EVERY_MS
        if (fresh && !force) return@withLock
        _link.value = old.copy(atHome = false)
        // a share has no bandwidth test: it plays the original in any case
        val bps = source?.takeIf { it.kind == MediaSourceKind.JELLYFIN && it.token != null }?.let { library(it).measure() }
        _link.value = MediaLink(atHome = false, bps = bps ?: old.bps.takeIf { old.atHome == false }, measuredAt = now)
    }

    /** What this device plays itself (the device profile for Jellyfin). */
    fun caps(): JellyfinDeviceCaps = device.caps()

    /**
     * "Gerät anmelden": confirms the Quick Connect code another device shows (a TV, a PC) with [source]'s access, so
     * it signs in without the Jellyfin app. Jellyfin 10.8 or newer, Quick Connect switched on.
     */
    suspend fun authorize(source: MediaSource, code: String): JellyfinAuthorize {
        if (source.kind != MediaSourceKind.JELLYFIN || source.token == null) return JellyfinAuthorize.SIGNED_OUT
        val lib = library(source) as? JellyfinLibrary ?: return JellyfinAuthorize.FAILED
        return lib.authorize(code)
    }

    /** Whether this device is in a Wi-Fi / Ethernet network at all (else the search cannot find anything). */
    fun hasLan(): Boolean = homeLan.lanLinks().isNotEmpty()

    /** Jellyfin servers that answer in the home network (the Wi-Fi's broadcast, not through the tunnel). */
    suspend fun discover(): List<JellyfinServerHint> {
        val links = homeLan.lanLinks()
        val targets = links.mapNotNull { JellyfinDiscovery.broadcastOf(it.address, it.prefix) } + "255.255.255.255"
        val network = links.firstOrNull()?.network
        return JellyfinDiscovery.discover(targets, bind = { socket -> network?.bindSocket(socket) }, lan = links.map { it.address to it.prefix })
    }

    /** A poster or backdrop of [url] (with [headers]); null when it cannot be loaded. 16-bit colour: half the memory (a TV stick has little). */
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

    /** A share's picture (its bytes): scaled down to about [maxHeight] px, 16-bit colour. */
    suspend fun decode(bytes: ByteArray, maxHeight: Int): Bitmap? = withContext(Dispatchers.Default) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= maxHeight) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
        }.getOrNull()
    }

    /** A source was removed: its connection goes. */
    fun forget(id: String) {
        listOf(id, "share:$id").forEach { k -> libraries.remove(k)?.second?.let { lib -> scope.launch { lib.close() } } }
        retired.remove("share:$id")?.let { lib -> scope.launch { lib.close() } }
    }

    /** Fire and forget (a progress report), on [scope]. */
    fun later(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    /** Unpaired (or signed out of the app): the sources are gone with the encrypted storage. */
    fun clear() {
        store.forgetAll()
        val open = libraries.values.map { it.second } + retired.values
        libraries.clear()
        retired.clear()
        scope.launch { open.forEach { runCatching { it.close() } } }
        _current.value = null
    }

    companion object {
        /** Away the bandwidth is measured again after this long. */
        const val MEASURE_EVERY_MS = 5 * 60_000L
        private const val MAX_PICTURE_BYTES = 4 * 1024 * 1024
    }
}
