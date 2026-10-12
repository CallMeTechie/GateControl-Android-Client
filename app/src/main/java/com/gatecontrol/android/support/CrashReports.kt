package com.gatecontrol.android.support

import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reports in app-private storage (filesDir/crash) — never in shared
 * storage such as Downloads, where any app could read them. Written by the
 * uncaught-exception handler and by [coroutineHandler]; the next start
 * sends them to the server as a support bundle ([CrashReportUploader]).
 */
object CrashReports {
    const val DIR_NAME = "crash"
    const val FILE_PREFIX = "gatecontrol_crash_"
    const val MAX_FILES = 10

    /** Set before Hilt init, so a crash during start-up is recorded too. */
    @Volatile private var dir: File? = null

    fun init(context: Context) {
        dir = try {
            File(context.filesDir, DIR_NAME)
        } catch (_: Exception) {
            null
        }
    }

    /** For tests. */
    internal fun init(directory: File?) {
        dir = directory
    }

    /**
     * A failed background coroutine (app-wide scopes): logged and recorded,
     * the app keeps running instead of crashing.
     */
    val coroutineHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Uncaught error in a background task")
        write("background", throwable)
    }

    /** Writes a report; never throws (it may run while the process is crashing). */
    fun write(tag: String, throwable: Throwable, now: Date = Date()) {
        val dir = dir ?: return
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now)
            val sw = StringWriter()
            PrintWriter(sw).use { pw ->
                pw.println("=== GateControl Crash Report ===")
                pw.println("Tag: $tag")
                pw.println("Time: $timestamp")
                pw.println("Android: ${android.os.Build.VERSION.SDK_INT}")
                pw.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                pw.println()
                throwable.printStackTrace(pw)
            }
            dir.mkdirs()
            pending().drop(MAX_FILES - 1).forEach { it.delete() }
            var file = File(dir, "$FILE_PREFIX${safe(tag)}_$timestamp.txt")
            var n = 1
            while (file.exists()) file = File(dir, "$FILE_PREFIX${safe(tag)}_${timestamp}_${n++}.txt")
            file.writeText(sw.toString())
        } catch (_: Throwable) {
            // Last resort — nothing else to do while crashing
        }
    }

    /** Reports not sent yet, newest first. */
    fun pending(): List<File> {
        val dir = dir ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    private fun safe(tag: String) = tag.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
}
