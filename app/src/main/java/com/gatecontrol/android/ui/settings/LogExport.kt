package com.gatecontrol.android.ui.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.gatecontrol.android.chat.OwnExports
import timber.log.Timber
import java.io.File

/** "Protokolle exportieren": the app's log as one file, handed to the share sheet. */
internal object LogExport {

    /** The log files of [FileLoggingTree][com.gatecontrol.android.FileLoggingTree], oldest first (the rotated one, then the current one). */
    fun logFiles(cacheDir: File): List<File> =
        File(cacheDir, "logs").listFiles()
            ?.filter { it.isFile && it.name.startsWith("gatecontrol.log") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()

    /**
     * Writes [content] (null: all log files) to a fresh folder below cacheDir/export — the only part of the cache the
     * FileProvider shares —
     * and opens the share sheet. False when that did not work.
     */
    fun share(context: Context, chooserTitle: String, content: String? = null): Boolean = try {
        val file = File(OwnExports.newDir(context.cacheDir), "gatecontrol-logs.txt")
        if (content != null) {
            file.writeText(content)
        } else {
            val logs = logFiles(context.cacheDir)
            file.outputStream().use { out ->
                if (logs.isEmpty()) out.write("No logs available\n".toByteArray())
                logs.forEach { log -> log.inputStream().use { it.copyTo(out) } }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        // may also go into GateControl itself (a chat, GateDrop, Drive)
        OwnExports.grant(uri.toString())
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, chooserTitle).apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    } catch (e: Exception) {
        Timber.w(e, "Log export failed")
        false
    }
}
