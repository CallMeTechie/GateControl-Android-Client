package com.gatecontrol.android.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import timber.log.Timber

/** Upper bound for one home-network check. */
const val HOME_NETWORK_TIMEOUT_MS = 8_000L

/** Answer of the server's home-network check (`GET /api/v1/client/home-network`). */
sealed class HomeNetworkStatus {
    /** The server says this device is in its LAN. */
    data class Home(val viaTunnel: Boolean) : HomeNetworkStatus()

    /** The server says this device is not in its LAN. */
    data class NotHome(val viaTunnel: Boolean) : HomeNetworkStatus()

    /** Older server without the endpoint (404) or without `same_lan`. */
    data object Unsupported : HomeNetworkStatus()

    /** Error, timeout or an answer that can't be read: never trust, never act on it. */
    data object Unknown : HomeNetworkStatus()
}

/**
 * Asks the server whether this device is in its home network. Goes the
 * normal way to the public server URL (through the tunnel while the VPN is
 * up). Never throws except for cancellation.
 */
suspend fun ApiClient.probeHomeNetwork(timeoutMs: Long = HOME_NETWORK_TIMEOUT_MS): HomeNetworkStatus {
    val response = try {
        withTimeoutOrNull(timeoutMs) { getHomeNetwork() }
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
    body.sameLan -> HomeNetworkStatus.Home(viaTunnel = body.via == "tunnel")
    else -> HomeNetworkStatus.NotHome(viaTunnel = body.via == "tunnel")
}
