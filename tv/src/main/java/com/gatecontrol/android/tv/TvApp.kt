package com.gatecontrol.android.tv

import android.app.Application
import android.content.pm.ApplicationInfo
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tv.tunnel.TvSupervisor
import com.gatecontrol.android.tv.tunnel.TvTunnel
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/** GateControl TV: Hilt's root (core modules + [com.gatecontrol.android.tv.di.TvModule]), the tunnel backend, push and heartbeat. */
@HiltAndroidApp
class TvApp : Application() {

    @Inject lateinit var tunnelManager: TunnelManager
    @Inject lateinit var tvTunnel: TvTunnel
    @Inject lateinit var supervisor: TvSupervisor

    override fun onCreate() {
        super.onCreate()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Timber.plant(Timber.DebugTree())
        tunnelManager.initialize()
        // Always-on VPN (on Fire OS only set by ADB): the system starts our VPN service, we bring the tunnel up.
        tunnelManager.setAlwaysOnHandler { tvTunnel.connectDetached() }
        // Push stream (own foreground service) and heartbeat; also after BOOT_COMPLETED, which starts this process.
        supervisor.start()
    }
}
