package com.gatecontrol.android.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiClientProvider @Inject constructor(
    private val authInterceptor: AuthInterceptor
) {
    private val cache = mutableMapOf<String, ApiClient>()
    private val lock = Any()

    fun getClient(baseUrl: String): ApiClient {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return synchronized(lock) {
            cache.getOrPut(normalizedUrl) {
                buildClient(normalizedUrl)
            }
        }
    }

    fun invalidate() {
        synchronized(lock) {
            cache.clear()
        }
    }

    private fun buildClient(baseUrl: String): ApiClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiClient::class.java)
    }
}
