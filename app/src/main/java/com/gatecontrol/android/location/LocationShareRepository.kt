package com.gatecontrol.android.location

import android.content.Context
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.LocationReportRequest
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.google.gson.Gson
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A position the app sent: epoch ms and accuracy in metres. */
data class SentLocation(val at: Long, val accuracy: Double?)

/** What the server says about location sharing for this device. */
sealed interface LocationServerStatus {
    data object Unknown : LocationServerStatus
    /** Older GateControl without the endpoint (404). */
    data object Unsupported : LocationServerStatus
    /** The administrator has not allowed it for this user. */
    data object NotAllowed : LocationServerStatus
    data class Allowed(val last: SentLocation?, val keep: Int, val retentionDays: Int) : LocationServerStatus
}

/** Progress of one tap on the satellite button. */
sealed interface LocationShareState {
    data object Idle : LocationShareState
    data object Locating : LocationShareState
    data object Sending : LocationShareState
    data class Sent(val location: SentLocation) : LocationShareState
    data class Failed(val reason: LocationFailure) : LocationShareState

    /** The satellite button is red: working, or sent within the last minute. */
    val active: Boolean get() = this is Locating || this is Sending || this is Sent
}

enum class LocationFailure { NO_PERMISSION, LOCATION_OFF, NO_FIX, NOT_ALLOWED, UNSUPPORTED, NETWORK, NOT_CONFIGURED }

/**
 * Location sharing on tap: only when the user taps the satellite button on
 * the start screen does the app take one position and send it to the own
 * GateControl server (`POST /api/v1/client/location`). No background
 * location, no periodic sending, nothing to third parties.
 */
