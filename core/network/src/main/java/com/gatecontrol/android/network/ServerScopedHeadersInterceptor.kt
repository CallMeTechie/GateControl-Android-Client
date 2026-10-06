package com.gatecontrol.android.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Keeps credentials and the device fingerprint on the configured server.
 *
 * Registered as a *network* interceptor on the client of one GateControl
 * server, so it sees every hop including redirects. OkHttp itself only strips
 * `Authorization` on a cross-host redirect, not custom headers — so for any
 * request that ends up at another scheme/host/port this removes
 * `X-API-Token` (added by [AuthInterceptor] or explicitly by a connection
 * test) and never adds `X-Machine-Fingerprint`. Requests to the server get
 * the fingerprint for its machine binding.
 */
class ServerScopedHeadersInterceptor(
    private val serverUrl: HttpUrl,
    private val fingerprintProvider: () -> String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder().removeHeader(FINGERPRINT_HEADER)
        if (isServer(request.url)) {
            val fingerprint = try {
                fingerprintProvider()
            } catch (_: Exception) {
                ""
            }
            if (FINGERPRINT_RE.matches(fingerprint)) builder.header(FINGERPRINT_HEADER, fingerprint)
        } else {
            builder.removeHeader(TOKEN_HEADER)
        }
        return chain.proceed(builder.build())
    }

    private fun isServer(url: HttpUrl): Boolean =
        url.scheme == serverUrl.scheme &&
            url.host.equals(serverUrl.host, ignoreCase = true) &&
            url.port == serverUrl.port

    companion object {
        const val FINGERPRINT_HEADER = "X-Machine-Fingerprint"
        const val TOKEN_HEADER = "X-API-Token"
        private val FINGERPRINT_RE = Regex("^[a-f0-9]{64}$")
    }
}
