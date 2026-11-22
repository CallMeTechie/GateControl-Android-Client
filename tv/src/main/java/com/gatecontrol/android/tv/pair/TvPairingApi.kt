package com.gatecontrol.android.tv.pair

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.gatecontrol.android.common.TvPairLink
import com.gatecontrol.android.data.MachineFingerprint
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.ApiErrors
import com.gatecontrol.android.network.ClientPlatform
import com.gatecontrol.android.network.EnrollRequest
import com.gatecontrol.android.network.PairPollRequest
import com.gatecontrol.android.network.PairStartRequest
import com.gatecontrol.android.network.RegisterRequest
import com.gatecontrol.android.tunnel.TunnelConfig
import com.gatecontrol.android.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import retrofit2.Response
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** How the server's answers of the pairing calls become [PairEvent]s. Pure, unit-tested. */
object PairAnswers {
    private val ID_RE = Regex("^[A-Za-z0-9_-]{22}$")
    private val SECRET_RE = Regex("^[A-Za-z0-9_-]{43}$")

    /** `pair/start`: [status] HTTP, its body fields, or the error body. */
    fun start(
        status: Int,
        server: String,
        pairingId: String?,
        pollSecret: String?,
        code: String?,
        expiresAt: Long?,
        intervalS: Int?,
        link: String?,
        errorBody: String?,
        now: Long,
    ): PairEvent {
        if (status !in 200..299) {
            return PairEvent.StartFailed(
                when {
                    status == 404 -> PairError.NOT_SUPPORTED
                    status == 429 -> PairError.RATE_LIMITED
                    status == 503 || ApiErrors.code(errorBody) == "busy" -> PairError.BUSY
                    else -> PairError.NETWORK
                },
            )
        }
        if (pairingId == null || !ID_RE.matches(pairingId) || pollSecret == null || !SECRET_RE.matches(pollSecret) ||
            code.isNullOrBlank()
        ) {
            return PairEvent.StartFailed(PairError.INVALID_ANSWER)
        }
        // the QR code holds the server's own link only when it points to this pairing on the server typed in
        val parsed = TvPairLink.parse(link)
        val qr = if (parsed != null && parsed.pairingId == pairingId && parsed.serverUrl.equals(server, ignoreCase = true)) {
            link!!
        } else {
            TvPairLink.build(server, pairingId)
        }
        val interval = ((intervalS ?: 5) * 1000L).coerceIn(PairingMachine.MIN_INTERVAL_MS, PairingMachine.MAX_INTERVAL_MS)
        // a clock that is off or an answer without an end: the server's 10 minutes from now
        val end = expiresAt?.takeIf { it > now && it - now <= TTL_MS + 60_000L } ?: (now + TTL_MS)
        return PairEvent.Started(PairCode(pairingId, pollSecret, code, qr, end, interval))
    }

    /** `pair/poll` for [pairingId]. */
    fun poll(pairingId: String, status: Int, state: String?, intervalS: Int?, setupCode: String?, name: String?, errorBody: String?): PairEvent {
        if (status in 200..299) {
            return when (state) {
                "pending" -> PairEvent.PollPending(pairingId, intervalS?.let { it * 1000L })
                "approved" -> if (setupCode.isNullOrBlank()) {
                    PairEvent.PollFailed(pairingId, PairError.INVALID_ANSWER)
                } else {
                    PairEvent.PollApproved(pairingId, setupCode, name?.takeIf { it.isNotBlank() })
                }
                else -> PairEvent.PollFailed(pairingId, PairError.INVALID_ANSWER)
            }
        }
        val error = ApiErrors.code(errorBody)
        return when {
            error == "invalid_or_expired" -> PairEvent.PollExpired(pairingId)
            error == "slow_down" -> PairEvent.PollSlowDown(pairingId, ApiErrors.number(errorBody, "interval")?.times(1000L))
            status == 429 -> PairEvent.PollSlowDown(pairingId, null)
            error == "enroll_not_allowed" -> PairEvent.PollFailed(pairingId, PairError.NOT_ALLOWED)
            error == "user_disabled" -> PairEvent.PollFailed(pairingId, PairError.USER_DISABLED)
            error == "limit_reached" -> PairEvent.PollFailed(pairingId, PairError.LIMIT_REACHED)
            status == 400 || status == 404 -> PairEvent.PollExpired(pairingId)
            else -> PairEvent.PollUnreachable(pairingId)
        }
    }

    /** A failed `POST /api/v1/client/enroll`: the error code of its body. */
    fun enrollError(code: String?): PairError = when (code) {
        "invalid_or_expired" -> PairError.INVALID_SETUP_CODE
        "user_disabled", "user_not_found" -> PairError.USER_DISABLED
        "no_valid_scopes", "enroll_not_allowed" -> PairError.NOT_ALLOWED
        "limit_reached" -> PairError.LIMIT_REACHED
        else -> PairError.ENROLL_FAILED
    }

