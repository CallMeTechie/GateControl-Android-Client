package com.gatecontrol.android.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

/**
 * Address to open [service] with (`POST /api/v1/client/services/{id}/open`):
 * a one-time automatic login link when the route allows it, else the
 * route's own address.
 *
 * Returns null when the caller has to fall back to [VpnService.url]:
 * non-2xx (older servers answer 404), network error, no answer within
 * [timeoutMs], missing `url`, or a `url` that is not https on the
 * service's own host.
 *
 * The link may carry a secret ticket: it is never logged, stored or cached.
 */
suspend fun ApiClient.getServiceLink(
    service: VpnService,
    timeoutMs: Long = PORTAL_LINK_TIMEOUT_MS,
): String? {
    val response = try {
        withTimeoutOrNull(timeoutMs) { requestServiceLink(service.id) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d("Service link unavailable (${e.javaClass.simpleName}) — using the route address")
        return null
    }
    if (response == null) {
        Timber.d("Service link timed out after $timeoutMs ms — using the route address")
        return null
    }
    if (!response.isSuccessful) {
        Timber.d("Service link returned HTTP ${response.code()} — using the route address")
        response.errorBody()?.close()
        return null
    }
    val link = response.body()?.url?.takeIf { it.isNotBlank() } ?: return null
    if (!isServiceHost(link, service.domain)) {
        Timber.w("Service link points to another host than the route — ignored")
        return null
    }
    return link
}

/** True when [link] is https on exactly [domain] (case-insensitive). */
internal fun isServiceHost(link: String, domain: String): Boolean {
    val url = link.toHttpUrlOrNull() ?: return false
    return url.scheme == "https" && url.host.equals(domain.trim().trimEnd('.'), ignoreCase = true)
}
