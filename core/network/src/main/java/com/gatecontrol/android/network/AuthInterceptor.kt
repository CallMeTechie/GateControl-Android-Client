package com.gatecontrol.android.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val tokenProvider: () -> String,
    /** The server the stored token belongs to; the token never goes anywhere else. */
    private val serverUrlProvider: () -> String,
    private val versionProvider: () -> String,
    private val platformProvider: () -> String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val requestBuilder = chain.request().newBuilder()

        // A request that already carries an explicit token (connection test
        // with a not-yet-saved token) keeps it; otherwise use the stored one —
        // but only towards the server it was issued by. Setting up another
        // server (setup code, company sign-in, connection test) must not hand
        // that server the old one's token.
        val token = sanitizeHeaderValue(tokenProvider())
        if (token.isNotEmpty() && chain.request().header("X-API-Token") == null && isStoredServer(chain.request().url)) {
            requestBuilder.header("X-API-Token", token)
        }

        requestBuilder.header("X-Client-Version", sanitizeHeaderValue(versionProvider()))
        requestBuilder.header("X-Client-Platform", sanitizeHeaderValue(platformProvider()))

        // ServerScopedHeadersInterceptor adds X-Machine-Fingerprint and strips the
        // token from any hop that leaves the configured server (redirects).

        return chain.proceed(requestBuilder.build())
    }

    private fun isStoredServer(url: HttpUrl): Boolean {
        val server = serverUrlProvider().trim().toHttpUrlOrNull() ?: return false
        return url.scheme == server.scheme && url.host.equals(server.host, ignoreCase = true) && url.port == server.port
    }

    /** Strip non-ASCII and control characters that OkHttp rejects in header values. */
    private fun sanitizeHeaderValue(value: String): String =
        value.replace(Regex("[^\\x20-\\x7E]"), "").trim()
}
