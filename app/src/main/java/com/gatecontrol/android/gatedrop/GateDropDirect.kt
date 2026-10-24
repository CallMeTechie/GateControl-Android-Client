package com.gatecontrol.android.gatedrop

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

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
 * candidate, not through the VPN, and are plain sockets ([GateDropHttp]):
 * OkHttp would refuse cleartext to LAN addresses under the app's network
 * security policy.
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

    /**
     * Sockets to [host]: bound to the Wi-Fi/Ethernet network that holds it
     * (so the VPN never routes the LAN address into the tunnel), else the
     * system's. Plain sockets on purpose — see [GateDropHttp].
     */
    internal var socketFactoryFor: (String) -> SocketFactory = { host ->
        networkFor(host)?.socketFactory ?: SocketFactory.getDefault().also {
            Timber.i("GateDrop: no Wi-Fi network holds the candidate, the system routes it")
        }
    }

    private fun http(host: String, readTimeoutMs: Int) =
        GateDropHttp(socketFactoryFor(host), GateDropLogic.CONNECT_TIMEOUT_MS.toInt(), readTimeoutMs)

    /**
     * Push (this device sends, the target listens): every file is posted
     * to the first candidate that accepts; that candidate is kept for the
     * other files. Failed reasons: `no_candidate`, the connect error of the
     * last candidate (`connect_timeout`, `refused`, `unreachable`, …) or,
     * once connected, `http_<status>` / `broken`.
     */
    suspend fun push(direct: GdDirect, files: List<GdLocalFile>, onProgress: (Long) -> Unit = {}): GdDirectOutcome = withContext(Dispatchers.IO) {
        val candidates = GateDropLogic.orderCandidates(direct.candidates, lanAddresses())
        if (candidates.isEmpty()) return@withContext GdDirectOutcome.Failed("no_candidate").also { Timber.i("GateDrop: push %s – no candidate", direct.transferId) }
        var chosen: GdCandidate? = null
        var total = 0L
        files.forEachIndexed { index, f ->
            val tryList = chosen?.let { listOf(it) } ?: candidates
            var reason = "push_failed"
            var ok = false
            for (c in tryList) {
                val base = total
                val r = runCatching { pushOne(c, direct, index, f) { onProgress(base + it) } }
                if (r.exceptionOrNull() is CancellationException) throw r.exceptionOrNull()!!
                val err = r.exceptionOrNull()
                if (err == null) {
                    chosen = c
                    ok = true
                    break
                }
                reason = (err as? GdHttpException)?.code ?: "broken"
                Timber.i("GateDrop: push of %s to %s:%d failed (%s: %s)", direct.transferId, c.host, c.port, reason, err.message)
                // Reached and refused / broken off: another address of the same device will not do better.
                if ((err as? GdHttpException)?.connected != false) break
            }
            if (!ok) return@withContext GdDirectOutcome.Failed(reason)
            total += f.size
            onProgress(total)
        }
        Timber.i("GateDrop: push of %s done (%d bytes)", direct.transferId, total)
        GdDirectOutcome.Done(total)
    }

    private fun pushOne(c: GdCandidate, direct: GdDirect, index: Int, f: GdLocalFile, onBytes: (Long) -> Unit) {
        val size = f.size
        val headers = linkedMapOf(
            "Content-Type" to "application/octet-stream",
            H_TICKET to direct.ticket,
            H_ITEM to index.toString(),
            H_NAME to encodeName(f.name),
            H_SIZE to size.toString(),
        )
        http(c.host, readTimeoutMs = 120_000).execute(
            c.host, c.port, "POST", "$PATH${direct.transferId}", headers, GateDropCrypto.encryptedLength(size),
        ) { sink ->
            GateDropCrypto.encryptingStream(FileInputStream(f.file), direct.key, direct.transferId, index, size).use { enc ->
                val buf = ByteArray(256 * 1024)
                var sent = 0L
                while (true) {
                    val n = enc.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    sent += n
                    onBytes(minOf(size, sent))
                }
            }
        }.use { r ->
            if (!r.isSuccessful) throw GdHttpException("http_${r.status}", connected = true)
        }
    }

    /**
     * Pull (this device receives, the sender listens): every file is
     * fetched from the first candidate that answers and written, checked
     * block by block, to [sinkFor]'s stream. A file that fails its check
     * fails the whole transfer. Failed reasons as for [push], plus
     * `auth_failed` (a block did not decrypt).
     */
    suspend fun pull(offer: GdTransferOffer, sinkFor: (Int, GdOfferItem) -> OutputStream, onProgress: (Long) -> Unit = {}): GdDirectOutcome = withContext(Dispatchers.IO) {
        val candidates = GateDropLogic.orderCandidates(offer.candidates, lanAddresses())
        if (candidates.isEmpty()) return@withContext GdDirectOutcome.Failed("no_candidate").also { Timber.i("GateDrop: pull %s – no candidate", offer.transferId) }
        var chosen: GdCandidate? = null
        var total = 0L
        offer.items.forEachIndexed { index, item ->
            val tryList = chosen?.let { listOf(it) } ?: candidates
            var reason = "pull_failed"
            var ok = false
            for (c in tryList) {
                val base = total
                val r = runCatching {
                    http(c.host, readTimeoutMs = 60_000).execute(
                        c.host, c.port, "GET", "$PATH${offer.transferId}/$index", mapOf(H_TICKET to offer.ticket),
                    ).use { resp ->
                        if (!resp.isSuccessful) throw GdHttpException("http_${resp.status}", connected = true)
                        val announced = resp.contentLength
                        if (announced != null && announced != GateDropCrypto.encryptedLength(item.bytes)) throw GdHttpException("bad_size", connected = true)
                        try {
                            sinkFor(index, item).use { out ->
                                GateDropCrypto.decryptStream(resp.body, out, offer.key, offer.transferId, index, item.bytes) { onProgress(base + it) }
                            }
                        } catch (e: GdHttpException) {
                            throw e
                        } catch (e: java.security.GeneralSecurityException) {
                            throw GdHttpException("auth_failed", connected = true, message = e.javaClass.simpleName, cause = e)
                        } catch (e: IOException) {
                            throw GdHttpException(if (e is java.net.SocketTimeoutException) "timeout" else "broken", connected = true, message = "${e.javaClass.simpleName}: ${e.message}", cause = e)
                        }
                    }
                }
                if (r.exceptionOrNull() is CancellationException) throw r.exceptionOrNull()!!
                val err = r.exceptionOrNull()
                if (err == null) {
                    chosen = c
                    ok = true
                    break
                }
                reason = (err as? GdHttpException)?.code ?: "broken"
                Timber.i("GateDrop: pull of %s from %s:%d failed (%s: %s)", offer.transferId, c.host, c.port, reason, err.message)
                // Reached and refused / broken off: another address of the same device will not do better.
                if ((err as? GdHttpException)?.connected != false) break
            }
            if (!ok) return@withContext GdDirectOutcome.Failed(reason)
            total += item.bytes
            onProgress(total)
        }
        Timber.i("GateDrop: pull of %s done (%d bytes)", offer.transferId, total)
        GdDirectOutcome.Done(total)
    }
}
