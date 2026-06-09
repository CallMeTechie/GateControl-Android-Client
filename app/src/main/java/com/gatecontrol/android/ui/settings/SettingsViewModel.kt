package com.gatecontrol.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.tunnel.WgConfigValidator
import com.gatecontrol.android.network.UpdateCheckResponse
import com.gatecontrol.android.common.Validation
import org.json.JSONArray
import org.json.JSONObject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

enum class ConnectionTestStatus {
    Idle, Testing, Success, Failure
}

data class SettingsUiState(
    val theme: String = "dark",
    val locale: String = "de",
    val autoConnect: Boolean = false,
    val killSwitch: Boolean = false,
    val splitTunnelEnabled: Boolean = false,
    val splitTunnelRoutes: String = "",
    val splitTunnelApps: String = "",
    val splitTunnelMode: String = "off",
    val splitTunnelNetworks: List<NetworkEntry> = emptyList(),
    val splitTunnelAppsV2: List<String> = emptyList(),
    val splitTunnelAdminLocked: Boolean = false,
    val checkInterval: Int = 30,
    val configPollInterval: Int = 300,
    val serverUrl: String = "",
    val apiToken: String = "",
    val connectionTestStatus: ConnectionTestStatus = ConnectionTestStatus.Idle,
    val isLoading: Boolean = false,
    val updateInfo: UpdateCheckResponse? = null,
    val appVersion: String = "",
    val error: String? = null,
    val success: String? = null,
    val isPro: Boolean = false,
    val licenseStatus: String = "",

    // ── 防检测设置 ────────────────────────────────────────────────────────
    /** 端口跳变：每次连接随机选择端口（默认关闭） */
    val stealthPortHopping: Boolean = false,

    /** 候选端口，以逗号分隔的字符串形式显示在 UI 中 */
    val stealthCandidatePorts: String = "443,80,8080,8443,53,123,51820",

    /** 时序抖动：握手前随机延迟（默认关闭） */
    val stealthTimingJitter: Boolean = false,

    /** 最小抖动延迟（ms） */
    val stealthJitterMinMs: Int = 100,

    /** 最大抖动延迟（ms） */
    val stealthJitterMaxMs: Int = 800,

    /** 包长混淆：通过 MTU 调整影响包长分布（默认关闭） */
    val stealthPacketPadding: Boolean = false,

    /** 目标 MTU（1024~1500） */
    val stealthPaddingMtu: Int = 1280,

    /** Keepalive 随机化（默认关闭） */
    val stealthKeepaliveRandom: Boolean = false,

    /** Keepalive 抖动范围（±秒，1~30） */
    val stealthKeepaliveJitterSec: Int = 5,

    /** 诱饵 DNS 查询（默认关闭） */
    val stealthDecoyDns: Boolean = false,

    /** 检测到封锁时自动端口跳变重连（默认关闭） */
    val stealthAutoReconnect: Boolean = false,
    // ─────────────────────────────────────────────────────────────────────
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val settingsRepository: SettingsRepository,
    private val apiClientProvider: ApiClientProvider,
    private val licenseRepository: LicenseRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadInitialState()
        refreshLicense()
    }

