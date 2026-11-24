package com.gatecontrol.android.tv.ui.mail

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gatecontrol.android.tv.ui.drive.TvLocalFile
import com.gatecontrol.android.tv.ui.drive.TvLocalPhotos
import com.gatecontrol.android.tv.ui.drive.TvPhotoSource
import com.gatecontrol.android.tv.ui.drive.TvPhotoViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** A mail's attachment opened on the TV: a photo or the pages of a PDF in the viewer of stage 2. */
sealed interface TvMailOpened {
    data class Photo(val file: TvLocalFile) : TvMailOpened
    data class Pdf(val source: TvPdfPages) : TvMailOpened
}

/**
 * The pages of a PDF on the stick as pictures for [TvPhotoViewer] (◀ ▶ page by page), drawn with Android's own
 * [PdfRenderer] on white. The file is opened for each page and closed again (nothing stays open behind the viewer).
 */
class TvPdfPages private constructor(private val file: File, private val name: String, override val count: Int, private val sidePx: Int) : TvPhotoSource {

    override fun key(i: Int) = "pdf:" + file.path + "#" + i
    override fun name(i: Int) = name
    override fun peek(i: Int): Bitmap? = null

    override suspend fun load(i: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    if (i !in 0 until pdf.pageCount) return@use null
                    pdf.openPage(i).use { page ->
                        val scale = min(sidePx.toFloat() / max(page.width, 1), sidePx.toFloat() / max(page.height, 1))
                        val w = (page.width * scale).toInt().coerceIn(1, sidePx)
                        val h = (page.height * scale).toInt().coerceIn(1, sidePx)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        }.getOrNull()
    }

    companion object {
        /** Long side of a page on the TV (1080p screen, sharp enough for text). */
        private const val SIDE_PX = 1920

        /** The PDF's pages; null when it is no PDF Android can draw (encrypted, broken). */
        suspend fun open(file: File, name: String, sidePx: Int = SIDE_PX): TvPdfPages? = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
            }.getOrNull()?.takeIf { it > 0 }?.let { TvPdfPages(file, name, it, sidePx) }
        }
    }
}

/** The viewer over the whole screen; Zurück closes it. */
@Composable
fun TvMailViewer(opened: TvMailOpened, onClose: () -> Unit) {
    var at by remember(opened) { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        when (opened) {
            is TvMailOpened.Photo -> TvPhotoViewer(remember(opened) { TvLocalPhotos(listOf(opened.file)) }, 0, onAt = {}, onClose = onClose)
            is TvMailOpened.Pdf -> TvPhotoViewer(opened.source, at.coerceIn(0, opened.source.count - 1), onAt = { at = it }, onClose = onClose, document = true)
        }
    }
}
