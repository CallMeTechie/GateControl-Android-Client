package com.gatecontrol.android.tv.tunnel

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.gatecontrol.android.common.HostnameSanitizer
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.HeartbeatRequest
import com.gatecontrol.android.network.HeartbeatResponse
import com.gatecontrol.android.notify.PushClient
import com.gatecontrol.android.notify.ServerControlSink
import com.gatecontrol.android.notify.model.ServerControl
import com.gatecontrol.android.notify.model.ServerState
import com.gatecontrol.android.tunnel.HeartbeatScheduler
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tv.doorbell.TvDoorbell
import com.gatecontrol.android.tv.push.TvPushController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the TV does with the server besides the screens, like the phone app's `TunnelSupervisor` and
 * `ServerControlHandler`:
 *
 * - the push stream ([TvPushController], own foreground service) and the doorbell's overlay ([TvDoorbell]),
 * - the heartbeat while the tunnel is up (last seen, traffic, name of the TV): first one right after connecting, then
 *   every 60 s, or every 15 min while the push stream delivers the control events itself ([HeartbeatScheduler]),
 * - the server's word about this device, pushed or in the heartbeat's answer: peer switched off → tunnel down; a new
 *   WireGuard config → stored (and used from the next connect).
 *
 * The TV has no client policy and no support bundle yet: those fields change nothing here.
 */
@Singleton
class TvSupervisor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tunnelManager: TunnelManager,
    private val tunnel: TvTunnel,
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    // Lazy: the push client itself needs this sink (ServerControlSink)
    private val pushClient: dagger.Lazy<PushClient>,
    private val pushController: dagger.Lazy<TvPushController>,
    private val doorbell: dagger.Lazy<TvDoorbell>,
    private val chatHint: dagger.Lazy<com.gatecontrol.android.tv.chat.TvChatHint>,
) : ServerControlSink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val configLock = Mutex()
    private var started = false
    private var heartbeatJob: Job? = null

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        pushController.get().start(scope)
        doorbell.get().start(scope)
        // „Neue Nachricht von …“ over other apps (off by default)
        chatHint.get().start(scope)
        scope.launch {
            tunnelManager.state.collect { state ->
                when (state) {
                    is TunnelState.Connected -> if (heartbeatJob?.isActive != true) startHeartbeat(state.connectedSince)
                    TunnelState.Disconnected -> {
                        heartbeatJob?.cancel()
                        heartbeatJob = null
                    }
                    else -> Unit
                }
            }
        }
    }

    /** The app came to the foreground. */
    fun onForeground() {
        scope.launch { pushController.get().onForeground() }
    }

    // ─── Push ───────────────────────────────────────────────────────────

    override fun onServerState(state: ServerState) {
        if (state.peerEnabled == false) {
            launchSafely { peerDisabled("push state") }
            return
        }
        state.configHash?.let { hash ->
            if (hash != setupRepository.getConfigHash()) launchSafely { syncConfig("push state") }
        }
    }

    override fun onControl(control: ServerControl) {
        when (control) {
            is ServerControl.PeerState -> if (!control.enabled) launchSafely { peerDisabled("push") }
            ServerControl.ConfigChanged -> launchSafely { syncConfig("push") }
            else -> Unit
        }
    }

    // ─── Heartbeat ──────────────────────────────────────────────────────

    private fun startHeartbeat(connectedSince: Long) {
        heartbeatJob = scope.launch {
            HeartbeatScheduler(pushClient.get().controlLive).run { sendHeartbeat(connectedSince) }
        }
    }

    /** One heartbeat; true when the server answered. */
    private suspend fun sendHeartbeat(connectedSince: Long): Boolean {
        if (tunnelManager.state.value !is TunnelState.Connected) return false
        val peerId = setupRepository.getPeerId()
        val serverUrl = setupRepository.getServerUrl()
        if (peerId <= 0 || serverUrl.isEmpty()) return false
        return try {
            val stats = tunnelManager.stats.value
            val response = apiClientProvider.getClient(serverUrl).sendHeartbeat(
                HeartbeatRequest(
                    peerId = peerId,
                    connected = true,
                    rxBytes = stats.rxBytes,
                    txBytes = stats.txBytes,
                    uptime = (System.currentTimeMillis() - connectedSince) / 1000,
                    hostname = hostname(),
                ),
            )
            onHeartbeat(response)
            response.ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("TV heartbeat failed (%s)", e.javaClass.simpleName)
            false
        }
    }

    private suspend fun onHeartbeat(response: HeartbeatResponse) {
        if (response.ok && response.peerEnabled == false) peerDisabled("heartbeat")
    }

    /**
     * The name of the TV for the server's device list: the name given in the Fire TV settings ("Mein Fire TV ›
     * Info › Name", `device_name`), else the model.
     */
    private fun hostname(): String {
        val named = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        return HostnameSanitizer.sanitize(named)?.takeIf { it.isNotBlank() }
            ?: HostnameSanitizer.sanitize(Build.MODEL).orEmpty()
    }

    // ─── Shared paths ───────────────────────────────────────────────────

    private suspend fun peerDisabled(source: String) {
        // A token without a bound peer always reports peer_enabled:false — nothing of ours is off.
        if (setupRepository.getPeerId() <= 0) return
        if (tunnelManager.state.value is TunnelState.Disconnected) return
        Timber.w("TV: peer disabled on the server (%s), tunnel down", source)
        tunnel.disconnect()
    }

    private suspend fun syncConfig(source: String) = configLock.withLock {
        if (tunnel.syncConfig()) Timber.i("TV: WireGuard config updated (%s)", source)
    }

    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("TV: server control task failed (%s)", e.javaClass.simpleName)
            }
        }
    }
}
