package com.gatecontrol.android.network

import com.google.gson.JsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress

/** Upper bound for one home-network check. */
const val HOME_NETWORK_TIMEOUT_MS = 8_000L

/** At most this many `local` addresses per check (the server's limit). */
const val HOME_NETWORK_MAX_LOCAL = 4

/** Longest gateway name shown (the server's own name, displayed as is). */
private const val MAX_SITE_NAME = 64

/**
 * Where the server recognised the home network (core 1.167+, `site`):
 * [Type.SERVER] = the server's own LAN, [Type.GATEWAY] = the LAN behind
 * one of its gateways ([name] e.g. "DS218+").
 */
data class HomeSite(val type: Type, val gatewayId: String? = null, val name: String? = null) {
    enum class Type { SERVER, GATEWAY, OTHER }
}

/** Answer of the server's home-network check (`GET /api/v1/client/home-network`). */
sealed class HomeNetworkStatus {
    /** The server says this device is in its home network; [site] = where (core 1.167+). */
    data class Home(val viaTunnel: Boolean, val site: HomeSite? = null) : HomeNetworkStatus()

    /**
     * The server says this device is not in its home network. [siteAware]
     * = the server knows gateway sites (core 1.167+, `site` in the answer):
     * its "no" holds for direct requests too. An older server says "no" to
     * every direct request, whatever the network.
     */
    data class NotHome(val viaTunnel: Boolean, val siteAware: Boolean = false) : HomeNetworkStatus()

    /** Older server without the endpoint (404) or without `same_lan`. */
    data object Unsupported : HomeNetworkStatus()

    /** Error, timeout or an answer that can't be read: never trust, never act on it. */
    data object Unknown : HomeNetworkStatus()
}

/**
 * Asks the server whether this device is in its home network. Goes the
 * normal way to the public server URL (through the tunnel while the VPN is
 * up). [local] = the device's private IPv4 addresses on Wi-Fi / Ethernet
 * ([localLanAddresses]); anything else in it is dropped. Never throws
 * except for cancellation.
 */
suspend fun ApiClient.probeHomeNetwork(
    local: List<String> = emptyList(),
    timeoutMs: Long = HOME_NETWORK_TIMEOUT_MS,
): HomeNetworkStatus {
    val query = homeNetworkLocalQuery(local)
    val response = try {
        withTimeoutOrNull(timeoutMs) { getHomeNetwork(query) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Class name only: messages may echo request details.
        Timber.d("Home-network check failed (%s)", e.javaClass.simpleName)
        return HomeNetworkStatus.Unknown
    }
    if (response == null) {
        Timber.d("Home-network check timed out after %d ms", timeoutMs)
        return HomeNetworkStatus.Unknown
    }
    return classifyHomeNetwork(response)
}

internal fun classifyHomeNetwork(response: Response<HomeNetworkResponse>): HomeNetworkStatus {
    if (!response.isSuccessful) response.errorBody()?.close()
    return classifyHomeNetwork(response.code(), if (response.isSuccessful) response.body() else null)
}

/**
 * 404 → [HomeNetworkStatus.Unsupported]; 2xx with `ok` and `same_lan` →
 * Home / NotHome; 2xx without `same_lan` → Unsupported; anything else →
 * [HomeNetworkStatus.Unknown].
 */
fun classifyHomeNetwork(code: Int, body: HomeNetworkResponse?): HomeNetworkStatus = when {
    code == 404 -> HomeNetworkStatus.Unsupported
    code !in 200..299 || body == null || !body.ok -> HomeNetworkStatus.Unknown
    body.sameLan == null -> HomeNetworkStatus.Unsupported
    body.sameLan -> HomeNetworkStatus.Home(viaTunnel = body.via == "tunnel", site = parseHomeSite(body.site))
    // `site` present (even null): the server knows gateway sites.
    else -> HomeNetworkStatus.NotHome(viaTunnel = body.via == "tunnel", siteAware = body.site != null)
}

/**
 * `site` of the answer: an object → [HomeSite] (unknown or missing type →
 * [HomeSite.Type.OTHER]); `null`, missing or anything else → null.
 */
fun parseHomeSite(site: JsonElement?): HomeSite? {
    val o = site?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    fun text(key: String): String? = o.get(key)
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        ?.filterNot { it.isISOControl() }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val type = when (text("type")?.lowercase()) {
        "server" -> HomeSite.Type.SERVER
        "gateway" -> HomeSite.Type.GATEWAY
        else -> HomeSite.Type.OTHER
    }
    return HomeSite(type, gatewayId = text("gatewayId"), name = text("name")?.take(MAX_SITE_NAME))
}

/**
 * A private (RFC 1918) IPv4 address in dotted form: 10/8, 172.16/12,
 * 192.168/16. Strict: four decimal octets 0–255, no leading zeros.
 */
fun isPrivateIpv4(address: String): Boolean {
    val parts = address.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { p ->
        if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || (p.length > 1 && p[0] == '0')) return false
        p.toInt().takeIf { it <= 255 } ?: return false
    }
    return when (octets[0]) {
        10 -> true
        172 -> octets[1] in 16..31
        192 -> octets[1] == 168
        else -> false
    }
}

/**
 * The device's own addresses worth sending as `local`: private IPv4
 * addresses only, in order, without duplicates, at most
 * [HOME_NETWORK_MAX_LOCAL]. IPv6, public, loopback and link-local
 * addresses are left out.
 */
fun localLanAddresses(candidates: Iterable<InetAddress>): List<String> =
    candidates.asSequence()
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
        .filter(::isPrivateIpv4)
        .distinct()
        .take(HOME_NETWORK_MAX_LOCAL)
        .toList()

/** The `local` query value ("192.168.27.5,10.0.0.3"), null when nothing valid is left. */
fun homeNetworkLocalQuery(local: List<String>): String? =
    local.map { it.trim() }
        .filter(::isPrivateIpv4)
        .distinct()
        .take(HOME_NETWORK_MAX_LOCAL)
        .joinToString(",")
        .ifEmpty { null }
