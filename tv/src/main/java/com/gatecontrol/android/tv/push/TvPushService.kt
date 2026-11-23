package com.gatecontrol.android.tv.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.gatecontrol.android.notify.PushClient
import com.gatecontrol.android.notify.PushStatus
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tv.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * The push stream of GateControl TV in a foreground service, like the phone app's mode "Immer, auch ohne VPN": the
 * SSE connection of core/notify to the own server without Google or Amazon services. Fire OS ends background apps
 * quickly on 2 GB of RAM; a foreground service stays. The stream does not depend on the tunnel: core/network resolves
 * the server outside the VPN (VpnSafeDns), as on the phone.
 */
@AndroidEntryPoint
class TvPushService : Service() {

    @Inject lateinit var client: PushClient
    @Inject lateinit var presenter: TvPushPresenter
    @Inject lateinit var tunnelManager: TunnelManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updates: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        presenter.ensureFixedChannels()
        val text = ongoingText(tunnelManager.state.value is TunnelState.Connected, client.status.value)
        try {
            // Android 9 (Fire OS 7) needs no type; from Android 14 the manifest's specialUse applies.
            startForeground(TvPushPresenter.ONGOING_ID, presenter.ongoing(text))
        } catch (e: Exception) {
            Timber.w("TV push: cannot run in the foreground (%s)", e.javaClass.simpleName)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        client.start(scope, OWNER)
        if (updates?.isActive != true) {
            updates = scope.launch {
                combine(client.status, tunnelManager.state.map { it is TunnelState.Connected }.distinctUntilChanged()) { status, vpn ->
                    ongoingText(vpn, status)
                }.distinctUntilChanged().collect { presenter.updateOngoing(it) }
            }
        }
        return START_STICKY
    }

    private fun ongoingText(vpn: Boolean, status: PushStatus): String {
        val v = getString(if (vpn) R.string.tv_ongoing_vpn_on else R.string.tv_ongoing_vpn_off)
        val p = getString(
            when (status) {
                is PushStatus.Connected -> R.string.tv_ongoing_ready
                is PushStatus.Blocked -> R.string.tv_ongoing_blocked
                is PushStatus.Waiting -> R.string.tv_ongoing_waiting
                else -> R.string.tv_ongoing_connecting
            },
        )
        return "$v · $p"
    }

    override fun onDestroy() {
        running = false
        client.stop(OWNER)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val OWNER = "service"

        @Volatile
        var running = false
            private set

        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, TvPushService::class.java))
            true
        } catch (e: Exception) {
            Timber.w("TV push: start refused (%s)", e.javaClass.simpleName)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TvPushService::class.java))
        }
    }
}
