package com.gatecontrol.android.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    /**
     * Runs a tile action through the non-exported [TileActionActivity]. The
     * exported MainActivity no longer accepts tile actions, so other apps
     * cannot toggle the VPN with a crafted intent.
     */
    private fun launchTileAction(action: TileAction) {
        val intent = TileActionActivity.intent(this, action)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val pi = android.app.PendingIntent.getActivity(
                this, action.ordinal, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun sendConnectBroadcast() {
        // Disconnect can be done directly (no permission needed)
        // Connect requires VPN permission → must go through Activity
        launchTileAction(TileAction.CONNECT)
    }

    private fun sendDisconnectBroadcast() {
        // Try direct disconnect first, fall back to app launch
        val tm = TunnelStateHolder.tunnelManager
        if (tm != null && TunnelStateHolder.isConnected) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    tm.disconnect()
                } catch (e: Exception) {
                    Timber.e(e, "VpnTileService: direct disconnect failed, launching app")
                    launchTileAction(TileAction.DISCONNECT)
                }
            }
        } else {
            launchTileAction(TileAction.DISCONNECT)
        }
    }

    companion object {
        const val ACTION_TILE_CONNECT = "tile_connect"
        const val ACTION_TILE_DISCONNECT = "tile_disconnect"
    }
}