    const val TTL_MS = 10 * 60_000L
}

/**
 * The server calls of the pairing (start, poll, enroll) for [PairingController]. start and poll are public, enroll
 * redeems the setup code exactly as the phone app does (`SetupViewModel.enroll`) — with `platform: android-tv`, the
 * TV's name and the device fingerprint — and stores token, peer and WireGuard config.
 */
@Singleton
class TvPairingApi @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClientProvider: ApiClientProvider,
    private val setupRepository: SetupRepository,
    private val machineFingerprint: MachineFingerprint,
    private val platform: ClientPlatform,
) {
    /** The name the TV suggests ("Fire TV Stick 4K Max" from the system, else the model); the phone may rename it. */
    fun deviceName(): String {
        val system = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        return (system?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "TV").trim().take(50)
    }

    suspend fun start(server: String, now: () -> Long): PairEvent = try {
        val res = apiClientProvider.getClient(server).pairStart(
            PairStartRequest(name = deviceName(), platform = platform.wire, clientVersion = BuildConfig.VERSION_NAME),
        )
        val b = res.body()
        PairAnswers.start(
            status = res.code(), server = server,
            pairingId = b?.pairingId, pollSecret = b?.pollSecret, code = b?.code, expiresAt = b?.expiresAt,
            intervalS = b?.interval, link = b?.link, errorBody = errorBody(res), now = now(),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w("TV pairing: start failed (%s)", e.javaClass.simpleName)
        PairEvent.StartFailed(PairError.NETWORK)
    }

    suspend fun poll(server: String, code: PairCode): PairEvent = try {
        val res = apiClientProvider.getClient(server).pairPoll(PairPollRequest(code.pairingId, code.pollSecret))
        val b = res.body()
        PairAnswers.poll(code.pairingId, res.code(), b?.status, b?.interval, b?.code, b?.name, errorBody(res))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PairEvent.PollUnreachable(code.pairingId)
    }

    /** Redeems [setupCode] and stores the setup; [PairEvent.Enrolled] or [PairEvent.EnrollFailed]. */
    suspend fun enroll(server: String, setupCode: String, name: String?): PairEvent = try {
        apiClientProvider.invalidate()
        val response = apiClientProvider.getClient(server).enroll(
            EnrollRequest(
                code = setupCode,
                hostname = name ?: deviceName(),
                platform = platform.wire,
                clientVersion = BuildConfig.VERSION_NAME,
                fingerprint = machineFingerprint.get(),
            ),
        )
        val token = response.token
        if (!response.ok || token.isNullOrBlank()) {
            PairEvent.EnrollFailed(PairAnswers.enrollError(response.error))
        } else {
            var peerId = response.peerId ?: -1
            var config = response.config
            var hash = response.hash
            if (peerId <= 0) {
                // A code without a peer (token wizard): register with the new token, as the phone app does.
                setupRepository.save(server, token, -1)
                apiClientProvider.invalidate()
                val registered = apiClientProvider.getClient(server).register(
                    RegisterRequest(hostname = name ?: deviceName(), platform = platform.wire, clientVersion = BuildConfig.VERSION_NAME),
                )
                peerId = registered.peerId
                config = registered.config
                hash = registered.hash
            }
            if (peerId <= 0 || config.isNullOrBlank() || !TunnelConfig.isSupported(config)) {
                setupRepository.clear()
                PairEvent.EnrollFailed(PairError.ENROLL_FAILED)
            } else {
                setupRepository.save(server, token, peerId)
                setupRepository.saveWireGuardConfig(config)
                hash?.let { setupRepository.saveConfigHash(it) }
                apiClientProvider.invalidate()
                PairEvent.Enrolled
            }
        }
    } catch (e: CancellationException) {
        dropHalfSetup()
        throw e
    } catch (e: HttpException) {
        dropHalfSetup()
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        Timber.w("TV pairing: enroll refused (%d)", e.code())
        PairEvent.EnrollFailed(if (e.code() == 429) PairError.RATE_LIMITED else PairAnswers.enrollError(ApiErrors.code(body)))
    } catch (e: IOException) {
        dropHalfSetup()
        PairEvent.EnrollFailed(PairError.NETWORK)
    } catch (e: Exception) {
        dropHalfSetup()
        Timber.w("TV pairing: enroll failed (%s)", e.javaClass.simpleName)
        PairEvent.EnrollFailed(PairError.ENROLL_FAILED)
    }

    /** A token without a WireGuard config (register failed half-way) is no setup: the next try starts clean. */
    private fun dropHalfSetup() {
        if (!setupRepository.hasWireGuardConfig()) {
            setupRepository.clear()
            apiClientProvider.invalidate()
        }
    }

    private fun errorBody(res: Response<*>): String? = if (res.isSuccessful) null else ApiErrors.body(res)
}
