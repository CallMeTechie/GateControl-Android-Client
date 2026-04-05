package com.gatecontrol.android.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import timber.log.Timber

/**
 * Quick Settings tile for GateControl VPN.
 *
 * Tile states:
 *  - STATE_ACTIVE   — VPN connected; subtitle shows the server host.
 *  - STATE_INACTIVE — VPN not connected.
 *  - STATE_UNAVAILABLE — used transiently while toggling.
 *
 * Real connection state must be supplied via a shared singleton / broadcast
 * once the WireGuard integration layer is wired in.  For now the tile tracks
 * its own local boolean as a placeholder.
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
                tile.state = Tile.STATE_INACTIVE
                tile.updateTile()
                sendDisconnectBroadcast()
            }
            Tile.STATE_INACTIVE -> {
                Timber.d("VpnTileService: connect requested")
                tile.state = Tile.STATE_UNAVAILABLE
                tile.updateTile()
                sendConnectBroadcast()
            }
            else -> { /* unavailable — ignore */ }
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun refreshTile() {
        val tile = qsTile ?: return
        // TODO: query real tunnel state from a shared state holder once available
        val connected = false
        applyTileState(tile, connected, serverHost = null)
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
        val intent = Intent(ACTION_VPN_CONNECT).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun sendDisconnectBroadcast() {
        val intent = Intent(VpnForegroundService.ACTION_DISCONNECT).apply {
            setPackage(packageName)
        }
        startService(Intent(this, VpnForegroundService::class.java).apply {
            action = VpnForegroundService.ACTION_DISCONNECT
        })
    }

    companion object {
        const val ACTION_VPN_CONNECT = "com.gatecontrol.android.ACTION_VPN_CONNECT"
    }
}
