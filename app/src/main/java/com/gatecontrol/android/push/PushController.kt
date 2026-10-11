package com.gatecontrol.android.push

import android.content.Context
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.notify.PushClient
import com.gatecontrol.android.notify.PushMode
import com.gatecontrol.android.notify.PushPrefsStore
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.notify.PushStatus
import com.gatecontrol.android.notify.WaitReason
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides where the push client runs:
 *
 * * off or not set up → nowhere;
 * * mode "Immer, auch ohne VPN" → [PushService] (own foreground service);
 *   if Android refuses to start it from the background, the client runs in
 *   the app process until the service can start;
 * * mode "Nur bei aktivem VPN" → no own service: the client runs in the
 *   tunnel supervisor's scope while the tunnel is connected.
 *
 * Started from [com.gatecontrol.android.service.TunnelSupervisor.start]
 * (app start, boot, Always-on VPN) with the supervisor's scope. Reports the
 * device preferences to the server whenever they change.
 */
@Singleton
class PushController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: PushPrefsStore,
    private val client: PushClient,
    private val repository: PushRepository,
    private val setupRepository: SetupRepository,
    private val tunnelManager: TunnelManager,
    private val notifier: PushNotifier,
) {
    private data class Desired(val configured: Boolean, val enabled: Boolean, val mode: PushMode, val vpn: Boolean, val revision: Long)

    private var scope: CoroutineScope? = null
    private var lastRevision = -1L
    private val desired = MutableStateFlow<Desired?>(null)
    private val lastDesired: Desired? get() = desired.value

    fun start(scope: CoroutineScope) {
        synchronized(this) {
            if (this.scope != null) return
            this.scope = scope
        }
        scope.launch { runCatching { notifier.ensureFixedChannels() } }
        scope.launch {
            combine(
                store.settings,
                tunnelManager.state.map { it is TunnelState.Connected }.distinctUntilChanged(),
                setupRepository.revision,
            ) { s, vpn, rev ->
                Desired(setupRepository.isConfigured(), s.enabled, s.mode, vpn, rev)
            }.distinctUntilChanged().collect { apply(it) }
        }
        // Switch, mode or muted topics changed: tell the server (also when switched off).
        scope.launch {
            store.settings.map { Triple(it.enabled, it.mode, it.mutedTopics) }
                .distinctUntilChanged()
                .drop(1)
                .collect { repository.syncPrefs() }
        }
    }

    @Synchronized
    private fun apply(d: Desired) {
        val scope = scope ?: return
        desired.value = d
        val setupChanged = lastRevision >= 0 && d.revision != lastRevision
        lastRevision = d.revision
        when {
            !d.configured || !d.enabled -> {
                PushService.stop(context)
                client.stop()
            }
            d.mode == PushMode.ALWAYS -> {
                if (client.currentOwner() == OWNER_TUNNEL) client.stop(OWNER_TUNNEL)
                if (!PushService.running && !PushService.start(context)) {
                    // Not allowed from the background right now: keep the stream in-process.
                    client.start(scope, OWNER_FALLBACK)
                }
            }
            else -> {
                PushService.stop(context)
                if (d.vpn) {
                    client.start(scope, OWNER_TUNNEL)
                } else {
                    client.stop(idle = PushStatus.WaitingForVpn)
                }
            }
        }
        if (setupChanged && client.isRunning) client.kick("setup", force = true)
        Timber.d("Push: enabled=%s mode=%s vpn=%s running=%s", d.enabled, d.mode, d.vpn, client.currentOwner())
    }

    /**
     * Boot completed: make sure the service runs in mode "always" while the
     * broadcast still allows starting it (the settings may still be loading).
     */
    fun onBoot() {
        val scope = scope ?: return
        scope.launch {
            withTimeoutOrNull(BOOT_WAIT_MS) { desired.filterNotNull().first() }?.let { apply(it) }
        }
    }

    /**
     * App came to the foreground: report a changed battery-optimisation
     * state, start the service if it could not start from the background,
     * and retry early when waiting for a long time (not after a rate limit).
     */
    fun onForeground() {
        val scope = scope ?: return
        // Notifications may just have been allowed: show the current state in the ongoing card.
        notifier.repostOngoing()
        val d = lastDesired
        if (d != null && d.configured && d.enabled && d.mode == PushMode.ALWAYS && client.currentOwner() != PushService.OWNER) {
            apply(d)
        }
        scope.launch { repository.syncPrefs() }
        val status = client.status.value
        if (status is PushStatus.Waiting && status.reason != WaitReason.RATE_LIMITED &&
            status.retryAt - System.currentTimeMillis() > EARLY_RETRY_MS
        ) {
            client.kick("foreground")
        }
    }

    /** "Erneut versuchen" in the settings. */
    fun retry() {
        client.kick("user", force = true)
    }

    private companion object {
        const val OWNER_TUNNEL = "tunnel"
        const val OWNER_FALLBACK = "app"
        const val EARLY_RETRY_MS = 60_000L
        const val BOOT_WAIT_MS = 5_000L
    }
}
