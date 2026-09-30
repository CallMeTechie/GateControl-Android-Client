package com.gatecontrol.android

import android.app.Application
import android.content.Context
import android.util.Log
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.service.TunnelStateHolder
import com.gatecontrol.android.service.TunnelSupervisor
import com.gatecontrol.android.tunnel.TunnelManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class GateControlApp : Application() {

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

        // Register singletons for Quick Settings tile (which can't use Hilt DI)
        try {
            val entryPoint = EntryPointAccessors.fromApplication(this, TileEntryPoint::class.java)
            TunnelStateHolder.tunnelManager = entryPoint.tunnelManager()
            TunnelStateHolder.setupRepository = entryPoint.setupRepository()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to register TunnelStateHolder singletons")
        }

        // Tunnel lifecycle outside the UI: Always-on VPN, auto-reconnect, tile state.
        // Must run before the system starts the VPN service for Always-on.
        try {
            EntryPointAccessors.fromApplication(this, TileEntryPoint::class.java).tunnelSupervisor().start()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to start TunnelSupervisor")
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
        fun tunnelManager(): TunnelManager
        fun setupRepository(): SetupRepository
        fun tunnelSupervisor(): TunnelSupervisor
    }

    private fun installCrashLogger(context: Context) {
        crashDir = try {
            File(context.filesDir, CRASH_DIR_NAME)
        } catch (_: Exception) {
            null
        }
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashToFile("uncaught_${thread.name}", throwable)
            } catch (_: Exception) {
                // Last resort
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_DIR_NAME = "crash"
        private const val MAX_CRASH_FILES = 10

        /** App-private crash report directory (filesDir/crash), set before Hilt init. */
        @Volatile private var crashDir: File? = null

        /**
         * Writes a crash report to app-private storage only — never to shared
         * storage such as Downloads, where any app could read it.
         */
        private fun writeCrashToFile(tag: String, throwable: Throwable) {
            val dir = crashDir ?: return
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val fileName = "gatecontrol_crash_${tag}_$timestamp.txt"

            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("=== GateControl Crash Report ===")
            pw.println("Tag: $tag")
            pw.println("Time: $timestamp")
            pw.println("Android: ${android.os.Build.VERSION.SDK_INT}")
            pw.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            pw.println()
            throwable.printStackTrace(pw)
            pw.flush()

            try {
                dir.mkdirs()
                dir.listFiles { f -> f.isFile && f.name.startsWith("gatecontrol_crash_") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_CRASH_FILES - 1)
                    ?.forEach { it.delete() }
                File(dir, fileName).writeText(sw.toString())
            } catch (_: Exception) {
                // Last resort — nothing else to do while crashing
            }
        }
    }
}
