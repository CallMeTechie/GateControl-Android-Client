package com.gatecontrol.android.network

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val tokenProvider: () -> String,
    private val versionProvider: () -> String,
    private val platformProvider: () -> String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val requestBuilder = chain.request().newBuilder()

        // A request that already carries an explicit token (connection test
        // with a not-yet-saved token) keeps it; otherwise use the stored one.
        val token = sanitizeHeaderValue(tokenProvider())
        if (token.isNotEmpty() && chain.request().header("X-API-Token") == null) {
            requestBuilder.header("X-API-Token", token)
        }

        requestBuilder.header("X-Client-Version", sanitizeHeaderValue(versionProvider()))
        requestBuilder.header("X-Client-Platform", sanitizeHeaderValue(platformProvider()))

        // X-Machine-Fingerprint is added per server by MachineFingerprintInterceptor.

        return chain.proceed(requestBuilder.build())
    }

    /** Strip non-ASCII and control characters that OkHttp rejects in header values. */
    private fun sanitizeHeaderValue(value: String): String =
        value.replace(Regex("[^\\x20-\\x7E]"), "").trim()
}
