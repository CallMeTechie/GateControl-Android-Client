package com.gatecontrol.android.media.jellyfin

import com.gatecontrol.android.media.MediaAddress
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** A Jellyfin server that answered in the home network: its base URL, id and name ("DS918+"). */
data class JellyfinServerHint(val address: String, val id: String, val name: String)

/**
 * The server search of Medien › Quellen: Jellyfin answers the UDP broadcast "who is JellyfinServer?" on port 7359
 * with `{"Address":"http://192.168.1.20:8096","Id":"…","Name":"DS918+","EndpointAddress":null}`.
 */
object JellyfinDiscovery {
    const val PORT = 7359
    const val MESSAGE = "who is JellyfinServer?"
    const val TIMEOUT_MS = 2_500

    private const val MAX_NAME = 64
    private const val MAX_SERVERS = 16

    /**
     * One answer → a hint, or null when it is not one. The address must be a home-network address
     * ([MediaAddress.isHomeHost]); a server that names another one (its public URL) is taken at the address it
     * answered from ([from]) on Jellyfin's HTTP port.
     */
    fun parse(text: String, from: String? = null): JellyfinServerHint? {
        if (text.length > 4096) return null
        val o = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        fun s(key: String): String? = o.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.filterNot { it.isISOControl() }?.trim()?.ifEmpty { null }
        val id = s("Id")?.takeIf { it.length <= 64 && it.all { c -> c.isLetterOrDigit() || c == '-' } } ?: return null
        val named = s("Address")?.toHttpUrlOrNull()
        val base = when {
            named != null && MediaAddress.isHomeHost(named.host) -> MediaAddress.normalize(named.toString())
            // its LAN address with Jellyfin's HTTP port (the named one is the public one)
            from != null && MediaAddress.isPrivateIpv4(from) -> MediaAddress.normalize("http://$from:${MediaAddress.JELLYFIN_HTTP_PORT}")
            else -> null
        } ?: return null
        val name = (s("Name") ?: MediaAddress.host(base) ?: return null).take(MAX_NAME)
        return JellyfinServerHint(base, id, name)
    }

    /**
     * The directed broadcast of [address]/[prefix] ("192.168.1.37"/24 → "192.168.1.255"); null for anything but
     * private IPv4 with a prefix of 8..30.
     */
    fun broadcastOf(address: String, prefix: Int): String? {
        if (!MediaAddress.isPrivateIpv4(address) || prefix !in 8..30) return null
        val v = address.split('.').fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
        val host = (1L shl (32 - prefix)) - 1
        val b = (v or host) and 0xFFFFFFFFL
        return listOf(24, 16, 8, 0).joinToString(".") { ((b shr it) and 0xFF).toString() }
    }

    /**
     * Sends the question to each of [targets] and collects the answers for [timeoutMs]; the same server once. [bind]
     * may tie the socket to the Wi-Fi network (not the VPN). Never throws except for cancellation.
     */
    suspend fun discover(
        targets: List<String>,
        port: Int = PORT,
        timeoutMs: Int = TIMEOUT_MS,
        bind: (DatagramSocket) -> Unit = {},
    ): List<JellyfinServerHint> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, JellyfinServerHint>()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                runCatching { bind(socket) }
                val question = MESSAGE.toByteArray(Charsets.UTF_8)
                for (t in targets.distinct()) {
                    runCatching { socket.send(DatagramPacket(question, question.size, InetAddress.getByName(t), port)) }
                        .onFailure { Timber.d("Jellyfin search: not sent (%s)", it.javaClass.simpleName) }
                }
                val until = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (found.size < MAX_SERVERS) {
                    val left = until - System.currentTimeMillis()
                    if (left <= 0) break
                    socket.soTimeout = left.toInt().coerceAtLeast(1)
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    parse(text, packet.address?.hostAddress)?.let { found.putIfAbsent(it.id, it) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Jellyfin search failed (%s)", e.javaClass.simpleName)
        }
        found.values.toList()
    }
}
