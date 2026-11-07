package com.gatecontrol.android.ui.drive

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveDocLogic
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.vault.DriveVaultHeld
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * The viewer of PDFs, texts and Markdown of the normal files of Drive (DriveDocLogic): the pages of the vault's viewer
 * (DriveDocPages.kt), full screen. The content is the file as "Öffnen" fetches it (the copy here, else a download —
 * the bar shows how far); a text can be selected and copied. On top: "Mit anderer App öffnen", "Senden" and, where
 * given, "Details" ([onDetails]). A file too large for it, one that is no readable PDF (damaged, a password) and a
 * failed download say so and offer another app. The caller asks about a file the virus scan marked first; a blocked
 * one never comes here (DriveDocLogic.opensInViewer).
 */
private sealed interface DocState {
    data object Loading : DocState
    data class Pdf(val doc: DrivePdf) : DocState
    data class Text(val text: String, val markdown: Boolean) : DocState

    /** [why]: `too_large`, `pdf_unreadable`, `failed`, or a code of the fetch (driveText). */
    data class Note(val why: String) : DocState
}

@Composable
internal fun DriveDocViewer(node: DNode, onClose: () -> Unit, onDetails: ((DNode) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media: DriveMediaViewModel = hiltViewModel()
    val downloading by media.downloading.collectAsStateWithLifecycle()
    val kind = DriveDocLogic.kind(node) ?: DriveDocLogic.Kind.TEXT
    // what this viewer opened (the PDF renderer) — released when it closes, also when a load finishes after that
    val held = remember(node.id, node.rev) { DriveVaultHeld() }
    DisposableEffect(held) { onDispose { held.close() } }
    var attempt by remember(node.id, node.rev) { mutableIntStateOf(0) }
    var state by remember(node.id, node.rev) {
        mutableStateOf<DocState>(if (DriveDocLogic.tooLarge(node)) DocState.Note("too_large") else DocState.Loading)
    }
    LaunchedEffect(node.id, node.rev, attempt) {
        if (state != DocState.Loading) return@LaunchedEffect
        state = when (val r = media.fetch(node)) {
            is DResult.Ok -> load(r.value, kind, held)
            is DResult.Failed -> DocState.Note(r.code)
            DResult.Offline -> DocState.Note("offline")
            DResult.NotConfigured -> DocState.Note("not_configured")
        }
    }
    var busy by remember { mutableStateOf(false) }
    val act: (Boolean) -> Unit = { send ->
        if (!busy) {
            busy = true
            scope.launch {
                fetchThen(context, node, media::fetch, then = { f -> if (send) sendFile(context, node, f) else openFile(context, node, f) }) { code ->
                    Toast.makeText(context, driveText(context, code), Toast.LENGTH_LONG).show()
                }
                busy = false
            }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (val s = state) {
                DocState.Loading -> DocLoading(stringResource(R.string.drive_viewer_loading), downloading[node.id])
                is DocState.Pdf -> DocPdfPages(s.doc)
                is DocState.Text -> DocTextPage(s.text, s.markdown, selectable = true)
                is DocState.Note -> {
                    val text = when (s.why) {
                        "too_large" -> stringResource(R.string.drive_doc_too_large, DriveDocLogic.limitMb(kind))
                        "pdf_unreadable" -> stringResource(R.string.drive_doc_pdf_unreadable)
                        "failed" -> stringResource(R.string.drive_doc_failed)
                        else -> driveText(context, s.why)
                    }
                    val retry = s.why !in setOf("too_large", "pdf_unreadable", "failed")
                    DocNote(kindLook(DriveLogic.kind(node)).icon, text) {
                        if (retry) {
                            TextButton(onClick = { state = DocState.Loading; attempt++ }) { Text(stringResource(R.string.drive_viewer_retry), color = Color.White) }
                        } else {
                            GcOutlineButton(stringResource(R.string.drive_doc_open_other), onClick = { act(false) }, icon = GcIcons.External, loading = busy)
                        }
                    }
                }
            }
            DocTopBar(node.name, subtitle = null, onBack = onClose) {
                GcIconButton(GcIcons.External, stringResource(R.string.drive_doc_open_other), onClick = { act(false) }, tint = Color.White, enabled = !busy)
                GcIconButton(GcIcons.Share, stringResource(R.string.drive_send), onClick = { act(true) }, tint = Color.White, enabled = !busy)
                if (onDetails != null) GcIconButton(GcIcons.Info, stringResource(R.string.drive_viewer_details), onClick = { onDetails(node) }, tint = Color.White)
            }
        }
    }
}

/** The fetched [file] for the page of [kind]; what is opened is kept in [held] (and closed at once if it already went). */
private suspend fun load(file: File, kind: DriveDocLogic.Kind, held: DriveVaultHeld): DocState = withContext(Dispatchers.IO) {
    // the size the server said may be old: the file itself decides
    if (DriveDocLogic.tooLarge(kind, file.length())) return@withContext DocState.Note("too_large")
    when (kind) {
        DriveDocLogic.Kind.PDF -> try {
            val pdf = DrivePdf.fromFile(file)
            if (held.add(pdf)) DocState.Pdf(pdf) else DocState.Loading
        } catch (e: Exception) {
            // no PDF, a damaged one, one with a password
            Timber.d("Drive: PDF not opened (%s)", e.javaClass.simpleName)
            DocState.Note("pdf_unreadable")
        }
        DriveDocLogic.Kind.TEXT, DriveDocLogic.Kind.MARKDOWN -> try {
            DocState.Text(DriveDocLogic.decode(file.readBytes()), kind == DriveDocLogic.Kind.MARKDOWN)
        } catch (e: Exception) {
            DocState.Note("failed")
        } catch (e: OutOfMemoryError) {
            DocState.Note("too_large")
        }
    }
}
