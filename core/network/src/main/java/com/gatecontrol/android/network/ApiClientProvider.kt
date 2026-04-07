package com.gatecontrol.android.network

import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
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

        // Gson that tolerates SQLite boolean fields (0/1 as NUMBER instead of true/false)
        val gson = GsonBuilder()
            .registerTypeAdapter(Boolean::class.java, LenientBooleanAdapter())
            .registerTypeAdapter(Boolean::class.javaPrimitiveType, LenientBooleanAdapter())
            .create()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiClient::class.java)
    }

    /** Reads JSON booleans, numbers (0/1), and strings ("true"/"false") as Boolean. */
    private class LenientBooleanAdapter : TypeAdapter<Boolean>() {
        override fun write(out: JsonWriter, value: Boolean?) {
            out.value(value)
        }

        override fun read(reader: JsonReader): Boolean {
            return when (reader.peek()) {
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NUMBER -> reader.nextInt() != 0
                JsonToken.STRING -> reader.nextString().equals("true", ignoreCase = true)
                JsonToken.NULL -> { reader.nextNull(); false }
                else -> { reader.skipValue(); false }
            }
        }
    }
}
