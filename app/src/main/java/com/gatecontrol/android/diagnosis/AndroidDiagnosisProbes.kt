package com.gatecontrol.android.diagnosis

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.DnsCheckResponse
import com.gatecontrol.android.network.SpeedTestAvailability
import com.gatecontrol.android.network.SpeedTestClient
import com.gatecontrol.android.network.SpeedTransfer
import com.gatecontrol.android.tunnel.TunnelConfig
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tunnel.TunnelStats
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class AndroidNetworkProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkProbe {

    private val cm: ConnectivityManager? get() = context.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION") // allNetworks: the only way to see the network under the VPN
    private fun networks(cm: ConnectivityManager): List<Pair<Network, NetworkCapabilities>> =
        cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }

    override fun internet(): InternetState? {
        val cm = cm ?: return null
        val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        // With the VPN up the default network is the VPN: judge the network underneath.
        val candidates = if (activeCaps == null || activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            networks(cm).map { it.second }.filter {
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } else {
            listOf(activeCaps)
        }
        val caps = candidates.firstOrNull { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            ?: candidates.firstOrNull()
            ?: activeCaps
            ?: return InternetState(connected = false, validated = false, transport = Transport.OTHER)
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            else -> Transport.OTHER
        }
        return InternetState(
            connected = true,
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            transport = transport,
        )
    }

    override fun vpnDns(): VpnDnsState {
        val cm = cm ?: return VpnDnsState(vpnNetworkFound = false, dnsServers = emptyList())
        val all = networks(cm)
        val active = cm.activeNetwork
        val vpn = all.firstOrNull { it.first == active && it.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }?.first
            ?: all.firstOrNull { it.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }?.first
        val underlying = all.firstOrNull {
            !it.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }?.first
        val vpnLp: LinkProperties? = vpn?.let { cm.getLinkProperties(it) }
        val baseLp: LinkProperties? = underlying?.let { cm.getLinkProperties(it) }
        return VpnDnsState(
            vpnNetworkFound = vpnLp != null,
            dnsServers = vpnLp?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            privateDnsActive = vpnLp?.isPrivateDnsActive == true || baseLp?.isPrivateDnsActive == true,
            // Only set in strict mode ("Private DNS provider hostname").
            privateDnsServerName = vpnLp?.privateDnsServerName ?: baseLp?.privateDnsServerName,
        )
    }
}

class ApiServerProbe @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) : ServerProbe {
    override fun isConfigured(): Boolean = setupRepository.getServerUrl().isNotBlank()

    override suspend fun ping(): Long {
        val client = apiClientProvider.getClient(setupRepository.getServerUrl())
        val start = System.nanoTime()
        client.ping()
        return ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1)
    }

    override suspend fun dnsCheck(): DnsCheckResponse =
        apiClientProvider.getClient(setupRepository.getServerUrl()).dnsCheck()
}

class WireGuardTunnelProbe @Inject constructor(
    private val tunnelManager: TunnelManager,
    private val setupRepository: SetupRepository,
) : TunnelProbe {
    override fun state(): TunnelState = tunnelManager.state.value

    override fun stats(): TunnelStats? = tunnelManager.getStatistics() ?: tunnelManager.stats.value

    override fun tunnelMtu(): Int = runCatching {
        TunnelConfig.parse(setupRepository.getWireGuardConfig()).mtu
    }.getOrNull()?.takeIf { it in MIN_PROBE_MTU..9000 } ?: DEFAULT_TUNNEL_MTU
}

/** Runs a command off the main thread; kills it after the timeout or on cancellation. */
class ProcessCommandRunner @Inject constructor() : CommandRunner {
    override suspend fun run(command: List<String>, timeoutMs: Long): CommandResult = withContext(Dispatchers.IO) {
        val binary = command.firstOrNull()?.let(::File)
        if (binary == null || !binary.canExecute()) return@withContext CommandResult(null, "not executable", started = false)
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return@withContext CommandResult(null, e.message.orEmpty(), started = false)
        }
        try {
            val finished = runInterruptible { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
            if (!finished) {
                CommandResult(null, "")
            } else {
                // ping prints a few lines: they fit the pipe buffer, reading after exit is safe.
                val output = process.inputStream.bufferedReader().use { it.readText() }.take(MAX_OUTPUT)
                CommandResult(process.exitValue(), output)
            }
        } finally {
            process.destroyForcibly()
        }
    }

    private companion object {
        const val MAX_OUTPUT = 4_000
    }
}

class ServerSpeedProbe @Inject constructor(
    private val setupRepository: SetupRepository,
    private val speedTestClient: SpeedTestClient,
) : SpeedProbe {
    override suspend fun info(): SpeedTestAvailability = speedTestClient.info(setupRepository.getServerUrl())

    override suspend fun download(bytes: Long, maxMillis: Long): SpeedTransfer =
        speedTestClient.download(setupRepository.getServerUrl(), bytes, maxMillis)

    override suspend fun upload(bytes: Long, maxMillis: Long): SpeedTransfer =
        speedTestClient.upload(setupRepository.getServerUrl(), bytes, maxMillis)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosisModule {
    @Binds abstract fun networkProbe(impl: AndroidNetworkProbe): NetworkProbe
    @Binds abstract fun serverProbe(impl: ApiServerProbe): ServerProbe
    @Binds abstract fun tunnelProbe(impl: WireGuardTunnelProbe): TunnelProbe
    @Binds abstract fun commandRunner(impl: ProcessCommandRunner): CommandRunner
    @Binds abstract fun speedProbe(impl: ServerSpeedProbe): SpeedProbe
}