//// loadInitialState() 去掉内部的 migrateSplitTunnelIfNeeded 调用：
private fun loadInitialState() {
    viewModelScope.launch {
        combine(
            settingsRepository.getTheme(),
            settingsRepository.getLocale(),
            settingsRepository.getAutoConnect(),
            settingsRepository.getKillSwitch(),
            settingsRepository.getSplitTunnelEnabled()
        ) { theme, locale, autoConnect, killSwitch, splitTunnelEnabled ->
            _uiState.update {
                it.copy(
                    theme = theme,
                    locale = locale,
                    autoConnect = autoConnect,
                    killSwitch = killSwitch,
                    splitTunnelEnabled = splitTunnelEnabled
                )
            }
        }.collect {}
    }

    viewModelScope.launch {
        settingsRepository.getSplitTunnelRoutes().collect { routes ->
            _uiState.update { it.copy(splitTunnelRoutes = routes) }
        }
    }

    viewModelScope.launch {
        settingsRepository.getSplitTunnelApps().collect { apps ->
            _uiState.update { it.copy(splitTunnelApps = apps) }
        }
    }

    viewModelScope.launch {
        settingsRepository.getSplitTunnelMode().collect { mode ->
            _uiState.update { it.copy(splitTunnelMode = mode) }
        }
    }
    viewModelScope.launch {
        settingsRepository.getSplitTunnelNetworks().collect { json ->
            val networks = parseSplitNetworksJson(json)
            _uiState.update { it.copy(splitTunnelNetworks = networks) }
        }
    }
    viewModelScope.launch {
        settingsRepository.getSplitTunnelAppsV2().collect { json ->
            val apps = parseSplitAppsJson(json)
            _uiState.update { it.copy(splitTunnelAppsV2 = apps) }
        }
    }
    viewModelScope.launch {
        settingsRepository.getSplitTunnelAdminLocked().collect { locked ->
            _uiState.update { it.copy(splitTunnelAdminLocked = locked) }
        }
    }

    viewModelScope.launch {
        settingsRepository.getCheckInterval().collect { interval ->
            _uiState.update { it.copy(checkInterval = interval) }
        }
    }

    viewModelScope.launch {
        settingsRepository.getConfigPollInterval().collect { interval ->
            _uiState.update { it.copy(configPollInterval = interval) }
        }
    }

    _uiState.update {
        it.copy(
            serverUrl = setupRepository.getServerUrl(),
            apiToken = setupRepository.getApiToken()
        )
    }

    // ── 加载防检测设置 ───────────────────────────────────────────────
    viewModelScope.launch {
        settingsRepository.getStealthPortHopping().collect { v ->
            _uiState.update { it.copy(stealthPortHopping = v) }
        }
    }
    viewModelScope.launch {
        settingsRepository.getStealthCandidatePorts().collect { ports ->
            _uiState.update { it.copy(stealthCandidatePorts = ports.joinToString(",")) }
        }
    }
    viewModelScope.launch {
        settingsRepository.getStealthTimingJitter().collect { v ->
            _uiState.update { it.copy(stealthTimingJitter = v) }
        }
    }
    viewModelScope.launch {
        settingsRepository.getStealthJitterMinMs().collect { v ->
            _uiState.update { it.copy(stealthJitterMinMs = v) }
        }
    }
    viewModelScope.launch {
        settingsRepository.getStealthJitterMaxMs().collect { v ->
            _uiState.update { it.copy(stealthJitterMaxMs = v) }
        }
    }
    viewModelScope.launch {
        settingsRepository.getStealthPacketPadding().collect { v ->
            _uiState.update { it.copy(stealthPacketPadding = v) }
        }
    }
    viewModelScope.launch {
        settingsRepository.getStealthPaddingMtu().collect { v ->
            _uiState.update { it.copy(stealthPaddingMtu = v) }
        }
    }
    viewModelScope.launch {
        settingsRepository.getStealthKeepaliveRandom().collect { v ->
            _uiState.update { it.copy(stealthKeepaliveRandom = v) }
        }
    }
    viewModelScope.launch {
        settingsRepository.getStealthKeepaliveJitterSec().collect { v ->
            _uiState.update { it.copy(stealthKeepaliveJitterSec = v) }
        }
    }
    viewModelScope.launch {
        settingsRepository.getStealthDecoyDns().collect { v ->
            _uiState.update { it.copy(stealthDecoyDns = v) }
        }
    }
    viewModelScope.launch {
        settingsRepository.getStealthAutoReconnect().collect { v ->
            _uiState.update { it.copy(stealthAutoReconnect = v) }
        }
    }
    // ─────────────────────────────────────────────────────────────────
}
    // ── 防检测设置写入方法 ────────────────────────────────────────────────

    fun setStealthPortHopping(enabled: Boolean) {
        _uiState.update { it.copy(stealthPortHopping = enabled) }
        viewModelScope.launch { settingsRepository.setStealthPortHopping(enabled) }
    }

    fun setStealthCandidatePorts(portsText: String) {
        _uiState.update { it.copy(stealthCandidatePorts = portsText) }
        viewModelScope.launch {
            val ports = portsText.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..65535 }
            settingsRepository.setStealthCandidatePorts(ports)
        }
    }

    fun setStealthTimingJitter(enabled: Boolean) {
        _uiState.update { it.copy(stealthTimingJitter = enabled) }
        viewModelScope.launch { settingsRepository.setStealthTimingJitter(enabled) }
    }

    fun setStealthJitterMinMs(ms: Int) {
        val clamped = ms.coerceIn(0, 2000)
        _uiState.update { it.copy(stealthJitterMinMs = clamped) }
        viewModelScope.launch { settingsRepository.setStealthJitterMinMs(clamped) }
    }

    fun setStealthJitterMaxMs(ms: Int) {
        val clamped = ms.coerceIn(50, 5000)
        _uiState.update { it.copy(stealthJitterMaxMs = clamped) }
        viewModelScope.launch { settingsRepository.setStealthJitterMaxMs(clamped) }
    }

    fun setStealthPacketPadding(enabled: Boolean) {
        _uiState.update { it.copy(stealthPacketPadding = enabled) }
        viewModelScope.launch { settingsRepository.setStealthPacketPadding(enabled) }
    }

    fun setStealthPaddingMtu(mtu: Int) {
        val clamped = mtu.coerceIn(1024, 1500)
        _uiState.update { it.copy(stealthPaddingMtu = clamped) }
        viewModelScope.launch { settingsRepository.setStealthPaddingMtu(clamped) }
    }

    fun setStealthKeepaliveRandom(enabled: Boolean) {
        _uiState.update { it.copy(stealthKeepaliveRandom = enabled) }
        viewModelScope.launch { settingsRepository.setStealthKeepaliveRandom(enabled) }
    }

    fun setStealthKeepaliveJitterSec(jitter: Int) {
        val clamped = jitter.coerceIn(1, 30)
        _uiState.update { it.copy(stealthKeepaliveJitterSec = clamped) }
        viewModelScope.launch { settingsRepository.setStealthKeepaliveJitterSec(clamped) }
    }

    fun setStealthDecoyDns(enabled: Boolean) {
        _uiState.update { it.copy(stealthDecoyDns = enabled) }
        viewModelScope.launch { settingsRepository.setStealthDecoyDns(enabled) }
    }

    fun setStealthAutoReconnect(enabled: Boolean) {
        _uiState.update { it.copy(stealthAutoReconnect = enabled) }
        viewModelScope.launch { settingsRepository.setStealthAutoReconnect(enabled) }
    }

    // ─────────────────────────────────────────────────────────────────────

    fun setTheme(theme: String) {
        viewModelScope.launch {
            settingsRepository.setTheme(theme)
            _uiState.update { it.copy(theme = theme) }
        }
    }

    fun setLocale(locale: String) {
        viewModelScope.launch {
            settingsRepository.setLocale(locale)
            _uiState.update { it.copy(locale = locale) }
        }
    }

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoConnect(enabled)
            _uiState.update { it.copy(autoConnect = enabled) }
        }
    }

    fun setKillSwitch(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKillSwitch(enabled)
            _uiState.update { it.copy(killSwitch = enabled) }
        }
    }

    fun setSplitTunnelEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSplitTunnelEnabled(enabled)
            _uiState.update { it.copy(splitTunnelEnabled = enabled) }
        }
    }

    fun setCheckInterval(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.setCheckInterval(seconds)
        }
    }

    fun setConfigPollInterval(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.setConfigPollInterval(seconds)
        }
    }

    fun testConnection(url: String, token: String) {
        viewModelScope.launch {
            val safeUrl = ensureHttps(url)
            if (safeUrl.isBlank()) {
                _uiState.update { it.copy(connectionTestStatus = ConnectionTestStatus.Failure) }
                return@launch
            }
            _uiState.update { it.copy(connectionTestStatus = ConnectionTestStatus.Testing) }
            try {
                val client = apiClientProvider.getClient(safeUrl)
                val response = client.ping()
                _uiState.update {
                    it.copy(
                        connectionTestStatus = if (response.ok) ConnectionTestStatus.Success
                        else ConnectionTestStatus.Failure
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Connection test failed")
                _uiState.update { it.copy(connectionTestStatus = ConnectionTestStatus.Failure) }
            }
        }
    }

    private fun ensureHttps(url: String): String {
        if (url.isBlank()) return url
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return "https://$url"
    }

    fun saveServer(url: String, token: String) {
        val url = ensureHttps(url)
        if (!Validation.validateServerUrl(url)) {
            _uiState.update { it.copy(error = "Invalid server URL") }
            return
        }
        if (!Validation.validateApiToken(token)) {
            _uiState.update { it.copy(error = "Invalid API token") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                apiClientProvider.invalidate()
                val client = apiClientProvider.getClient(url)

                val peerId = setupRepository.getPeerId()
                if (peerId > 0) {
                    client.ping()
                }

                setupRepository.save(url, token, peerId.coerceAtLeast(0))
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        serverUrl = url,
                        apiToken = token,
                        connectionTestStatus = ConnectionTestStatus.Success
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to save server settings")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message,
                        connectionTestStatus = ConnectionTestStatus.Failure
                    )
                }
            }
        }
    }

    fun saveSplitRoutes(routes: String) {
        val validRoutes = Validation.parseSplitRoutes(routes)
        val cleaned = validRoutes.joinToString("\n")
        viewModelScope.launch {
            settingsRepository.setSplitTunnelRoutes(cleaned)
            _uiState.update { it.copy(splitTunnelRoutes = cleaned) }
        }
    }

    fun setSplitTunnelApps(apps: String) {
        viewModelScope.launch {
            settingsRepository.setSplitTunnelApps(apps)
            _uiState.update { it.copy(splitTunnelApps = apps) }
        }
    }

    fun setSplitTunnelMode(mode: String) {
        _uiState.update { it.copy(splitTunnelMode = mode) }
        viewModelScope.launch { settingsRepository.setSplitTunnelMode(mode) }
    }

    fun setSplitTunnelNetworks(networks: List<NetworkEntry>) {
        _uiState.update { it.copy(splitTunnelNetworks = networks) }
        viewModelScope.launch {
            val arr = JSONArray()
            networks.forEach { arr.put(JSONObject().put("cidr", it.cidr).put("label", it.label)) }
            settingsRepository.setSplitTunnelNetworks(arr.toString())
        }
    }

    fun setSplitTunnelAppsV2(apps: List<String>) {
        _uiState.update { it.copy(splitTunnelAppsV2 = apps) }
        viewModelScope.launch {
            val arr = JSONArray()
            apps.forEach { arr.put(JSONObject().put("package", it).put("label", "")) }
            settingsRepository.setSplitTunnelAppsV2(arr.toString())
        }
    }

    private fun parseSplitNetworksJson(json: String): List<NetworkEntry> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                NetworkEntry(obj.getString("cidr"), obj.optString("label", ""))
            }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Failed to parse split-tunnel networks JSON")
            emptyList()
        }
    }

    private fun parseSplitAppsJson(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).getString("package") }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Failed to parse split-tunnel apps JSON")
            emptyList()
        }
    }

    fun checkForUpdate(currentVersion: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isBlank()) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                val client = apiClientProvider.getClient(serverUrl)
                val response = client.checkUpdate(
                    version = currentVersion,
                    platform = "android",
                    client = "android"
                )
                _uiState.update { it.copy(isLoading = false, updateInfo = response) }
            } catch (e: Exception) {
                Timber.e(e, "Update check failed")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Update check: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun refreshLicense() {
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isBlank()) {
                    _uiState.update { it.copy(licenseStatus = "No server configured") }
                    return@launch
                }
                val client = apiClientProvider.getClient(serverUrl)
                val response = client.getPermissions()
                if (response.ok) {
                    val perms = response.permissions
                    licenseRepository.updatePermissions(
                        services = perms.services,
                        traffic = perms.traffic,
                        dns = perms.dns,
                        rdp = perms.rdp,
                    )
                    val isPro = perms.rdp || perms.traffic || perms.dns
                    _uiState.update {
                        it.copy(
                            isPro = isPro,
                            licenseStatus = if (isPro) "Pro" else "Community",
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "License refresh failed")
                _uiState.update { it.copy(error = "License refresh failed: ${e.localizedMessage}") }
            }
        }
    }

    fun dismissUpdate() {
        _uiState.update { it.copy(updateInfo = null) }
    }

    private val _requestFilePicker = kotlinx.coroutines.flow.MutableStateFlow(false)
    val requestFilePicker: kotlinx.coroutines.flow.StateFlow<Boolean> = _requestFilePicker

    fun requestConfigImport() {
        _requestFilePicker.value = true
    }

    fun onFilePickerLaunched() {
        _requestFilePicker.value = false
    }

    fun importConfigFromUri(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val input = context.contentResolver.openInputStream(uri)
                val config = input?.bufferedReader()?.readText() ?: return@launch
                input.close()
                val validation = WgConfigValidator.validate(config)
                if (!validation.ok) {
                    Timber.w("importConfigFromUri rejected: %s", validation.errors.joinToString(", "))
                    _uiState.update {
                        it.copy(error = context.getString(R.string.setup_invalid_config))
                    }
                    return@launch
                }
                setupRepository.saveWireGuardConfig(config)
                _uiState.update { it.copy(error = null, success = "Config imported successfully") }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Import failed: ${e.message}") }
            }
        }
    }

    fun exportLogs(cacheDir: File): File? {
        return try {
            val logFile = File(cacheDir, "gatecontrol-logs.txt")
            val logDir = File(cacheDir, "logs")
            if (logDir.exists()) {
                val logs = logDir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.firstOrNull()
                if (logs != null) {
                    logs.copyTo(logFile, overwrite = true)
                    logFile
                } else {
                    logFile.writeText("No logs available")
                    logFile
                }
            } else {
                logFile.writeText("No log directory found")
                logFile
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to export logs")
            null
        }
    }
}
