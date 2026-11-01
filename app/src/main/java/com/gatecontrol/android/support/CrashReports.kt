package com.gatecontrol.android.support

import android.app.ActivityManager
import android.app.ApplicationExitInfo
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
    private const val PREFS = "crash_reports"
    private const val KEY_EXIT_SEEN = "exit_seen"
    private const val EXITS_MAX = 8
    private const val EXIT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val EXIT_TRACE_BYTES = 64 * 1024
    private const val EXIT_TEXT_CHARS = 24 * 1024

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
        try {
            val sw = StringWriter()
            PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
            writeText(tag, sw.toString(), now)
        } catch (_: Throwable) {
            // Last resort — nothing else to do while crashing
        }
    }

    /** A report of [body] (a stack trace, or what Android says of an exit); never throws. */
    private fun writeText(tag: String, body: String, now: Date) {
        val dir = dir ?: return
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now)
            val text = buildString {
                appendLine("=== GateControl Crash Report ===")
                appendLine("Tag: $tag")
                appendLine("Time: $timestamp")
                appendLine("Android: ${android.os.Build.VERSION.SDK_INT}")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine()
                append(body)
            }
            dir.mkdirs()
            pending().drop(MAX_FILES - 1).forEach { it.delete() }
            var file = File(dir, "$FILE_PREFIX${safe(tag)}_$timestamp.txt")
            var n = 1
            while (file.exists()) file = File(dir, "$FILE_PREFIX${safe(tag)}_${timestamp}_${n++}.txt")
            file.writeText(text)
        } catch (_: Throwable) {
            // Last resort — nothing else to do while crashing
        }
    }

    /**
     * How earlier runs ended, as Android recorded it (`ApplicationExitInfo`): an ANR, a native crash, the system ending
     * the app in front for memory, and a crash whose own report could not be written (out of memory) — none of them
     * reaches the uncaught-exception handler. Each such exit of the last days becomes a report (sent like the others);
     * each exit once. Call at start in the main process.
     */
    fun recordExits(context: Context) {
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(KEY_EXIT_SEEN, 0L)
            val since = maxOf(seen, System.currentTimeMillis() - EXIT_MAX_AGE_MS)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, EXITS_MAX).filter { it.timestamp > since }
            if (exits.isEmpty()) return
            prefs.edit().putLong(KEY_EXIT_SEEN, exits.maxOf { it.timestamp }).apply()
            for (e in exits.sortedBy { it.timestamp }) {
                if (!exitReported(e.reason, e.importance)) continue
                // an ANR's trace is text (a native crash's tombstone is not)
                val trace = if (e.reason == ApplicationExitInfo.REASON_ANR) {
                    runCatching { e.traceInputStream?.use { head(it, EXIT_TRACE_BYTES) } }.getOrNull()
                } else {
                    null
                }
                writeText("exit_${exitName(e.reason)}", exitText(e.reason, e.status, e.importance, e.pss, e.rss, e.processName, e.description, trace), Date(e.timestamp))
            }
        } catch (e: Exception) {
            Timber.w("Exit reasons not read: %s", e.javaClass.simpleName)
        }
    }

    /** The first [max] bytes of [input] as text. */
    private fun head(input: java.io.InputStream, max: Int): String {
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val r = input.read(buf, n, max - n)
            if (r < 0) break
            n += r
        }
        return String(buf, 0, n)
    }

    /** An exit worth a report: a crash, an ANR, or the system ending the app while it was seen (not in the background). */
    internal fun exitReported(reason: Int, importance: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> true
        ApplicationExitInfo.REASON_LOW_MEMORY -> importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        else -> false
    }

    internal fun exitName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init_failure"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resources"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        else -> "reason_$reason"
    }

    /** The report of an exit: what Android says (memory in kB), an ANR's trace below. */
    internal fun exitText(reason: Int, status: Int, importance: Int, pssKb: Long, rssKb: Long, process: String?, description: String?, trace: String?): String =
        buildString {
            appendLine("Exit: ${exitName(reason)} (status $status, importance $importance)")
            appendLine("Process: ${process.orEmpty()}")
            appendLine("Memory: pss ${pssKb} kB, rss ${rssKb} kB")
            if (!description.isNullOrBlank()) appendLine("Description: ${description.take(EXIT_TEXT_CHARS)}")
            if (!trace.isNullOrBlank()) {
                appendLine()
                append(trace.take(EXIT_TEXT_CHARS))
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
