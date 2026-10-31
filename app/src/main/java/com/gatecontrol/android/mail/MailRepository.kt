package com.gatecontrol.android.mail

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.gatecontrol.android.MailSigninActivity
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.support.CrashReports
import com.google.gson.JsonElement
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** "Gesendet · Rückgängig" after `send`: until [untilElapsed] (elapsed realtime) the send can be stopped. */
data class MUndo(val outboxId: Long, val draftId: Long?, val untilElapsed: Long, val sendAt: Long?, val planned: Boolean)

/**
 * What the Mail screens share: the last `me` (accounts, features, unread) and the folders, the plugin's realtime
 * events (`mail { kind: 'changed' }` → the lists load again) and the send waiting to be undone.
 */
@Singleton
class MailRepository @Inject constructor(
    val api: MailApi,
    val cache: MailCache,
    /** Calendars and contacts (since Mail 1.2.0): their live events and their copy go with Mail's. */
    val pim: MailPimRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    private val _me = MutableStateFlow<MMe?>(null)
    val me: StateFlow<MMe?> = _me.asStateFlow()

    private val _folders = MutableStateFlow<Map<Long, List<MFolder>>>(emptyMap())
    val folders: StateFlow<Map<Long, List<MFolder>>> = _folders.asStateFlow()

    /** `me` could not be had: its code (offline, not_allowed, …); null once it came. */
    private val _meError = MutableStateFlow<String?>(null)
    val meError: StateFlow<String?> = _meError.asStateFlow()

    private val _changed = MutableSharedFlow<MChanged>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changed: SharedFlow<MChanged> = _changed.asSharedFlow()

    private val _undo = MutableStateFlow<MUndo?>(null)
    val undo: StateFlow<MUndo?> = _undo.asStateFlow()

    /** The menu's folded accounts (account id → folders shown), kept while the app runs. */
    private val _folds = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val folds: StateFlow<Map<Long, Boolean>> = _folds.asStateFlow()

    /** A tap on an account in the menu: its folders open or close ([MailLogic.toggleFold]). */
    fun toggleFold(accountId: Long, box: MailLogic.Box) {
        _folds.update { MailLogic.toggleFold(it, accountId, box) }
    }

    @Volatile private var meAt = 0L
    private var refreshJob: Job? = null

    init {
        scope.launch {
            if (_me.value == null) cache.me()?.let { if (_me.value == null) _me.value = it }
            if (_folders.value.isEmpty()) cache.folders()?.let { if (_folders.value.isEmpty()) _folders.value = it }
        }
    }

    /** `GET me` (and the folders) now. */
    suspend fun refresh(): MResult<MMe> {
        val r = api.me()
        when (r) {
            is MResult.Ok -> {
                _me.value = r.value.first
                _meError.value = null
                meAt = SystemClock.elapsedRealtime()
                cache.putMe(r.value.second)
                api.folders().valueOrNull()?.let { (f, raw) ->
                    _folders.value = f
                    cache.putFolders(raw)
                }
            }
            is MResult.Failed -> _meError.value = r.code
            MResult.Offline -> _meError.value = MailApi.CODE_OFFLINE
            MResult.NotConfigured -> _meError.value = "not_configured"
        }
        return when (r) {
            is MResult.Ok -> MResult.Ok(r.value.first)
            else -> r.notOk()
        }
    }

    /** On showing the tab: `me` again unless it came a moment ago. */
    fun refreshSoon(maxAgeMs: Long = 20_000L) {
        if (_me.value != null && SystemClock.elapsedRealtime() - meAt < maxAgeMs) return
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch { refresh() }
    }

    fun setMe(me: MMe) {
        _me.value = me
    }

    /** `event: plugin` of Mail (`mail { kind: 'changed', accountId, folderId }`): the lists and the counts load again. */
    fun onPluginEvent(type: String, data: JsonElement?) {
        // `calendar` and `contacts` (since Mail 1.2.0) belong to the calendar and the contacts
        if (pim.onPluginEvent(type, data)) return
        val changed = MailJson.changed(data) ?: if (type == "mail" || type == "changed") MChanged(null, null) else return
        _changed.tryEmit(changed)
        if (refreshJob?.isActive != true) {
            refreshJob = scope.launch {
                delay(500)
                refresh()
            }
        }
    }

    /** (Re)connected: what changed meanwhile. */
    fun onStreamOpen() {
        pim.onStreamOpen()
        if (_me.value == null) return
        _changed.tryEmit(MChanged(null, null))
    }

    // ─── undo after sending ─────────────────────────────────────────

    fun startUndo(send: MSend, draftId: Long?, callMs: Long, now: Long = System.currentTimeMillis()) {
        val planned = MailLogic.planned(send.sendAt, now)
        val window = if (planned) PLANNED_UNDO_MS else MailLogic.undoWindow(send.undoUntil, now, callMs)
        _undo.value = MUndo(send.outboxId, draftId, SystemClock.elapsedRealtime() + window, send.sendAt, planned)
    }

    fun clearUndo(outboxId: Long? = null) {
        if (outboxId == null || _undo.value?.outboxId == outboxId) _undo.value = null
    }

    /** "Rückgängig": the send stops, the draft it came from (to open again). */
    suspend fun undo(u: MUndo): MResult<Long?> {
        val r = api.cancel(u.outboxId)
        clearUndo(u.outboxId)
        return r
    }

    /** Everything this device keeps of Mail goes (another login, the plugin gone, the token revoked). */
    suspend fun forget() {
        _me.value = null
        _folders.value = emptyMap()
        _undo.value = null
        pim.forget()
        cache.wipe()
    }

    private companion object {
        /** A planned send: its bar stays a little longer (it can be stopped any time before). */
        const val PLANNED_UNDO_MS = 8_000L
    }
}

/**
 * Mail's duties outside its screens, started with the app: the Microsoft redirect (`msauth://…`) is only handled
 * while the server offers Mail, and the offline copy goes when Mail is gone, the login changes or the token is
 * revoked (contract D "beim Fernlöschen mit löschen"). Without the plugin nothing else happens.
 */
@Singleton
class MailStartup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pluginDirectory: PluginDirectory,
    private val setupRepository: SetupRepository,
    private val cache: MailCache,
    private val api: MailApi,
    private val repository: dagger.Lazy<MailRepository>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private var started = false

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            pluginDirectory.state.map { it.mail }.distinctUntilChanged().collect { on ->
                setSigninEnabled(context, on)
                if (!on && cache.hasData()) repository.get().forget()
            }
        }
        scope.launch {
            setupRepository.revision.drop(1).collect { if (cache.hasData()) repository.get().forget() }
        }
        scope.launch {
            api.unauthorized.collect { if (cache.hasData()) repository.get().forget() }
        }
    }

    private fun setSigninEnabled(context: Context, enabled: Boolean) {
        runCatching {
            val component = ComponentName(context, MailSigninActivity::class.java)
            val want = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (context.packageManager.getComponentEnabledSetting(component) != want) {
                context.packageManager.setComponentEnabledSetting(component, want, PackageManager.DONT_KILL_APP)
            }
        }.onFailure { Timber.w("Mail: sign-in redirect not switched (%s)", it.javaClass.simpleName) }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MailEntryPoint {
    fun mailStartup(): MailStartup
    fun mailAvatars(): MailAvatars
    fun mailPim(): MailPimRepository
}
