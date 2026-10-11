package com.gatecontrol.android.notify

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import android.os.SystemClock
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

/** Where the push channel connects to (the configured GateControl server). */
interface PushEndpoint {
    /** Server base URL, empty when not set up. */
    fun serverUrl(): String

    /** Changes whenever server or token change (never the token itself). */
    fun credentialKey(): String

    fun api(serverUrl: String): ApiClient

    fun streamingClient(serverUrl: String): OkHttpClient
}

/** Android state the push client reacts to. */
interface PushPlatform {
    /** Battery optimisation is active for the app (Android may cut the connection in Doze). */
    fun isBatteryRestricted(): Boolean

    /**
     * Calls [onChange] when the default network changes (Wi-Fi ↔ mobile)
     * or a VPN comes up or goes down. Close the result to stop watching.
     */
    fun watchNetwork(onChange: (String) -> Unit): Closeable
}

@Singleton
class DefaultPushEndpoint @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) : PushEndpoint {
    private val streamingClients = mutableMapOf<String, OkHttpClient>()

    override fun serverUrl(): String = setupRepository.getServerUrl().trim()

    override fun credentialKey(): String =
        (setupRepository.getServerUrl() + "\u0000" + setupRepository.getApiToken()).hashCode().toString(16)

    override fun api(serverUrl: String): ApiClient = apiClientProvider.getClient(serverUrl)

    override fun streamingClient(serverUrl: String): OkHttpClient = synchronized(streamingClients) {
        streamingClients.getOrPut(serverUrl) { apiClientProvider.streamingClient(serverUrl) }
    }
}

@Singleton
class AndroidPushPlatform @Inject constructor(
    @ApplicationContext private val context: Context,
) : PushPlatform {
    private companion object {
        const val REGISTER_GRACE_MS = 3_000L
    }

    override fun isBatteryRestricted(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    override fun watchNetwork(onChange: (String) -> Unit): Closeable {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Closeable {}

        // The default network of this app (it is excluded from its own VPN):
        // a different network object means Wi-Fi ↔ mobile or a new Wi-Fi.
        val defaultCallback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            override fun onAvailable(network: Network) {
                val prev = current
                current = network
                if (prev != null && prev != network) onChange("default_network")
                else if (prev == null) onChange("network_available")
            }

            override fun onLost(network: Network) {
                if (current == network) current = null
            }
        }

        // Any VPN (ours or another app's) up or down. Registering reports the
        // VPNs that already exist — those are not a change.
        val registeredAt = SystemClock.elapsedRealtime()
        val vpnCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (SystemClock.elapsedRealtime() - registeredAt > REGISTER_GRACE_MS) onChange("vpn_up")
            }

            override fun onLost(network: Network) {
                onChange("vpn_down")
            }
        }
        val vpnRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val registered = mutableListOf<ConnectivityManager.NetworkCallback>()
        try {
            cm.registerDefaultNetworkCallback(defaultCallback)
            registered += defaultCallback
            cm.registerNetworkCallback(vpnRequest, vpnCallback)
            registered += vpnCallback
        } catch (e: Exception) {
            Timber.w("Push: network callback not registered (%s)", e.javaClass.simpleName)
        }
        return Closeable {
            registered.forEach { cb -> runCatching { cm.unregisterNetworkCallback(cb) } }
        }
    }
}
