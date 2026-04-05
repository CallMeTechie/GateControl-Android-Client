package com.gatecontrol.android.tunnel

data class TunnelStats(
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val rxSpeed: Long = 0,
    val txSpeed: Long = 0,
    val lastHandshakeEpoch: Long = 0
) {
    val handshakeAgeSeconds: Long
        get() = if (lastHandshakeEpoch > 0) (System.currentTimeMillis() / 1000) - lastHandshakeEpoch
                else Long.MAX_VALUE
}
