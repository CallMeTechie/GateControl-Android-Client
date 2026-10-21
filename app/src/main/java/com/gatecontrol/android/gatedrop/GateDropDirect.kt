package com.gatecontrol.android.gatedrop

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Proxy
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A local file to send directly (index = position in the send). */
data class GdLocalFile(val file: File, val name: String, val size: Long)

/** Outcome of a direct transfer. */
sealed interface GdDirectOutcome {
    data class Done(val bytes: Long) : GdDirectOutcome

    /** Nothing reachable / refused / broken: the server path takes over. */
    data class Failed(val reason: String) : GdDirectOutcome
}

/**
 * Client side of the direct LAN transfer (protocol C v1): push the files
 * to a listening device, or pull them from a listening sender. Plain HTTP
 * inside the LAN, every file AES-256-GCM encrypted ([GateDropCrypto]),
 * the ticket from the plugin as the only credential — the GateControl API
 * token never leaves for a LAN address. Each candidate gets 2 s to accept
 * the connection; after that (or any error) the caller falls back to the
 * server. Sockets go out over the Wi-Fi/Ethernet network that holds the
 * candidate, not through the VPN.
 */
@Singleton
class GateDropDirect @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        /** Listener port of the Windows client (contract C); also this app's when it listens. */
        const val DEFAULT_PORT = 47731
        const val PATH = "/gatedrop/v1/transfer/"
        const val H_TICKET = "X-GD-Ticket"
        const val H_ITEM = "X-GD-Item"
        const val H_NAME = "X-GD-Name"
        const val H_SIZE = "X-GD-Size"
        private val OCTET = "application/octet-stream".toMediaType()

        fun encodeName(name: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray(Charsets.UTF_8))

        fun decodeName(value: String?): String? = value?.let {
            runCatching { String(Base64.getUrlDecoder().decode(it.trim()), Charsets.UTF_8) }.getOrNull()
        }
    }

    private val cm: ConnectivityManager? get() = context.getSystemService(ConnectivityManager::class.java)

    /** IPv4 addresses (+ prefix) of this device's Wi-Fi and Ethernet networks — never the VPN's. */
    fun lanAddresses(): List<Pair<String, Int>> = lanNetworks().flatMap { (_, addrs) -> addrs }.distinct()

    private fun lanNetworks(): List<Pair<Network, List<Pair<String, Int>>>> {
        val cm = cm ?: return emptyList()
        return runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks.mapNotNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@mapNotNull null
                val addrs = cm.getLinkProperties(net)?.linkAddresses.orEmpty()
                    .filter { it.address is Inet4Address }
                    .mapNotNull { la -> la.address.hostAddress?.takeIf { GateDropJson.isPrivateIpv4(it) }?.let { it to la.prefixLength } }
                if (addrs.isEmpty()) null else net to addrs
            }
        }.getOrDefault(emptyList())
    }

    /** The LAN network whose subnet holds [host] (null: the system decides). */
    private fun networkFor(host: String): Network? =
        lanNetworks().firstOrNull { (_, addrs) -> addrs.any { (ip, prefix) -> GateDropLogic.inSubnet(host, ip, prefix) } }?.first

    private fun client(network: Network?, readTimeoutS: Long = 30): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(GateDropLogic.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutS, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .apply { network?.let { socketFactory(it.socketFactory) } }
        .build()

    /**
     * Push (this device sends, the target listens): every file is posted
     * to the first candidate that accepts; that candidate is kept for the
     * other files.
     */
    suspend fun push(direct: GdDirect, files: List<GdLocalFile>, onProgress: (Long) -> Unit = {}): GdDirectOutcome = withContext(Dispatchers.IO) {
        val candidates = GateDropLogic.orderCandidates(direct.candidates, lanAddresses())
        if (candidates.isEmpty()) return@withContext GdDirectOutcome.Failed("no_candidate")
        var chosen: GdCandidate? = null
        var total = 0L
        files.forEachIndexed { index, f ->
            val tryList = chosen?.let { listOf(it) } ?: candidates
            var ok = false
            for (c in tryList) {
                val base = total
                val r = runCatching { pushOne(c, direct, index, f) { onProgress(base + it) } }
                if (r.exceptionOrNull() is CancellationException) throw r.exceptionOrNull()!!
                if (r.getOrDefault(false)) {
                    chosen = c
                    ok = true
                    break
                }
                Timber.d("GateDrop: push to a candidate failed (%s)", r.exceptionOrNull()?.javaClass?.simpleName ?: "refused")
            }
            if (!ok) return@withContext GdDirectOutcome.Failed("push_failed")
            total += f.size
            onProgress(total)
        }
        GdDirectOutcome.Done(total)
    }

    private fun pushOne(c: GdCandidate, direct: GdDirect, index: Int, f: GdLocalFile, onBytes: (Long) -> Unit): Boolean {
        val size = f.size
        val body = object : RequestBody() {
            override fun contentType() = OCTET
            override fun contentLength(): Long = GateDropCrypto.encryptedLength(size)
            override fun writeTo(sink: BufferedSink) {
                GateDropCrypto.encryptingStream(FileInputStream(f.file), direct.key, direct.transferId, index, size).use { enc ->
                    val src = enc.source()
                    val buf = okio.Buffer()
                    var sent = 0L
                    while (true) {
                        val n = src.read(buf, 256 * 1024L)
                        if (n < 0) break
                        sink.write(buf, n)
                        sent += n
                        onBytes(minOf(size, sent))
                    }
                }
            }
        }
        val request = Request.Builder()
            .url("http://${c.host}:${c.port}$PATH${direct.transferId}")
            .header(H_TICKET, direct.ticket)
            .header(H_ITEM, index.toString())
            .header(H_NAME, encodeName(f.name))
            .header(H_SIZE, size.toString())
            .post(body)
            .build()
        client(networkFor(c.host), readTimeoutS = 120).newCall(request).execute().use { r -> return r.isSuccessful }
    }

    /**
     * Pull (this device receives, the sender listens): every file is
     * fetched from the first candidate that answers and written, checked
     * block by block, to [sinkFor]'s stream. A file that fails its check
     * fails the whole transfer.
     */
    suspend fun pull(offer: GdTransferOffer, sinkFor: (Int, GdOfferItem) -> OutputStream, onProgress: (Long) -> Unit = {}): GdDirectOutcome = withContext(Dispatchers.IO) {
        val candidates = GateDropLogic.orderCandidates(offer.candidates, lanAddresses())
        if (candidates.isEmpty()) return@withContext GdDirectOutcome.Failed("no_candidate")
        var chosen: GdCandidate? = null
        var total = 0L
        offer.items.forEachIndexed { index, item ->
            val tryList = chosen?.let { listOf(it) } ?: candidates
            var ok = false
            for (c in tryList) {
                val base = total
                val request = Request.Builder()
                    .url("http://${c.host}:${c.port}$PATH${offer.transferId}/$index")
                    .header(H_TICKET, offer.ticket)
                    .get()
                    .build()
                val r = runCatching {
                    client(networkFor(c.host), readTimeoutS = 60).newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) return@use false
                        val body = resp.body
                        val announced = body.contentLength()
                        if (announced >= 0 && announced != GateDropCrypto.encryptedLength(item.bytes)) throw IOException("length mismatch")
                        sinkFor(index, item).use { out ->
                            GateDropCrypto.decryptStream(body.byteStream(), out, offer.key, offer.transferId, index, item.bytes) { onProgress(base + it) }
                        }
                        true
                    }
                }
                if (r.exceptionOrNull() is CancellationException) throw r.exceptionOrNull()!!
                if (r.getOrDefault(false)) {
                    chosen = c
                    ok = true
                    break
                }
                Timber.d("GateDrop: pull from a candidate failed (%s)", r.exceptionOrNull()?.javaClass?.simpleName ?: "refused")
                // A broken item after the connection was made: no other candidate gets the same file twice.
                if (r.exceptionOrNull() != null && chosen != null) break
            }
            if (!ok) return@withContext GdDirectOutcome.Failed("pull_failed")
            total += item.bytes
            onProgress(total)
        }
        GdDirectOutcome.Done(total)
    }
}
