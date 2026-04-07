package com.gatecontrol.android.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import timber.log.Timber

/** Shared state holder so the TileService can read tunnel state without DI. */
object TunnelStateHolder {
    @Volatile var isConnected: Boolean = false
    @Volatile var serverHost: String? = null
    @Volatile var tunnelManager: com.gatecontrol.android.tunnel.TunnelManager? = null
    @Volatile var setupRepository: com.gatecontrol.android.data.SetupRepository? = null
}

/**
 * Quick Settings tile for GateControl VPN.
 */
class VpnTileService : TileService() {

    // ---------------------------------------------------------------------------
    // Tile lifecycle
    // ---------------------------------------------------------------------------

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onStopListening() {
        super.onStopListening()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
    }

    // ---------------------------------------------------------------------------
    // Click handler
    // ---------------------------------------------------------------------------

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return

        when (tile.state) {
            Tile.STATE_ACTIVE -> {
                Timber.d("VpnTileService: disconnect requested")
                sendDisconnectBroadcast()
                // Tile will be updated by the state monitoring coroutine
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(com.gatecontrol.android.R.string.tile_not_connected)
                tile.updateTile()
            }
            Tile.STATE_INACTIVE -> {
                Timber.d("VpnTileService: connect requested")
                sendConnectBroadcast()
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = getString(com.gatecontrol.android.R.string.vpn_connecting)
                tile.updateTile()
            }
            else -> {
                // Reset from unavailable
                refreshTile()
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun refreshTile() {
        val tile = qsTile ?: return
        val connected = TunnelStateHolder.isConnected
        applyTileState(tile, connected, serverHost = TunnelStateHolder.serverHost)
    }

    private fun applyTileState(tile: Tile, connected: Boolean, serverHost: String?) {
        if (connected) {
            tile.state = Tile.STATE_ACTIVE
            tile.subtitle = serverHost ?: getString(com.gatecontrol.android.R.string.vpn_connected)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = getString(com.gatecontrol.android.R.string.tile_not_connected)
        }
        tile.updateTile()
    }

    private fun sendConnectBroadcast() {
        val tm = TunnelStateHolder.tunnelManager
        val repo = TunnelStateHolder.setupRepository
        if (tm == null || repo == null) {
            Timber.w("VpnTileService: TunnelManager or SetupRepository not available")
            // Launch app to connect
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityAndCollapse(android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                ))
            }
            return
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val config = repo.getWireGuardConfig()
                if (config.isNotEmpty()) {
                    tm.connect(config)
                }
            } catch (e: Exception) {
                Timber.e(e, "VpnTileService: connect failed")
            }
        }
    }

    private fun sendDisconnectBroadcast() {
        val tm = TunnelStateHolder.tunnelManager ?: return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                tm.disconnect()
            } catch (e: Exception) {
                Timber.e(e, "VpnTileService: disconnect failed")
            }
        }
    }
}
