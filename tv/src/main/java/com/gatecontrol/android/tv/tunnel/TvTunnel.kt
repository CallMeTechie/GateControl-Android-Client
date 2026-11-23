package com.gatecontrol.android.tv.tunnel

import com.gatecontrol.android.common.VpnSubnet
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.tunnel.SplitTunnelConfig
import com.gatecontrol.android.tunnel.TunnelConfig
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tunnel.WgConfigValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The WireGuard tunnel of GateControl TV over core/tunnel's [TunnelManager] (GoBackend, armeabi-v7a included). The
 * same steps as the phone app's `TunnelConnector`: the VPN subnet for the VPN-safe DNS, the server name resolved
 * before the tunnel is up, the config checked against the server (`config/check`) — then the whole config (the
 * server's AllowedIPs; a split tunnel of its own comes with the TV settings later).
 *
 * The VPN consent (`VpnService.prepare`) must have been given once in the app; without it the backend cannot start.
 */
@Singleton
class TvTunnel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val tunnelManager: TunnelManager,
) {
    val state: StateFlow<TunnelState> get() = tunnelManager.state

    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [connect] outside the caller's lifetime (a screen that goes away, the always-on callback). */
    fun connectDetached(): Job = detached.launch { connect() }

    fun disconnectDetached(): Job = detached.launch { tunnelManager.disconnect() }

    /** Brings the tunnel up with the stored config; false without a config or when the backend failed. */
    suspend fun connect(): Boolean {
        var config = setupRepository.getWireGuardConfig()
        if (config.isEmpty()) {
            Timber.w("TV tunnel: no WireGuard config")
            return false
        }
        applyVpnSubnet(config)
        val server = setupRepository.getServerUrl()
        if (server.isNotEmpty()) {
            runCatching { java.net.URI(server).host }.getOrNull()?.let { host ->
                runCatching { apiClientProvider.preResolveDns(host) }
            }
            config = refreshConfig(server, config)
            applyVpnSubnet(config)
        }
        return try {
            tunnelManager.connect(config, SplitTunnelConfig())
            tunnelManager.state.value is TunnelState.Connected
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TV tunnel: connect failed")
            false
        }
    }

    suspend fun disconnect() {
        tunnelManager.disconnect()
        apiClientProvider.clearDnsCache()
    }

    /**
     * The server says the WireGuard config changed (push or `hello.state`): the newer one is stored and used from the
     * next connect. True when there was a new one.
     */
    suspend fun syncConfig(): Boolean {
        val server = setupRepository.getServerUrl()
        val current = setupRepository.getWireGuardConfig()
        if (server.isEmpty() || current.isEmpty()) return false
        return refreshConfig(server, current) != current
    }

    private fun applyVpnSubnet(config: String) {
        val address = runCatching { TunnelConfig.parse(config).address }.getOrNull() ?: return
        apiClientProvider.vpnSubnet = VpnSubnet.fromAddress(address) ?: VpnSubnet.DEFAULT
    }

    /** The server's newer config, if it has one (best effort: any failure keeps the stored one). */
    private suspend fun refreshConfig(server: String, current: String): String {
        val peerId = setupRepository.getPeerId()
        if (peerId <= 0) return current
        return try {
            val response = apiClientProvider.getClient(server).checkConfigUpdate(peerId, setupRepository.getConfigHash().ifBlank { null })
            val fresh = response.config
            if (!response.updated || fresh.isNullOrBlank()) return current
            if (!WgConfigValidator.validate(fresh).ok || TunnelConfig.peerCount(fresh) != 1) {
                Timber.w("TV tunnel: server config rejected, keeping the stored one")
                return current
            }
            setupRepository.saveWireGuardConfig(fresh)
            response.hash?.let { setupRepository.saveConfigHash(it) }
            fresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("TV tunnel: config check failed (%s), using the stored one", e.javaClass.simpleName)
            current
        }
    }
}
