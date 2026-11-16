package com.gatecontrol.android.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.gatecontrol.android.BuildConfig
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A request allowed by the person, waiting for Android's question (MediaProjection) and the relay. [control]: the person
 * chose „Ansehen und steuern“ and the plugin granted it (stage 6) — the relay still decides the mode in force.
 */
data class ScreenPending(val requestId: String, val ticket: ScreenTicket, val viewerName: String, val control: Boolean = false)

/**
 * The sharing runs ([viewerJoined]: the helper's page is open and gets the picture). [control]: the helper may operate
 * the phone right now (stage 6: granted, the relay's mode in force, the accessibility service bound) — red bar „… steuert“.
 */
data class ScreenLive(val requestId: String, val viewerName: String, val since: Long, val viewerJoined: Boolean, val control: Boolean = false)

/** The last sharing ended: who, how long — „Sitzung beendet. Neuer Code bereit.“ */
data class ScreenEnded(val viewerName: String, val minutes: Int, val at: Long)

/** Everything the card „Bildschirm freigeben“ shows. */
data class ScreenShareUi(
    /** The plugin „Fernzugriff“ answers this app (the card is shown at all). */
    val available: Boolean = false,
    /** The switch „Bildschirm freigeben“. */
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val code: ScreenCode? = null,
    val shareMode: String = "code",
    val allowOpen: Boolean = true,
    /** A helper asks (the dialog). */
    val request: ScreenRequest? = null,
    /** Allowed: Android's question comes next. */
    val pending: ScreenPending? = null,
    val live: ScreenLive? = null,
    val ended: ScreenEnded? = null,
    /** A short error code (no_share_right, screen_unavailable, offline, …), shown as a hint. */
    val error: String? = null,
    /** Stage 6: this build can control at all (the GitHub APK; the Play build only shows the screen). */
    val controlSupported: Boolean = false,
    /** Stage 6: „GateControl-Fernsteuerung“ is switched on in Android's accessibility settings (and bound). */
    val controlReady: Boolean = false,
)

/**
 * „Bildschirm freigeben“ (Fernzugriff, stage 5 — only watching): the switch, the code, the helper's question, the
 * hand-over to Android's question and to [ScreenShareService], and a new code after every session. The state of
 * the card lives here (one per app); the service reports the running sharing back. Never logs a code, a ref or a
 * token; the switch is kept on the device, everything else comes from the plugin.
 */
