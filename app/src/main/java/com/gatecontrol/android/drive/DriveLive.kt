package com.gatecontrol.android.drive

import android.content.Context
import android.os.SystemClock
import com.gatecontrol.android.drive.backup.DriveBackupWork
import com.gatecontrol.android.drive.backup.DriveFolderBackupWork
import com.gatecontrol.android.support.CrashReports
import com.google.gson.JsonElement
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Drive tells the app between calls: the last `GET me` (features, portal links, ransomware protection) and the
 * plugin's push events — `changed { areas, seq }` (an open folder and the Files app reload) and `guard { incident }`
 * (the protection's state is fetched again). [locked] says whether this device's writes are stopped: set by a
 * `423 device_locked` or by `me.guard.locked`, cleared only by a `GET me` that says so (not by the one a refusal
 * itself starts), so uploads and the photo backup wait instead of trying again and again.
 */
@Singleton
class DriveLive @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    private val _me = MutableStateFlow<DMe?>(null)
    /** The last answer of `GET me` (null until there was one). */
    val me: StateFlow<DMe?> = _me.asStateFlow()
    @Volatile private var meAt = 0L

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _changed = MutableSharedFlow<DChanged>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Something changed on the server (another device, the portal, an upload here). */
    val changed: SharedFlow<DChanged> = _changed.asSharedFlow()

    private val _vault = MutableSharedFlow<DVaultEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Drive 1.9 vaults: a device asks, a device was approved, a new key (event `vault`). */
    val vault: SharedFlow<DVaultEvent> = _vault.asSharedFlow()

    private val _ops = MutableSharedFlow<DOp>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Drive 1.12: a job's progress (event `op`, sent now and then while it runs, and at its end). */
    val ops: SharedFlow<DOp> = _ops.asSharedFlow()

    @Volatile private var lastSeq = 0L
    private var lockJob: Job? = null

    init {
        scope.launch {
            api.locked.collect {
                setLocked(true)
                // Learn about the incident for the banner — at most every half minute, and without lifting the stop.
                if (lockJob?.isActive != true && SystemClock.elapsedRealtime() - meAt > LOCK_REFRESH_MS) {
                    lockJob = scope.launch { refresh(mayUnlock = false) }
                }
            }
        }
    }

    /** `GET me` now; its answer updates [me] and [locked]. */
    suspend fun refresh(): DResult<DMe> = refresh(mayUnlock = true)

    private suspend fun refresh(mayUnlock: Boolean): DResult<DMe> {
        val r = api.me()
        if (r is DResult.Ok) {
            _me.value = r.value
            meAt = SystemClock.elapsedRealtime()
            val locked = DriveLogic.locked(r.value)
            if (locked || mayUnlock) setLocked(locked)
        }
        return r
    }

    /** The answer of `GET me`, at most [maxAgeMs] old (fetched when older); null when Drive cannot be reached. */
    suspend fun current(maxAgeMs: Long = FRESH_MS): DMe? {
        val known = _me.value
        if (known != null && SystemClock.elapsedRealtime() - meAt < maxAgeMs) return known
        return refresh().valueOrNull() ?: known
    }

    /** Whether the server offers [feature] (`emptyFiles`, `links`, `guard` …). */
    suspend fun supports(feature: String): Boolean = current()?.has(feature) == true

    private fun setLocked(value: Boolean) {
        val was = _locked.value
        _locked.value = value
        if (was && !value) {
            Timber.i("Drive: this device may write again")
            // The photo backup and the folder backup stopped at the lock: they go on now.
            runCatching { DriveBackupWork.runNow(context) }
            runCatching { DriveFolderBackupWork.runNow(context) }
        }
    }

    /** A write was refused with `device_locked` (the refusal's own signal may come a moment later). */
    fun stopped() = setLocked(true)

    /** Undo or release done in the app: what Drive says now. */
    fun guardChanged() {
        scope.launch { refresh() }
    }

    // ─── push events ────────────────────────────────────────────────

    /** `event: plugin` of Drive (from the push stream; returns at once). */
    fun onPluginEvent(type: String, data: JsonElement?) {
        when (type) {
            "changed" -> {
                val e = DriveJson.changedEvent(data)
                val seq = e.seq
                // The same event twice (two connections, a replay): once is enough.
                if (seq != null) {
                    if (seq == lastSeq) return
                    lastSeq = seq
                }
                _changed.tryEmit(e)
            }
            "guard" -> scope.launch { refresh() }
            "vault" -> DriveJson.vaultEvent(data)?.let { _vault.tryEmit(it) }
            "op" -> DriveJson.opEvent(data)?.let { _ops.tryEmit(it) }
        }
    }

    /** The push stream (re)connected: events of the time offline are missing, so whatever is open loads again. */
    fun onStreamOpen() {
        if (_me.value == null) return
        _changed.tryEmit(DChanged(emptySet(), null))
        if (_me.value?.has(DMe.FEATURE_GUARD) == true) scope.launch { refresh() }
    }

    private companion object {
        const val FRESH_MS = 5 * 60_000L
        const val LOCK_REFRESH_MS = 30_000L
    }
}
