package com.gatecontrol.android.rdp

import android.content.Context
import android.os.Environment
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes RDP diagnostic messages to a log file in the Downloads folder.
 * Each RDP session creates one file: rdp-diag-<timestamp>.log
 *
 * Purpose: debug credential passing, OnAuthenticate callbacks, and
 * FreeRDP native behavior that is invisible in Timber/Logcat.
 */
class RdpDiagnosticLog(context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val writer: PrintWriter?

    init {
        writer = try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = File(dir, "rdp-diag-$timestamp.log")
            PrintWriter(FileWriter(file, true), true).also {
                Timber.i("RDP diagnostic log: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not create RDP diagnostic log")
            null
        }
    }

    fun log(message: String) {
        val ts = dateFormat.format(Date())
        val line = "$ts  $message"
        Timber.d("RDP-DIAG: $message")
        try {
            writer?.println(line)
        } catch (_: Exception) {}
    }

    fun close() {
        try { writer?.close() } catch (_: Exception) {}
    }
}
