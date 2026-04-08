package com.gatecontrol.android.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.RegisterRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

enum class StatusType { INFO, SUCCESS, ERROR }

data class SetupUiState(
    val serverUrl: String = "",
    val apiToken: String = "",
    val isLoading: Boolean = false,
    val statusMessage: String = "",
    val statusType: StatusType = StatusType.INFO,
    val isSetupComplete: Boolean = false,
    val isManualExpanded: Boolean = false,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SetupUiState(
            serverUrl = setupRepository.getServerUrl(),
            apiToken = setupRepository.getApiToken(),
            isSetupComplete = setupRepository.isConfigured() || setupRepository.hasWireGuardConfig(),
        ),
    )
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun onServerUrlChanged(value: String) {
        _uiState.update { it.copy(serverUrl = value, statusMessage = "") }
    }

    fun onApiTokenChanged(value: String) {
        _uiState.update { it.copy(apiToken = value, statusMessage = "") }
    }

    fun toggleManualExpanded() {
        _uiState.update { it.copy(isManualExpanded = !it.isManualExpanded) }
    }

    fun testConnection() {
        val url = ensureHttps(_uiState.value.serverUrl.trim())
        val token = _uiState.value.apiToken.trim()
        if (url.isBlank()) {
            _uiState.update {
                it.copy(statusMessage = "Server URL required", statusType = StatusType.ERROR)
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Testing connection…", statusType = StatusType.INFO) }
            try {
                // Temporarily save token so AuthInterceptor can use it
                if (token.isNotEmpty()) {
                    setupRepository.save(url, token, -1)
                    apiClientProvider.invalidate()
                }
                val client = apiClientProvider.getClient(url)
                client.ping()
                _uiState.update {
                    it.copy(isLoading = false, statusMessage = "Connection successful", statusType = StatusType.SUCCESS)
                }
            } catch (e: Exception) {
                Timber.w(e, "testConnection failed")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Connection failed: ${e.localizedMessage}",
                        statusType = StatusType.ERROR,
                    )
                }
            }
        }
    }

    fun saveAndRegister() {
        val url = ensureHttps(_uiState.value.serverUrl.trim())
        val token = _uiState.value.apiToken.trim()

        if (url.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Server URL required", statusType = StatusType.ERROR) }
            return
        }
        if (token.isBlank()) {
            _uiState.update { it.copy(statusMessage = "API token required", statusType = StatusType.ERROR) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Registering…", statusType = StatusType.INFO) }
            try {
                // Save token BEFORE register call so AuthInterceptor can use it
                setupRepository.save(url, token, -1)
                // Invalidate cached clients so new token is picked up
                apiClientProvider.invalidate()

                val client = apiClientProvider.getClient(url)
                client.ping()

                val response = client.register(
                    RegisterRequest(
                        hostname = android.os.Build.MODEL,
                        platform = "android",
                        clientVersion = "1.0.0",
                    ),
                )

                if (!response.ok || response.peerId <= 0) {
                    setupRepository.clear()
                    apiClientProvider.invalidate()
                    throw IllegalStateException("Registration rejected by server")
                }

                // Update with actual peer ID
                setupRepository.save(url, token, response.peerId)

                response.config?.let { config ->
                    setupRepository.saveWireGuardConfig(config)
                }
                response.hash?.let { hash ->
                    setupRepository.saveConfigHash(hash)
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Successfully registered!",
                        statusType = StatusType.SUCCESS,
                        isSetupComplete = true,
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "saveAndRegister failed")
                // Clean up on failure
                setupRepository.clear()
                apiClientProvider.invalidate()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Registration failed: ${e.localizedMessage}",
                        statusType = StatusType.ERROR,
                    )
                }
            }
        }
    }

    fun handleDeepLink(url: String, token: String) {
        _uiState.update { it.copy(serverUrl = url, apiToken = token) }
        saveAndRegister()
    }

    private fun ensureHttps(url: String): String {
        if (url.isBlank()) return url
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return "https://$url"
    }

    fun importConfig(configText: String) {
        viewModelScope.launch {
            try {
                if (configText.isBlank() || !configText.contains("[Interface]")) {
                    _uiState.update {
                        it.copy(statusMessage = "Invalid WireGuard config", statusType = StatusType.ERROR)
                    }
                    return@launch
                }

                setupRepository.saveWireGuardConfig(configText)

                _uiState.update {
                    it.copy(
                        statusMessage = "Config imported successfully",
                        statusType = StatusType.SUCCESS,
                        isSetupComplete = true,
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "importConfig failed")
                _uiState.update {
                    it.copy(
                        statusMessage = "Import failed: ${e.localizedMessage}",
                        statusType = StatusType.ERROR,
                    )
                }
            }
        }
    }
}
