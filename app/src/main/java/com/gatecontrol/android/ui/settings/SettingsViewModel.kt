package com.gatecontrol.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.UpdateCheckResponse
import com.gatecontrol.android.common.Validation
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
    val checkInterval: Int = 30,
    val configPollInterval: Int = 300,
    val serverUrl: String = "",
    val apiToken: String = "",
    val connectionTestStatus: ConnectionTestStatus = ConnectionTestStatus.Idle,
    val isLoading: Boolean = false,
    val updateInfo: UpdateCheckResponse? = null,
    val appVersion: String = "",
    val error: String? = null
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

    fun testConnection() {
        viewModelScope.launch {
            val serverUrl = _uiState.value.serverUrl
            if (serverUrl.isBlank()) {
                _uiState.update { it.copy(connectionTestStatus = ConnectionTestStatus.Failure) }
                return@launch
            }
            _uiState.update { it.copy(connectionTestStatus = ConnectionTestStatus.Testing) }
            try {
                val client = apiClientProvider.getClient(serverUrl)
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

    fun saveServer(url: String, token: String) {
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
                val response = client.checkUpdate(version = currentVersion)
                _uiState.update { it.copy(isLoading = false, updateInfo = response) }
            } catch (e: Exception) {
                Timber.e(e, "Update check failed")
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun activateLicense() {
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isBlank()) return@launch
                val client = apiClientProvider.getClient(serverUrl)
                val response = client.getPermissions()
                if (response.ok) {
                    licenseRepository.updatePermissions(
                        services = response.permissions.services,
                        traffic = response.permissions.traffic,
                        dns = response.permissions.dns,
                        rdp = response.permissions.rdp,
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "License activation failed")
                _uiState.update { it.copy(error = "License activation failed: ${e.localizedMessage}") }
            }
        }
    }

    fun dismissUpdate() {
        _uiState.update { it.copy(updateInfo = null) }
    }

    fun requestConfigImport() {
        // Signal to UI to launch file picker — handled via shared state
        _uiState.update { it.copy(error = "Use the setup screen to import config files") }
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
