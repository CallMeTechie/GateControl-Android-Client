package com.gatecontrol.android.ui.drive

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DriveDocLogic
import com.gatecontrol.android.drive.vault.DriveVaultMemFile
import com.gatecontrol.android.drive.vault.DriveVaultView
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.MonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/*
 * The pages of the document viewers — one look for both: the vault's (DriveVaultViewer.kt, decrypted in memory only)
 * and the one of the normal files of Drive (DriveDocViewer.kt, the file as "Öffnen" fetches it). A PDF page by page
 * (PdfRenderer), a text or Markdown in a list that lays out only what is shown, the bar on top, a note in the middle.
 * Where the bytes come from and what is allowed with them (selecting text, another app) is the viewer's business.
 */

/** A PDF for PdfRenderer; one page at a time (PdfRenderer is not thread-safe). [close] releases what it was opened from. */
class DrivePdf private constructor(private val renderer: PdfRenderer, private val owned: Closeable?) : Closeable {
    private val lock = Mutex()
    @Volatile private var closed = false
    val pages: Int = renderer.pageCount

    /** Page [i] (0-based) as a bitmap [width] pixels wide; null once closed or when the page cannot be drawn. */
    suspend fun page(i: Int, width: Int): Bitmap? = withContext(Dispatchers.Default) {
        lock.withLock {
            if (closed) return@withLock null
            try {
                renderer.openPage(i).use { p ->
                    val w = width.coerceIn(200, 2400)
                    val h = (w.toLong() * p.height / p.width.coerceAtLeast(1)).toInt().coerceIn(1, 4 * w)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // waits for a page being rendered; then the renderer, its descriptor and what it was opened from go
        kotlinx.coroutines.runBlocking { lock.withLock { runCatching { renderer.close() } } }
        owned?.let { runCatching { it.close() } }
    }

    companion object {
        private fun renderer(pfd: ParcelFileDescriptor): PdfRenderer = try {
            PdfRenderer(pfd)
        } catch (e: Exception) {
            runCatching { pfd.close() }
            throw e
        }

        /** A vault's PDF from its anonymous memory file (emptied when this closes; the caller closes it on a throw). */
        fun fromMemory(file: DriveVaultMemFile): DrivePdf = DrivePdf(renderer(file.openRead()), file)

        /**
         * A PDF of a normal Drive file, as fetched to the cache (the file stays there). Throws an IOException for a file
         * that is no PDF or a damaged one, a SecurityException for one with a password.
         */
        fun fromFile(file: File): DrivePdf = DrivePdf(renderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)), null)
    }
}

/** Room for the bar on top of a page ([DocTopBar]). */
private val BAR = 56.dp

/**
 * The bar of a document viewer over its page: back, the name ([subtitle] under it), then the viewer's [actions]
 * (white icons on the dark bar).
 */
@Composable
internal fun DocTopBar(name: String, subtitle: String?, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GcIconButton(GcIcons.Back, stringResource(R.string.drive_back), onClick = onBack, tint = Color.White)
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        actions()
    }
}

/** While loading: a spinner, [text] under it, the share loaded so far when it is known. */
@Composable
internal fun DocLoading(text: String, progress: Float?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(36.dp), color = Color.White)
            Text(text, color = Color.White)
            progress?.let { androidx.compose.material3.LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(0.6f)) }
        }
    }
}

/** The pages of [doc] one under the other, each drawn as wide as the screen when it comes into view. */
@Composable
internal fun DocPdfPages(doc: DrivePdf, onUse: () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(top = BAR),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(doc.pages) { i ->
                var failed by remember(doc, i) { mutableStateOf(false) }
                val page by produceState<Bitmap?>(null, doc, i, widthPx) {
                    value = doc.page(i, widthPx)
                    if (value == null) failed = true
                    onUse()
                    awaitDispose { value?.recycle(); value = null }
                }
                val bmp = page
                if (bmp != null && !bmp.isRecycled) {
                    Image(bmp.asImageBitmap(), contentDescription = stringResource(R.string.drive_vault_view_page, i + 1), contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                } else {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                        if (failed) {
                            Text(stringResource(R.string.drive_doc_page_failed, i + 1), color = Color.White, style = MaterialTheme.typography.bodySmall)
                        } else {
                            CircularProgressIndicator(Modifier.size(28.dp), color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A text or Markdown (headings, lists, quotes, code — never HTML) in a list that lays out only what is shown. Only a
 * [selectable] one can be selected and copied: the vault's stays out of the clipboard.
 */
@Composable
internal fun DocTextPage(text: String, markdown: Boolean, selectable: Boolean) {
    val blocks = remember(text, markdown) { if (markdown) DriveVaultView.markdown(text) else null }
    val chunks = remember(text, markdown) { if (markdown) emptyList() else DriveDocLogic.chunks(text) }
    val list: @Composable () -> Unit = {
        LazyColumn(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.statusBars).padding(top = BAR),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(if (markdown) 10.dp else 0.dp),
        ) {
            if (blocks == null) {
                itemsIndexed(chunks) { _, c -> Text(c, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily)) }
            } else {
                itemsIndexed(blocks) { _, b -> MarkdownBlock(b) }
            }
            item { Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) }
        }
    }
    if (selectable) SelectionContainer { list() } else list()
}

@Composable
private fun MarkdownBlock(b: DriveVaultView.Block) {
    when (b) {
        is DriveVaultView.Block.Heading -> Text(inline(b.text), style = when (b.level) {
            1 -> MaterialTheme.typography.headlineSmall
            2 -> MaterialTheme.typography.titleLarge
            else -> MaterialTheme.typography.titleMedium
        })
        is DriveVaultView.Block.Paragraph -> Text(inline(b.text), style = MaterialTheme.typography.bodyMedium)
        is DriveVaultView.Block.Item -> Row { Text(b.marker + "  "); Text(inline(b.text), style = MaterialTheme.typography.bodyMedium) }
        is DriveVaultView.Block.Quote -> Text(inline(b.text), style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), modifier = Modifier.padding(start = 12.dp))
        is DriveVaultView.Block.Code -> GcCard { Text(b.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily)) }
        DriveVaultView.Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).size(1.dp).background(MaterialTheme.colorScheme.outline))
    }
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    DriveVaultView.spans(text).forEach { s ->
        when (s) {
            is DriveVaultView.Span.Plain -> append(s.text)
            is DriveVaultView.Span.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.text) }
            is DriveVaultView.Span.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.text) }
            is DriveVaultView.Span.Mono -> withStyle(SpanStyle(fontFamily = MonoFontFamily)) { append(s.text) }
        }
    }
}

/** A note in the middle of the viewer instead of the file: [icon], [text], then [actions] (buttons). */
@Composable
internal fun DocNote(icon: ImageVector, text: String, actions: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
            Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            actions()
        }
    }
}
