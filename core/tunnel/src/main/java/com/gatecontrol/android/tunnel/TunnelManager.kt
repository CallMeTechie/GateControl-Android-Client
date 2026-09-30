package com.gatecontrol.android.tunnel

import android.content.Context
import android.net.VpnService
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.InetAddresses
import com.wireguard.config.InetNetwork
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TunnelManager @Inject constructor(private val context: Context) {

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private var backend: Backend? = null
    private var tunnel: Tunnel? = null

    private var prevRxBytes: Long = 0L
    private var prevTxBytes: Long = 0L
    private var prevStatsTime: Long = 0L

    /** Serialises connect / reconnect / disconnect so they never interleave. */
    private val lifecycleMutex = Mutex()

    /** Last config brought up, reused by [reconnect]. */
    @Volatile private var lastConfig: String? = null
    @Volatile private var lastSplitConfig: SplitTunnelConfig = SplitTunnelConfig()

    fun initialize() {
        try {
            backend = GoBackend(context)
            tunnel = object : Tunnel {
                override fun getName(): String = TUNNEL_NAME
                override fun onStateChange(newState: Tunnel.State) {
                    Timber.d("Tunnel state changed: $newState")
                    // The backend reports DOWN on its own when the system
                    // tears the VPN down (another VPN app took over, the user
                    // revoked it in Android settings). Only react while we
                    // think the tunnel is up — during our own connect,
                    // reconnect or disconnect the state is managed there.
                    if (newState == Tunnel.State.DOWN && _state.value is TunnelState.Connected) {
                        Timber.w("Tunnel was stopped by the system")
                        resetCounters()
                        _state.value = TunnelState.Disconnected
                    }
                }
            }
            Timber.d("TunnelManager initialized")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize TunnelManager")
        }
    }

    /**
     * Legacy connect signature — kept for backward compatibility (BootReceiver, Tile, etc.).
     * Converts the old (routes, apps) parameters to a [SplitTunnelConfig] and delegates.
     */
    suspend fun connect(
        configString: String,
        splitTunnelRoutes: List<String> = emptyList(),
        excludedApps: List<String> = emptyList()
    ) {
        val splitConfig = if (splitTunnelRoutes.isNotEmpty() || excludedApps.isNotEmpty()) {
            SplitTunnelConfig(
                mode = "include",
                networks = splitTunnelRoutes,
                apps = excludedApps,
            )
        } else {
            SplitTunnelConfig() // mode = "off"
        }
        connectInternal(configString, splitConfig)
    }

    /**
     * New connect signature accepting a full [SplitTunnelConfig] with mode-aware routing.
     */
    suspend fun connect(configString: String, splitConfig: SplitTunnelConfig) {
        connectInternal(configString, splitConfig)
    }

    /**
     * Bring the last connected config up again. A fresh [Config] is built, so
     * the backend restarts the tunnel and resolves the endpoint hostname anew
     * (the server may have moved to a new IP behind DDNS). Returns false when
     * there is nothing to reconnect or the attempt failed.
     */
    suspend fun reconnect(attempt: Int, maxAttempts: Int): Boolean {
        val config = lastConfig ?: return false
        connectInternal(config, lastSplitConfig, TunnelState.Reconnecting(attempt, maxAttempts))
        return _state.value is TunnelState.Connected
    }

    /** Called by the system's "Always-on VPN" when it starts our VPN service. */
    fun setAlwaysOnHandler(handler: () -> Unit) {
        GoBackend.setAlwaysOnCallback { handler() }
    }

    private suspend fun connectInternal(
        configString: String,
        splitConfig: SplitTunnelConfig,
        pendingState: TunnelState = TunnelState.Connecting,
    ) = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val parsedConfig = TunnelConfig.parse(configString)
                val wgConfig = buildWgConfig(parsedConfig, splitConfig)

                _state.value = pendingState
                Timber.d("Connecting tunnel with split-tunnel mode: ${splitConfig.mode}")

                val currentBackend = backend ?: run {
                    initialize()
                    backend
                } ?: throw IllegalStateException("Backend not available")

                val currentTunnel = tunnel
                    ?: throw IllegalStateException("Tunnel not initialized")

                currentBackend.setState(currentTunnel, Tunnel.State.UP, wgConfig)
                lastConfig = configString
                lastSplitConfig = splitConfig

                prevRxBytes = 0L
                prevTxBytes = 0L
                prevStatsTime = System.currentTimeMillis()

                // The interface is up, but only a handshake proves the server
                // answers. Stay in the pending state until one arrives (or the
                // wait runs out — an idle tunnel without keepalive may not
                // handshake before the first packet; the monitor catches a
                // peer that really is dead).
                if (awaitHandshake(currentBackend, currentTunnel)) {
                    Timber.i("Tunnel connected, handshake completed")
                } else {
                    Timber.w("Tunnel up, but no handshake within ${HANDSHAKE_WAIT_MS / 1000} s")
                }
                _state.value = TunnelState.Connected()
            } catch (e: Exception) {
                Timber.e(e, "Failed to connect tunnel")
                _state.value = TunnelState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun awaitHandshake(backend: Backend, tunnel: Tunnel): Boolean {
        val deadline = System.currentTimeMillis() + HANDSHAKE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val handshake = runCatching { latestHandshakeMillis(backend.getStatistics(tunnel)) }.getOrDefault(0L)
            if (handshake > 0L) return true
            delay(HANDSHAKE_POLL_MS)
        }
        return false
    }

    private fun resetCounters() {
        _stats.value = TunnelStats()
        prevRxBytes = 0L
        prevTxBytes = 0L
        prevStatsTime = 0L
    }

    suspend fun disconnect() = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                _state.value = TunnelState.Disconnecting
                Timber.d("Disconnecting tunnel...")

                val currentBackend = backend
                val currentTunnel = tunnel

                if (currentBackend != null && currentTunnel != null) {
                    currentBackend.setState(currentTunnel, Tunnel.State.DOWN, null)
                }
                lastConfig = null

                resetCounters()

                _state.value = TunnelState.Disconnected
                Timber.i("Tunnel disconnected")
            } catch (e: Exception) {
                Timber.e(e, "Failed to disconnect tunnel")
                _state.value = TunnelState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun getStatistics(): TunnelStats? {
        return try {
            val currentBackend = backend ?: return null
            val currentTunnel = tunnel ?: return null

            if (_state.value !is TunnelState.Connected) return null

            val statistics = currentBackend.getStatistics(currentTunnel)
            val now = System.currentTimeMillis()
            val elapsedSec = if (prevStatsTime > 0) (now - prevStatsTime) / 1000.0 else 1.0

            val totalRx = statistics.totalRx()
            val totalTx = statistics.totalTx()

            val latestHandshake = latestHandshakeMillis(statistics)

            val rxSpeed = if (elapsedSec > 0) ((totalRx - prevRxBytes) / elapsedSec).toLong() else 0L
            val txSpeed = if (elapsedSec > 0) ((totalTx - prevTxBytes) / elapsedSec).toLong() else 0L

            prevRxBytes = totalRx
            prevTxBytes = totalTx
            prevStatsTime = now

            val tunnelStats = TunnelStats(
                rxBytes = totalRx,
                txBytes = totalTx,
                rxSpeed = maxOf(0L, rxSpeed),
                txSpeed = maxOf(0L, txSpeed),
                lastHandshakeEpoch = latestHandshake / 1000
            )

            _stats.value = tunnelStats
            tunnelStats
        } catch (e: Exception) {
            Timber.e(e, "Failed to get tunnel statistics")
            null
        }
    }

    fun isConnected(): Boolean = _state.value is TunnelState.Connected

    private fun latestHandshakeMillis(statistics: Statistics): Long =
        statistics.peers().maxOfOrNull { key -> statistics.peer(key)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L

    private fun buildWgConfig(
        parsed: TunnelConfig,
        splitConfig: SplitTunnelConfig,
    ): Config {
        val ifaceBuilder = Interface.Builder()
            .parsePrivateKey(parsed.privateKey)
            .parseAddresses(parsed.address)

        parsed.dns.forEach { dns ->
            ifaceBuilder.parseDnsServers(dns)
        }
        parsed.mtu?.let { ifaceBuilder.setMtu(it) }

        // App filtering — excludeApplications and includeApplications are mutually exclusive
        when (splitConfig.mode) {
            "exclude" -> {
                if (splitConfig.apps.isNotEmpty()) {
                    ifaceBuilder.excludeApplications(splitConfig.apps.toSet())
                }
            }
            "include" -> {
                if (splitConfig.apps.isNotEmpty()) {
                    ifaceBuilder.includeApplications(splitConfig.apps.toSet())
                }
            }
            // "off" — no app filtering
        }

        val peerBuilder = Peer.Builder()
            .parsePublicKey(parsed.publicKey)
            .parseEndpoint(parsed.endpoint)

        parsed.presharedKey?.let { peerBuilder.parsePreSharedKey(it) }
        parsed.persistentKeepalive?.let { peerBuilder.setPersistentKeepalive(it) }

        // DNS IPs as /32 (or /128 for IPv6) — always included to prevent DNS leaks
        val dnsIps = parsed.dns.map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.contains(":")) "$it/128" else "$it/32" }

        val allowedIpsRaw = when (splitConfig.mode) {
            "exclude" -> {
                if (splitConfig.networks.isEmpty()) {
                    // No networks excluded — full tunnel (use original AllowedIPs)
                    parsed.allowedIps
                } else {
                    // Compute complement: 0.0.0.0/0 minus excluded networks (IPv4)
                    val complement = CidrComplement.computeAllowedIps(splitConfig.networks)
                    // Always include ::/0 to prevent IPv6 leaks — exclude mode means
                    // "everything through VPN except these networks", so IPv6 must also
                    // be tunneled. Also add DNS + VPN subnet to prevent DNS leaks.
                    (complement + listOf("::/0") + dnsIps + VPN_SUBNET).distinct().joinToString(",")
                }
            }
            "include" -> {
                // Only route specified networks + DNS + VPN subnet
                (splitConfig.networks + dnsIps + VPN_SUBNET).distinct().joinToString(",")
            }
            else -> {
                // Off — use original AllowedIPs from WG config
                parsed.allowedIps
            }
        }
        peerBuilder.parseAllowedIPs(allowedIpsRaw)

        return Config.Builder()
            .setInterface(ifaceBuilder.build())
            .addPeer(peerBuilder.build())
            .build()
    }

    companion object {
        private const val TUNNEL_NAME = "gatecontrol"
        private const val VPN_SUBNET = "10.8.0.0/24"
        private const val HANDSHAKE_WAIT_MS = 10_000L
        private const val HANDSHAKE_POLL_MS = 250L
    }
}