@Singleton
class ScreenShareRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: RemoteApi,
    private val notifier: ScreenShareNotifier,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(ScreenShareUi(enabled = prefs.getBoolean(KEY_ON, false), controlSupported = ScreenControl.supported))
    val state: StateFlow<ScreenShareUi> = _state.asStateFlow()

    init {
        // stage 6: the accessibility service came or went (switched on/off in Android's settings)
        if (ScreenControl.supported) scope.launch { ScreenControl.connected.collect { refreshControl() } }
    }

    /** Stage 6: is „GateControl-Fernsteuerung“ on? (The card asks again when it comes back from Android's settings.) */
    fun refreshControl() {
        val ready = ScreenControl.supported && ScreenControl.enabledInSettings(context) && ScreenControl.connected.value
        _state.update { it.copy(controlReady = ready) }
    }

    /**
     * The plugin is (no longer) listed for this app: the card comes or goes. Never in a Play build without the
     * foreground service (BuildConfig.SCREEN_SHARE false until Play accepted its declaration).
     */
    fun setAvailable(available: Boolean) {
        _state.update { it.copy(available = available && BuildConfig.SCREEN_SHARE) }
    }

    private fun failed(r: RemoteResult<*>): String = when (r) {
        is RemoteResult.Failed -> r.code
        RemoteResult.Offline -> "offline"
        is RemoteResult.Ok -> "failed"
    }

    private fun deviceName(): String = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL)
        .joinToString(" ").trim().ifEmpty { "Android" }

    /** The switch. On: register, show a code, keep reporting; off: everything ends. */
    fun setEnabled(on: Boolean) {
        scope.launch {
            mutex.withLock {
                _state.update { it.copy(busy = true, error = null) }
                if (on) {
                    when (val r = api.register(deviceName(), BuildConfig.VERSION_NAME)) {
                        is RemoteResult.Ok -> {
                            prefs.edit { putBoolean(KEY_ON, true) }
                            _state.update { it.copy(enabled = true, shareMode = r.value.shareMode, allowOpen = r.value.allowOpen) }
                            ScreenShareHeartbeat.keepUp(context, true)
                            fetchCodeLocked()
                        }
                        else -> {
                            prefs.edit { putBoolean(KEY_ON, false) }
                            _state.update { it.copy(enabled = false, error = failed(r)) }
                        }
                    }
                } else {
                    stopService()
                    prefs.edit { putBoolean(KEY_ON, false) }
                    ScreenShareHeartbeat.keepUp(context, false)
                    notifier.cancelRequest()
                    _state.update { it.copy(enabled = false, code = null, request = null, pending = null, live = null, ended = null) }
                    api.unregister()
                }
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun fetchCodeLocked() {
        when (val r = api.newCode()) {
            is RemoteResult.Ok -> _state.update { it.copy(code = r.value, error = null) }
            else -> {
                val code = failed(r)
                // the phone is no longer registered (deleted by an administrator, another owner): start over
                if (code == "not_found") registerAgainLocked() else _state.update { it.copy(code = null, error = code) }
            }
        }
    }

    private suspend fun registerAgainLocked() {
        when (val r = api.register(deviceName(), BuildConfig.VERSION_NAME)) {
            is RemoteResult.Ok -> {
                _state.update { it.copy(shareMode = r.value.shareMode, allowOpen = r.value.allowOpen) }
                (api.newCode() as? RemoteResult.Ok)?.let { c -> _state.update { it.copy(code = c.value, error = null) } }
            }
            else -> _state.update { it.copy(code = null, error = failed(r)) }
        }
    }

    /** A new code (it ran out, it became void, a sharing ended). Not while a helper is asked or watching. */
    fun refreshCode() {
        val s = _state.value
        if (!s.enabled || s.live != null || s.pending != null) return
        scope.launch { mutex.withLock { fetchCodeLocked() } }
    }

    /** The card opened: a code that ran out meanwhile (or none yet) is fetched again. */
    fun refreshCodeIfStale() {
        val s = _state.value
        if (s.enabled && (s.code == null || s.code.expiresAt <= System.currentTimeMillis())) refreshCode()
    }

    /** Still here: the plugin counts the phone online; questions a lost signal did not bring come with it. */
    suspend fun heartbeat() {
        if (!prefs.getBoolean(KEY_ON, false)) return
        when (val r = api.heartbeat()) {
            is RemoteResult.Ok -> {
                _state.update { it.copy(shareMode = r.value.shareMode, allowOpen = r.value.allowOpen) }
                r.value.pending.firstOrNull()?.let { onRequest(it) }
            }
            is RemoteResult.Failed -> if (r.code == "not_found") mutex.withLock { registerAgainLocked() }
            RemoteResult.Offline -> Unit
        }
    }

    /** „Ohne Code für Berechtigte“. */
    fun setOpen(open: Boolean) {
        scope.launch {
            mutex.withLock {
                when (val r = api.setShareMode(open)) {
                    is RemoteResult.Ok -> _state.update { it.copy(shareMode = r.value, error = null) }
                    else -> _state.update { it.copy(error = failed(r)) }
                }
            }
        }
    }

    // ─── Signals of the plugin (push stream) ───

    fun onSignal(signal: ScreenSignal) {
        when (signal) {
            is ScreenSignal.Request -> onRequest(signal.request)
            is ScreenSignal.Cancel -> if (_state.value.request?.id == signal.requestId) {
                notifier.cancelRequest()
                _state.update { it.copy(request = null) }
                refreshCode()
            }
            ScreenSignal.CodeVoid -> refreshCode()
            is ScreenSignal.End -> {
                val s = _state.value
                if (s.live?.requestId == signal.requestId) stopService()
                if (s.pending?.requestId == signal.requestId) {
                    _state.update { it.copy(pending = null) }
                    refreshCode()
                }
            }
        }
    }

    private fun onRequest(req: ScreenRequest) {
        val s = _state.value
        if (!s.enabled || s.live != null || s.pending != null || s.request?.id == req.id) return
        if (req.expiresAt <= System.currentTimeMillis()) return
        _state.update { it.copy(request = req) }
        // in the background: a notification that opens the card (the dialog waits there)
        if (!foreground()) notifier.showRequest(req)
    }

    private fun foreground(): Boolean =
        runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(false)

    /** The question ran out without an answer (30 s). */
    fun requestExpired(id: String) {
        if (_state.value.request?.id != id) return
        notifier.cancelRequest()
        _state.update { it.copy(request = null) }
        refreshCode()
    }

    /**
     * The person's answer to the helper's question. Allowed → [ScreenPending]: the UI asks Android next. [control]: the
     * person left „Nur ansehen lassen“ off for a request of „Ansehen und steuern“ (stage 6) — only then, only in a build
     * that can control and only with the accessibility service on.
     */
    fun answer(allow: Boolean, control: Boolean = false) {
        val req = _state.value.request ?: return
        notifier.cancelRequest()
        val wantsControl = allow && control && req.control && ScreenControl.available
        scope.launch {
            mutex.withLock {
                _state.update { it.copy(request = null, busy = true) }
                when (val r = api.decide(req.id, allow, wantsControl)) {
                    is RemoteResult.Ok -> {
                        val t = r.value.ticket
                        if (allow && t != null) _state.update { it.copy(pending = ScreenPending(req.id, t, req.fromName, wantsControl && r.value.control), ended = null) }
                        else fetchCodeLocked()
                    }
                    else -> {
                        _state.update { it.copy(error = failed(r)) }
                        fetchCodeLocked()
                    }
                }
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Android's answer to the question of [ScreenPending] ([data] the consent of MediaProjection, null: declined).
     * Granted: the foreground service of the type mediaProjection takes over. Declined: the request ends, a new code.
     */
    fun consent(resultCode: Int, data: Intent?) {
        val p = _state.value.pending ?: return
        if (data == null) {
            _state.update { it.copy(pending = null) }
            scope.launch {
                api.endRequest(p.requestId, "consent_denied")
                mutex.withLock { fetchCodeLocked() }
            }
            return
        }
        // control is shown only once the relay says so (ScreenShareService → controlChanged)
        _state.update { it.copy(pending = null, live = ScreenLive(p.requestId, p.viewerName, System.currentTimeMillis(), false, false)) }
        ScreenShareService.start(context, p, resultCode, data)
    }

    // ─── Reports of ScreenShareService ───

    fun viewerJoined(name: String?) {
        _state.update { s -> s.live?.let { s.copy(live = it.copy(viewerJoined = true, viewerName = name ?: it.viewerName)) } ?: s }
    }

    /** Stage 6: the helper may operate the phone now (true) — or no longer (false: view only, for good). */
    fun controlChanged(active: Boolean) {
        _state.update { s -> s.live?.let { s.copy(live = it.copy(control = active)) } ?: s }
    }

    fun viewerLeft() {
        _state.update { s -> s.live?.let { s.copy(live = it.copy(viewerJoined = false)) } ?: s }
    }

    /** The sharing is over (whoever ended it): a new code at once. [failedBeforeStart]: it never ran — the plugin hears it. */
    fun sessionEnded(requestId: String, failedBeforeStart: Boolean) {
        val live = _state.value.live
        val ended = live?.takeIf { it.requestId == requestId }?.let {
            ScreenEnded(it.viewerName, ((System.currentTimeMillis() - it.since) / 60_000L).toInt(), System.currentTimeMillis())
        }
        _state.update { it.copy(live = null, ended = ended ?: it.ended) }
        scope.launch {
            if (failedBeforeStart) api.endRequest(requestId, "failed")
            mutex.withLock { if (prefs.getBoolean(KEY_ON, false)) fetchCodeLocked() }
        }
    }

    /** „Beenden“ in the card or the red bar. */
    fun stop() {
        stopService()
    }

    /** Stage 6: „Nur ansehen“ in the card or the red bar — the helper can no longer operate the phone, watching goes on. */
    fun viewOnly() {
        controlChanged(false)
        if (!ScreenShareService.running) return
        runCatching { context.startService(Intent(context, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_VIEW_ONLY)) }
            .onFailure { Timber.d("Fernzugriff: view only not delivered (%s)", it.javaClass.simpleName) }
    }

    private fun stopService() {
        if (!ScreenShareService.running) return
        runCatching { context.startService(Intent(context, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_STOP)) }
            .onFailure { Timber.d("Fernzugriff: stop not delivered (%s)", it.javaClass.simpleName) }
    }

    fun clearEnded() {
        _state.update { it.copy(ended = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        private const val PREFS = "remote_screen"
        private const val KEY_ON = "on"

        /** The switch as stored (the heartbeat worker reads it without the repository). */
        fun isOn(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
    }
}
