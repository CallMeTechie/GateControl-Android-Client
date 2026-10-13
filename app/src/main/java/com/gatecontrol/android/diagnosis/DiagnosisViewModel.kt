package com.gatecontrol.android.diagnosis

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.data.MachineFingerprint
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.data.SplitTunnelJson
import com.gatecontrol.android.network.MachineBindingError
import com.gatecontrol.android.network.SupportBundleUploader
import com.gatecontrol.android.support.SupportBundleCollector
import com.gatecontrol.android.support.SupportRequestHolder
import com.gatecontrol.android.ui.UiText
import com.gatecontrol.android.ui.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

enum class DiagnosisPhase { IDLE, RUNNING, DONE }

data class DiagnosisUiState(
    val phase: DiagnosisPhase = DiagnosisPhase.IDLE,
    val checks: List<CheckResult> = CheckId.entries.map(CheckResult::pending),
    val report: DiagnosisReport? = null,
    /** "Problem melden" was tapped before a run: open the dialog once it is done. */
    val reportAfterRun: Boolean = false,
    val reportDialogVisible: Boolean = false,
    /** Text of "Was funktioniert nicht?" — kept here so it survives rotation. */
    val description: String = "",
    val sending: Boolean = false,
    /** One-shot result of the upload (toast, then consumed). */
    val message: UiText? = null,
    val serverHost: String = "",
)

/**
 * Diagnosis screen: runs [DiagnosisRunner], keeps the results across
 * configuration changes and sends them with the support bundle
 * ("Problem melden").
 */
