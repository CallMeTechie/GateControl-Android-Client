package com.gatecontrol.android.network

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiClientProvider @Inject constructor(
    private val authInterceptor: AuthInterceptor,
    @ApplicationContext private val context: Context
) {
    private val cache = mutableMapOf<String, ApiClient>()
    private val lock = Any()

    /**
     * DNS cache for VPN-safe resolution. When the VPN is active, Android's
     * system DNS points to the VPN-internal resolver (10.8.0.1) but the
     * GateControl app is excluded from the VPN (VpnService requirement).
     * System DNS lookups fail with EAI_NODATA because 10.8.0.1 is unreachable
     * outside the tunnel. We cache DNS results resolved BEFORE the VPN starts
     * and fall back to the cache when system DNS fails.
     */
    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    /**
     * Resolve and cache the server hostname. Safe to call from any thread.
     * Re-resolves to pick up DNS changes, but rejects VPN-internal addresses
     * (10.8.x.x) that appear when the tunnel's split-horizon DNS is active.
     * Call clearDnsCache() on disconnect so the next connect starts fresh.
     */
    suspend fun preResolveDns(hostname: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val addresses = InetAddress.getAllByName(hostname)
                if (addresses.isNotEmpty()) {
                    // Reject VPN-internal addresses — when the tunnel is up,
                    // system DNS may return the VPN gateway (10.8.0.1) instead
                    // of the real public IP.
                    val isVpnInternal = addresses.all { addr ->
                        val ip = addr.hostAddress ?: ""
                        ip.startsWith("10.8.")
                    }
                    if (isVpnInternal) {
                        timber.log.Timber.d("DNS for $hostname returned VPN-internal address — keeping cache")
                        return@withContext
                    }

                    val prev = dnsCache[hostname]?.map { it.hostAddress }
                    dnsCache[hostname] = addresses.toList()
                    val resolved = addresses.map { it.hostAddress }
                    if (prev != null && prev != resolved) {
                        timber.log.Timber.i("DNS changed for $hostname: $prev -> $resolved")
                    } else {
                        timber.log.Timber.d("DNS pre-resolved: $hostname -> $resolved")
                    }
                }
            } catch (e: Exception) {
                if (dnsCache.containsKey(hostname)) {
                    timber.log.Timber.d("DNS re-resolve failed for $hostname, keeping cached entry: ${e.message}")
                } else {
                    timber.log.Timber.w("DNS pre-resolve failed for $hostname: ${e.message}")
                }
            }
        }
    }

    /**
     * Clear the DNS cache. Call on VPN disconnect so the next connect
     * picks up any DNS changes that occurred while the tunnel was active.
     */
    fun clearDnsCache() {
        dnsCache.clear()
        timber.log.Timber.d("DNS cache cleared")
    }

    private val vpnSafeDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // System DNS may return VPN-internal addresses (10.8.x.x) even
            // when the GateControl app is excluded from the tunnel: Android
            // caches the WG-internal resolver's answer system-wide. The app
            // can't reach 10.8.0.1 from outside the tunnel, so OkHttp would
            // hit SocketTimeout from the local Wi-Fi IP / ECONNREFUSED via
            // VPN. Filter those out and prefer the pre-resolve cache,
            // which preResolveDns() guarantees never holds 10.8.x.x.
            val systemResults = try {
                Dns.SYSTEM.lookup(hostname).filter { addr ->
                    val ip = addr.hostAddress ?: ""
                    !ip.startsWith("10.8.")
                }
            } catch (_: Exception) {
                emptyList()
            }
            if (systemResults.isNotEmpty()) return systemResults
            return dnsCache[hostname] ?: throw java.net.UnknownHostException(
                "DNS lookup failed for $hostname (system DNS returned only VPN-internal addresses, no cache)"
            )
        }
    }

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
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val logging = HttpLoggingInterceptor().apply {
            level = if (isDebuggable) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }

        val okHttpClient = OkHttpClient.Builder()
            .dns(vpnSafeDns)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        // Gson that tolerates SQLite boolean fields (0/1 as NUMBER instead of true/false)
        // Uses TypeAdapterFactory to cover both Boolean and Boolean? (nullable) fields
        val gson = GsonBuilder()
            .registerTypeAdapterFactory(LenientBooleanAdapterFactory())
            .create()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiClient::class.java)
    }

    /** Factory that applies LenientBooleanAdapter to both Boolean and Boolean? fields. */
    private class LenientBooleanAdapterFactory : TypeAdapterFactory {
        @Suppress("UNCHECKED_CAST")
        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            if (type.rawType != Boolean::class.java && type.rawType != Boolean::class.javaPrimitiveType) {
                return null
            }
            return LenientBooleanAdapter() as TypeAdapter<T>
        }
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
