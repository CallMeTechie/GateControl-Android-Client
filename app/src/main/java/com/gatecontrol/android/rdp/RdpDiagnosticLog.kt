package com.gatecontrol.android.rdp

import android.content.Context
import com.gatecontrol.android.BuildConfig
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-session RDP diagnostics (credential passing, OnAuthenticate callbacks,
 * FreeRDP native behavior that is invisible in Logcat).
 *
 * Debug builds only: release builds write nothing. Files go to app-private
 * storage (filesDir/diagnostics/rdp-diag-<timestamp>.log), never to shared
 * storage, and only the newest [MAX_FILES] sessions are kept. Callers must
 * not pass usernames, passwords or their lengths.
 */
class RdpDiagnosticLog(context: Context, enabled: Boolean = BuildConfig.DEBUG) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val writer: PrintWriter?

    init {
        writer = if (!enabled) {
            null
        } else {
            try {
                val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
                pruneOldFiles(dir)
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(dir, "rdp-diag-$timestamp.log")
                PrintWriter(FileWriter(file, true), true)
            } catch (e: Exception) {
                Timber.w(e, "Could not create RDP diagnostic log")
                null
            }
        }
    }

    fun log(message: String) {
        val out = writer ?: return
        Timber.d("RDP-DIAG: $message")
        try {
            out.println("${dateFormat.format(Date())}  $message")
        } catch (_: Exception) {}
    }

    fun close() {
        try { writer?.close() } catch (_: Exception) {}
    }

    private fun pruneOldFiles(dir: File) {
        dir.listFiles { f -> f.isFile && f.name.startsWith("rdp-diag-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES - 1)
            ?.forEach { it.delete() }
    }

    companion object {
        const val DIR_NAME = "diagnostics"
        private const val MAX_FILES = 5
    }
}
