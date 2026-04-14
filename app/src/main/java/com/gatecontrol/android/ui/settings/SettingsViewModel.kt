package com.gatecontrol.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.network.ApiClientProvider
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
    val licenseStatus: String = ""
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

        // V2 split-tunnel loading
        viewModelScope.launch {
            settingsRepository.migrateSplitTunnelIfNeeded()
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
    }

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
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val obj = arr.getJSONObject(it)
            NetworkEntry(obj.getString("cidr"), obj.optString("label", ""))
        }
    }

    private fun parseSplitAppsJson(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("package") }
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
                if (!config.contains("[Interface]") || !config.contains("PrivateKey")) {
                    _uiState.update { it.copy(error = "Invalid WireGuard config file") }
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
