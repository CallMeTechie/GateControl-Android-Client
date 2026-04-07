package com.gatecontrol.android

import android.app.Application
import android.content.pm.ApplicationInfo
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class GateControlApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Install crash logger that writes to a file accessible via device file manager
        installCrashLogger()

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Timber.plant(Timber.DebugTree())
        }
    }

    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val crashDir = File(getExternalFilesDir(null), "crashes")
                crashDir.mkdirs()
                val crashFile = File(crashDir, "crash_$timestamp.txt")

                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("=== GateControl Crash Report ===")
                pw.println("Time: $timestamp")
                pw.println("Thread: ${thread.name}")
                pw.println("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                pw.println("Android: ${android.os.Build.VERSION.SDK_INT}")
                pw.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                pw.println()
                throwable.printStackTrace(pw)
                pw.flush()

                crashFile.writeText(sw.toString())
            } catch (_: Exception) {
                // Last resort — don't crash the crash handler
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