@HiltViewModel
class DiagnosisViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runner: DiagnosisRunner,
    private val setupRepository: SetupRepository,
    private val settingsRepository: SettingsRepository,
    private val machineFingerprint: MachineFingerprint,
    private val supportBundleCollector: SupportBundleCollector,
    private val supportBundleUploader: SupportBundleUploader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DiagnosisUiState(
            serverHost = setupRepository.getServerUrl().removePrefix("https://").removePrefix("http://").trimEnd('/'),
        ),
    )
    val uiState: StateFlow<DiagnosisUiState> = _uiState.asStateFlow()

    private var runJob: Job? = null

    fun start() {
        if (runJob?.isActive == true) return
        _uiState.update { it.copy(phase = DiagnosisPhase.RUNNING, report = null, checks = CheckId.entries.map(CheckResult::pending)) }
        runJob = viewModelScope.launch {
            val report = runner.run { checks -> _uiState.update { it.copy(checks = checks) } }
            Timber.i("Diagnosis finished: %s (%s)", report.summary.key, report.checks.joinToString { "${it.id.key}=${it.status.key}" })
            _uiState.update {
                it.copy(
                    phase = DiagnosisPhase.DONE,
                    report = report,
                    checks = report.checks,
                    reportDialogVisible = it.reportDialogVisible || it.reportAfterRun,
                    reportAfterRun = false,
                )
            }
        }
    }

    /** "Problem melden": the dialog, after a diagnosis run (started now if there is none yet). */
    fun reportProblem() {
        if (setupRepository.getServerUrl().isBlank() || setupRepository.getPeerId() <= 0) {
            _uiState.update { it.copy(message = UiText.Res(R.string.support_not_configured)) }
            return
        }
        val state = _uiState.value
        when {
            state.report != null && state.phase == DiagnosisPhase.DONE -> _uiState.update { it.copy(reportDialogVisible = true) }
            else -> {
                _uiState.update { it.copy(reportAfterRun = true) }
                start()
            }
        }
    }

    fun dismissReport() {
        _uiState.update { it.copy(reportDialogVisible = false) }
    }

    fun setDescription(text: String) {
        _uiState.update { it.copy(description = text.take(DiagnosisPayload.MAX_DESCRIPTION)) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /** Collects the redacted bundle with description and diagnosis and uploads it. */
    fun send(appVersion: String) {
        val state = _uiState.value
        val report = state.report ?: return
        if (state.sending) return
        val serverUrl = setupRepository.getServerUrl()
        val peerId = setupRepository.getPeerId()
        if (serverUrl.isBlank() || peerId <= 0) {
            _uiState.update { it.copy(reportDialogVisible = false, message = UiText.Res(R.string.support_not_configured)) }
            return
        }
        _uiState.update { it.copy(reportDialogVisible = false, sending = true) }
        viewModelScope.launch {
            val message = try {
                val locale = runCatching { settingsRepository.getLocale().first() }.getOrDefault("")
                val settings = settingsSnapshot(locale)
                val bundle = withContext(Dispatchers.IO) {
                    val res = localizedResources(locale)
                    supportBundleCollector.collect(
                        appVersion = appVersion,
                        locale = locale,
                        settings = settings,
                        reason = if (SupportRequestHolder.request.value != null) "admin_request" else "user",
                        description = DiagnosisPayload.description(state.description),
                        diagnosis = DiagnosisPayload.build(report) { it.resolve(res) },
                    )
                }
                val response = supportBundleUploader.upload(serverUrl, peerId, bundle)
                if (response.ok) {
                    SupportRequestHolder.clear()
                    Timber.i("Problem report sent (bundle %s)", response.bundle?.id)
                    _uiState.update { it.copy(description = "") }
                    UiText.Res(R.string.diag_report_success)
                } else {
                    UiText.Res(R.string.support_failed, response.error ?: "")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                Timber.w("Problem report rejected: HTTP %d", e.code())
                uploadError(e)
            } catch (e: Exception) {
                Timber.w("Problem report failed: %s", e.javaClass.simpleName)
                UiText.Res(R.string.support_failed, e.localizedMessage ?: e.javaClass.simpleName)
            }
            _uiState.update { it.copy(sending = false, message = message) }
        }
    }

    /** Context in the app language (Settings › Darstellung › Sprache), as on screen. */
    private fun localizedResources(locale: String): Resources {
        if (locale.isBlank()) return context.resources
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
        return context.createConfigurationContext(config).resources
    }

    /** The same settings as Settings › Support-Paket — never the API token. */
    private suspend fun settingsSnapshot(locale: String): Map<String, Any?> = mapOf(
        "serverUrl" to setupRepository.getServerUrl(),
        "peerId" to setupRepository.getPeerId(),
        "deviceId" to runCatching { machineFingerprint.shortId() }.getOrDefault(""),
        "theme" to runCatching { settingsRepository.getTheme().first() }.getOrNull(),
        "locale" to locale,
        "autoConnect" to runCatching { settingsRepository.getAutoConnect().first() }.getOrNull(),
        "splitTunnelMode" to runCatching { settingsRepository.getSplitTunnelMode().first().name }.getOrNull(),
        "splitTunnelNetworks" to runCatching {
            SplitTunnelJson.decodeNetworks(settingsRepository.getSplitTunnelNetworks().first()).map { mapOf("cidr" to it.cidr, "label" to it.label) }
        }.getOrNull(),
        "splitTunnelApps" to runCatching { SplitTunnelJson.decodeApps(settingsRepository.getSplitTunnelAppsV2().first()) }.getOrNull(),
        "splitTunnelAdminLocked" to runCatching { settingsRepository.getSplitTunnelAdminLocked().first() }.getOrNull(),
    )

    companion object {
        /** Same messages as the support bundle row in Settings. */
        internal fun uploadError(e: HttpException): UiText {
            val bindingError = MachineBindingError.from(e)
            if (bindingError != null) return bindingError.toUiText()
            return when (e.code()) {
                429 -> UiText.Res(R.string.support_rate_limited)
                413 -> UiText.Res(R.string.support_too_large)
                401, 403 -> UiText.Res(R.string.support_forbidden)
                404 -> UiText.Res(R.string.support_unsupported)
                else -> UiText.Res(R.string.support_failed, "HTTP ${e.code()}")
            }
        }
    }
}
