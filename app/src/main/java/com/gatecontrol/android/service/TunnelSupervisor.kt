package com.gatecontrol.android.service

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.gatecontrol.android.common.HostnameSanitizer
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.HeartbeatRequest
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelMonitor
import com.gatecontrol.android.tunnel.TunnelState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide owner of the tunnel lifecycle, independent of any screen:
 *
 * - answers Android's "Always-on VPN" start (boot, lockdown mode) by
 *   connecting with the user's settings,
 * - runs [TunnelMonitor] while connected and reconnects a dead tunnel,
 * - keeps the Quick Settings tile in sync with the real tunnel state.
 *
 * Started once from [com.gatecontrol.android.GateControlApp.onCreate].
 */
@Singleton
class TunnelSupervisor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tunnelManager: TunnelManager,
    private val tunnelConnector: TunnelConnector,
    private val tunnelMonitor: TunnelMonitor,
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val clientPolicyManager: ClientPolicyManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var heartbeatJob: Job? = null

    fun start() {
        if (started) return
        started = true

        tunnelManager.setAlwaysOnHandler {
            Timber.i("Always-on VPN start requested by the system")
            connectIfIdle()
        }

        // Client policy: last known one applies right away (persisted), then
        // ask the server. Required auto-connect / always-on bring the tunnel up
        // when the app starts or the policy arrives (VPN consent needed).
        clientPolicyManager.refreshAsync()
        scope.launch {
            clientPolicyManager.policy.collect { policy ->
                if (policy.autoConnect != com.gatecontrol.android.common.ClientPolicy.AutoConnect.USER) {
                    connectForPolicy()
                }
            }
        }

        scope.launch {
            tunnelManager.state.collect { state ->
                TunnelStateHolder.isConnected = state is TunnelState.Connected
                TunnelStateHolder.serverHost = serverHost()
                refreshTile()
                when (state) {
                    is TunnelState.Connected -> {
                        if (!tunnelMonitor.isRunning) startMonitor()
                        if (heartbeatJob?.isActive != true) startHeartbeat(state.connectedSince)
                    }
                    // User disconnect or the system took the VPN away: stop
                    // watching, never fight the user or another VPN app.
                    TunnelState.Disconnected -> {
                        tunnelMonitor.stop()
                        heartbeatJob?.cancel()
                        heartbeatJob = null
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Connect with the stored config unless a tunnel is already up or on its way. */
    fun connectIfIdle() {
        scope.launch {
            val state = tunnelManager.state.value
            if (state is TunnelState.Disconnected || state is TunnelState.Error) {
                tunnelConnector.connectWithUserSettings()
            }
        }
    }

    /** Connect because the client policy requires it (configured + VPN consent only). */
    private fun connectForPolicy() {
        if (!setupRepository.hasWireGuardConfig()) return
        if (android.net.VpnService.prepare(context) != null) {
            Timber.w("Client policy requires auto-connect, but VPN consent is missing")
            return
        }
        Timber.i("Client policy requires auto-connect — connecting")
        connectIfIdle()
    }

    /**
     * Disconnect on behalf of a short-lived caller (Quick Settings tile). Runs
     * in the app-wide scope so it completes even when the caller is unbound
     * right away; on failure the tile is refreshed to show the real state.
     */
    fun disconnect() {
        scope.launch {
            if (!clientPolicyManager.current().canDisconnect) {
                Timber.i("Disconnect refused: always-on client policy")
                refreshTile()
                return@launch
            }
            try {
                tunnelManager.disconnect()
            } catch (e: Exception) {
                Timber.e(e, "Disconnect failed")
                refreshTile()
            }
        }
    }

    private fun startMonitor() {
        tunnelMonitor.start(
            scope = scope,
            statsProvider = { tunnelManager.getStatistics() },
            reconnect = { attempt, max ->
                // The user may have disconnected meanwhile — never bring the tunnel back then.
                if (tunnelManager.state.value is TunnelState.Disconnected) {
                    tunnelMonitor.stop()
                    false
                } else {
                    tunnelManager.reconnect(attempt, max)
                }
            },
        )
    }

    /**
     * Report to the server while connected (last seen, traffic, hostname), the
     * same heartbeat the desktop clients send. When the server answers that
     * the peer was disabled, the tunnel is closed — also without the app open.
     */
    private fun startHeartbeat(connectedSince: Long) {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (tunnelManager.state.value !is TunnelState.Connected) continue
                val peerId = setupRepository.getPeerId()
                val serverUrl = setupRepository.getServerUrl()
                if (peerId <= 0 || serverUrl.isEmpty()) continue
                try {
                    val stats = tunnelManager.stats.value
                    val response = apiClientProvider.getClient(serverUrl).sendHeartbeat(
                        HeartbeatRequest(
                            peerId = peerId,
                            connected = true,
                            rxBytes = stats.rxBytes,
                            txBytes = stats.txBytes,
                            uptime = (System.currentTimeMillis() - connectedSince) / 1000,
                            hostname = HostnameSanitizer.sanitize(android.os.Build.MODEL).orEmpty(),
                        ),
                    )
                    clientPolicyManager.noteVersionAsync(response.policyVersion)
                    if (response.ok && response.peerEnabled == false) {
                        Timber.w("Peer disabled on server — disconnecting tunnel")
                        tunnelManager.disconnect()
                        apiClientProvider.clearDnsCache()
                    }
                } catch (e: Exception) {
                    Timber.d("Heartbeat failed: %s", e.message)
                }
            }
        }
    }

    private fun serverHost(): String? = runCatching {
        java.net.URI(setupRepository.getServerUrl()).host
    }.getOrNull()

    private fun refreshTile() {
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, VpnTileService::class.java))
        }
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 60_000L
    }
}
