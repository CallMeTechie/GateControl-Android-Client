package com.gatecontrol.android.service

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelMonitor
import com.gatecontrol.android.tunnel.TunnelState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    fun start() {
        if (started) return
        started = true

        tunnelManager.setAlwaysOnHandler {
            Timber.i("Always-on VPN start requested by the system")
            connectIfIdle()
        }

        scope.launch {
            tunnelManager.state.collect { state ->
                TunnelStateHolder.isConnected = state is TunnelState.Connected
                TunnelStateHolder.serverHost = serverHost()
                refreshTile()
                when (state) {
                    is TunnelState.Connected -> if (!tunnelMonitor.isRunning) startMonitor()
                    // User disconnect or the system took the VPN away: stop
                    // watching, never fight the user or another VPN app.
                    TunnelState.Disconnected -> tunnelMonitor.stop()
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

    private fun serverHost(): String? = runCatching {
        java.net.URI(setupRepository.getServerUrl()).host
    }.getOrNull()

    private fun refreshTile() {
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, VpnTileService::class.java))
        }
    }
}
