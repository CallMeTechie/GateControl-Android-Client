package com.gatecontrol.android

import android.app.Application
import android.content.Context
import android.util.Log
import com.gatecontrol.android.service.TunnelStateHolder
import com.gatecontrol.android.service.TunnelSupervisor
import com.gatecontrol.android.support.CrashReportUploader
import com.gatecontrol.android.support.CrashReports
import com.gatecontrol.android.trustednet.TrustedNetworkMonitor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

@HiltAndroidApp
class GateControlApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    override fun attachBaseContext(base: Context) {
        // Install crash handler as early as possible — before Hilt init in super.attachBaseContext
        installCrashLogger(base)
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        try {
            super.onCreate()
        } catch (e: Throwable) {
            writeCrashToFile("onCreate", e)
            throw e
        }

        try {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            }
            // Always plant a file-based tree so logs are available in release
            // builds (app-private cacheDir/logs); release keeps INFO and up.
            Timber.plant(
                FileLoggingTree(this, minPriority = if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO),
            )
        } catch (e: Throwable) {
            writeCrashToFile("timber_init", e)
            throw e
        }

        // Tunnel lifecycle outside the UI: Always-on VPN, auto-reconnect, tile state.
        // Must run before the system starts the VPN service for Always-on.
        // Also registered for the Quick Settings tile, which can't use Hilt DI.
        try {
            val supervisor = EntryPointAccessors.fromApplication(this, TileEntryPoint::class.java).tunnelSupervisor()
            TunnelStateHolder.supervisor = supervisor
            supervisor.start()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to start TunnelSupervisor")
        }

        // Trusted WLANs: disconnect in a trusted WLAN, connect again outside it.
        try {
            EntryPointAccessors.fromApplication(this, TileEntryPoint::class.java).trustedNetworkMonitor().start()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to start the trusted-network monitor")
        }

        // Home-screen widget: redraw on VPN, inbox and policy changes.
        try {
            EntryPointAccessors.fromApplication(this, com.gatecontrol.android.widget.VpnWidget.WidgetEntryPoint::class.java)
                .widgetState().start()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to start the widget state")
        }

        // Familie plugin: geofences back, plugin list and membership fresh, waiting changes sent.
        try {
            EntryPointAccessors.fromApplication(this, TileEntryPoint::class.java).familieStartup().onAppStart()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to start the Familie background work")
        }

        // Crash reports of earlier runs go to the server as a support bundle.
        try {
            val uploader = EntryPointAccessors.fromApplication(this, TileEntryPoint::class.java).crashReportUploader()
            if (CrashReports.pending().isNotEmpty()) {
                appScope.launch { uploader.uploadPending(BuildConfig.VERSION_NAME, Locale.getDefault().language) }
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to start the crash report upload")
        }

        // Initialize FreeRDP's GlobalApp.sessionMap, which is normally set in
        // GlobalApp.onCreate(). Since GateControlApp extends Application (Hilt
        // requirement), not GlobalApp, that lifecycle never fires. Without this,
        // GlobalApp.createSession() NPEs on the null sessionMap when a user taps
        // an RDP route. Reflection avoids modifying the freerdp submodule.
        try {
            val sessionMapField = com.freerdp.freerdpcore.application.GlobalApp::class.java
                .getDeclaredField("sessionMap")
            sessionMapField.isAccessible = true
            if (sessionMapField.get(null) == null) {
                sessionMapField.set(null, java.util.Collections.synchronizedMap(
                    java.util.HashMap<Long, Any>()
                ))
                Timber.d("FreeRDP sessionMap initialized")
            }
        } catch (e: Throwable) {
            Timber.w(e, "FreeRDP GlobalApp init failed — embedded RDP may crash")
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TileEntryPoint {
        fun tunnelSupervisor(): TunnelSupervisor
        fun trustedNetworkMonitor(): TrustedNetworkMonitor
        fun crashReportUploader(): CrashReportUploader
        fun familieStartup(): com.gatecontrol.android.familie.FamilieStartup
    }

    private fun installCrashLogger(context: Context) {
        CrashReports.init(context)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashReports.write("uncaught_${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashToFile(tag: String, throwable: Throwable) = CrashReports.write(tag, throwable)
}
