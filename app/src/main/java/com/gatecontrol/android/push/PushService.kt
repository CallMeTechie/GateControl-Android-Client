package com.gatecontrol.android.push

import com.gatecontrol.android.support.CrashReports
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.gatecontrol.android.notify.PushClient
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
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
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service of mode "Immer, auch ohne VPN": keeps the push stream
 * to the user's own GateControl server open without Google services
 * (type specialUse, see the subtype in the manifest). Shows the one ongoing,
 * silent notification ("Verbindung", importance min) with VPN and push state.
 *
 * Started and stopped by [PushController] only.
 */
@AndroidEntryPoint
class PushService : Service() {

    @Inject lateinit var client: PushClient
    @Inject lateinit var notifier: PushNotifier
    @Inject lateinit var tunnelManager: TunnelManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashReports.coroutineHandler)
    private var updates: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = runBlocking { notifier.ongoingText(tunnelManager.state.value is TunnelState.Connected, client.status.value) }
        val notification = runBlocking { notifier.ongoing(text) }
        try {
            // No explicit type: from Android 14 the manifest's specialUse applies, before that none is needed.
            startForeground(PushNotifier.ONGOING_ID, notification)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (started from the background) and friends.
            Timber.w("PushService: cannot run in the foreground (%s)", e.javaClass.simpleName)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        client.start(scope, OWNER)
        if (updates?.isActive != true) {
            updates = scope.launch {
                combine(
                    client.status,
                    tunnelManager.state.map { it is TunnelState.Connected }.distinctUntilChanged(),
                ) { status, vpn -> notifier.ongoingText(vpn, status) }
                    .distinctUntilChanged()
                    .collect { notifier.updateOngoing(notifier.ongoing(it)) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        client.stop(OWNER)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val OWNER = "service"

        /** The service runs in the foreground (set once startForeground succeeded). */
        @Volatile
        var running = false
            private set

        /** True when the start was accepted (Android may refuse it from the background). */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, PushService::class.java))
            true
        } catch (e: Exception) {
            Timber.w("PushService: start refused (%s)", e.javaClass.simpleName)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushService::class.java))
        }
    }
}
