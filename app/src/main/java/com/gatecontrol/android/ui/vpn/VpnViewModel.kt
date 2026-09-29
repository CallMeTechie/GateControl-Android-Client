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

    /** "off", "exclude" or "include" — shown on the split-tunnel tile. */
    val splitTunnelMode: StateFlow<String> = settingsRepository.getSplitTunnelMode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "off")

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
                reportDeviceHostname(serverUrl)
            } catch (e: Exception) {
                Timber.e(e, "VpnViewModel: connect failed")
            }
        }
    }

    /**
     * Fire-and-forget hostname report for internal DNS resolution.
     * Server rate-limits (3/min/token) and feature-gates (403) — we
     * never surface failures; they're logged at debug level only.
     * Uses Build.MODEL as the source — user-set Settings.Global.DEVICE_NAME
     * would be preferable but requires a Context, which is not in scope
     * here; can be plumbed through later without changing the API.
     */
    private suspend fun reportDeviceHostname(serverUrl: String) {
        try {
            val sanitized = com.gatecontrol.android.common.HostnameSanitizer.sanitize(android.os.Build.MODEL)
            if (sanitized.isNullOrBlank()) return

            val client = apiClientProvider.getClient(serverUrl)
            val response = client.reportHostname(
                com.gatecontrol.android.network.HostnameReportRequest(sanitized)
            )
            Timber.d("Hostname report: assigned=${response.assigned} changed=${response.changed}")
        } catch (e: Exception) {
            Timber.d(e, "Hostname report skipped: ${e.message}")
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
