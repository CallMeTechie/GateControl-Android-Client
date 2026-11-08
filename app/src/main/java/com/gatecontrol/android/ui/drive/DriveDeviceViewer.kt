package com.gatecontrol.android.ui.drive

import android.graphics.ImageDecoder
import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DriveDocLogic
import com.gatecontrol.android.drive.device.DeviceEntry
import com.gatecontrol.android.drive.device.DeviceFilesLogic
import com.gatecontrol.android.drive.vault.DriveVaultHeld
import com.gatecontrol.android.drive.vault.DriveVaultView
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** What the app shows itself: pictures, videos, PDFs, texts and Markdown (as Drive's own viewers). */
internal enum class DeviceView { IMAGE, VIDEO, PDF, TEXT, MARKDOWN }

internal fun deviceView(e: DeviceEntry): DeviceView? {
    if (e.isDir) return null
    return when (DeviceFilesLogic.typeOf(e)) {
        DeviceFilesLogic.Type.IMAGE -> if (DeviceFilesLogic.ext(e.name) == "svg") null else DeviceView.IMAGE
        DeviceFilesLogic.Type.VIDEO -> DeviceView.VIDEO
        DeviceFilesLogic.Type.PDF -> DeviceView.PDF
        else -> when (DriveVaultView.kindOf(e.name)) {
            DriveVaultView.Kind.MARKDOWN -> DeviceView.MARKDOWN
            DriveVaultView.Kind.TEXT -> DeviceView.TEXT
            else -> null
        }
    }
}

private sealed interface ViewState {
    data object Loading : ViewState
    data class Picture(val bitmap: ImageBitmap) : ViewState
    data class Pdf(val doc: DrivePdf) : ViewState
    data class Text(val text: String, val markdown: Boolean) : ViewState
    data object Video : ViewState
    /** [res]: what to say; the file opens in another app instead. */
    data class Note(val res: Int) : ViewState
}

/**
 * A file of the phone, full screen, with Drive's viewers: a picture (zoom), a video (the player), a PDF page by page,
 * a text or Markdown. On top: "Mit anderer App öffnen" and "Teilen". Anything else opens in another app directly.
 */
@Composable
internal fun DeviceViewer(e: DeviceEntry, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = deviceView(e)
    if (view == null) {
        LaunchedEffect(e.path) {
            openDeviceFile(context, e)
            onClose()
        }
        return
    }
    val held = remember(e.path) { DriveVaultHeld() }
    DisposableEffect(held) { onDispose { held.close() } }
    var state by remember(e.path) { mutableStateOf<ViewState>(if (view == DeviceView.VIDEO) ViewState.Video else ViewState.Loading) }
    LaunchedEffect(e.path) {
        if (state == ViewState.Loading) state = load(File(e.path), view, held)
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (val s = state) {
                ViewState.Loading -> CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
                is ViewState.Picture -> ZoomableImage(s.bitmap, contentDescription = e.name, modifier = Modifier.fillMaxSize())
                is ViewState.Pdf -> DocPdfPages(s.doc)
                is ViewState.Text -> DocTextPage(s.text, s.markdown, selectable = true)
                ViewState.Video -> DeviceVideo(File(e.path))
                is ViewState.Note -> DocNote(typeLook(DeviceFilesLogic.typeOf(e)).icon, stringResource(s.res)) {
                    GcOutlineButton(stringResource(R.string.drive_dev_open_other), onClick = { openDeviceFile(context, e) }, icon = GcIcons.External)
                }
            }
            DocTopBar(e.name, subtitle = null, onBack = onClose) {
                GcIconButton(GcIcons.External, stringResource(R.string.drive_dev_open_other), onClick = { openDeviceFile(context, e) }, tint = Color.White)
                GcIconButton(GcIcons.Share, stringResource(R.string.drive_dev_share), onClick = { shareDeviceFiles(context, listOf(e)) }, tint = Color.White)
            }
        }
    }
}

/** The picture at most this many pixels on its longer side (zoom stays sharp enough, memory stays small). */
private const val MAX_PICTURE = 2560

private suspend fun load(file: File, view: DeviceView, held: DriveVaultHeld): ViewState = withContext(Dispatchers.IO) {
    try {
        when (view) {
            DeviceView.IMAGE -> {
                // ImageDecoder turns the picture as its EXIF says and reads HEIC
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
                    val longer = maxOf(info.size.width, info.size.height)
                    if (longer > MAX_PICTURE) d.setTargetSampleSize((longer + MAX_PICTURE - 1) / MAX_PICTURE)
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                ViewState.Picture(bmp.asImageBitmap())
            }
            DeviceView.PDF -> {
                if (DriveDocLogic.tooLarge(DriveDocLogic.Kind.PDF, file.length())) return@withContext ViewState.Note(R.string.drive_dev_too_large_view)
                val pdf = DrivePdf.fromFile(file)
                if (held.add(pdf)) ViewState.Pdf(pdf) else ViewState.Loading
            }
            DeviceView.TEXT, DeviceView.MARKDOWN -> {
                val kind = if (view == DeviceView.MARKDOWN) DriveDocLogic.Kind.MARKDOWN else DriveDocLogic.Kind.TEXT
                if (DriveDocLogic.tooLarge(kind, file.length())) return@withContext ViewState.Note(R.string.drive_dev_too_large_view)
                ViewState.Text(DriveDocLogic.decode(file.readBytes()), view == DeviceView.MARKDOWN)
            }
            DeviceView.VIDEO -> ViewState.Video
        }
    } catch (e: OutOfMemoryError) {
        ViewState.Note(R.string.drive_dev_too_large_view)
    } catch (e: Exception) {
        Timber.d("Device: not shown (%s)", e.javaClass.simpleName)
        ViewState.Note(R.string.drive_dev_cannot_show)
    }
}

/** A video of the phone in the player (its controls: play / pause, seek, sound). */
@OptIn(UnstableApi::class)
@Composable
private fun DeviceVideo(file: File) {
    val context = LocalContext.current
    val player = remember(file.path) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = true
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}
