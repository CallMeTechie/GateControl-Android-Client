package com.gatecontrol.android.familie.internet

import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.FamilieStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * „Internet zu Hause“ as the server answered last, shared by the card on
 * the Familie start screen and the page: a change on the page shows on the
 * card at once. Kept in memory only (per login). A server without the
 * function — an older plugin answers 404, a newer one `available: false` —
 * leaves [snapshot] without anything to show; that is not an error.
 */
@Singleton
class InternetRepository @Inject constructor(
    private val api: FamilieApi,
    private val storage: FamilieStorage,
) {
    private val _snapshot = MutableStateFlow<InternetSnapshot?>(null)
    val snapshot: StateFlow<InternetSnapshot?> = _snapshot.asStateFlow()

    @Volatile private var owner: String? = null

    /** `GET internet`. An answer replaces the snapshot; a refusal drops it, no answer (offline) keeps it. */
    suspend fun refresh(nowMs: Long = System.currentTimeMillis()): FamilieResult<InternetSnapshot> {
        val key = storage.ownerKey()
        if (key != owner) {
            owner = key
            _snapshot.value = null
        }
        return when (val r = api.internet()) {
            is FamilieResult.Ok -> InternetSnapshot(r.value, nowMs).let {
                _snapshot.value = it
                FamilieResult.Ok(it)
            }
            is FamilieResult.Failed -> {
                _snapshot.value = null
                if (missing(r)) FamilieResult.Ok(InternetSnapshot(UNAVAILABLE, nowMs)) else r
            }
            FamilieResult.Offline -> FamilieResult.Offline
            FamilieResult.NotConfigured -> FamilieResult.NotConfigured
        }
    }

    suspend fun pause(childId: Int, request: PauseRequest): FamilieResult<InternetChild> = changed(api.internetPause(childId, request))

    suspend fun resume(childId: Int): FamilieResult<InternetChild> = changed(api.internetResume(childId))

    suspend fun extend(childId: Int, minutes: Int, fromNow: Boolean = false): FamilieResult<InternetChild> =
        changed(api.internetExtend(childId, minutes, fromNow))

    /** The answer of a change is the child as it is now. */
    private fun changed(r: FamilieResult<InternetChild>): FamilieResult<InternetChild> {
        if (r is FamilieResult.Ok) _snapshot.value = _snapshot.value?.with(r.value)
        return r
    }

    companion object {
        private val UNAVAILABLE = InternetPayload(available = false, manage = false, children = emptyList())

        /** The server has no „Internet zu Hause“: a plugin before 1.18 (unknown path) or one that says so. */
        fun missing(r: FamilieResult.Failed): Boolean = r.http == 404 || r.code == "unavailable"
    }
}
