package com.gatecontrol.android.service

import com.gatecontrol.android.tunnel.TunnelManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Actions the Quick Settings tile can request. */
enum class TileAction(val wireValue: String) {
    CONNECT(VpnTileService.ACTION_TILE_CONNECT),
    DISCONNECT(VpnTileService.ACTION_TILE_DISCONNECT),
    ;

    companion object {
        /** Unknown or missing values are ignored (null), never guessed. */
        fun parse(raw: String?): TileAction? = entries.firstOrNull { it.wireValue == raw }
    }
}

/**
 * Executes tile actions. Only reachable through [TileActionActivity], which is
 * not exported, so no other app can connect or disconnect the VPN by sending
 * an intent.
 */
@Singleton
class TileActionHandler @Inject constructor(
    private val tunnelConnector: TunnelConnector,
    private val tunnelManager: TunnelManager,
) {

    /**
     * Brings the tunnel up with the user's split-tunnel settings. The VPN
     * permission must already be granted (checked by the caller).
     */
    suspend fun connect(): Boolean = try {
        tunnelConnector.connectWithUserSettings().also { started ->
            if (started) Timber.d("Tile connect succeeded") else Timber.w("Tile connect aborted (no config)")
        }
    } catch (e: Exception) {
        Timber.e(e, "Tile connect failed")
        false
    }

    suspend fun disconnect() {
        try {
            tunnelManager.disconnect()
            Timber.d("Tile disconnect succeeded")
        } catch (e: Exception) {
            Timber.e(e, "Tile disconnect failed")
        }
    }
}
