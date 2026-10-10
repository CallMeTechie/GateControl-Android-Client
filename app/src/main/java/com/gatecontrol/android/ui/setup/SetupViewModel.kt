package com.gatecontrol.android.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.UiText
import com.gatecontrol.android.common.EnrollmentLink
import com.gatecontrol.android.data.MachineFingerprint
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.MachineBindingError
import com.gatecontrol.android.ui.toUiText
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.service.ClientPolicyManager
import com.gatecontrol.android.network.EnrollRequest
import com.gatecontrol.android.network.RegisterRequest
import com.gatecontrol.android.tunnel.TunnelConfig
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
    val statusMessage: UiText? = null,
    val statusType: StatusType = StatusType.INFO,
    val isSetupComplete: Boolean = false,
    val isManualExpanded: Boolean = false,
    /** Scanned/opened setup link waiting for the user's confirmation. */
    val pendingEnrollment: EnrollmentLink? = null,
    /** True once a setup action succeeded in this screen (not just "already configured"). */
    val completedNow: Boolean = false,
    /** Legacy gatecontrol://setup?url&token link waiting for the user's confirmation. */
    val pendingTokenSetup: TokenSetupLink? = null,
)

/** Server URL (https only) + API token from a legacy `gatecontrol://setup` link. */
data class TokenSetupLink(val serverUrl: String, val token: String) {
    /** Host (and non-default port) shown in the confirmation dialog. */
    val displayHost: String
        get() = serverUrl.removePrefix("https://").substringBefore('/')

