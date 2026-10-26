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
import androidx.lifecycle.SavedStateHandle
import com.gatecontrol.android.common.CompanySignin
import com.gatecontrol.android.common.SigninRedirect
import com.gatecontrol.android.network.SigninCodeResponse
import com.gatecontrol.android.network.SigninHandoffRequest
import com.gatecontrol.android.network.SigninPasswordRequest
import com.google.gson.JsonParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** "Mit Firmenkonto anmelden" for the typed server (GateControl ≥ 1.201.0). */
    val signin: CompanySigninUi = CompanySigninUi(),
)

/** One Entra directory with "Mit Microsoft anmelden". */
data class MicrosoftSignin(val id: Int, val label: String)

/**
 * Company sign-in of the setup screen: the methods the server offers
 * (`GET /api/v1/client/signin`) and the password form. Both ways end with a
 * one-shot setup code that goes through the normal enroll path.
 * [toString] never shows the password or the verification code.
 */
data class CompanySigninUi(
    /** Normalized https server the methods belong to; null while none are known. */
    val serverUrl: String? = null,
    val passwordEnabled: Boolean = false,
    /** Server hint below the password field, e.g. "mit dem Windows-Passwort anmelden". */
    val hint: String? = null,
    val microsoft: List<MicrosoftSignin> = emptyList(),
    val username: String = "",
    val password: String = "",
    val totp: String = "",
    /** The account has 2FA: the next attempt carries the 6-digit code. */
    val totpRequired: Boolean = false,
    val busy: Boolean = false,
    /** Server checked without company sign-in (older server): the offer stays hidden. */
    val unavailableFor: String? = null,
    /** "Mit Microsoft anmelden": browser URL the screen opens once. */
    val browserUrl: String? = null,
) {
    val available: Boolean get() = serverUrl != null && (passwordEnabled || microsoft.isNotEmpty())

    override fun toString(): String =
        "CompanySigninUi(serverUrl=$serverUrl, passwordEnabled=$passwordEnabled, microsoft=${microsoft.size}, " +
            "totpRequired=$totpRequired, busy=$busy, unavailableFor=$unavailableFor)"
}

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
    /** Keeps a started Microsoft sign-in (PKCE verifier, state) across process death. */
    private val savedStateHandle: SavedStateHandle,
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
        scheduleSigninMethods(value, SIGNIN_METHODS_DEBOUNCE_MS)
    }

    fun onApiTokenChanged(value: String) {
        _uiState.update { it.copy(apiToken = value, statusMessage = null) }
    }

    fun toggleManualExpanded() {
        _uiState.update { it.copy(isManualExpanded = !it.isManualExpanded) }
        if (_uiState.value.isManualExpanded) scheduleSigninMethods(_uiState.value.serverUrl, 0)
    }

    // -------------------------------------------------------------------------
    // Company sign-in ("Mit Firmenkonto anmelden", GateControl ≥ 1.201.0)
    // -------------------------------------------------------------------------

    private var methodsJob: Job? = null

    /** Bottom button: opens the server entry; the sign-in appears once the server answered. */
    fun openCompanySignin() {
        _uiState.update { it.copy(isManualExpanded = true) }
        scheduleSigninMethods(_uiState.value.serverUrl, 0)
    }

    /**
     * Asks the typed server which company sign-in it offers. Only https
     * servers (as for a setup code); an older server (404) hides the offer.
     */
    private fun scheduleSigninMethods(rawUrl: String, delayMs: Long) {
        val server = EnrollmentLink.normalizeServerUrl(ensureHttps(rawUrl.trim()))
        val current = _uiState.value.signin
        if (server != null && (server == current.serverUrl || server == current.unavailableFor)) return
        methodsJob?.cancel()
        if (current.serverUrl != null || current.unavailableFor != null || current.totpRequired) {
            // Methods (and a typed password) of another server must not stay.
            _uiState.update {
                it.copy(signin = CompanySigninUi(username = it.signin.username, busy = it.signin.busy, browserUrl = it.signin.browserUrl))
            }
        }
        if (server == null) return
        methodsJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            loadSigninMethods(server)
        }
    }

    private suspend fun loadSigninMethods(server: String) {
        try {
            val response = apiClientProvider.getClient(server).signinMethods()
            val microsoft = response.microsoft.orEmpty().mapNotNull { m ->
                val id = m.id?.takeIf { it > 0 } ?: return@mapNotNull null
                MicrosoftSignin(id, plainText(m.label, MAX_LABEL))
            }.distinctBy { it.id }.take(MAX_MICROSOFT)
            val password = response.password?.enabled == true
            if (!response.ok || (!password && microsoft.isEmpty())) {
                _uiState.update { it.copy(signin = it.signin.copy(unavailableFor = server)) }
                return
            }
            _uiState.update {
                it.copy(
                    signin = it.signin.copy(
                        serverUrl = server,
                        passwordEnabled = password,
                        hint = response.password?.hint?.let { h -> plainText(h, MAX_HINT) }?.ifBlank { null },
                        microsoft = microsoft,
                        unavailableFor = null,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Timber.i("Company sign-in not offered (HTTP %d)", e.code())
            if (e.code() == 404) _uiState.update { it.copy(signin = it.signin.copy(unavailableFor = server)) }
        } catch (e: Exception) {
            // Network trouble: nothing is shown, the next server entry asks again.
            Timber.i("Company sign-in methods not loaded: %s", e.javaClass.simpleName)
        }
    }

    fun onSigninUsernameChanged(value: String) {
        _uiState.update { it.copy(signin = it.signin.copy(username = value.take(MAX_USERNAME)), statusMessage = null) }
    }

    fun onSigninPasswordChanged(value: String) {
        _uiState.update { it.copy(signin = it.signin.copy(password = value.take(MAX_PASSWORD)), statusMessage = null) }
    }

    fun onSigninTotpChanged(value: String) {
        _uiState.update { it.copy(signin = it.signin.copy(totp = value.filter(Char::isDigit).take(6)), statusMessage = null) }
    }

    /** „Anmelden“: account password (+ 2FA code) → setup code → enroll. */
    fun submitCompanyPassword() {
        val state = _uiState.value
        val signin = state.signin
        val server = signin.serverUrl ?: return
        if (!signin.passwordEnabled || signin.busy || state.isLoading) return
        val username = signin.username.trim()
        val password = signin.password
        if (username.isEmpty() || password.isEmpty()) {
            showError(UiText.Res(R.string.signin_error_fields))
            return
        }
        val totp = if (signin.totpRequired) signin.totp else null
        if (totp != null && !TOTP_RE.matches(totp)) {
            showError(UiText.Res(R.string.signin_error_totp_format))
            return
        }
        viewModelScope.launch {
            setSigninBusy()
            try {
                val response = apiClientProvider.getClient(server).signinPassword(
                    SigninPasswordRequest(
                        username = username,
                        password = password,
                        totp = totp,
                        hostname = android.os.Build.MODEL ?: "android",
                        platform = "android",
                    ),
                )
                val code = setupCodeOf(response) ?: throw SigninFailure(response.error ?: "failed", response.retryAfter)
                _uiState.update {
                    it.copy(signin = it.signin.copy(password = "", totp = "", totpRequired = false, busy = false))
                }
                enroll(server, code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val (code, retryAfter) = signinError(e)
                Timber.w("Company sign-in failed: %s", code)
                when (code) {
                    "totp_required" -> _uiState.update {
                        it.copy(
                            signin = it.signin.copy(totpRequired = true, totp = "", busy = false),
                            statusMessage = UiText.Res(R.string.signin_totp_required),
                            statusType = StatusType.INFO,
                        )
                    }
                    "totp_invalid" -> showError(signinErrorMessage(code, retryAfter, e)) { it.copy(totp = "") }
                    "invalid_credentials" -> showError(signinErrorMessage(code, retryAfter, e)) {
                        it.copy(password = "", totp = "", totpRequired = false)
                    }
                    else -> showError(signinErrorMessage(code, retryAfter, e))
                }
            }
        }
    }

    /**
     * „Mit Microsoft anmelden“: a fresh PKCE verifier + state (kept in the
     * SavedStateHandle) and the browser URL for the screen to open.
     */
    fun startMicrosoftSignin(directoryId: Int) {
        val state = _uiState.value
        val server = state.signin.serverUrl ?: return
        if (state.signin.busy || state.isLoading || state.signin.microsoft.none { it.id == directoryId }) return
        val verifier = CompanySignin.newVerifier()
        val nonce = CompanySignin.newState()
        savedStateHandle[KEY_VERIFIER] = verifier
        savedStateHandle[KEY_STATE] = nonce
        savedStateHandle[KEY_SERVER] = server
        _uiState.update {
            it.copy(
                statusMessage = null,
                signin = it.signin.copy(browserUrl = CompanySignin.startUrl(server, directoryId, nonce, CompanySignin.challenge(verifier))),
            )
        }
    }

    /** The screen opened [CompanySigninUi.browserUrl]. */
    fun onSigninBrowserOpened() {
        _uiState.update { it.copy(signin = it.signin.copy(browserUrl = null)) }
    }

    /** No browser could open the sign-in page. */
    fun onSigninBrowserUnavailable() {
        clearPendingMicrosoft()
        _uiState.update { it.copy(signin = it.signin.copy(browserUrl = null)) }
        showError(UiText.Res(R.string.signin_error_no_browser))
    }

    /**
     * `gatecontrol://signin?handoff=…&state=…` (or `?error=…`) from the
     * browser. Only accepted for the sign-in this app started (same state);
     * the handoff is then traded with the verifier for a setup code.
     */
    fun onSigninRedirect(raw: String) {
        val redirect = SigninRedirect.parse(raw)
        val expected: String? = savedStateHandle[KEY_STATE]
        val verifier: String? = savedStateHandle[KEY_VERIFIER]
        val server: String? = savedStateHandle[KEY_SERVER]
        if (redirect == null || expected == null || verifier == null || server == null ||
            !java.security.MessageDigest.isEqual(redirect.state.toByteArray(), expected.toByteArray())
        ) {
            // Not the sign-in started here (or a stale / forged link): a
            // running sign-in stays valid for its real answer.
            Timber.w("Company sign-in redirect rejected")
            showError(UiText.Res(R.string.signin_error_state))
            return
        }
        clearPendingMicrosoft()
        redirect.error?.let { error ->
            Timber.w("Company sign-in (Microsoft) failed: %s", error)
            showError(signinErrorMessage(error, null, null))
            return
        }
        val handoff = redirect.handoff ?: return
        viewModelScope.launch {
            setSigninBusy()
            try {
                val response = apiClientProvider.getClient(server).signinHandoff(SigninHandoffRequest(handoff, verifier))
                val code = setupCodeOf(response) ?: throw SigninFailure(response.error ?: "failed", response.retryAfter)
                _uiState.update { it.copy(signin = it.signin.copy(busy = false)) }
                enroll(server, code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val (code, retryAfter) = signinError(e)
                Timber.w("Company sign-in (handoff) failed: %s", code)
                showError(signinErrorMessage(code, retryAfter, e))
            }
        }
    }

    private fun clearPendingMicrosoft() {
        savedStateHandle.remove<String>(KEY_VERIFIER)
        savedStateHandle.remove<String>(KEY_STATE)
        savedStateHandle.remove<String>(KEY_SERVER)
    }

    private fun setSigninBusy() {
        _uiState.update {
            it.copy(
                signin = it.signin.copy(busy = true),
                statusMessage = UiText.Res(R.string.signin_running),
                statusType = StatusType.INFO,
            )
        }
    }

    private fun showError(message: UiText, signin: (CompanySigninUi) -> CompanySigninUi = { it }) {
        _uiState.update {
            it.copy(
                signin = signin(it.signin).copy(busy = false),
                statusMessage = message,
                statusType = StatusType.ERROR,
            )
        }
    }

    /** The setup code of a successful answer, in the form the enroll path expects. */
    private fun setupCodeOf(response: SigninCodeResponse): String? {
        val raw = response.code?.trim()?.takeIf { response.ok && it.isNotEmpty() } ?: return null
        return EnrollmentLink.normalizeCode(raw) ?: raw.takeIf { SETUP_CODE_RE.matches(it) }
    }

    private class SigninFailure(val code: String, val retryAfter: Long?) : Exception(code)

    /** Error code and wait time of a failed sign-in call ("network" when the server was not reached). */
    private fun signinError(e: Exception): Pair<String, Long?> = when (e) {
        is SigninFailure -> e.code to e.retryAfter
        is HttpException -> {
            val body = try {
                e.response()?.errorBody()?.string().orEmpty()
            } catch (_: Exception) {
                ""
            }
            val json = try {
                JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
            } catch (_: Exception) {
                null
            }
            val code = json?.get("error")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { ERROR_RE.matches(it) }
                ?: if (e.code() == 429) "rate_limited" else "failed"
            val retryAfter = json?.get("retryAfter")?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()?.toLong()
                ?: e.response()?.headers()?.get("Retry-After")?.trim()?.toLongOrNull()
            code to retryAfter
        }
        else -> "network" to null
    }

    private fun signinErrorMessage(code: String, retryAfter: Long?, e: Exception?): UiText = when (code) {
        "invalid_credentials" -> UiText.Res(R.string.signin_error_invalid_credentials)
        "totp_invalid" -> UiText.Res(R.string.signin_error_totp_invalid)
        "totp_required" -> UiText.Res(R.string.signin_totp_required)
        "enroll_not_allowed" -> UiText.Res(R.string.signin_error_enroll_not_allowed)
        "user_disabled" -> UiText.Res(R.string.signin_error_user_disabled)
        "limit_reached" -> UiText.Res(R.string.signin_error_limit_reached)
        "not_linked" -> UiText.Res(R.string.signin_error_not_linked)
        "password_change_required" -> UiText.Res(R.string.signin_error_password_change_required)
        "directory_unavailable" -> UiText.Res(R.string.signin_error_directory_unavailable)
        "access_denied" -> UiText.Res(R.string.signin_error_cancelled)
        "invalid_or_expired" -> UiText.Res(R.string.signin_error_expired)
        "rate_limited", "locked" -> when {
            retryAfter == null || retryAfter <= 0 -> UiText.Res(R.string.setup_enroll_rate_limited)
            retryAfter <= 60 -> UiText.Res(R.string.signin_error_locked_one)
            else -> UiText.Res(R.string.signin_error_locked, ((retryAfter + 59) / 60).toInt())
        }
        "network" -> UiText.Res(R.string.signin_error_network, e?.localizedMessage ?: e?.javaClass?.simpleName.orEmpty())
        else -> UiText.Res(R.string.signin_error_failed)
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

    private companion object {
        const val SIGNIN_METHODS_DEBOUNCE_MS = 700L
        const val KEY_VERIFIER = "company_signin_verifier"
        const val KEY_STATE = "company_signin_state"
        const val KEY_SERVER = "company_signin_server"
        const val MAX_LABEL = 60
        const val MAX_HINT = 200
        const val MAX_MICROSOFT = 6
        const val MAX_USERNAME = 256
        const val MAX_PASSWORD = 1024
        val TOTP_RE = Regex("^[0-9]{6}$")
        val SETUP_CODE_RE = Regex("^[A-Za-z0-9-]{4,128}$")
        val ERROR_RE = Regex("^[a-z_]{1,40}$")
        val CONTROL_RE = Regex("[\\u0000-\\u001F\\u007F]")

        /** Server text shown as plain text: control characters removed, length capped. */
        fun plainText(value: String?, max: Int): String =
            CONTROL_RE.replace(value.orEmpty(), " ").trim().take(max)
    }
}
