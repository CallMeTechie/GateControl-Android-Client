package com.gatecontrol.android.tv.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tv.TvActivity
import com.gatecontrol.android.tv.TvSettings
import com.gatecontrol.android.tv.tunnel.TvTunnel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/** What happens after the stick started. Pure, unit-tested. */
object BootPlan {
    data class Plan(val connectTunnel: Boolean, val openApp: Boolean)

    /**
     * The tunnel comes up when the TV is paired, "Nach dem Einschalten verbinden" is on (default), the VPN consent was
     * given once in the app and nothing brought it up already (always-on VPN). The app opens only when
     * "GateControl beim Start öffnen" is on (default off).
     */
    fun decide(paired: Boolean, autostartTunnel: Boolean, openOnBoot: Boolean, vpnConsent: Boolean, tunnelActive: Boolean): Plan =
        Plan(
            connectTunnel = paired && autostartTunnel && vpnConsent && !tunnelActive,
            openApp = openOnBoot,
        )
}

/**
 * BOOT_COMPLETED on the stick (Fire OS 7 delivers it to installed apps): tunnel and, if wanted, the app. Android 9
 * still lets a receiver start an activity; on Android TV 10+ the system may refuse that start (then only the tunnel
 * comes up).
 */
@AndroidEntryPoint
class TvBootReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: TvSettings
    @Inject lateinit var setupRepository: SetupRepository
    @Inject lateinit var tvTunnel: TvTunnel

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val plan = BootPlan.decide(
            paired = setupRepository.isConfigured() && setupRepository.hasWireGuardConfig(),
            autostartTunnel = settings.autostartTunnel.value,
            openOnBoot = settings.openOnBoot.value,
            vpnConsent = runCatching { VpnService.prepare(context) == null }.getOrDefault(false),
            tunnelActive = tvTunnel.state.value !is TunnelState.Disconnected,
        )
        Timber.i("TV boot: tunnel %s, open app %s", plan.connectTunnel, plan.openApp)
        if (plan.openApp) {
            runCatching {
                context.startActivity(Intent(context, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Timber.w("TV boot: opening the app was refused (%s)", it.javaClass.simpleName) }
        }
        if (!plan.connectTunnel) return
        val pending = goAsync()
        scope.launch {
            try {
                // within the broadcast's time budget (goAsync)
                val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { tvTunnel.connect() } == true
                Timber.i("TV boot: tunnel %s", if (ok) "up" else "not up")
            } catch (e: Exception) {
                Timber.w("TV boot: tunnel failed (%s)", e.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 50_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
