package com.gatecontrol.android.trustednet

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiInfo
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.HomeNetworkStatus
import com.gatecontrol.android.network.probeHomeNetwork
import com.gatecontrol.android.service.ClientPolicyManager
import com.gatecontrol.android.service.TunnelConnector
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** The non-VPN network the device uses right now. */
data class TrustedNetSnapshot(
    val kind: Kind = Kind.NONE,
    val wifiHandle: Long? = null,
    /** Normalised SSID; null without location permission or when Android hides it. */
    val ssid: String? = null,
) {
    enum class Kind { NONE, WIFI, OTHER }

    val key: String? get() = TrustedNetworkRules.networkKey(wifiHandle, kind == Kind.OTHER)
}

/** State of the "Heimnetz des Servers" check, for the UI. */
enum class HomeCheck {
    OFF,
    CHECKING,
    HOME,
    NOT_HOME,
    UNSUPPORTED,
    /** Asked through the tunnel, no usable answer (error, timeout). */
    FAILED,
    /** VPN down and nothing known yet: checked the next time the tunnel is up. */
    NEXT_CONNECT,
}

/** Last evaluation, for the UI. */
data class TrustedNetStatus(
    val key: String? = null,
    val home: HomeCheck = HomeCheck.OFF,
    /** true / false / null = not known yet or feature off. */
    val trusted: Boolean? = null,
    val trustedBySsid: Boolean = false,
)

/**
 * "Vertrauenswürdige WLANs": disconnects the VPN when the device joins a
 * trusted WLAN and connects it again when it leaves (other WLAN, mobile
 * data). A WLAN is trusted when its SSID is on the user's list or the
 * server confirms, for a request through the connected tunnel, that the
 * device is in its LAN (`GET api/v1/client/home-network`).
 *
 * Home detection on a new WLAN: VPN down → connect first (untrusted until
 * proven otherwise), VPN up → after a short settle delay ask the server
 * through the tunnel; home → disconnect, anything else → stay connected.
 * The network is then remembered as handled ([TrustedNetworkRules.decide]),
 * so nothing loops and a manual connect / disconnect on the same network
 * is never undone. Started once from
 * [com.gatecontrol.android.GateControlApp.onCreate].
 */
