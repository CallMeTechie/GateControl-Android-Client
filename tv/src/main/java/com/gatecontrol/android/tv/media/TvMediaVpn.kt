package com.gatecontrol.android.tv.media

import android.content.Context
import android.net.VpnService
import com.gatecontrol.android.media.MediaVpn
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tv.tunnel.TvTunnel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** The TV's tunnel for Medien away (Netz-Regel): started by [TvTunnel] when media are opened or played. */
@Singleton
class TvMediaVpn @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tunnel: TvTunnel,
) : MediaVpn {
    override fun up(): Boolean = tunnel.state.value is TunnelState.Connected

    override fun consentNeeded(): Boolean = runCatching { VpnService.prepare(context) != null }.getOrDefault(true)

    override suspend fun connect(): Boolean {
        if (up()) return true
        val state = tunnel.state.value
        if (state !is TunnelState.Connecting && state !is TunnelState.Reconnecting) {
            if (!tunnel.connect()) return up()
        }
        return withTimeoutOrNull(CONNECT_MS) {
            tunnel.state.first { it is TunnelState.Connected || it is TunnelState.Error || it is TunnelState.Disconnected }
        } is TunnelState.Connected
    }

    private companion object {
        const val CONNECT_MS = 25_000L
    }
}
