package com.gatecontrol.android.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `X-Machine-Fingerprint` for the server's machine binding.
 *
 * Registered as a *network* interceptor on the client of one GateControl
 * server, so it sees every hop including redirects: the header goes only to
 * that server (same scheme, host and port) and is stripped from any request
 * that ends up at another host.
 */
class MachineFingerprintInterceptor(
    private val serverUrl: HttpUrl,
    private val fingerprintProvider: () -> String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder().removeHeader(HEADER)
        if (isServer(request.url)) {
            val fingerprint = try {
                fingerprintProvider()
            } catch (_: Exception) {
                ""
            }
            if (FINGERPRINT_RE.matches(fingerprint)) builder.header(HEADER, fingerprint)
        }
        return chain.proceed(builder.build())
    }

    private fun isServer(url: HttpUrl): Boolean =
        url.scheme == serverUrl.scheme &&
            url.host.equals(serverUrl.host, ignoreCase = true) &&
            url.port == serverUrl.port

    companion object {
        const val HEADER = "X-Machine-Fingerprint"
        private val FINGERPRINT_RE = Regex("^[a-f0-9]{64}$")
    }
}
