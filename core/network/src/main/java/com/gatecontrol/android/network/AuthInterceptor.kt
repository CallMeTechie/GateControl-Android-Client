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

        val token = tokenProvider()
        if (token.isNotEmpty()) {
            requestBuilder.header("X-API-Token", token)
        }

        requestBuilder.header("X-Client-Version", versionProvider())
        requestBuilder.header("X-Client-Platform", platformProvider())

        val fingerprint = fingerprintProvider()
        if (fingerprint.isNotEmpty()) {
            requestBuilder.header("X-Machine-Fingerprint", fingerprint)
        }

        return chain.proceed(requestBuilder.build())
    }
}
