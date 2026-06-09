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
import com.gatecontrol.android.tunnel.StealthConfig
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
import kotlinx.coroutines.flow.combine
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

    private val _tokenInvalid = MutableStateFlow(false)
    val tokenInvalid: StateFlow<Boolean> = _tokenInvalid.asStateFlow()

    private val _peerDisabled = MutableStateFlow(false)
    val peerDisabled: StateFlow<Boolean> = _peerDisabled.asStateFlow()

    fun validateToken() {
        val serverUrl = setupRepository.getServerUrl()
        val token = setupRepository.getApiToken()
        if (serverUrl.isEmpty() || token.isEmpty()) return

        viewModelScope.launch {
            try {
                val client = apiClientProvider.getClient(serverUrl)
                client.ping()
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 401 || e.code() == 403) {
                    Timber.w("Token invalid (HTTP ${e.code()}) — clearing config, redirecting to setup")
                    setupRepository.clear()
                    apiClientProvider.invalidate()
                    _tokenInvalid.value = true
                }
            } catch (e: Exception) {
                Timber.d("Token validation skipped (offline): ${e.message}")
            }
        }
    }

    fun startMonitoring() {
        if (monitoringStarted) return
        monitoringStarted = true

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

        // 双轨探测引擎已在 TunnelManager.connectInternal() 中随连接自动启动，
        // 负责精准判断"精准阻断"并触发端口跳变重连。
        // 此处不再重复轮询握手超时，避免误触发和竞争。

        viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                if (tunnelState.value is TunnelState.Connected) {
                    checkPeerEnabled()
                }
            }
        }
    }

    private suspend fun checkPeerEnabled() {
        try {
            val serverUrl = setupRepository.getServerUrl()
            if (serverUrl.isEmpty()) return
            val peerId = setupRepository.getPeerId()
            if (peerId <= 0) return
            val client = apiClientProvider.getClient(serverUrl)
            val response = client.getPeerInfo(peerId)
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

    // ── 连接动作 ──────────────────────────────────────────────────────────

    fun connect() {
        viewModelScope.launch {
            val config = setupRepository.getWireGuardConfig()
            if (config.isEmpty()) {
                Timber.w("VpnViewModel: no WireGuard config available")
                return@launch
            }

            val serverUrl = setupRepository.getServerUrl()
            if (serverUrl.isNotEmpty()) {
                try {
                    val host = java.net.URI(serverUrl).host
                    if (host != null) apiClientProvider.preResolveDns(host)
                } catch (_: Exception) {}
            }

            // 加载分流配置
            var splitTunnelConfig = SplitTunnelConfig()
            try {
                var adminPresetActive = false
                if (serverUrl.isNotEmpty()) {
                    try {
                        val client = apiClientProvider.getClient(serverUrl)
                        val preset = client.getSplitTunnelPreset()
                        if (preset.ok && preset.mode != "off" && preset.source != "none") {
                            settingsRepository.setSplitTunnelMode(preset.mode)
                            val arr = JSONArray()
                            preset.networks.forEach { arr.put(JSONObject().put("cidr", it.cidr).put("label", it.label)) }
                            settingsRepository.setSplitTunnelNetworks(arr.toString())
                            settingsRepository.setSplitTunnelAdminLocked(preset.locked)
                            adminPresetActive = true

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
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Split-tunnel config load failed")
            }

            // ── 加载防检测配置 ────────────────────────────────────────────
            val stealthConfig = loadStealthConfig()
            Timber.d(
                "VpnViewModel: stealth config: portHop=%b jitter=%b padding=%b keepalive=%b decoy=%b autoReconnect=%b",
                stealthConfig.portHoppingEnabled,
                stealthConfig.timingJitterEnabled,
                stealthConfig.packetPaddingEnabled,
                stealthConfig.keepaliveRandomEnabled,
                stealthConfig.decoyDnsEnabled,
                stealthConfig.autoReconnectOnBlock,
            )
            // ─────────────────────────────────────────────────────────────

            try {
                tunnelManager.connect(config, splitTunnelConfig, stealthConfig)
                Timber.d("VpnViewModel: tunnel connect requested")
                reportDeviceHostname(serverUrl)
            } catch (e: Exception) {
                Timber.e(e, "VpnViewModel: connect failed")
            }
        }
    }

    /**
     * 从 SettingsRepository 读取所有防检测开关，组装为 [StealthConfig]。
     */
    private suspend fun loadStealthConfig(): StealthConfig {
        return try {
            val portHopping = settingsRepository.getStealthPortHopping().first()
            val timingJitter = settingsRepository.getStealthTimingJitter().first()
            val packetPadding = settingsRepository.getStealthPacketPadding().first()
            val paddingMtu = settingsRepository.getStealthPaddingMtu().first()
            val keepaliveRandom = settingsRepository.getStealthKeepaliveRandom().first()
            val keepaliveJitter = settingsRepository.getStealthKeepaliveJitterSec().first()
            val decoyDns = settingsRepository.getStealthDecoyDns().first()
            val autoReconnect = settingsRepository.getStealthAutoReconnect().first()
            val candidatePorts = settingsRepository.getStealthCandidatePorts().first()
            val jitterMin = settingsRepository.getStealthJitterMinMs().first()
            val jitterMax = settingsRepository.getStealthJitterMaxMs().first()

            StealthConfig(
                portHoppingEnabled = portHopping,
                candidatePorts = candidatePorts,
                timingJitterEnabled = timingJitter,
                jitterMinMs = jitterMin.toLong(),
                jitterMaxMs = jitterMax.toLong(),
                packetPaddingEnabled = packetPadding,
                paddingTargetMtu = paddingMtu,
                keepaliveRandomEnabled = keepaliveRandom,
                keepaliveJitterSec = keepaliveJitter,
                decoyDnsEnabled = decoyDns,
                autoReconnectOnBlock = autoReconnect,
            )
        } catch (e: Exception) {
            Timber.w(e, "VpnViewModel: failed to load stealth config, using defaults (all off)")
            StealthConfig()
        }
    }

    private suspend fun reportDeviceHostname(serverUrl: String) {
        try {
            // Guard: Retrofit requires a valid http/https URL. An empty or
            // unconfigured serverUrl would produce "/" after normalization and
            // crash with "Expected URL scheme 'http' or 'https'".
            if (serverUrl.isBlank() ||
                (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://"))) {
                Timber.d("Hostname report skipped: serverUrl is not a valid http(s) URL: '$serverUrl'")
                return
            }

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
