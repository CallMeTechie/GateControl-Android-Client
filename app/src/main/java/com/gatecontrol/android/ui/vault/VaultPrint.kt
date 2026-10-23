package com.gatecontrol.android.ui.vault

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import androidx.compose.ui.graphics.asAndroidBitmap
import com.gatecontrol.android.R
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date

/**
 * The recovery key on paper (or as PDF through Android's print dialog): title, the key in groups, its QR code, a
 * short note. Drawn directly into the print document — never written to a file by the app.
 */
object VaultPrint {
    fun recoveryKey(context: Context, key: String) {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val title = context.getString(R.string.vault_recovery_print_title)
        val note = context.getString(R.string.vault_recovery_print_note, DateFormat.getDateInstance(DateFormat.LONG).format(Date()))
        pm.print(title, Adapter(context, title, key, note), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
    }

    private class Adapter(val context: Context, val title: String, val key: String, val note: String) : PrintDocumentAdapter() {
        private var attrs: PrintAttributes? = null

        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            attrs = new
            if (cancel?.isCanceled == true) return cb.onLayoutCancelled()
            cb.onLayoutFinished(PrintDocumentInfo.Builder("gatecontrol-vault-recovery.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1).build(), true)
        }

        override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
            val doc = PrintedPdfDocument(context, attrs ?: PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
            try {
                val page = doc.startPage(0)
                val c = page.canvas
                val w = c.width.toFloat()
                val m = 56f
                val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
                val pMono = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f; typeface = Typeface.MONOSPACE; letterSpacing = 0.08f }
                val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f }
                var y = m + 20f
                c.drawText(title, m, y, pTitle)
                y += 40f
                key.split('-').chunked(4).forEach { row ->
                    c.drawText(row.joinToString("  "), m, y, pMono)
                    y += 28f
                }
                val qr = vaultQr(key, 360).asAndroidBitmap()
                val size = 180f
                c.drawBitmap(qr, null, android.graphics.RectF(w - m - size, m + 40f, w - m, m + 40f + size), null)
                y = maxOf(y, m + 40f + size) + 32f
                // wrap the note to the page width
                val words = note.split(' ')
                var line = ""
                for (word in words) {
                    val next = if (line.isEmpty()) word else "$line $word"
                    if (pText.measureText(next) > w - 2 * m) {
                        c.drawText(line, m, y, pText)
                        y += 16f
                        line = word
                    } else {
                        line = next
                    }
                }
                if (line.isNotEmpty()) c.drawText(line, m, y, pText)
                doc.finishPage(page)
                FileOutputStream(dest.fileDescriptor).use { doc.writeTo(it) }
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                cb.onWriteFailed(e.javaClass.simpleName)
            } finally {
                doc.close()
            }
        }
    }
}
