package com.gatecontrol.android.network

import com.gatecontrol.android.common.VpnSubnet
import com.gatecontrol.android.data.MachineFingerprint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiClientProvider @Inject constructor(
    private val authInterceptor: AuthInterceptor,
    @ApplicationContext private val context: Context,
    private val machineFingerprint: MachineFingerprint,
    private val machineBindingMonitor: MachineBindingMonitor,
) {
    private val cache = mutableMapOf<String, ApiClient>()
    private val speedTestCache = mutableMapOf<String, ApiClient>()
    private val lock = Any()

    /**
     * VPN-internal subnet of the current server (from the WireGuard config's
     * Address). Addresses inside it are only reachable through the tunnel.
     * Set by the connect path before the tunnel comes up.
     */
    @Volatile
    var vpnSubnet: String = VpnSubnet.DEFAULT

    private fun isVpnInternal(addr: InetAddress): Boolean =
        VpnSubnet.contains(vpnSubnet, addr.hostAddress ?: "")

    private val connectivity: ConnectivityManager?
        get() = try {
            context.getSystemService(ConnectivityManager::class.java)
        } catch (_: Exception) {
            null
        }

    /**
     * Server name resolution that works with and without the tunnel (see
     * [VpnSafeDns]): whether a VPN-internal answer is usable depends on the
     * route of this app's own traffic — through the tunnel (no per-app
     * exception for GateControl) it is the way in, outside it is not.
     */
    private val vpnSafeDns = VpnSafeDns(
        system = Dns.SYSTEM::lookup,
        isVpnInternal = ::isVpnInternal,
        routedThroughVpn = ::appUsesVpn,
        outsideVpn = ::lookupOutsideVpn,
        lastKnown = PrefsDnsLastKnown { context.getSharedPreferences(PrefsDnsLastKnown.PREFS_NAME, Context.MODE_PRIVATE) },
    )

    /**
     * This app's own traffic goes through a VPN: its default network (per
     * app — a split-tunnel exception makes it the underlying network) is one.
     */
    private fun appUsesVpn(): Boolean {
        val cm = connectivity ?: return false
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    /**
     * [hostname] resolved by the DNS of a network outside the VPN (validated
     * networks first). The app owns the VPN and may use the underlying
     * networks, like the tunnel's own socket does.
     */
    private fun lookupOutsideVpn(hostname: String): List<InetAddress> {
        val cm = connectivity ?: return emptyList()
        @Suppress("DEPRECATION")
        val candidates = cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            network to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.sortedByDescending { it.second }.map { it.first }
        for (network in candidates) {
            val found = try {
                network.getAllByName(hostname).toList()
            } catch (_: Exception) {
                emptyList()
            }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /**
     * Resolve and remember the server hostname. Safe to call from any thread.
     * Re-resolves to pick up DNS changes; a VPN-internal answer (the tunnel's
     * split-horizon DNS) is never kept — the network outside the VPN is asked
     * instead. Call clearDnsCache() on disconnect so the next connect starts fresh.
     */
    suspend fun preResolveDns(hostname: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            vpnSafeDns.preResolve(hostname)
        }
    }

    /**
     * Forget the addresses of this connection. Call on VPN disconnect so the
     * next connect picks up any DNS changes that occurred while the tunnel was
     * active. The last known public addresses stay (persisted) as the last
     * resort after an app restart with the tunnel up.
     */
    fun clearDnsCache() {
        vpnSafeDns.clear()
        timber.log.Timber.d("DNS cache cleared")
    }

    companion object {
        /** Headers never written to the HTTP log. */
        val SENSITIVE_HEADERS = listOf(
            "X-API-Token",
            "Authorization",
            "Proxy-Authorization",
            "Cookie",
            "Set-Cookie",
            "X-Machine-Fingerprint",
            // the device's LAN addresses for the home cache's site check
            HomeCache.HEADER_LOCAL,
        )
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
            speedTestCache.clear()
            mediaCache.clear()
        }
    }

    /**
     * Client for the diagnosis speed test (`/client/speedtest/…`): the same
     * DNS and interceptors (token, client headers, machine binding) as
     * [getClient], with timeouts long enough for a bulk transfer. The call
     * timeout bounds a whole transfer, including reading the body.
     */
    fun speedTestClient(baseUrl: String): ApiClient {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return synchronized(lock) {
            speedTestCache.getOrPut(normalizedUrl) {
                val okHttp = buildOkHttpClient(normalizedUrl).newBuilder()
                    .readTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)
                    .callTimeout(40, TimeUnit.SECONDS)
                    .build()
                buildRetrofit(normalizedUrl, okHttp)
            }
        }
    }

    private val mediaCache = mutableMapOf<String, ApiClient>()

    /**
     * Client for plugin files (`PUT/GET /api/v1/client/plugins/:id/media…`,
     * e.g. chat pictures and voice messages up to 25 MB): the same DNS and
     * interceptors as [getClient], with read/write timeouts long enough for a
     * slow mobile upload and no limit on the whole call.
     */
    fun mediaClient(baseUrl: String): ApiClient {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return synchronized(lock) {
            mediaCache.getOrPut(normalizedUrl) {
                val okHttp = buildOkHttpClient(normalizedUrl).newBuilder()
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build()
                buildRetrofit(normalizedUrl, okHttp)
            }
        }
    }

    /**
     * Client for long-lived streams (push, `GET /api/v1/client/push`): the
     * same DNS, interceptors (token, client headers, machine binding) and
     * connect timeout as [getClient], but no read timeout — the stream is
     * idle between events. Dead connections are detected by the caller
     * (keepalive watchdog in core/notify).
     */
    fun streamingClient(baseUrl: String): OkHttpClient {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return buildOkHttpClient(normalizedUrl).newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // HTTP/2 pings: a connection that died under the stream (network
            // or VPN change) fails within seconds instead of staying silent.
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private fun buildClient(baseUrl: String): ApiClient = buildRetrofit(baseUrl, buildOkHttpClient(baseUrl))

    private fun buildRetrofit(baseUrl: String, okHttpClient: OkHttpClient): ApiClient {
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

    internal fun buildOkHttpClient(baseUrl: String): OkHttpClient {
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // Never log bodies: register/enroll responses carry the WireGuard
        // private key and API tokens. Debug builds log headers with all
        // credentials redacted, release builds log nothing.
        val logging = HttpLoggingInterceptor().apply {
            level = if (isDebuggable) HttpLoggingInterceptor.Level.HEADERS else HttpLoggingInterceptor.Level.NONE
            SENSITIVE_HEADERS.forEach { redactHeader(it) }
            // The device's LAN addresses of the home-network check.
            redactQueryParams("local")
        }

        return OkHttpClient.Builder()
            .dns(vpnSafeDns)
            .addInterceptor(authInterceptor)
            .addInterceptor(machineBindingMonitor)
            .addInterceptor(logging)
            // Network interceptor: runs per hop, so a redirect to another
            // host never carries the API token or the device fingerprint.
            .addNetworkInterceptor(ServerScopedHeadersInterceptor(baseUrl.toHttpUrl()) { machineFingerprint.get() })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
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
