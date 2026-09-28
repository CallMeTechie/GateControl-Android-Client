package com.gatecontrol.android.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.common.EnrollmentLink
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.EnrollRequest
import com.gatecontrol.android.network.RegisterRequest
import com.gatecontrol.android.tunnel.WgConfigValidator
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import timber.log.Timber
import javax.inject.Inject

enum class StatusType { INFO, SUCCESS, ERROR }

/** Server error code in a JSON error body: {"ok":false,"error":"invalid_or_expired"}. */
private val ERROR_CODE_RE = Regex("\"error\"\\s*:\\s*\"([a-z_]+)\"")

data class SetupUiState(
    val serverUrl: String = "",
    val apiToken: String = "",
    val isLoading: Boolean = false,
    val statusMessage: String = "",
    val statusType: StatusType = StatusType.INFO,
    val isSetupComplete: Boolean = false,
    val isManualExpanded: Boolean = false,
    /** Scanned/opened setup link waiting for the user's confirmation. */
    val pendingEnrollment: EnrollmentLink? = null,
    /** True once a setup action succeeded in this screen (not just "already configured"). */
    val completedNow: Boolean = false,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val appVersion: String by lazy {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Exception) { "0.0.0" }
    }

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

        // The token field also takes the short setup code shown next to the
        // server's QR code — typed codes need no confirmation, the user
        // entered the server URL themselves.
        EnrollmentLink.normalizeCode(token)?.let { code ->
            val serverUrl = EnrollmentLink.normalizeServerUrl(url)
            if (serverUrl == null) {
                _uiState.update { it.copy(statusMessage = context.getString(R.string.setup_enroll_https_required), statusType = StatusType.ERROR) }
            } else {
                enroll(serverUrl, code)
            }
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
                        clientVersion = appVersion,
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
                        completedNow = true,
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

    /**
     * A setup link was scanned or opened. Nothing happens until the user
     * confirms the server — a foreign QR code must not silently repoint the
     * app to another server.
     */
    fun onEnrollmentLink(link: EnrollmentLink) {
        _uiState.update { it.copy(pendingEnrollment = link, statusMessage = "") }
    }

    fun cancelEnrollment() {
        _uiState.update { it.copy(pendingEnrollment = null) }
    }

    fun confirmEnrollment() {
        val link = _uiState.value.pendingEnrollment ?: return
        _uiState.update { it.copy(pendingEnrollment = null) }
        enroll(link.serverUrl, link.code)
    }

    /**
     * Trades a one-shot setup code for a peer-bound API token plus the
     * WireGuard config (POST /api/v1/client/enroll). On failure the existing
     * setup stays untouched — the code may simply have expired.
     */
    private fun enroll(serverUrl: String, code: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    serverUrl = serverUrl,
                    statusMessage = context.getString(R.string.setup_enroll_running),
                    statusType = StatusType.INFO,
                )
            }
            try {
                apiClientProvider.invalidate()
                val response = apiClientProvider.getClient(serverUrl).enroll(
                    EnrollRequest(
                        code = code,
                        hostname = android.os.Build.MODEL ?: "android",
                        platform = "android",
                        clientVersion = appVersion,
                    ),
                )
                val token = response.token
                val peerId = response.peerId
                val config = response.config
                if (!response.ok || token.isNullOrBlank() || peerId == null || peerId <= 0 || config.isNullOrBlank()) {
                    throw IllegalStateException(response.error ?: "enroll_failed")
                }
                if (!WgConfigValidator.validate(config).ok) {
                    throw IllegalStateException("invalid_config")
                }

                setupRepository.save(serverUrl, token, peerId)
                setupRepository.saveWireGuardConfig(config)
                response.hash?.let { setupRepository.saveConfigHash(it) }
                apiClientProvider.invalidate()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        apiToken = "",
                        statusMessage = context.getString(R.string.setup_enroll_success),
                        statusType = StatusType.SUCCESS,
                        isSetupComplete = true,
                        completedNow = true,
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "enroll failed")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = enrollErrorMessage(e),
                        statusType = StatusType.ERROR,
                    )
                }
            }
        }
    }

    private fun enrollErrorMessage(e: Exception): String {
        val code = if (e is HttpException) {
            if (e.code() == 429) {
                "rate_limited"
            } else {
                val body = try {
                    e.response()?.errorBody()?.string().orEmpty()
                } catch (_: Exception) {
                    ""
                }
                ERROR_CODE_RE.find(body)?.groupValues?.get(1).orEmpty()
            }
        } else {
            e.message ?: ""
        }
        val res = when (code) {
            "invalid_or_expired" -> R.string.setup_enroll_invalid
            "user_disabled", "user_not_found", "no_valid_scopes" -> R.string.setup_enroll_forbidden
            "limit_reached" -> R.string.setup_enroll_limit
            "rate_limited" -> R.string.setup_enroll_rate_limited
            else -> null
        }
        return if (res != null) {
            context.getString(res)
        } else {
            context.getString(R.string.setup_enroll_failed, e.localizedMessage ?: code)
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
                val validation = WgConfigValidator.validate(configText)
                if (!validation.ok) {
                    Timber.w("importConfig rejected: %s", validation.errors.joinToString(", "))
                    _uiState.update {
                        it.copy(
                            statusMessage = context.getString(R.string.setup_invalid_config),
                            statusType = StatusType.ERROR,
                        )
                    }
                    return@launch
                }

                setupRepository.saveWireGuardConfig(configText)

                _uiState.update {
                    it.copy(
                        statusMessage = "Config imported successfully",
                        statusType = StatusType.SUCCESS,
                        isSetupComplete = true,
                        completedNow = true,
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
