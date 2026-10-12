package com.gatecontrol.android.service

import com.gatecontrol.android.support.CrashReports
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.HeartbeatResponse
import com.gatecontrol.android.notify.ServerControlSink
import com.gatecontrol.android.notify.model.ServerControl
import com.gatecontrol.android.notify.model.ServerState
import com.gatecontrol.android.support.SupportRequestHolder
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "The server switched this peer off" for the VPN screen's notice. Set when
 * the tunnel was closed for that reason (heartbeat, push or the screen's
 * own check), cleared when a tunnel is up again.
 */
object PeerStateHolder {
    private val _disabled = MutableStateFlow(false)
    val disabled: StateFlow<Boolean> = _disabled.asStateFlow()

    fun markDisabled() { _disabled.value = true }

    fun clear() { _disabled.value = false }
}

/**
 * Acts on what the server says about this device — pushed (`control`
 * events, `hello.state` on every reconnect) or polled (heartbeat answer).
 * All sources run the same code paths:
 *
 * - policy changed → [ClientPolicyManager] refresh,
 * - support bundle requested → [SupportRequestHolder] (Settings asks the user),
 * - peer disabled → tunnel closed, DNS cache cleared, notice,
 * - config changed → [TunnelConnector.syncConfigFromServer].
 *
 * Fields a server does not send (older servers) change nothing.
 */
@Singleton
class ServerControlHandler @Inject constructor(
    private val clientPolicyManager: ClientPolicyManager,
    private val tunnelManager: TunnelManager,
    private val tunnelConnector: TunnelConnector,
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) : ServerControlSink {

    /** Replaceable in tests. */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashReports.coroutineHandler)

    private val configLock = Mutex()

    /** Last policy version the push stream named (opaque; the same value as heartbeat and ETag). */
    @Volatile private var lastPolicySignal: String? = null

    // ─── Push ───────────────────────────────────────────────────────────

    override fun onServerState(state: ServerState) {
        state.policyVersion?.let { version ->
            if (version != lastPolicySignal) {
                lastPolicySignal = version
                clientPolicyManager.refreshUnlessCurrentAsync(version)
            }
        }
        SupportRequestHolder.update(state.supportBundleRequested, state.supportBundleRequestedAt)
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
            is ServerControl.PolicyChanged -> {
                control.version?.let { lastPolicySignal = it }
                // Same opaque version as heartbeat/ETag: no fetch when the heartbeat got it already.
                clientPolicyManager.refreshUnlessCurrentAsync(control.version)
            }
            is ServerControl.SupportBundleRequested -> SupportRequestHolder.update(true, control.requestedAt)
            is ServerControl.PeerState -> if (!control.enabled) launchSafely { peerDisabled("push") }
            ServerControl.ConfigChanged -> launchSafely { syncConfig("push") }
        }
    }

    // ─── Heartbeat ──────────────────────────────────────────────────────

    /** Answer of `POST /client/heartbeat` (fallback while no push control is live). */
    suspend fun onHeartbeat(response: HeartbeatResponse) {
        clientPolicyManager.noteVersionAsync(response.policyVersion)
        if (!response.ok) return
        // Admin asked for a support bundle: Settings shows it,
        // nothing is sent without the user's confirmation.
        SupportRequestHolder.update(response.supportBundleRequested, response.supportBundleRequestedAt)
        if (response.peerEnabled == false) peerDisabled("heartbeat")
    }

    // ─── Shared paths ───────────────────────────────────────────────────

    /** The admin switched the peer off: close the tunnel (also without the app open). */
    suspend fun peerDisabled(source: String) {
        // A token without a bound peer always reports peer_enabled:false — nothing of ours is off.
        if (setupRepository.getPeerId() <= 0) return
        if (tunnelManager.state.value is TunnelState.Disconnected) return
        Timber.w("Peer disabled on server (%s) — disconnecting tunnel", source)
        tunnelManager.disconnect()
        apiClientProvider.clearDnsCache()
        PeerStateHolder.markDisabled()
    }

    private suspend fun syncConfig(source: String) = configLock.withLock {
        if (tunnelConnector.syncConfigFromServer()) Timber.i("WireGuard config updated (%s)", source)
    }

    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (e: Exception) { Timber.w(e, "Server control task failed") }
        }
    }
}
