package com.gatecontrol.android.familie.emergency

import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieJson
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.FamilieStorage
import com.gatecontrol.android.familie.errorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the emergency screen shows. */
data class EmergencyState(
    val card: EmergencyCard? = null,
    /** When [card] came from the server (epoch ms). */
    val savedAtMs: Long? = null,
    /** The last refresh did not reach the server: [card] is the offline copy ("Stand: …"). */
    val stale: Boolean = false,
    /** An encrypted copy is on the phone ("offline gespeichert"). */
    val offlineCopy: Boolean = false,
    val loading: Boolean = false,
    /** Error code of the last refresh. */
    val error: String? = null,
)

/**
 * The emergency card: from the server when reachable, else from the
 * encrypted copy of the last answer ([EmergencyCacheStore]). Every
 * successful `GET emergency` replaces the copy; leaving the family or the
 * module being switched off removes it.
 */
@Singleton
class EmergencyRepository @Inject constructor(
    private val api: FamilieApi,
    private val cache: EmergencyCacheStore,
    private val storage: FamilieStorage,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(EmergencyState())
    val state: StateFlow<EmergencyState> = _state.asStateFlow()

    /** Login the state in memory belongs to. */
    @Volatile private var owner: String? = null

    private suspend fun ensureOwner() {
        val key = storage.ownerKey()
        if (owner != key) {
            owner = key
            _state.value = EmergencyState()
        }
    }

    /** The offline copy, when nothing is shown yet. */
    suspend fun loadCached() = mutex.withLock {
        ensureOwner()
        if (_state.value.card != null) return@withLock
        val entry = withContext(Dispatchers.IO) { cache.read() } ?: return@withLock
        _state.update { it.copy(card = FamilieJson.emergency(entry.data), savedAtMs = entry.savedAtMs, stale = true, offlineCopy = true) }
    }

    suspend fun refresh(): FamilieResult<EmergencyCard> {
        loadCached()
        _state.update { it.copy(loading = true) }
        val r = api.emergency()
        mutex.withLock {
            when (r) {
                is FamilieResult.Ok -> {
                    val now = System.currentTimeMillis()
                    val saved = withContext(Dispatchers.IO) { cache.write(r.value.raw, now) }
                    _state.value = EmergencyState(card = r.value.card, savedAtMs = now, stale = false, offlineCopy = saved)
                }
                is FamilieResult.Failed -> if (r.code == "not_member" || r.code == "module_off" || r.http == 404) {
                    withContext(Dispatchers.IO) { cache.clear() }
                    _state.value = EmergencyState(error = r.code)
                } else {
                    _state.update { it.copy(loading = false, stale = it.card != null, error = r.code) }
                }
                else -> _state.update { it.copy(loading = false, stale = it.card != null, error = r.errorCode()) }
            }
        }
        return when (r) {
            is FamilieResult.Ok -> FamilieResult.Ok(r.value.card)
            is FamilieResult.Failed -> r
            FamilieResult.Offline -> FamilieResult.Offline
            FamilieResult.NotConfigured -> FamilieResult.NotConfigured
        }
    }

    suspend fun saveMember(userId: Int, draft: EmergencyMemberDraft): FamilieResult<Unit> =
        api.putEmergencyMember(userId, draft).also { if (it is FamilieResult.Ok) refresh() }

    suspend fun saveContacts(contacts: List<EmergencyContact>): FamilieResult<Unit> =
        api.putEmergencyContacts(contacts).also { if (it is FamilieResult.Ok) refresh() }
}