@Singleton
class LocationShareRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    val enabled: StateFlow<Boolean> = settingsRepository.getLocationSharing()
        .stateIn(scope, SharingStarted.Eagerly, false)

    val lastSent: StateFlow<SentLocation?> = settingsRepository.getLocationLastSent()
        .map { decode(it) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _server = MutableStateFlow<LocationServerStatus>(LocationServerStatus.Unknown)
    val server: StateFlow<LocationServerStatus> = _server.asStateFlow()

    private val _state = MutableStateFlow<LocationShareState>(LocationShareState.Idle)
    val state: StateFlow<LocationShareState> = _state.asStateFlow()

    private var resetJob: Job? = null

    suspend fun setEnabled(value: Boolean) {
        settingsRepository.setLocationSharing(value)
        if (!value) {
            resetJob?.cancel()
            _state.value = LocationShareState.Idle
        }
    }

    /** Asks the server whether this device may share (and what it keeps). */
    suspend fun refreshServer(): LocationServerStatus {
        val serverUrl = setupRepository.getServerUrl()
        if (serverUrl.isBlank()) return _server.value
        val status = try {
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(serverUrl).getLocationStatus() }
            when {
                response == null -> _server.value
                response.code() == 404 -> LocationServerStatus.Unsupported
                // 403 here is about the token (binding, no device), not the permission.
                !response.isSuccessful -> _server.value.also { response.errorBody()?.close() }
                else -> {
                    val body = response.body()
                    if (body?.allowed == true) {
                        LocationServerStatus.Allowed(
                            last = body.last?.takeIf { it.at > 0 }?.let { SentLocation(it.at, it.accuracy) },
                            keep = body.keep ?: DEFAULT_KEEP,
                            retentionDays = body.retentionDays ?: DEFAULT_RETENTION_DAYS,
                        )
                    } else {
                        LocationServerStatus.NotAllowed
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Location status unavailable (%s)", e.javaClass.simpleName)
            _server.value
        }
        _server.value = status
        return status
    }

    fun refreshServerAsync() {
        scope.launch { refreshServer() }
    }

    /** One tap on the satellite button: locate once, send once. */
    fun shareNow() {
        val current = _state.value
        if (current is LocationShareState.Locating || current is LocationShareState.Sending) return
        resetJob?.cancel()
        _state.value = LocationShareState.Locating
        scope.launch {
            val result = share()
            _state.value = result
            // Red for a minute after sending, a failure shows a little longer than a blink.
            resetJob = scope.launch {
                delay(if (result is LocationShareState.Sent) ACTIVE_AFTER_SEND_MS else FAILURE_SHOWN_MS)
                if (_state.value == result) _state.value = LocationShareState.Idle
            }
        }
    }

    fun dismiss() {
        resetJob?.cancel()
        val s = _state.value
        if (s !is LocationShareState.Locating && s !is LocationShareState.Sending) _state.value = LocationShareState.Idle
    }

    /** Locate once and send once; the outcome as a state. */
    internal suspend fun share(): LocationShareState {
        // Read from storage: the state flow may not have its first value yet right after start.
        if (!settingsRepository.getLocationSharing().first()) return LocationShareState.Failed(LocationFailure.NOT_ALLOWED)
        val serverUrl = setupRepository.getServerUrl()
        if (serverUrl.isBlank()) return LocationShareState.Failed(LocationFailure.NOT_CONFIGURED)

        val location = when (val r = DeviceLocator.locate(context)) {
            is LocateResult.Fix -> r.location
            LocateResult.NoPermission -> return LocationShareState.Failed(LocationFailure.NO_PERMISSION)
            LocateResult.LocationOff -> return LocationShareState.Failed(LocationFailure.LOCATION_OFF)
            LocateResult.NoFix -> return LocationShareState.Failed(LocationFailure.NO_FIX)
        }
        _state.value = LocationShareState.Sending
        val request = LocationReportRequest(
            lat = location.latitude,
            lng = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            altitude = if (location.hasAltitude()) location.altitude else null,
            provider = location.provider?.take(16),
            capturedAt = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        return try {
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(serverUrl).reportLocation(request) }
                ?: return LocationShareState.Failed(LocationFailure.NETWORK)
            when {
                response.code() == 404 -> {
                    _server.value = LocationServerStatus.Unsupported
                    LocationShareState.Failed(LocationFailure.UNSUPPORTED)
                }
                response.code() == 403 -> {
                    val notAllowed = response.errorBody()?.use { it.string() }?.contains(NOT_ALLOWED_ERROR) == true
                    if (notAllowed) _server.value = LocationServerStatus.NotAllowed
                    LocationShareState.Failed(if (notAllowed) LocationFailure.NOT_ALLOWED else LocationFailure.NETWORK)
                }
                !response.isSuccessful || response.body()?.ok != true -> {
                    response.errorBody()?.close()
                    Timber.w("Location report failed: HTTP %d", response.code())
                    LocationShareState.Failed(LocationFailure.NETWORK)
                }
                else -> {
                    val sent = SentLocation(response.body()?.at ?: System.currentTimeMillis(), request.accuracy)
                    settingsRepository.setLocationLastSent(encode(sent))
                    (_server.value as? LocationServerStatus.Allowed)?.let { _server.value = it.copy(last = sent) }
                    LocationShareState.Sent(sent)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Location report failed (%s)", e.javaClass.simpleName)
            LocationShareState.Failed(LocationFailure.NETWORK)
        }
    }

    /** Deletes every position the server keeps for this device. true = done. */
    suspend fun deleteOnServer(): Boolean {
        val serverUrl = setupRepository.getServerUrl()
        if (serverUrl.isBlank()) return false
        return try {
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(serverUrl).deleteLocations() }
            val ok = response != null && response.isSuccessful && response.body()?.ok == true
            response?.errorBody()?.close()
            if (ok) {
                settingsRepository.setLocationLastSent(null)
                (_server.value as? LocationServerStatus.Allowed)?.let { _server.value = it.copy(last = null) }
            }
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Location delete failed (%s)", e.javaClass.simpleName)
            false
        }
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        const val ACTIVE_AFTER_SEND_MS = 60_000L
        private const val FAILURE_SHOWN_MS = 8_000L
        const val DEFAULT_KEEP = 20
        const val DEFAULT_RETENTION_DAYS = 30
        private const val NOT_ALLOWED_ERROR = "location_not_allowed"

        private val gson = Gson()

        internal fun encode(s: SentLocation): String = gson.toJson(s)

        internal fun decode(raw: String?): SentLocation? {
            if (raw.isNullOrBlank()) return null
            val s = runCatching { gson.fromJson(raw, SentLocation::class.java) }.getOrNull() ?: return null
            return s.takeIf { it.at > 0 }
        }
    }
}
