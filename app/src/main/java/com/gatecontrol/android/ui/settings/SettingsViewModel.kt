package com.gatecontrol.android.ui.settings

import com.gatecontrol.android.common.SplitTunnelMode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.UiText
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.data.SplitTunnelJson
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.tunnel.TunnelConfig
import com.gatecontrol.android.tunnel.WgConfigValidator
import com.gatecontrol.android.network.UpdateCheckResponse
import com.gatecontrol.android.common.Validation
import com.gatecontrol.android.common.ClientPolicy
import com.gatecontrol.android.service.ClientPolicyManager
import com.gatecontrol.android.network.SupportBundleUploader
import com.gatecontrol.android.support.SupportBundleCollector
import com.gatecontrol.android.support.SupportRequestHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
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
    val splitTunnelEnabled: Boolean = false,
    val splitTunnelRoutes: String = "",
    val splitTunnelApps: String = "",
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.OFF,
    val splitTunnelNetworks: List<NetworkEntry> = emptyList(),
    val splitTunnelAppsV2: List<String> = emptyList(),
    val splitTunnelAdminLocked: Boolean = false,
    val serverUrl: String = "",
    val apiToken: String = "",
    val connectionTestStatus: ConnectionTestStatus = ConnectionTestStatus.Idle,
    val isLoading: Boolean = false,
    val updateInfo: UpdateCheckResponse? = null,
    val appVersion: String = "",
    val error: UiText? = null,
    val isPro: Boolean = false,
    val peerId: Int = 0,
    /** Client policy from the server (unrestricted until one was fetched). */
    val policy: ClientPolicy = ClientPolicy.UNRESTRICTED,
    /** Confirmation dialog for "Support-Paket senden" is open. */
    val supportDialogVisible: Boolean = false,
    val supportSending: Boolean = false,
    /** One-shot result of the last upload (shown as a toast, then consumed). */
    val supportMessage: UiText? = null,
    /** An admin asked for a support bundle (heartbeat). */
    val supportRequested: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val settingsRepository: SettingsRepository,
    private val apiClientProvider: ApiClientProvider,
    private val licenseRepository: LicenseRepository,
    private val supportBundleCollector: SupportBundleCollector,
    private val supportBundleUploader: SupportBundleUploader,
    private val clientPolicyManager: ClientPolicyManager,
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
                settingsRepository.getSplitTunnelEnabled()
            ) { theme, locale, autoConnect, splitTunnelEnabled ->
                _uiState.update {
                    it.copy(
                        theme = theme,
                        locale = locale,
                        autoConnect = autoConnect,
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
                val networks = SplitTunnelJson.decodeNetworks(json).map { NetworkEntry(it.cidr, it.label) }
                _uiState.update { it.copy(splitTunnelNetworks = networks) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getSplitTunnelAppsV2().collect { json ->
                val apps = SplitTunnelJson.decodeApps(json)
                _uiState.update { it.copy(splitTunnelAppsV2 = apps) }
            }
        }
        viewModelScope.launch {
            clientPolicyManager.policy.collect { policy ->
                _uiState.update { it.copy(policy = policy) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getSplitTunnelAdminLocked().collect { locked ->
                _uiState.update { it.copy(splitTunnelAdminLocked = locked) }
            }
        }



        viewModelScope.launch {
            SupportRequestHolder.request.collect { request ->
                _uiState.update { it.copy(supportRequested = request != null) }
            }
        }

        _uiState.update {
            it.copy(
                serverUrl = setupRepository.getServerUrl(),
                apiToken = setupRepository.getApiToken(),
                peerId = setupRepository.getPeerId(),
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
        if (_uiState.value.policy.autoConnectLocked) return
        viewModelScope.launch {
            settingsRepository.setAutoConnect(enabled)
            _uiState.update { it.copy(autoConnect = enabled) }
        }
    }

    fun setSplitTunnelEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSplitTunnelEnabled(enabled)
            _uiState.update { it.copy(splitTunnelEnabled = enabled) }
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
            _uiState.update { it.copy(error = UiText.Res(R.string.settings_error_invalid_url)) }
            return
        }
        if (!Validation.validateApiToken(token)) {
            _uiState.update { it.copy(error = UiText.Res(R.string.settings_error_invalid_token)) }
            return
        }
        if (_uiState.value.policy.lockServer) {
            _uiState.update { it.copy(error = UiText.Res(R.string.policy_server_locked)) }
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
                // New server / token: the old server's policy must not stick.
                clientPolicyManager.reset()
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
                        error = UiText.Res(R.string.setup_connection_failed, e.localizedMessage ?: ""),
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

    fun setSplitTunnelMode(mode: SplitTunnelMode) {
        val policy = _uiState.value.policy
        if (policy.splitTunnelFrozen || !policy.isModeAllowed(mode)) return
        _uiState.update { it.copy(splitTunnelMode = mode) }
        viewModelScope.launch { settingsRepository.setSplitTunnelMode(mode) }
    }

    fun setSplitTunnelNetworks(networks: List<NetworkEntry>) {
        if (_uiState.value.policy.splitTunnelFrozen) return
        _uiState.update { it.copy(splitTunnelNetworks = networks) }
        viewModelScope.launch {
            settingsRepository.setSplitTunnelNetworks(
                SplitTunnelJson.encodeNetworks(networks.map { SplitTunnelJson.Network(it.cidr, it.label) }),
            )
        }
    }

    fun setSplitTunnelAppsV2(apps: List<String>) {
        if (_uiState.value.policy.lockSettings) return
        _uiState.update { it.copy(splitTunnelAppsV2 = apps) }
        viewModelScope.launch {
            settingsRepository.setSplitTunnelAppsV2(SplitTunnelJson.encodeApps(apps))
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
                        error = UiText.Res(R.string.settings_error_update_check, e.localizedMessage ?: "")
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
                        pihole = perms.pihole,
                        piholeControl = perms.piholeControl,
                    )
                    clientPolicyManager.noteVersionAsync(response.policyVersion)
                    val isPro = perms.rdp || perms.traffic || perms.dns
                    _uiState.update {
                        it.copy(
                            isPro = isPro,
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "License refresh failed")
                _uiState.update { it.copy(error = UiText.Res(R.string.settings_error_license, e.localizedMessage ?: "")) }
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
        if (_uiState.value.policy.lockServer) {
            _uiState.update { it.copy(error = UiText.Res(R.string.policy_server_locked)) }
            return
        }
        viewModelScope.launch {
            try {
                val input = context.contentResolver.openInputStream(uri)
                val config = input?.bufferedReader()?.readText() ?: return@launch
                input.close()
                val validation = WgConfigValidator.validate(config)
                if (!validation.ok) {
                    Timber.w("importConfigFromUri rejected: %s", validation.errors.joinToString(", "))
                    _uiState.update {
                        it.copy(error = UiText.Res(R.string.setup_invalid_config))
                    }
                    return@launch
                }
                if (TunnelConfig.peerCount(config) > 1) {
                    Timber.w("importConfigFromUri rejected: multiple [Peer] sections")
                    _uiState.update { it.copy(error = UiText.Res(R.string.setup_multi_peer_config)) }
                    return@launch
                }
                setupRepository.saveWireGuardConfig(config)
                _uiState.update { it.copy(error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = UiText.Res(R.string.setup_import_failed, e.message ?: "")) }
            }
        }
    }

    // ── Support bundle ("Support-Paket senden") ───────────────────────────

    /** Opens the confirmation dialog (server and peer must be set up). */
    fun requestSupportBundle() {
        if (setupRepository.getServerUrl().isBlank() || setupRepository.getPeerId() <= 0) {
            _uiState.update { it.copy(supportMessage = UiText.Res(R.string.support_not_configured)) }
            return
        }
        _uiState.update { it.copy(supportDialogVisible = true) }
    }

    fun dismissSupportDialog() {
        _uiState.update { it.copy(supportDialogVisible = false) }
    }

    fun consumeSupportMessage() {
        _uiState.update { it.copy(supportMessage = null) }
    }

    /**
     * Collects the redacted bundle and uploads it — only called from the
     * confirmation dialog.
     */
    fun sendSupportBundle(appVersion: String) {
        if (_uiState.value.supportSending) return
        val state = _uiState.value
        val serverUrl = setupRepository.getServerUrl()
        val peerId = setupRepository.getPeerId()
        if (serverUrl.isBlank() || peerId <= 0) {
            _uiState.update { it.copy(supportDialogVisible = false, supportMessage = UiText.Res(R.string.support_not_configured)) }
            return
        }
        val reason = if (state.supportRequested) "admin_request" else "user"
        _uiState.update { it.copy(supportDialogVisible = false, supportSending = true) }
        viewModelScope.launch {
            val message = try {
                val bundle = withContext(Dispatchers.IO) {
                    supportBundleCollector.collect(
                        appVersion = appVersion,
                        locale = state.locale,
                        settings = supportSettingsSnapshot(state),
                        reason = reason,
                    )
                }
                val response = supportBundleUploader.upload(serverUrl, peerId, bundle)
                if (response.ok) {
                    SupportRequestHolder.clear()
                    Timber.i("Support bundle sent (id %s)", response.bundle?.id)
                    UiText.Res(R.string.support_success)
                } else {
                    UiText.Res(R.string.support_failed, response.error ?: "")
                }
            } catch (e: HttpException) {
                Timber.w("Support bundle upload rejected: HTTP %d", e.code())
                when (e.code()) {
                    429 -> UiText.Res(R.string.support_rate_limited)
                    413 -> UiText.Res(R.string.support_too_large)
                    401, 403 -> UiText.Res(R.string.support_forbidden)
                    404 -> UiText.Res(R.string.support_unsupported)
                    else -> UiText.Res(R.string.support_failed, "HTTP ${e.code()}")
                }
            } catch (e: Exception) {
                Timber.w("Support bundle upload failed: %s", e.javaClass.simpleName)
                UiText.Res(R.string.support_failed, e.localizedMessage ?: e.javaClass.simpleName)
            }
            _uiState.update { it.copy(supportSending = false, supportMessage = message) }
        }
    }

    /** Settings for the bundle — never the API token. */
    private fun supportSettingsSnapshot(state: SettingsUiState): Map<String, Any?> = mapOf(
        "serverUrl" to state.serverUrl,
        "peerId" to state.peerId,
        "theme" to state.theme,
        "locale" to state.locale,
        "autoConnect" to state.autoConnect,
        "splitTunnelMode" to state.splitTunnelMode.name,
        "splitTunnelNetworks" to state.splitTunnelNetworks.map { mapOf("cidr" to it.cidr, "label" to it.label) },
        "splitTunnelApps" to state.splitTunnelAppsV2,
        "splitTunnelAdminLocked" to state.splitTunnelAdminLocked,
        "isPro" to state.isPro,
    )

    fun exportLogs(cacheDir: File): File? {
        return try {
            val logFile = File(File(cacheDir, "export").apply { mkdirs() }, "gatecontrol-logs.txt")
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