@Singleton
class TrustedNetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val tunnelManager: TunnelManager,
    private val tunnelConnector: TunnelConnector,
    private val clientPolicyManager: ClientPolicyManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectivity: ConnectivityManager? by lazy { context.getSystemService(ConnectivityManager::class.java) }
    private val networks = ConcurrentHashMap<Network, NetworkCapabilities>()
    /** Home answers through the tunnel, per network connection (handle-based key). */
    private val probeCache = ConcurrentHashMap<String, HomeProbe>()
    private val evalLock = Mutex()
    /** Network on which we already brought the tunnel up for the home check. */
    @Volatile private var connectAttemptKey: String? = null
    private val refreshTick = MutableStateFlow(0L)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var started = false

    private val _network = MutableStateFlow(TrustedNetSnapshot())
    /** Live network (not debounced). */
    val network: StateFlow<TrustedNetSnapshot> = _network.asStateFlow()

    private val _status = MutableStateFlow(TrustedNetStatus())
    val status: StateFlow<TrustedNetStatus> = _status.asStateFlow()

    @OptIn(FlowPreview::class)
    @Synchronized
    fun start() {
        if (started) return
        started = true
        registerCallback()
        scope.launch {
            combine(
                _network,
                // The key we write ourselves is no reason to look again.
                settingsRepository.getTrustedNetworkSettings().map { it.copy(lastActedKey = null) }.distinctUntilChanged(),
                clientPolicyManager.policy.map { it.canDisconnect }.distinctUntilChanged(),
                // Tunnel up / down continues a running home check.
                tunnelManager.state.map { it.phase() }.distinctUntilChanged(),
                refreshTick,
            ) { _, _, _, _, _ -> Unit }
                .debounce(DEBOUNCE_MS)
                .collect { runEvaluation() }
        }
    }

    /**
     * Look again now, e.g. after a location permission was granted: the
     * callback is registered anew, because Android decides at registration
     * whether it may deliver the SSID.
     */
    @Synchronized
    fun refresh() {
        if (!started) return
        callback?.let { cb -> runCatching { connectivity?.unregisterNetworkCallback(cb) } }
        callback = null
        networks.clear()
        registerCallback()
        refreshTick.update { it + 1 }
    }

    private fun registerCallback() {
        val cm = connectivity ?: return
        // Default requests exclude VPN networks: this sees the real uplink.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                networks[network] = capabilities
                publish()
            }

            override fun onLost(network: Network) {
                networks.remove(network)
                publish()
            }
        }
        try {
            cm.registerNetworkCallback(request, cb)
            callback = cb
        } catch (e: Exception) {
            Timber.w(e, "TrustedNet: network callback registration failed")
        }
    }

    private fun publish() {
        val wifi = networks.entries
            .filter { it.value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            .maxByOrNull { if (it.value.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1 else 0 }
        _network.value = when {
            wifi != null -> TrustedNetSnapshot(
                kind = TrustedNetSnapshot.Kind.WIFI,
                wifiHandle = wifi.key.networkHandle,
                ssid = TrustedNetworkRules.normalizeSsid((wifi.value.transportInfo as? WifiInfo)?.ssid),
            )
            networks.isNotEmpty() -> TrustedNetSnapshot(kind = TrustedNetSnapshot.Kind.OTHER)
            else -> TrustedNetSnapshot()
        }
    }

    private suspend fun runEvaluation() = evalLock.withLock {
        try {
            evaluate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "TrustedNet: evaluation failed")
        }
    }

    private suspend fun evaluate() {
        val snap = _network.value
        val key = snap.key
        val settings = settingsRepository.getTrustedNetworkSettings().first()
        val canDisconnect = clientPolicyManager.current().canDisconnect
        val isWifi = snap.kind == TrustedNetSnapshot.Kind.WIFI
        val ssidTrusted = isWifi && TrustedNetworkRules.ssidMatches(snap.ssid, settings.ssids)
        val homeMode = settings.enabled && settings.homeEnabled && isWifi
        val prepared = runCatching { VpnService.prepare(context) == null }.getOrDefault(false)
        val configured = setupRepository.hasWireGuardConfig()

        var probe: HomeProbe? = key?.let { probeCache[it] }
        var decision: TrustedNetDecision
        while (true) {
            val phase = tunnelManager.state.value.phase()
            decision = TrustedNetworkRules.decide(
                previousKey = settings.lastActedKey,
                currentKey = key,
                ssidTrusted = ssidTrusted,
                homeMode = homeMode,
                tunnel = phase,
                homeProbe = probe,
                canDisconnect = canDisconnect,
                prepared = prepared,
                configured = configured,
                enabled = settings.enabled,
                connectAttempted = key != null && key == connectAttemptKey,
            )
            if (decision.action != TrustedNetAction.PROBE || key == null) break
            probe = probeHome(key) ?: run {
                // Network or tunnel changed while settling: the next trigger takes over.
                publishStatus(key, settings.enabled && canDisconnect, ssidTrusted, homeMode, probe)
                return
            }
        }

        // Same network, tunnel up, nothing known yet: ask for the status line only.
        if (decision.reason == TrustedNetDecision.Reason.SAME_NETWORK && homeMode && canDisconnect && probe == null &&
            key != null && tunnelManager.state.value.phase() == TunnelPhase.CONNECTED
        ) {
            probe = probeHome(key, settle = false)
        }
        publishStatus(key, settings.enabled && canDisconnect, ssidTrusted, homeMode, probe)

        if (decision.reason == TrustedNetDecision.Reason.SAME_NETWORK ||
            decision.reason == TrustedNetDecision.Reason.NO_NETWORK
        ) {
            return
        }

        // Remember the network before acting: a crash mid-connect must not
        // make the next start act on the same network again.
        if (decision.commitKey && key != null) {
            settingsRepository.setTrustedLastActedKey(key)
            connectAttemptKey = null
        }

        if (settings.enabled && decision.reason != TrustedNetDecision.Reason.WAITING_FOR_TUNNEL) {
            Timber.i(
                "TrustedNet: %s (%s) → %s",
                trustLabel(isWifi, ssidTrusted, homeMode, probe),
                source(isWifi, ssidTrusted, homeMode, probe),
                describe(decision, homeMode && !ssidTrusted),
            )
        }

        when (decision.action) {
            TrustedNetAction.DISCONNECT -> disconnect()
            TrustedNetAction.CONNECT -> {
                if (!decision.commitKey) connectAttemptKey = key
                connect()
            }
            TrustedNetAction.NONE, TrustedNetAction.PROBE -> Unit
        }
    }

    private fun publishStatus(key: String?, active: Boolean, ssidTrusted: Boolean, homeMode: Boolean, probe: HomeProbe?) {
        val home = homeCheck(active && homeMode, probe)
        _status.value = TrustedNetStatus(
            key = key,
            home = home,
            trusted = when {
                !active || key == null -> null
                ssidTrusted -> true
                !homeMode -> false
                probe == null -> null
                else -> probe.trusted
            },
            trustedBySsid = ssidTrusted,
        )
    }

    private suspend fun disconnect() {
        // Policy may have changed since the decision.
        if (!clientPolicyManager.current().canDisconnect) return
        try {
            tunnelManager.disconnect()
        } catch (e: Exception) {
            Timber.e(e, "TrustedNet: disconnect failed")
        }
    }

    private suspend fun connect() {
        val state = tunnelManager.state.value
        if (state !is TunnelState.Disconnected && state !is TunnelState.Error) return
        try {
            if (!tunnelConnector.connectWithUserSettings()) Timber.w("TrustedNet: connect aborted")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TrustedNet: connect failed")
        }
    }

    /**
     * Home check through the connected tunnel, after a short settle delay so
     * WireGuard has roamed to the new endpoint. Null when the network or the
     * tunnel changed meanwhile. Answers are cached for the network
     * connection; failures are not.
     */
    private suspend fun probeHome(key: String, settle: Boolean = true): HomeProbe? {
        _status.update { it.copy(key = key, home = HomeCheck.CHECKING) }
        if (settle) delay(SETTLE_MS)
        if (_network.value.key != key) return null
        if (tunnelManager.state.value.phase() != TunnelPhase.CONNECTED) return null
        val serverUrl = setupRepository.getServerUrl()
        val status = if (serverUrl.isBlank()) {
            HomeNetworkStatus.Unknown
        } else {
            try {
                apiClientProvider.getClient(serverUrl).probeHomeNetwork()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HomeNetworkStatus.Unknown
            }
        }
        // Only an answer that went through the tunnel from start to end counts.
        val throughTunnel = tunnelManager.state.value.phase() == TunnelPhase.CONNECTED && _network.value.key == key
        val probe = HomeProbe(status, throughTunnel)
        if (probeCache.size > MAX_CACHE) probeCache.clear()
        if (throughTunnel && status != HomeNetworkStatus.Unknown) probeCache[key] = probe
        return probe
    }

    private fun homeCheck(active: Boolean, probe: HomeProbe?): HomeCheck = when {
        !active -> HomeCheck.OFF
        probe == null -> if (tunnelManager.state.value.phase() == TunnelPhase.CONNECTED) HomeCheck.CHECKING else HomeCheck.NEXT_CONNECT
        probe.trusted -> HomeCheck.HOME
        probe.status == HomeNetworkStatus.Unsupported -> HomeCheck.UNSUPPORTED
        probe.status == HomeNetworkStatus.Unknown -> HomeCheck.FAILED
        else -> HomeCheck.NOT_HOME
    }

    private fun trustLabel(isWifi: Boolean, ssidTrusted: Boolean, homeMode: Boolean, probe: HomeProbe?): String = when {
        ssidTrusted || probe?.trusted == true -> "trusted"
        !isWifi || !homeMode || probe != null -> "untrusted"
        else -> "not yet known"
    }

    /** Log detail without the SSID (privacy). */
    private fun source(isWifi: Boolean, ssidTrusted: Boolean, homeMode: Boolean, probe: HomeProbe?): String = when {
        !isWifi -> "mobile/other"
        ssidTrusted -> "ssid"
        !homeMode -> "no ssid match"
        probe == null -> "home, needs the tunnel"
        probe.trusted -> "home"
        probe.status == HomeNetworkStatus.Unsupported -> "home check unsupported"
        probe.status == HomeNetworkStatus.Unknown -> "home check failed"
        probe.status is HomeNetworkStatus.Home -> "home answer not via tunnel, ignored"
        else -> "not home"
    }

    private fun describe(decision: TrustedNetDecision, homeCheck: Boolean): String = when (decision.action) {
        TrustedNetAction.DISCONNECT -> "disconnect"
        TrustedNetAction.CONNECT ->
            if (decision.reason == TrustedNetDecision.Reason.CONNECT_FOR_HOME_CHECK) "connect, then check home" else "connect"
        TrustedNetAction.PROBE -> "check home"
        TrustedNetAction.NONE -> when (decision.reason) {
            TrustedNetDecision.Reason.NO_VPN_CONSENT, TrustedNetDecision.Reason.NOT_CONFIGURED,
            TrustedNetDecision.Reason.TUNNEL_NOT_UP,
            -> if (!homeCheck) "no action (" + decision.reason.name.lowercase().replace('_', ' ') + ")" else "no action (" + decision.reason.name.lowercase().replace('_', ' ') + "; home check can't run)"
            else -> "no action (" + decision.reason.name.lowercase().replace('_', ' ') + ")"
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 3_000L
        /** Lets WireGuard roam to the new endpoint before the home check. */
        const val SETTLE_MS = 2_500L
        const val MAX_CACHE = 16
    }
}

private fun TunnelState.phase(): TunnelPhase = when (this) {
    is TunnelState.Connected -> TunnelPhase.CONNECTED
    TunnelState.Connecting, is TunnelState.Reconnecting -> TunnelPhase.CONNECTING
    TunnelState.Disconnected, TunnelState.Disconnecting, is TunnelState.Error -> TunnelPhase.DOWN
}
