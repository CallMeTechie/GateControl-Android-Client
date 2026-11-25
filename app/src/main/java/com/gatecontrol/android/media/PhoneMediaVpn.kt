package com.gatecontrol.android.media

import android.content.Context
import android.net.VpnService
import com.gatecontrol.android.service.TunnelConnector
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's tunnel for Medien away (Netz-Regel): started with the user's settings ([TunnelConnector], the same as
 * the VPN tab), waited for until it is up or failed.
 */
@Singleton
class PhoneMediaVpn @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tunnelManager: TunnelManager,
    private val tunnelConnector: TunnelConnector,
) : MediaVpn {
    override fun up(): Boolean = tunnelManager.state.value is TunnelState.Connected

    override fun consentNeeded(): Boolean = runCatching { VpnService.prepare(context) != null }.getOrDefault(true)

    override suspend fun connect(): Boolean {
        if (up()) return true
        val state = tunnelManager.state.value
        if (state !is TunnelState.Connecting && state !is TunnelState.Reconnecting) {
            if (!tunnelConnector.connectWithUserSettings()) return up()
        }
        return withTimeoutOrNull(CONNECT_MS) {
            tunnelManager.state.first { it is TunnelState.Connected || it is TunnelState.Error || it is TunnelState.Disconnected }
        } is TunnelState.Connected
    }

    private companion object {
        const val CONNECT_MS = 25_000L
    }
}
