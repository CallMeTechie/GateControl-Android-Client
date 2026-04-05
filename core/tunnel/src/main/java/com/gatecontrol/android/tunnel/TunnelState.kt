package com.gatecontrol.android.tunnel

sealed class TunnelState {
    data object Disconnected : TunnelState()
    data object Connecting : TunnelState()
    data class Connected(val connectedSince: Long = System.currentTimeMillis()) : TunnelState()
    data object Disconnecting : TunnelState()
    data class Error(val message: String) : TunnelState()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : TunnelState()
}
