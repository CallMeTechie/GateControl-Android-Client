package com.gatecontrol.android.network

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val tokenProvider: () -> String,
    private val versionProvider: () -> String,
    private val platformProvider: () -> String,
    private val fingerprintProvider: () -> String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val requestBuilder = chain.request().newBuilder()

        val token = sanitizeHeaderValue(tokenProvider())
        if (token.isNotEmpty()) {
            requestBuilder.header("X-API-Token", token)
        }

        requestBuilder.header("X-Client-Version", sanitizeHeaderValue(versionProvider()))
        requestBuilder.header("X-Client-Platform", sanitizeHeaderValue(platformProvider()))

        val fingerprint = sanitizeHeaderValue(fingerprintProvider())
        if (fingerprint.isNotEmpty()) {
            requestBuilder.header("X-Machine-Fingerprint", fingerprint)
        }

        return chain.proceed(requestBuilder.build())
    }

    /** Strip non-ASCII and control characters that OkHttp rejects in header values. */
    private fun sanitizeHeaderValue(value: String): String =
        value.replace(Regex("[^\\x20-\\x7E]"), "").trim()
}
