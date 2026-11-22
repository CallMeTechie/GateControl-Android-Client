package com.gatecontrol.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.common.EnrollmentLink
import com.gatecontrol.android.common.TvPairCode
import com.gatecontrol.android.common.TvPairLink
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.ApiErrors
import com.gatecontrol.android.network.PairApproveRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Why approving a TV did not work (texts in [TvPairScreen]). */
enum class TvPairError {
    /** Wrong, expired or already used code (the server does not tell them apart). */
    INVALID_CODE,

    /** Too many wrong codes on this account: approvals locked for a while. */
    LOCKED,

    /** This account may not set up own devices. */
    NOT_ALLOWED,
    USER_DISABLED,
    RATE_LIMITED,

    /** The server cannot pair TVs yet (before 1.318.0). */
    NOT_SUPPORTED,

    /** The scanned QR code belongs to another server than this phone's. */
    OTHER_SERVER,

    /** The scanned QR code is no TV pairing code. */
    NOT_A_TV_CODE,

    /** This phone is not set up with a token yet. */
    NOT_SET_UP,
    NETWORK,
}

sealed interface TvPairResult {
    data class Paired(val name: String?) : TvPairResult
    data class Failed(val error: TvPairError, val retryAfterS: Long? = null) : TvPairResult
}

/** Pure rules of the approval (unit-tested). */
object TvPairApproval {
    /** The error of a failed `POST /api/v1/client/pair/approve` ([status] HTTP, [code] the answer's `error`). */
    fun errorOf(status: Int, code: String?): TvPairError = when {
        code == "invalid_code" -> TvPairError.INVALID_CODE
        code == "locked" -> TvPairError.LOCKED
        code == "enroll_not_allowed" || code == "token_required" -> TvPairError.NOT_ALLOWED
        code == "user_disabled" -> TvPairError.USER_DISABLED
        status == 429 -> TvPairError.RATE_LIMITED
        status == 404 -> TvPairError.NOT_SUPPORTED
        status == 400 -> TvPairError.INVALID_CODE
        status == 401 || status == 403 -> TvPairError.NOT_ALLOWED
        else -> TvPairError.NETWORK
    }

    /** The TV shows a QR code of the same server this phone is set up with ([phoneServer] as stored). */
    fun sameServer(link: TvPairLink, phoneServer: String): Boolean {
        val own = EnrollmentLink.normalizeServerUrl(phoneServer) ?: return false
        return own.equals(link.serverUrl, ignoreCase = true)
    }
}

/**
 * "TV koppeln" (Settings › Verbindung): approves GateControl TV with this phone's device token
 * (`POST /api/v1/client/pair/approve`, server ≥ 1.318.0). Works from anywhere, the pairing goes through the server.
 * The code from the TV screen is always needed; the QR code (scanned, or opened as `gatecontrol://tv-pair` link)
 * adds the pairing id, so only that one TV can be approved.
 */
@HiltViewModel
class TvPairViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) : ViewModel() {

    data class UiState(
        val code: String = "",
        /** From the TV's QR code: its server and pairing id. */
        val link: TvPairLink? = null,
        val busy: Boolean = false,
        val result: TvPairResult? = null,
    ) {
        val canApprove: Boolean get() = !busy && TvPairCode.normalize(code) != null && result !is TvPairResult.Paired
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** A scanned QR code or an opened link. Anything but a TV pairing link of this phone's server is refused. */
    fun onLink(raw: String?) {
        if (raw.isNullOrBlank()) return
        val link = TvPairLink.parse(raw)
        val error = when {
            link == null -> TvPairError.NOT_A_TV_CODE
            !setupRepository.isConfigured() -> TvPairError.NOT_SET_UP
            !TvPairApproval.sameServer(link, setupRepository.getServerUrl()) -> TvPairError.OTHER_SERVER
            else -> null
        }
        _state.update {
            if (error != null) it.copy(link = null, result = TvPairResult.Failed(error)) else it.copy(link = link, result = null)
        }
    }

    fun onCodeChange(raw: String) {
        _state.update { it.copy(code = TvPairCode.cleanInput(raw), result = (it.result as? TvPairResult.Paired)) }
    }

    fun approve() {
        val s = _state.value
        val code = TvPairCode.normalize(s.code) ?: return
        if (s.busy) return
        val server = setupRepository.getServerUrl()
        if (!setupRepository.isConfigured()) {
            _state.update { it.copy(result = TvPairResult.Failed(TvPairError.NOT_SET_UP)) }
            return
        }
        _state.update { it.copy(busy = true, result = null) }
        viewModelScope.launch {
            val result = try {
                val res = apiClientProvider.getClient(server).pairApprove(PairApproveRequest(code = code, pairingId = s.link?.pairingId))
                val body = res.body()
                if (res.isSuccessful && body?.ok == true) {
                    TvPairResult.Paired(body.name)
                } else {
                    val err = ApiErrors.body(res)
                    TvPairResult.Failed(TvPairApproval.errorOf(res.code(), ApiErrors.code(err)), ApiErrors.number(err, "retryAfter"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("TV pairing: approve failed (%s)", e.javaClass.simpleName)
                TvPairResult.Failed(TvPairError.NETWORK)
            }
            _state.update {
                it.copy(busy = false, result = result, code = if (result is TvPairResult.Paired) "" else it.code)
            }
        }
    }
}
