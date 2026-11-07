package com.gatecontrol.android.homecache

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import com.gatecontrol.android.common.VpnSubnet
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.HomeCache
import com.gatecontrol.android.network.HomeCacheFetcher
import com.gatecontrol.android.network.HomeNetworkStatus
import com.gatecontrol.android.network.LanHint
import com.gatecontrol.android.network.isPrivateIpv4
import com.gatecontrol.android.network.probeHomeNetwork
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

/**
 * Whether this device is in the home network, remembered per set of LAN addresses for a while (the server's answer to
 * `GET /api/v1/client/home-network`). Home is kept [HOME_MS], "not home" [NOT_HOME_MS], no answer only [UNKNOWN_MS]
 * (asked again soon). Pure, unit-tested.
 */
internal class HomeSiteMemory(private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val home: Boolean, val until: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** The remembered answer for [key] (null: none, or it is too old). */
    fun get(key: String): Boolean? = entries[key]?.takeIf { it.until > now() }?.home

    fun put(key: String, status: HomeNetworkStatus) {
        if (entries.size > MAX) entries.clear()
        val t = now()
        entries[key] = when (status) {
            is HomeNetworkStatus.Home -> Entry(true, t + HOME_MS)
            is HomeNetworkStatus.NotHome, HomeNetworkStatus.Unsupported -> Entry(false, t + NOT_HOME_MS)
            HomeNetworkStatus.Unknown -> Entry(false, t + UNKNOWN_MS)
        }
    }

    fun clear() = entries.clear()

    companion object {
        const val HOME_MS = 10 * 60_000L
        const val NOT_HOME_MS = 10 * 60_000L
        const val UNKNOWN_MS = 60_000L
        private const val MAX = 16
    }
}

/**
 * The app side of the home cache ("Zwischenspeicher im Heimnetz", GateControl ≥ 1.231.0): the `X-GC-Local` header
 * for a download that may come with a `lan` hint, and loading such a hint from the gateway ([HomeCacheFetcher]: the
 * certificate pinned, the bytes checked, any error → the caller takes the normal download).
 *
 * The header carries the device's private IPv4 addresses on its Wi-Fi / Ethernet networks — never the VPN's own
 * addresses or mobile data — and only while the server's home-network check says this device is at home (the same
 * check as "Vertrauenswürdige WLANs" and Familie's presence; asked at most every few minutes, see [HomeSiteMemory]).
 *
 * The connection to the gateway is bound to the Wi-Fi / Ethernet network whose subnet holds the gateway's address,
 * so it goes straight through the LAN even while the tunnel is up (this app owns the VPN and may use the networks
 * below it, as GateDrop's direct transfer does). Without such a network the system routes it (through the tunnel,
 * if it carries the home network — correct, only not faster).
 */
@Singleton
class HomeCacheLan @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) {
    private val cm: ConnectivityManager? get() = runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
    private val memory = HomeSiteMemory()
    private val probing = Mutex()

    private val fetcher = HomeCacheFetcher(socketFactoryFor = ::socketFactoryFor)

    /** The Wi-Fi / Ethernet networks (never the VPN) with their private IPv4 addresses, Wi-Fi first. */
    private fun lanNetworks(): List<Pair<Network, List<LinkAddress>>> {
        val cm = cm ?: return emptyList()
        val vpnSubnet = apiClientProvider.vpnSubnet
        return runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks.mapNotNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (!wifi && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@mapNotNull null
                val addrs = cm.getLinkProperties(net)?.linkAddresses.orEmpty().filter { la ->
                    val ip = (la.address as? Inet4Address)?.hostAddress ?: return@filter false
                    isPrivateIpv4(ip) && !VpnSubnet.contains(vpnSubnet, ip)
                }
                if (addrs.isEmpty()) null else Triple(net, addrs, wifi)
            }.sortedByDescending { it.third }.map { it.first to it.second }
        }.getOrDefault(emptyList())
    }

    /** This device's own LAN IPv4 addresses (Wi-Fi / Ethernet, private, not the VPN's). */
    fun localAddresses(): List<String> = lanNetworks().flatMap { (_, addrs) -> addrs.mapNotNull { it.address.hostAddress } }.distinct()

    /**
     * The `X-GC-Local` value for a request now: the LAN addresses while the server says this device is at home; null
     * otherwise (no Wi-Fi / Ethernet, mobile data only, not at home, no answer). Never throws except cancellation.
     */
    suspend fun localHeader(): String? {
        val local = HomeCache.localHeader(localAddresses(), excluded = { VpnSubnet.contains(apiClientProvider.vpnSubnet, it) }) ?: return null
        memory.get(local)?.let { return if (it) local else null }
        return probing.withLock {
            memory.get(local)?.let { return@withLock if (it) local else null }
            val url = setupRepository.getServerUrl()
            if (url.isBlank()) return@withLock null
            val status = try {
                apiClientProvider.getClient(url).probeHomeNetwork(local.split(','), timeoutMs = PROBE_TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HomeNetworkStatus.Unknown
            }
            memory.put(local, status)
            Timber.d("Home cache: home network %s", if (status is HomeNetworkStatus.Home) "yes" else "no")
            if (status is HomeNetworkStatus.Home) local else null
        }
    }

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [localHeader] without waiting: the value while "at home" is known already, null otherwise — then the check runs in
     * the background (one at a time), so a later request has it. For requests that must not wait for the check, such
     * as Drive's lists (their previews may then come with hints, Drive 1.24).
     */
    fun localHeaderIfKnown(): String? {
        val local = HomeCache.localHeader(localAddresses(), excluded = { VpnSubnet.contains(apiClientProvider.vpnSubnet, it) }) ?: return null
        memory.get(local)?.let { return if (it) local else null }
        if (!probing.isLocked) background.launch { runCatching { localHeader() } }
        return null
    }

    /** Forget whether this is home (e.g. after a network change the next request asks again anyway: other addresses). */
    fun forget() = memory.clear()

    /** Sockets to [host]: bound to the Wi-Fi / Ethernet network whose subnet holds it; null: the system decides. */
    private fun socketFactoryFor(host: String): SocketFactory? =
        lanNetworks().firstOrNull { (_, addrs) ->
            addrs.any { la -> la.address.hostAddress?.let { HomeCache.inSubnet(host, it, la.prefixLength) } == true }
        }?.first?.socketFactory

    private fun dir(): File = File(context.cacheDir, DIR).apply { mkdirs() }

    /**
     * [hint] from the gateway into a new file of the app cache (the caller deletes it) — null when anything went wrong
     * (then nothing is left behind). [expectedSha256] / [expectedSize]: what the caller knows of the content.
     */
    suspend fun fetch(
        hint: LanHint,
        expectedSha256: String? = null,
        expectedSize: Long? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File? = withContext(Dispatchers.IO) {
        prune()
        val target = File.createTempFile("lan", ".bin", dir())
        val r = try {
            fetcher.fetch(hint, target, expectedSha256, expectedSize, onProgress)
        } catch (e: CancellationException) {
            target.delete()
            throw e
        }
        if (r is HomeCacheFetcher.Result.Ok) r.file else {
            target.delete()
            null
        }
    }

    /** Left-overs of an interrupted run (older than an hour) go. */
    private fun prune() {
        val old = System.currentTimeMillis() - 3_600_000L
        runCatching { File(context.cacheDir, DIR).listFiles()?.forEach { if (it.lastModified() < old) it.delete() } }
    }

    private companion object {
        const val DIR = "home-cache"
        /** The home check before a download: shorter than the usual one, a slow answer only means the normal way. */
        const val PROBE_TIMEOUT_MS = 4_000L
    }
}
