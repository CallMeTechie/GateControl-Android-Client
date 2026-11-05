package com.gatecontrol.android.drive

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Offline verfügbar" (Drive 1.20, feature `offline`): the files and folders the person marked. Drive offers them to
 * the home gateways' caches first (GateControl's "Zwischenspeicher im Heimnetz"); at home the app then loads them from
 * the gateway. This app keeps no copies of its own beyond the opened files' cache: the mark means "kept ready at home",
 * not "on this phone". [ids] marks the rows (a folder's content is marked through the folder only).
 */
@Singleton
class DriveOffline @Inject constructor(private val api: DriveApi) {

    private val _ids = MutableStateFlow<Set<Long>>(emptySet())
    /** The marked nodes as far as known (after [list] or [refresh]). */
    val ids: StateFlow<Set<Long>> = _ids.asStateFlow()

    @Volatile
    private var loadedAt = 0L

    /** The marked nodes, newest first; [ids] follows. */
    suspend fun list(): DResult<List<DNode>> = api.offline().also { r ->
        if (r is DResult.Ok) {
            _ids.value = r.value.mapTo(HashSet()) { it.id }
            loadedAt = SystemClock.elapsedRealtime()
        }
    }

    /** Loads the marks when they are older than a minute (a folder shown again). */
    suspend fun refresh(me: DMe?) {
        if (me?.has(DMe.FEATURE_OFFLINE) != true) return
        if (loadedAt != 0L && SystemClock.elapsedRealtime() - loadedAt < STALE_MS) return
        list()
    }

    /** Marks or unmarks [node] → null on success, else the code (`offline_max` at the limit of 500). */
    suspend fun set(node: DNode, on: Boolean): String? {
        val r: DResult<*> = if (on) api.markOffline(node.id) else api.unmarkOffline(node.id)
        if (r is DResult.Ok) {
            _ids.update { if (on) it + node.id else it - node.id }
            return null
        }
        return when (r) {
            is DResult.Failed -> if (DriveOfflineLogic.atLimit(r)) "offline_max" else r.code
            DResult.Offline -> "offline"
            DResult.NotConfigured -> "not_configured"
            is DResult.Ok -> null
        }
    }

    private companion object {
        const val STALE_MS = 60_000L
    }
}

/** Pure parts of "Offline verfügbar", unit-tested. */
object DriveOfflineLogic {
    /** Drive's refusal at the limit: `400 invalid { field: 'offline', max: 500 }`. */
    fun atLimit(f: DResult.Failed): Boolean =
        f.code == "invalid" && f.body?.get("field")?.takeIf { it.isJsonPrimitive }?.asString == "offline"
}
