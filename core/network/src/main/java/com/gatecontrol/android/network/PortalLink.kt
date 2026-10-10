package com.gatecontrol.android.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

/** Upper bound for the portal-link request before falling back to the plain portal URL. */
const val PORTAL_LINK_TIMEOUT_MS = 5_000L

/**
 * Fetches a fresh one-time automatic login link for the portal
 * (`POST /api/v1/client/portal-link`).
 *
 * Returns the link, or null when the caller has to fall back to the plain
 * [portalUrl]: non-2xx status, network error, no answer within [timeoutMs],
 * missing `url`, or a `url` whose scheme or host differs from [portalUrl].
 *
 * The link carries a secret ticket: it is never logged, stored or cached —
 * every call mints a new one. Log lines only name the failure class.
 */
suspend fun ApiClient.getPortalLink(
    portalUrl: String,
    timeoutMs: Long = PORTAL_LINK_TIMEOUT_MS,
): String? {
    val response = try {
        withTimeoutOrNull(timeoutMs) { requestPortalLink() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Class name only: exception messages may echo request/response details.
        Timber.d("Portal link unavailable (${e.javaClass.simpleName}) — using portal URL")
        return null
    }
    if (response == null) {
        Timber.d("Portal link timed out after $timeoutMs ms — using portal URL")
        return null
    }
    if (!response.isSuccessful) {
        Timber.d("Portal link returned HTTP ${response.code()} — using portal URL")
        response.errorBody()?.close()
        return null
    }
    val link = response.body()?.url?.takeIf { it.isNotBlank() }
    if (link == null) {
        Timber.d("Portal link response has no url — using portal URL")
        return null
    }
    if (!isSameOrigin(link, portalUrl)) {
        Timber.w("Portal link points to a different scheme/host than the portal URL — ignored")
        return null
    }
    return link
}

/** True when [link] has the same scheme and host as [portalUrl] (case-insensitive). */
internal fun isSameOrigin(link: String, portalUrl: String): Boolean {
    val a = link.toHttpUrlOrNull() ?: return false
    val b = portalUrl.toHttpUrlOrNull() ?: return false
    // HttpUrl normalises scheme and host to lower case.
    return a.scheme == b.scheme && a.host == b.host
}
