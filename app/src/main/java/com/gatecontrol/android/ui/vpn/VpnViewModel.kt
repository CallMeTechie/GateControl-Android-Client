package com.gatecontrol.android.ui.vpn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.PermissionFlags
import com.gatecontrol.android.network.TrafficStats
import com.gatecontrol.android.network.VpnService
import com.gatecontrol.android.service.TunnelStateHolder
import com.gatecontrol.android.tunnel.SplitTunnelConfig
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelMonitor
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tunnel.TunnelStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class VpnViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val settingsRepository: SettingsRepository,
    private val licenseRepository: LicenseRepository,
    private val apiClientProvider: ApiClientProvider,
    private val tunnelManager: TunnelManager,
) : ViewModel() {

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

    val killSwitchEnabled: StateFlow<Boolean> = settingsRepository.getKillSwitch()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var monitoringStarted = false

    /** Emits true when the stored token is invalid and the user should be
     *  redirected to the Setup screen. Observed by the UI layer. */
    private val _tokenInvalid = MutableStateFlow(false)
    val tokenInvalid: StateFlow<Boolean> = _tokenInvalid.asStateFlow()

    /**
     * Validate the stored API token against the server via /client/ping.
     * If the server returns 401 → token is expired/deleted → clear local
     * config and signal the UI to redirect to the Setup screen.
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
                if (e.code() == 401 || e.code() == 403) {
                    Timber.w("Token invalid (HTTP ${e.code()}) — clearing config, redirecting to setup")
                    setupRepository.clear()
                    apiClientProvider.invalidate()
                    _tokenInvalid.value = true
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
            tunnelManager.state.collect { state ->
                TunnelStateHolder.isConnected = state is TunnelState.Connected
                TunnelStateHolder.serverHost = serverHost
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
    }

    // --- Actions ---

    fun connect() {
        viewModelScope.launch {
            val config = setupRepository.getWireGuardConfig()
            if (config.isEmpty()) {
                Timber.w("VpnViewModel: no WireGuard config available")
                return@launch
            }
            // Pre-resolve server hostname BEFORE VPN starts, so API calls
            // work after the VPN is up (when system DNS points to 10.8.0.1
            // which is unreachable from the excluded GateControl app).
            val serverUrl = setupRepository.getServerUrl()
            if (serverUrl.isNotEmpty()) {
                try {
                    val host = java.net.URI(serverUrl).host
                    if (host != null) apiClientProvider.preResolveDns(host)
                } catch (_: Exception) {}
            }
            // Fetch admin split-tunnel preset (graceful — never blocks connect)
            var splitTunnelConfig = SplitTunnelConfig() // default: mode=off
            try {
                var adminPresetActive = false
                if (serverUrl.isNotEmpty()) {
                    try {
                        val client = apiClientProvider.getClient(serverUrl)
                        val preset = client.getSplitTunnelPreset()
                        if (preset.ok && preset.mode != "off" && preset.source != "none") {
                            // Admin preset exists — store and use it
                            settingsRepository.setSplitTunnelMode(preset.mode)
                            val arr = JSONArray()
                            preset.networks.forEach { arr.put(JSONObject().put("cidr", it.cidr).put("label", it.label)) }
                            settingsRepository.setSplitTunnelNetworks(arr.toString())
                            settingsRepository.setSplitTunnelAdminLocked(preset.locked)
                            adminPresetActive = true

                            // Merge: admin networks + user apps (apps ALWAYS user-controlled)
                            val userApps = settingsRepository.getSplitTunnelAppsV2().first()
                            val appsList = parseSplitAppsJson(userApps)

                            splitTunnelConfig = SplitTunnelConfig(
                                mode = preset.mode,
                                networks = preset.networks.map { it.cidr },
                                apps = appsList,
                            )
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Split-tunnel preset fetch failed")
                    }
                }

                // No admin preset — use LOCAL user settings from DataStore
                if (!adminPresetActive) {
                    val mode = settingsRepository.getSplitTunnelMode().first()
                    if (mode != "off") {
                        val networksJson = settingsRepository.getSplitTunnelNetworks().first()
                        val appsJson = settingsRepository.getSplitTunnelAppsV2().first()
                        splitTunnelConfig = SplitTunnelConfig(
                            mode = mode,
                            networks = parseSplitNetworksJsonToCidrs(networksJson),
                            apps = parseSplitAppsJson(appsJson),
                        )
                        Timber.d("Split-tunnel: using local config mode=$mode, ${splitTunnelConfig.networks.size} networks, ${splitTunnelConfig.apps.size} apps")
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Split-tunnel config load failed")
            }

            try {
                tunnelManager.connect(config, splitTunnelConfig)
                Timber.d("VpnViewModel: tunnel connect requested")
            } catch (e: Exception) {
                Timber.e(e, "VpnViewModel: connect failed")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
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

    fun toggleKillSwitch(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKillSwitch(enabled)
            Timber.d("VpnViewModel: kill-switch set to $enabled")
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

    fun runDnsLeakTest(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) {
                    onResult("No server configured")
                    return@launch
                }
                val client = apiClientProvider.getClient(serverUrl)
                val response = client.dnsCheck()
                if (response.ok) {
                    onResult("DNS: ${response.vpnDns} (Subnet: ${response.vpnSubnet})")
                } else {
                    onResult("DNS check failed")
                }
            } catch (e: Exception) {
                Timber.w(e, "VpnViewModel: DNS leak test failed")
                onResult("DNS test error: ${e.localizedMessage}")
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
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "VpnViewModel: failed to load permissions")
            }
        }
    }

    // --- Split-tunnel JSON helpers ---

    private fun parseSplitNetworksJsonToCidrs(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).getString("cidr") }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse split-tunnel networks JSON, falling back to empty")
            emptyList()
        }
    }

    private fun parseSplitAppsJson(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).getString("package") }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse split-tunnel apps JSON, falling back to empty")
            emptyList()
        }
    }
}
