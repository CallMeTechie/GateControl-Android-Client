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

        // Drive work photos: the WLAN timeline (needs the monitor above; Android drops the WLAN wake on reboot).
        try {
            EntryPointAccessors.fromApplication(this, com.gatecontrol.android.drive.work.DriveWorkEntryPoint::class.java).driveWorkWifi().start()
        } catch (e: Throwable) {
            Timber.w(e, "Drive work: WLAN timeline not started")
        }

        // Drive photo backup: its work is scheduled while it is on (it may have been lost).
        try {
            com.gatecontrol.android.drive.backup.DriveBackupWork.ensure(this)
        } catch (e: Throwable) {
            Timber.w(e, "Drive backup: not ensured")
        }
        // Drive folder backup: the same for the phone folders backed up.
        try {
            com.gatecontrol.android.drive.backup.DriveFolderBackupWork.ensure(this)
        } catch (e: Throwable) {
            Timber.w(e, "Drive folders: not ensured")
        }

        // Drive offline files: signed out, another server or login — the vaults' offline ciphertext and wrapped keys go.
        try {
            EntryPointAccessors.fromApplication(this, com.gatecontrol.android.drive.DriveOfflineStoreEntryPoint::class.java).driveOfflineStore().watchAccount()
        } catch (e: Throwable) {
            Timber.w(e, "Drive offline: login not watched")
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

        // GateDrop: share target on with chats or GateDrop, LAN presence for direct transfers.
        try {
            EntryPointAccessors.fromApplication(this, TileEntryPoint::class.java).gateDropStartup().start()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to start GateDrop")
        }

        // Mail: the Microsoft redirect only with the plugin; its offline copy goes with the plugin or the login.
        try {
            EntryPointAccessors.fromApplication(this, com.gatecontrol.android.mail.MailEntryPoint::class.java).mailStartup().start()
        } catch (e: Throwable) {
            Timber.w(e, "Mail: not started")
        }

        // Notizen & Aufgaben: the phone's own alarms for the reminders of tasks (plugin 1.3.0; nothing with an older one).
        try {
            EntryPointAccessors.fromApplication(this, com.gatecontrol.android.notizen.NotizenReminderEntryPoint::class.java).notizenReminderCenter().start()
        } catch (e: Throwable) {
            Timber.w(e, "Notizen: reminders not started")
        }

        // Google Play build: in-app update (network watch, install in the background).
        if (com.gatecontrol.android.update.PlayUpdater.enabled) {
            try {
                EntryPointAccessors.fromApplication(this, TileEntryPoint::class.java).updateCoordinator().start()
            } catch (e: Throwable) {
                Timber.e(e, "Failed to start the Play update coordinator")
            }
        }

        // GitHub build: the update APK of an earlier run (installed by now, or given up) goes from the cache.
        if (!com.gatecontrol.android.update.PlayUpdater.enabled) {
            appScope.launch {
                runCatching { EntryPointAccessors.fromApplication(this@GateControlApp, TileEntryPoint::class.java).apkUpdater().cleanup() }
            }
        }

        // Crash reports of earlier runs go to the server as a support bundle — with the exits Android recorded that
        // the handler never saw (ANR, native crash, ended for memory).
        try {
            val uploader = EntryPointAccessors.fromApplication(this, TileEntryPoint::class.java).crashReportUploader()
            val main = getProcessName() == packageName
            appScope.launch {
                if (main) CrashReports.recordExits(this@GateControlApp)
                if (CrashReports.pending().isNotEmpty()) uploader.uploadPending(BuildConfig.VERSION_NAME, Locale.getDefault().language)
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
        fun updateCoordinator(): com.gatecontrol.android.update.UpdateCoordinator
        fun gateDropStartup(): com.gatecontrol.android.gatedrop.GateDropStartup
        fun apkUpdater(): com.gatecontrol.android.update.ApkUpdater
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
