package com.gatecontrol.android.familie

import com.gatecontrol.android.familie.push.FamiliePushEvent
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** State of the caller's SOS on a child's phone (Kinder-Handy). */
data class SosState(
    val sosId: Int?,
    /** Epoch ms when it was sent. */
    val sentAt: Long,
    /** Text of the parent's acknowledgement push, once it arrived. */
    val ack: String? = null,
)

/**
 * The caller's membership (`GET me`: role, modules, members), kept on the
 * device for offline start and for background work (push, geofences), plus
 * the events the push stream hands to open screens.
 */
@Singleton
class FamilieRepository @Inject constructor(
    private val api: FamilieApi,
    private val storage: FamilieStorage,
) {
    private data class StoredProfile(val owner: String? = null, val profile: FamilieProfile? = null)

    private val mutex = Mutex()
    private val _profile = MutableStateFlow<FamilieProfile?>(null)
    val profile: StateFlow<FamilieProfile?> = _profile.asStateFlow()

    /** Last error of `GET me` (code), null after a success. */
    private val _profileError = MutableStateFlow<String?>(null)
    val profileError: StateFlow<String?> = _profileError.asStateFlow()

    private val _events = MutableSharedFlow<FamiliePushEvent>(extraBufferCapacity = 16)
    /** Familie push messages as they arrive, for screens that want to refresh. */
    val events: SharedFlow<FamiliePushEvent> = _events.asSharedFlow()

    private val _sos = MutableStateFlow<SosState?>(null)
    val sos: StateFlow<SosState?> = _sos.asStateFlow()

    @Volatile private var loaded = false

    /** The stored profile (no network); null when there is none for this login. */
    suspend fun cachedProfile(): FamilieProfile? = mutex.withLock {
        if (!loaded) {
            val stored = storage.read<StoredProfile>(FILE, object : TypeToken<StoredProfile>() {}.type)
            _profile.value = stored?.takeIf { it.owner == storage.ownerKey() }?.profile?.takeIf { validStored(it) }
            loaded = true
        }
        _profile.value
    }

    /** Gson ignores Kotlin nullability: a stored profile from an older format may lack fields. */
    @Suppress("SENSELESS_COMPARISON")
    private fun validStored(p: FamilieProfile): Boolean =
        p.me != null && p.me.role != null && p.me.name != null && p.modules != null && p.members != null &&
            p.members.all { it != null && it.role != null && it.name != null }

    /** `GET me`; the stored copy on failure (offline). */
    suspend fun refreshProfile(): FamilieResult<FamilieProfile> {
        cachedProfile()
        val result = api.me()
        when (result) {
            is FamilieResult.Ok -> {
                mutex.withLock {
                    _profile.value = result.value
                    _profileError.value = null
                    storage.write(FILE, StoredProfile(storage.ownerKey(), result.value))
                }
            }
            is FamilieResult.Failed -> {
                _profileError.value = result.code
                // Not (or no longer) a member, or the plugin is gone: nothing to keep.
                if (result.code == "not_member" || result.http == 404) {
                    mutex.withLock {
                        _profile.value = null
                        storage.delete(FILE)
                    }
                }
            }
            else -> _profileError.value = FamilieApi.CODE_OFFLINE
        }
        return result
    }

    /** The profile, from the server when possible, else the stored one. */
    suspend fun profileOrCached(): FamilieProfile? =
        refreshProfile().valueOrNull() ?: _profile.value

    fun emit(event: FamiliePushEvent) {
        _events.tryEmit(event)
    }

    fun sosSent(sosId: Int?, at: Long) {
        _sos.value = SosState(sosId, at)
    }

    fun sosAcknowledged(text: String) {
        val current = _sos.value ?: SosState(null, System.currentTimeMillis())
        _sos.value = current.copy(ack = text)
    }

    fun clearSos() {
        _sos.value = null
    }

    companion object {
        private const val FILE = "profile.json"
    }
}
