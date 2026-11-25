package com.gatecontrol.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gatecontrol.android.homecache.HomeCacheLan
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import com.gatecontrol.android.media.jellyfin.JellyfinJson.str
import com.gatecontrol.android.media.jellyfin.JellyfinLibrary
import com.gatecontrol.android.media.jellyfin.JellyfinSignIn
import com.gatecontrol.android.media.server.MediaHomeProbe
import com.gatecontrol.android.media.server.MediaNetworkRule
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
import android.net.Network
import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
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
 * start page's "Weiterschauen" and the player. No UI state: the pages keep theirs ([MediaBrowse]); the sources come from [com.gatecontrol.android.media.server.MediaServerSources].
 */
@Singleton
class MediaHub @Inject constructor(
    val store: MediaSourceStore,
    private val homeLan: HomeCacheLan,
    private val device: MediaDevice,
    private val vpn: MediaVpn,
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

    /** Who asks Jellyfin for [source]: a source of the server with the DeviceId and name the plugin gave this device. */
    fun infoFor(source: MediaSource): MediaClientInfo =
        if (source.deviceId != null) info.copy(deviceId = source.deviceId, device = source.deviceName ?: info.device) else info

    // --- the way (Netz-Regel): at home bound to the LAN, away only through the VPN or to the admin's HTTPS address ---

    @Volatile
    private var probes: List<MediaHomeProbe> = emptyList()

    /** The Wi-Fi / Ethernet network a home probe answered on (the LAN media go through at home). */
    @Volatile
    private var homeNetwork: Network? = null

    /** The server's network rule (`v1/sources` › `network`): its probes recognise the home network. */
    fun setNetworkRule(rule: MediaNetworkRule) {
        if (rule.probes != probes) {
            probes = rule.probes
            homeKey = null
        }
    }

    private val _gate = MutableStateFlow(MediaGate.OPEN)

    /** The VPN for media away: not needed or up, „VPN wird verbunden …“, the consent asked, or off. */
    val gate: StateFlow<MediaGate> = _gate.asStateFlow()

    fun vpnUp(): Boolean = runCatching { vpn.up() }.getOrDefault(false)

    /** The way to [source] now. */
    fun route(source: MediaSource): MediaRoute = MediaRouting.route(source, _link.value.atHome, vpnUp())

    /** Away, media go only through the VPN: refused before a packet leaves (no other way, no fallback over the internet). */
    private val blocked: OkHttpClient by lazy {
        base.newBuilder().addInterceptor(Interceptor { throw IOException("Medien: away only through the VPN") }).build()
    }

    /** OkHttp to [source] on its way now: at home bound to the LAN, away through the tunnel or to its HTTPS address, or refused. */
    fun http(source: MediaSource): OkHttpClient {
        val route = route(source)
        val host = MediaAddress.host(MediaRouting.effective(source, route).address)
        val client = when (route) {
            MediaRoute.BLOCKED -> return blocked
            MediaRoute.HOME -> lanClient(host)
            else -> base
        }
        val pin = source.certSha256?.takeIf { route != MediaRoute.DIRECT && source.kind == MediaSourceKind.WEBDAV }
        return if (pin != null) clients.getOrPut("pin:$route:$host:$pin") { MediaPinning.client(client, pin) } else client
    }

    /** OkHttp to [host] at home: bound to the Wi-Fi / Ethernet network the home probe answered on (or that holds it). */
    private fun lanClient(host: String?): OkHttpClient {
        val factory = (host?.let { homeLan.lanSocketFactory(it) } ?: homeNetwork?.socketFactory) ?: return base
        return clients.getOrPut("lan:$host:${homeNetwork?.hashCode()}") { base.newBuilder().socketFactory(factory).build() }
    }

    /** The library of [source] (made again when its access, its way or the network changed). */
    fun library(source: MediaSource): MediaLibrary {
        if (source.kind.share) return shareLibrary(source)
        val route = route(source)
        val use = MediaRouting.effective(source, route)
        val key = "${source.token.hashCode()}:${use.address}:${_link.value.atHome}:$route:${source.deviceId}"
        libraries[source.id]?.let { (k, lib) -> if (k == key) return lib }
        val lib: MediaLibrary = JellyfinLibrary(use, http(source), infoFor(source), ::caps)
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
        val pick = MediaLogic.shareFor(smb, webdav, atHome) ?: source
        val route = route(pick)
        val use = MediaRouting.effective(pick, route)
        val key = "${use.id}:${use.address}:${use.userName}:${use.password.hashCode()}:$atHome:$route"
        val group = "share:${primary.id}"
        libraries[group]?.let { (k, lib) -> if (k == key) return lib }
        val lib = ShareLibrary(primary, shareFs(use, pick), store, primary.id, seasonName = device::seasonName)
        libraries.put(group, key to lib)?.second?.let { old -> retired.put(group, old)?.let { gone -> scope.launch { gone.close() } } }
        return lib
    }

    /** The library a share had before the network changed (a running player may still read from it). */
    private val retired = ConcurrentHashMap<String, MediaLibrary>()

    /** The files of share [s] ([source]: as stored, for its way): SMB at home bound to the LAN; WebDAV through [http]. */
    private fun shareFs(s: MediaSource, source: MediaSource = s): ShareFs = when (s.kind) {
        MediaSourceKind.SMB -> {
            val a = MediaAddress.parseSmb(s.address) ?: error("not an SMB address")
            val factory = when (route(source)) {
                MediaRoute.HOME -> homeLan.lanSocketFactory(a.host) ?: homeNetwork?.socketFactory
                MediaRoute.BLOCKED -> BlockedSockets
                else -> null
            }
            SmbFs(a, s.userName, s.password, factory)
        }
        else -> WebDavFs(s.address, http(source), s.userName, s.password)
    }

    /** Sockets refused away without the VPN (SMB never goes over the internet). */
    private object BlockedSockets : SocketFactory() {
        private fun no(): Nothing = throw IOException("Medien: away only through the VPN")
        override fun createSocket(): Socket = no()
        override fun createSocket(host: String?, port: Int): Socket = no()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = no()
        override fun createSocket(host: InetAddress?, port: Int): Socket = no()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = no()
    }

    /** A share's root can be read with this access (the first SMB sign-in with the server's password, `shares/{id}/checked`). */
    suspend fun tryShare(source: MediaSource): MediaResult<Unit> {
        val s = source
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

    /** Quick Connect at [source] on its way now (the plugin's automatic sign-in, [com.gatecontrol.android.media.server.MediaAutoPair]). */
    fun signIn(source: MediaSource): JellyfinSignIn =
        JellyfinSignIn(MediaRouting.effective(source, route(source)).address, http(source), infoFor(source))

    private val checking = Mutex()

    @Volatile
    private var homeKey: String? = null

    @Volatile
    private var homeAt = 0L

    /**
     * At home: GateControl's own home-network check decides ([HomeCacheLan.localHeader], the same as „Internet zu
     * Hause“, the home cache and the trusted networks; the server compares the WireGuard endpoint or the gateway's site,
     * so another 192.168.1.x network is no home). Only then the server's probes of the plugin (`network.home.probes`:
     * Jellyfin's `Id`, the NAS's certificate) are asked over the local networks, as an additional confirmation that
     * tells which Wi-Fi / Ethernet network the NAS is in (media are bound to it). A probe alone never makes „home“, and a
     * plain `tcp` probe is not used at all (any device with that address would pass it). Kept while the networks stay
     * the same, at most [HOME_MS].
     */
    private suspend fun atHome(): Boolean {
        val home = try {
            homeLan.localHeader() != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        val list = probes
        if (!home || list.isEmpty()) {
            homeNetwork = null
            homeKey = null
            return home
        }
        val links = homeLan.lanLinks()
        val key = links.joinToString(",") { "${it.network}:${it.address}" } + "|" + vpnUp()
        val now = System.currentTimeMillis()
        if (key != homeKey || now - homeAt >= HOME_MS) {
            val networks = links.map { it.network }.distinct()
            homeNetwork = MediaHomeCheck.first(networks, list) { net, probe -> probe(net, probe) }
            homeKey = key
            homeAt = now
            Timber.d("Medien: home network confirmed by a probe: %s", if (homeNetwork != null) "yes" else "no")
        }
        return true
    }

    private suspend fun probe(network: Network, probe: MediaHomeProbe): Boolean = withContext(Dispatchers.IO) {
        val client = base.newBuilder()
            .socketFactory(network.socketFactory)
            .dns { host -> network.getAllByName(host).toList() }
            .connectTimeout(PROBE_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_MS, TimeUnit.MILLISECONDS)
            .build()
        try {
            when (probe) {
                is MediaHomeProbe.Jellyfin -> client.newCall(Request.Builder().url(probe.url).get().build()).execute().use { r ->
                    if (!r.isSuccessful) return@use false
                    val text = r.body.string().take(64_000)
                    val id = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()?.str("Id")
                    MediaHomeCheck.sameId(id, probe.serverId)
                }
                // only a handshake with exactly this certificate gets an answer
                is MediaHomeProbe.Tls -> MediaPinning.client(client, probe.certSha256).newCall(Request.Builder().url(probe.url).head().build()).execute().use { true }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Home or away now, and away the bandwidth from [source] (at most every [MEASURE_EVERY_MS] unless [force]).
     * Never throws except for cancellation.
     */
    suspend fun check(source: MediaSource? = currentSource(), force: Boolean = false) = checking.withLock {
        val home = atHome()
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

    private val preparing = Mutex()

    /**
     * Opening or playing media: home or away; away, when a source needs it and the VPN is off, the app starts it itself
     * ([gate] [MediaGate.CONNECTING] meanwhile, [MediaGate.CONSENT] when Android's consent is missing: the screen asks
     * once, then [consent]). Without the VPN nothing goes out ([MediaGate.BLOCKED], „Unterwegs nur über VPN“); downloads
     * still play. Never throws except for cancellation.
     */
    suspend fun prepare(source: MediaSource? = currentSource() ?: shares().firstOrNull()): MediaGate = preparing.withLock {
        check(source)
        val sources = store.sources.value
        if (_link.value.atHome == true || sources.isEmpty() || !MediaRouting.needsVpn(sources) || vpnUp()) {
            _gate.value = MediaGate.OPEN
            return@withLock MediaGate.OPEN
        }
        if (runCatching { vpn.consentNeeded() }.getOrDefault(false)) {
            _gate.value = MediaGate.CONSENT
            return@withLock MediaGate.CONSENT
        }
        _gate.value = MediaGate.CONNECTING
        val up = try {
            vpn.connect()
        } catch (e: CancellationException) {
            if (_gate.value == MediaGate.CONNECTING) _gate.value = MediaGate.BLOCKED
            throw e
        } catch (e: Exception) {
            false
        }
        val next = if (up || vpnUp()) MediaGate.OPEN else MediaGate.BLOCKED
        if (next == MediaGate.OPEN) {
            // through the tunnel now: home or away asked again (the server's check may see the tunnel's address)
            homeKey = null
            homeLan.forget()
            check(source, force = true)
        }
        _gate.value = next
        next
    }

    /** The screen asked for Android's VPN consent: given → the VPN starts; refused → media stay blocked away. */
    suspend fun consent(granted: Boolean): MediaGate {
        if (!granted) {
            _gate.value = MediaGate.BLOCKED
            return MediaGate.BLOCKED
        }
        return prepare()
    }

    /** A source answered on its way (or did not): the plugin's Geräte-Reiter shows it ([reachListener]). */
    @Volatile
    var reachListener: ((MediaSource, MediaRoute, Boolean) -> Unit)? = null

    fun reached(source: MediaSource, ok: Boolean) {
        runCatching { reachListener?.invoke(source, route(source), ok) }
    }

    /** A poster or backdrop of [url] (with [headers]); null when it cannot be loaded. 16-bit colour: half the memory (a TV stick has little). */
    suspend fun picture(url: String, headers: Map<String, String>, source: MediaSource): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            http(source).newCall(request).execute().use { r ->
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
        probes = emptyList()
        homeKey = null
        homeNetwork = null
        _gate.value = MediaGate.OPEN
    }

    companion object {
        /** Away the bandwidth is measured again after this long. */
        const val MEASURE_EVERY_MS = 5 * 60_000L

        /** A home probe answers within this long (contract: 1.5 s). */
        const val PROBE_MS = 1_500L

        /** Home or away is kept this long while the networks stay the same. */
        const val HOME_MS = 2 * 60_000L
        private const val MAX_PICTURE_BYTES = 4 * 1024 * 1024
    }
}
