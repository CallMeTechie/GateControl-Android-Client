package com.gatecontrol.android.tunnel

import android.content.Context
import android.net.VpnService
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.InetAddresses
import com.wireguard.config.InetNetwork
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun initialize() {
        try {
            backend = GoBackend(context)
            tunnel = object : Tunnel {
                override fun getName(): String = TUNNEL_NAME
                override fun onStateChange(newState: Tunnel.State) {
                    Timber.d("Tunnel state changed: $newState")
                }
            }
            Timber.d("TunnelManager initialized")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize TunnelManager")
        }
    }

    suspend fun connect(
        configString: String,
        splitTunnelRoutes: List<String> = emptyList(),
        excludedApps: List<String> = emptyList()
    ) {
        withContext(Dispatchers.IO) {
            try {
                val parsedConfig = TunnelConfig.parse(configString)
                val wgConfig = buildWgConfig(parsedConfig, splitTunnelRoutes, excludedApps)

                _state.value = TunnelState.Connecting
                Timber.d("Connecting tunnel...")

                val currentBackend = backend ?: run {
                    initialize()
                    backend
                } ?: throw IllegalStateException("Backend not available")

                val currentTunnel = tunnel
                    ?: throw IllegalStateException("Tunnel not initialized")

                currentBackend.setState(currentTunnel, Tunnel.State.UP, wgConfig)

                prevRxBytes = 0L
                prevTxBytes = 0L
                prevStatsTime = System.currentTimeMillis()

                _state.value = TunnelState.Connected()
                Timber.i("Tunnel connected successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to connect tunnel")
                _state.value = TunnelState.Error(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                _state.value = TunnelState.Disconnecting
                Timber.d("Disconnecting tunnel...")

                val currentBackend = backend
                val currentTunnel = tunnel

                if (currentBackend != null && currentTunnel != null) {
                    currentBackend.setState(currentTunnel, Tunnel.State.DOWN, null)
                }

                _stats.value = TunnelStats()
                prevRxBytes = 0L
                prevTxBytes = 0L
                prevStatsTime = 0L

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

            // Get latest handshake from peer statistics via reflection
            // (API varies across WireGuard library versions)
            var latestHandshake = 0L
            try {
                val peersMethod = statistics.javaClass.getMethod("peers")
                val peerKeys = peersMethod.invoke(statistics) as? Set<*>
                peerKeys?.forEach { key ->
                    try {
                        val peerMethod = statistics.javaClass.getMethod("peer", key!!.javaClass)
                        val peerStats = peerMethod.invoke(statistics, key)
                        if (peerStats != null) {
                            val hsField = peerStats.javaClass.getField("latestHandshakeEpochMillis")
                            val hs = hsField.getLong(peerStats)
                            if (hs > latestHandshake) latestHandshake = hs
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) {
                Timber.d("Handshake timestamp not available from Statistics API")
            }

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

    private fun buildWgConfig(
        parsed: TunnelConfig,
        splitTunnelRoutes: List<String>,
        excludedApps: List<String>
    ): Config {
        val ifaceBuilder = Interface.Builder()
            .parsePrivateKey(parsed.privateKey)
            .parseAddresses(parsed.address)

        parsed.dns.forEach { dns ->
            ifaceBuilder.parseDnsServers(dns)
        }
        parsed.mtu?.let { ifaceBuilder.setMtu(it) }

        if (excludedApps.isNotEmpty()) {
            ifaceBuilder.excludeApplications(excludedApps.toSet())
        }

        val peerBuilder = Peer.Builder()
            .parsePublicKey(parsed.publicKey)
            .parseEndpoint(parsed.endpoint)

        parsed.presharedKey?.let { peerBuilder.parsePreSharedKey(it) }
        parsed.persistentKeepalive?.let { peerBuilder.setPersistentKeepalive(it) }

        val allowedIpsRaw = if (splitTunnelRoutes.isNotEmpty()) {
            splitTunnelRoutes.joinToString(",")
        } else {
            parsed.allowedIps
        }
        peerBuilder.parseAllowedIPs(allowedIpsRaw)

        return Config.Builder()
            .setInterface(ifaceBuilder.build())
            .addPeer(peerBuilder.build())
            .build()
    }

    companion object {
        private const val TUNNEL_NAME = "gatecontrol"
    }
}
