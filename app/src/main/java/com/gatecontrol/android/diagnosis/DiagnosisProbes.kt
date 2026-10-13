package com.gatecontrol.android.diagnosis

import com.gatecontrol.android.network.DnsCheckResponse
import com.gatecontrol.android.network.SpeedTestAvailability
import com.gatecontrol.android.network.SpeedTransfer
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tunnel.TunnelStats

/*
 * Small seams between the DiagnosisRunner and Android / the server, so the
 * runner can be tested with fakes. Implementations: AndroidDiagnosisProbes.kt.
 */

/** ConnectivityManager facts. */
interface NetworkProbe {
    /** The device's own network (not the VPN), or null when there is none. */
    fun internet(): InternetState?

    /** DNS servers of the VPN network and the private DNS mode. */
    fun vpnDns(): VpnDnsState
}

/** The own GateControl server (client API). */
interface ServerProbe {
    fun isConfigured(): Boolean

    /** One `GET api/v1/client/ping`; returns the round trip in ms, throws on failure. */
    suspend fun ping(): Long

    /** `GET api/v1/client/dns-check`; throws on failure. */
    suspend fun dnsCheck(): DnsCheckResponse
}

/** The WireGuard tunnel. */
interface TunnelProbe {
    fun state(): TunnelState

    /** Fresh statistics from the backend (null when unavailable). */
    fun stats(): TunnelStats?

    /** Interface MTU of the stored WireGuard config (default 1420). */
    fun tunnelMtu(): Int
}

/** Runs an external command (the system ping), killed after [timeoutMs]. */
interface CommandRunner {
    suspend fun run(command: List<String>, timeoutMs: Long): CommandResult
}

/** Speed test endpoints of the own server. */
interface SpeedProbe {
    suspend fun info(): SpeedTestAvailability
    suspend fun download(bytes: Long, maxMillis: Long): SpeedTransfer
    suspend fun upload(bytes: Long, maxMillis: Long): SpeedTransfer
}