    override fun toString(): String = "TokenSetupLink(serverUrl=$serverUrl, token=***)"
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    @ApplicationContext private val context: Context,
    private val clientPolicyManager: ClientPolicyManager,
    private val machineFingerprint: MachineFingerprint,
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
        _uiState.update { it.copy(serverUrl = value, statusMessage = null) }
    }

    fun onApiTokenChanged(value: String) {
        _uiState.update { it.copy(apiToken = value, statusMessage = null) }
    }

    fun toggleManualExpanded() {
        _uiState.update { it.copy(isManualExpanded = !it.isManualExpanded) }
    }

    fun testConnection() {
        val url = ensureHttps(_uiState.value.serverUrl.trim())
        val token = _uiState.value.apiToken.trim()
        if (url.isBlank()) {
            _uiState.update {
                it.copy(statusMessage = UiText.Res(R.string.setup_error_url_required), statusType = StatusType.ERROR)
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = UiText.Res(R.string.setup_testing_connection), statusType = StatusType.INFO) }
            try {
                // Side-effect free: the typed token is sent only with this
                // request and never written to the repository, so a test
                // cannot break an existing, working setup.
                val client = apiClientProvider.getClient(url)
                if (token.isNotEmpty() && EnrollmentLink.normalizeCode(token) == null) {
                    client.pingWithToken(token)
                } else {
                    client.ping()
                }
                _uiState.update {
                    it.copy(isLoading = false, statusMessage = UiText.Res(R.string.settings_connection_ok), statusType = StatusType.SUCCESS)
                }
            } catch (e: Exception) {
                Timber.w(e, "testConnection failed")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = UiText.Res(R.string.setup_connection_failed, e.localizedMessage ?: ""),
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
            _uiState.update { it.copy(statusMessage = UiText.Res(R.string.setup_error_url_required), statusType = StatusType.ERROR) }
            return
        }
        if (token.isBlank()) {
            _uiState.update { it.copy(statusMessage = UiText.Res(R.string.setup_error_token_required), statusType = StatusType.ERROR) }
            return
        }

        // The token field also takes the short setup code shown next to the
        // server's QR code — typed codes need no confirmation, the user
        // entered the server URL themselves.
        EnrollmentLink.normalizeCode(token)?.let { code ->
            val serverUrl = EnrollmentLink.normalizeServerUrl(url)
            if (serverUrl == null) {
                _uiState.update { it.copy(statusMessage = UiText.Res(R.string.setup_enroll_https_required), statusType = StatusType.ERROR) }
            } else {
                enroll(serverUrl, code)
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = UiText.Res(R.string.setup_registering), statusType = StatusType.INFO) }
            // Restored if registration fails — a failed attempt must never
            // wipe a working setup.
            val previousUrl = setupRepository.getServerUrl()
            val previousToken = setupRepository.getApiToken()
            val previousPeerId = setupRepository.getPeerId()
            try {
                // Save token BEFORE register call so AuthInterceptor can use it
                setupRepository.save(url, token, -1)
                // Invalidate cached clients so new token is picked up
                apiClientProvider.invalidate()

                val client = apiClientProvider.getClient(url)
                client.ping()

                val response = client.register(
                    RegisterRequest(
                        hostname = android.os.Build.MODEL ?: "android",
                        platform = "android",
                        clientVersion = appVersion,
                    ),
                )

                if (!response.ok || response.peerId <= 0) {
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
                // New server / token: the old server's client policy must not stick.
                resetClientPolicy()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = UiText.Res(R.string.setup_success),
                        statusType = StatusType.SUCCESS,
                        isSetupComplete = true,
                        completedNow = true,
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "saveAndRegister failed")
                // Roll back to the previous configuration instead of clearing it
                setupRepository.save(previousUrl, previousToken, previousPeerId)
                apiClientProvider.invalidate()
                val message = MachineBindingError.from(e)?.toUiText()
                    ?: UiText.Res(R.string.setup_error, e.localizedMessage ?: "")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = message,
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
        _uiState.update { it.copy(pendingEnrollment = link, statusMessage = null) }
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
                    statusMessage = UiText.Res(R.string.setup_enroll_running),
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
                        fingerprint = machineFingerprint.get(),
                    ),
                )
                val token = response.token
                if (!response.ok || token.isNullOrBlank()) {
                    throw IllegalStateException(response.error ?: "enroll_failed")
                }
                var peerId = response.peerId ?: -1
                var config = response.config
                var hash = response.hash
                if (peerId <= 0) {
                    // Code from the token wizard without a peer: register with
                    // the new token, exactly like a hand-entered token. The old
                    // setup is restored if that fails.
                    val registered = registerWithToken(serverUrl, token)
                    peerId = registered.peerId
                    config = registered.config
                    hash = registered.hash
                }
                if (config.isNullOrBlank() || !TunnelConfig.isSupported(config)) {
                    throw IllegalStateException("invalid_config")
                }

                setupRepository.save(serverUrl, token, peerId)
                setupRepository.saveWireGuardConfig(config)
                hash?.let { setupRepository.saveConfigHash(it) }
                apiClientProvider.invalidate()
                resetClientPolicy()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        apiToken = "",
                        statusMessage = UiText.Res(R.string.setup_enroll_success),
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

    private suspend fun registerWithToken(serverUrl: String, token: String): com.gatecontrol.android.network.RegisterResponse {
        val previousUrl = setupRepository.getServerUrl()
        val previousToken = setupRepository.getApiToken()
        val previousPeerId = setupRepository.getPeerId()
        // AuthInterceptor reads the token from the repository.
        setupRepository.save(serverUrl, token, -1)
        apiClientProvider.invalidate()
        return try {
            val registered = apiClientProvider.getClient(serverUrl).register(
                RegisterRequest(
                    hostname = android.os.Build.MODEL ?: "android",
                    platform = "android",
                    clientVersion = appVersion,
                ),
            )
            if (!registered.ok || registered.peerId <= 0) throw IllegalStateException("enroll_failed")
            registered
        } catch (e: Exception) {
            setupRepository.save(previousUrl, previousToken, previousPeerId)
            apiClientProvider.invalidate()
            throw e
        }
    }

    private fun enrollErrorMessage(e: Exception): UiText {
        val code = if (e is HttpException) {
            if (e.code() == 429) {
                "rate_limited"
            } else {
                val body = try {
                    e.response()?.errorBody()?.string().orEmpty()
                } catch (_: Exception) {
                    ""
                }
                // Binding errors also come from the register call of a token-wizard code.
                MachineBindingError.fromResponse(e.code(), body)?.let { return it.toUiText() }
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
            "fingerprint_required" -> R.string.binding_required
            else -> null
        }
        return if (res != null) {
            UiText.Res(res)
        } else {
            UiText.Res(R.string.setup_enroll_failed, e.localizedMessage ?: code)
        }
    }

    /**
     * Legacy `gatecontrol://setup?url=…&token=…` link (QR code). Like an
     * enrollment link it only takes effect after the user confirmed the
     * server, and only https servers are accepted.
     */
    fun handleDeepLink(url: String, token: String) {
        val link = parseTokenSetup(url, token)
        if (link == null) {
            _uiState.update {
                it.copy(
                    pendingTokenSetup = null,
                    statusMessage = UiText.Res(R.string.setup_link_https_required),
                    statusType = StatusType.ERROR,
                )
            }
            return
        }
        _uiState.update { it.copy(pendingTokenSetup = link, statusMessage = null) }
    }

    fun cancelTokenSetup() {
        _uiState.update { it.copy(pendingTokenSetup = null) }
    }

    fun confirmTokenSetup() {
        val link = _uiState.value.pendingTokenSetup ?: return
        _uiState.update { it.copy(pendingTokenSetup = null, serverUrl = link.serverUrl, apiToken = link.token) }
        saveAndRegister()
    }

    private fun parseTokenSetup(url: String, token: String): TokenSetupLink? {
        val trimmedToken = token.trim()
        if (trimmedToken.isEmpty()) return null
        val uri = try {
            java.net.URI(url.trim())
        } catch (_: Exception) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return TokenSetupLink(url.trim().trimEnd('/'), trimmedToken)
    }

    private suspend fun resetClientPolicy() {
        try {
            clientPolicyManager.reset()
        } catch (e: Exception) {
            Timber.w(e, "Client policy reset failed")
        }
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
                            statusMessage = UiText.Res(R.string.setup_invalid_config),
                            statusType = StatusType.ERROR,
                        )
                    }
                    return@launch
                }
                if (TunnelConfig.peerCount(configText) > 1) {
                    Timber.w("importConfig rejected: multiple [Peer] sections")
                    _uiState.update {
                        it.copy(
                            statusMessage = UiText.Res(R.string.setup_multi_peer_config),
                            statusType = StatusType.ERROR,
                        )
                    }
                    return@launch
                }

                setupRepository.saveWireGuardConfig(configText)

                _uiState.update {
                    it.copy(
                        statusMessage = UiText.Res(R.string.setup_config_imported),
                        statusType = StatusType.SUCCESS,
                        isSetupComplete = true,
                        completedNow = true,
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "importConfig failed")
                _uiState.update {
                    it.copy(
                        statusMessage = UiText.Res(R.string.setup_import_failed, e.localizedMessage ?: ""),
                        statusType = StatusType.ERROR,
                    )
                }
            }
        }
    }
}
