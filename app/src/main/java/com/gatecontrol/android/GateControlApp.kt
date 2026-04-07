package com.gatecontrol.android

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Environment
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
            if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                Timber.plant(Timber.DebugTree())
            }
            // Always plant a file-based tree so logs are available in release builds
            Timber.plant(FileLoggingTree(this))
        } catch (e: Throwable) {
            writeCrashToFile("timber_init", e)
            throw e
        }
    }

    private fun installCrashLogger(context: Context) {
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
        private fun writeCrashToFile(tag: String, throwable: Throwable) {
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
            val content = sw.toString()

            // Try multiple locations — at least one should work
            val candidates = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStorageDirectory(),
                File("/sdcard/Download"),
                File("/storage/emulated/0/Download"),
            )

            for (dir in candidates) {
                try {
                    dir.mkdirs()
                    File(dir, fileName).writeText(content)
                    return // success
                } catch (_: Exception) {
                    // try next
                }
            }
        }
    }
}
