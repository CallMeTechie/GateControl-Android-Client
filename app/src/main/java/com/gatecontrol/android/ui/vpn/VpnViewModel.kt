package com.gatecontrol.android.ui.vpn

import com.gatecontrol.android.common.SplitTunnelMode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.PermissionFlags
import com.gatecontrol.android.network.TrafficStats
import com.gatecontrol.android.network.VpnService
import com.gatecontrol.android.common.ClientPolicy
import com.gatecontrol.android.service.ClientPolicyManager
import com.gatecontrol.android.service.TunnelConnector
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tunnel.TunnelStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class VpnViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val settingsRepository: SettingsRepository,
    private val licenseRepository: LicenseRepository,
    private val apiClientProvider: ApiClientProvider,
    private val tunnelManager: TunnelManager,
    private val tunnelConnector: TunnelConnector,
    private val clientPolicyManager: ClientPolicyManager,
) : ViewModel() {

    /** Client policy from the server (unrestricted until one was fetched). */
    val clientPolicy: StateFlow<ClientPolicy> = clientPolicyManager.policy

    val tunnelState: StateFlow<TunnelState> = tunnelManager.state

    private val _stats = MutableStateFlow(TunnelStats())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private val _trafficUsage = MutableStateFlow<TrafficStats?>(null)
    val trafficUsage: StateFlow<TrafficStats?> = _trafficUsage.asStateFlow()

    private val _permissions = MutableStateFlow(PermissionFlags(
        services = false,
        traffic = false,
        dns = false,
        rdp = false,
    ))
    val permissions: StateFlow<PermissionFlags> = _permissions.asStateFlow()

    private val _services = MutableStateFlow<List<VpnService>>(emptyList())
    val services: StateFlow<List<VpnService>> = _services.asStateFlow()

    /** Shown on the split-tunnel tile. */
    val splitTunnelMode: StateFlow<SplitTunnelMode> = settingsRepository.getSplitTunnelMode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SplitTunnelMode.OFF)

    val theme: StateFlow<String> = settingsRepository.getTheme()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")

    /** Peer expiry (epoch millis) from /client/peer-info, null when unlimited or unknown. */
    private val _peerExpiresAt = MutableStateFlow<Long?>(null)
    val peerExpiresAt: StateFlow<Long?> = _peerExpiresAt.asStateFlow()

    private var monitoringStarted = false

    private val _portalUrl = MutableStateFlow(setupRepository.getPortalUrl())
    val portalUrl: StateFlow<String?> = _portalUrl.asStateFlow()

    // ponytail: not cached — auto-open is a per-connect server decision; false until fetch confirms
    private val _autoOpenPortal = MutableStateFlow(false)
    val autoOpenPortal: StateFlow<Boolean> = _autoOpenPortal.asStateFlow()

    /** Emits true when the stored token is invalid and the user should be
     *  redirected to the Setup screen. Observed by the UI layer. */
    private val _tokenInvalid = MutableStateFlow(false)
    val tokenInvalid: StateFlow<Boolean> = _tokenInvalid.asStateFlow()

    /** Emits true when the peer was disabled on the server and the tunnel was disconnected. */
    private val _peerDisabled = MutableStateFlow(false)
    val peerDisabled: StateFlow<Boolean> = _peerDisabled.asStateFlow()

    /**
     * Validate the stored API token against the server via /client/ping.
     * If the server returns 401 → token is expired/deleted → clear local
     * config and signal the UI to redirect to the Setup screen.
     * Only 401 means "token invalid": a 403 may come from a WAF, reverse
     * proxy or a missing scope and must not wipe a working setup.
     * Network errors are ignored (offline mode — allow cached config).
     */
    fun validateToken() {
        val serverUrl = setupRepository.getServerUrl()
        val token = setupRepository.getApiToken()
        if (serverUrl.isEmpty() || token.isEmpty()) return

        viewModelScope.launch {
            try {
                val client = apiClientProvider.getClient(serverUrl)
                client.ping()
                // Token is valid — nothing to do
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 401) {
                    Timber.w("Token invalid (HTTP ${e.code()}) — clearing config, redirecting to setup")
                    setupRepository.clear()
                    apiClientProvider.invalidate()
                    _tokenInvalid.value = true
                } else {
                    Timber.w("Token check returned HTTP ${e.code()} — keeping config")
                }
            } catch (e: Exception) {
                // Network error (timeout, DNS, etc.) — allow offline mode
                Timber.d("Token validation skipped (offline): ${e.message}")
            }
        }
    }

    /** Start background monitoring loops. Called from the UI layer via LaunchedEffect. */
    fun startMonitoring() {
        if (monitoringStarted) return
        monitoringStarted = true

        // Pre-resolve DNS for the server in case VPN is already active
        viewModelScope.launch {
            val serverUrl = setupRepository.getServerUrl()
            if (serverUrl.isNotEmpty()) {
                try {
                    val host = java.net.URI(serverUrl).host
                    if (host != null) apiClientProvider.preResolveDns(host)
                } catch (_: Exception) {}
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                if (tunnelState.value is TunnelState.Connected) {
                    tunnelManager.getStatistics()?.let { _stats.value = it }
                }
            }
        }
        // Periodic peer status check — detect server-side peer disabling
        viewModelScope.launch {
            while (isActive) {
                delay(60_000) // Check every 60 seconds
                if (tunnelState.value is TunnelState.Connected) {
                    checkPeerEnabled()
                }
            }
        }
    }

    /**
     * Check if the peer is still enabled on the server.
     * If disabled, disconnect the tunnel immediately.
     */
    private suspend fun checkPeerEnabled() {
        try {
            val serverUrl = setupRepository.getServerUrl()
            if (serverUrl.isEmpty()) return
            val peerId = setupRepository.getPeerId()
            if (peerId <= 0) return
            val client = apiClientProvider.getClient(serverUrl)
            val response = client.getPeerInfo(peerId)
            if (response.ok) _peerExpiresAt.value = parseServerTime(response.peer.expiresAt)
            if (response.ok && !response.peer.enabled) {
                Timber.w("Peer disabled on server (id=$peerId) — disconnecting tunnel")
                tunnelManager.disconnect()
                _stats.value = TunnelStats()
                apiClientProvider.clearDnsCache()
                _peerDisabled.value = true
            }
        } catch (e: Exception) {
            Timber.d("Peer status check failed (offline): ${e.message}")
        }
    }

    // --- Actions ---

    /**
     * Connect through [TunnelConnector] — the same path as the Quick Settings
     * tile, boot auto-connect and Always-on VPN (config refresh from the
     * server, admin split-tunnel preset, DNS pre-resolve, hostname report).
     */
    fun connect() {
        viewModelScope.launch {
            try {
                if (!tunnelConnector.connectWithUserSettings()) {
                    Timber.w("VpnViewModel: connect not possible")
                }
            } catch (e: Exception) {
                Timber.e(e, "VpnViewModel: connect failed")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            // Client policy "always on": no manual disconnect in the app.
            if (!clientPolicyManager.current().canDisconnect) {
                Timber.i("VpnViewModel: disconnect refused by always-on client policy")
                return@launch
            }
            try {
                tunnelManager.disconnect()
                _stats.value = TunnelStats()
                apiClientProvider.clearDnsCache()
                Timber.d("VpnViewModel: tunnel disconnected, DNS cache cleared")
            } catch (e: Exception) {
                Timber.e(e, "VpnViewModel: disconnect failed")
            }
        }
    }

    fun openPortal(context: android.content.Context) {
        val url = portalUrl.value ?: return
        if (!url.startsWith("https://")) return
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun loadTrafficStats() {
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) return@launch
                val client = apiClientProvider.getClient(serverUrl)
                val peerId = setupRepository.getPeerId()
                if (peerId <= 0) return@launch
                val response = client.getTraffic(peerId)
                if (response.ok) {
                    _trafficUsage.value = response.traffic
                }
            } catch (e: Exception) {
                Timber.w(e, "VpnViewModel: failed to load traffic stats")
            }
        }
    }

    fun loadServices() {
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) return@launch
                val client = apiClientProvider.getClient(serverUrl)
                val response = client.getServices()
                if (response.ok) {
                    _services.value = response.services
                }
            } catch (e: Exception) {
                Timber.w(e, "VpnViewModel: failed to load services")
            }
        }
    }

    /** Load the peer's expiry once (the 60 s monitor loop refreshes it while connected). */
    fun loadPeerInfo() {
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                val peerId = setupRepository.getPeerId()
                if (serverUrl.isEmpty() || peerId <= 0) return@launch
                val response = apiClientProvider.getClient(serverUrl).getPeerInfo(peerId)
                if (response.ok) _peerExpiresAt.value = parseServerTime(response.peer.expiresAt)
            } catch (e: Exception) {
                Timber.d("Peer info not loaded (offline): ${e.message}")
            }
        }
    }

    fun setTheme(theme: String) {
        viewModelScope.launch { settingsRepository.setTheme(theme) }
    }

    /** Host part of the configured server URL (for the header). */
    val serverUrlHost: String?
        get() = setupRepository.getServerUrl().takeIf { it.isNotBlank() }?.let {
            runCatching { java.net.URI(it).host }.getOrNull()
        }

    /** Tunnel address from the WireGuard config, without prefix length. */
    val tunnelAddress: String?
        get() {
            val config = setupRepository.getWireGuardConfig()
            if (config.isEmpty()) return null
            return runCatching {
                com.gatecontrol.android.tunnel.TunnelConfig.parse(config).address
                    .split(",").first().trim().substringBefore("/")
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }

    /** Derive server hostname from stored WireGuard config. */
    val serverHost: String?
        get() {
            val config = setupRepository.getWireGuardConfig()
            if (config.isEmpty()) return null
            return try {
                com.gatecontrol.android.tunnel.TunnelConfig.parse(config).getServerHost()
            } catch (_: Exception) {
                null
            }
        }

    /** Asks the server which resolver the tunnel uses; reports (ok, vpnDns or error detail). */
    fun runDnsLeakTest(onResult: (ok: Boolean, detail: String?) -> Unit) {
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) {
                    onResult(false, null)
                    return@launch
                }
                val client = apiClientProvider.getClient(serverUrl)
                val response = client.dnsCheck()
                onResult(response.ok, response.vpnDns)
            } catch (e: Exception) {
                Timber.w(e, "VpnViewModel: DNS leak test failed")
                onResult(false, e.localizedMessage)
            }
        }
    }

    /**
     * Invalidate cached API clients so the next request uses a fresh connection.
     * Must be called when the network changes (VPN connect/disconnect) because
     * OkHttp's connection pool may hold stale connections on the old interface.
     */
    fun invalidateApiClients() {
        apiClientProvider.invalidate()
    }

    fun loadPermissions() {
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) return@launch
                val client = apiClientProvider.getClient(serverUrl)
                val response = client.getPermissions()
                if (response.ok) {
                    val flags = response.permissions
                    _permissions.value = flags
                    licenseRepository.updatePermissions(
                        services = flags.services,
                        traffic = flags.traffic,
                        dns = flags.dns,
                        rdp = flags.rdp,
                        pihole = flags.pihole,
                        piholeControl = flags.piholeControl,
                    )
                    setupRepository.setPortalUrl(response.portalUrl)
                    _portalUrl.value = response.portalUrl
                    _autoOpenPortal.value = response.autoOpenPortal
                }
            } catch (e: Exception) {
                Timber.w(e, "VpnViewModel: failed to load permissions")
            }
        }
    }

    // --- Split-tunnel JSON helpers ---

    companion object {
        /** Accepts ISO-8601 ("…Z" / offset) and SQLite "yyyy-MM-dd HH:mm:ss" (UTC). */
        internal fun parseServerTime(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
                ?: runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching {
                    java.time.LocalDateTime.parse(raw.trim().replace(' ', 'T'))
                        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                }.getOrNull()
        }
    }
}
