package com.gatecontrol.android.ui.drive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.vault.DriveVaultSession
import com.gatecontrol.android.drive.vault.DriveVaultVideoSource
import com.gatecontrol.android.drive.vault.DriveVaultView
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import kotlinx.coroutines.delay
import java.nio.ByteBuffer

/**
 * The viewer of vault files (Drive plugin 1.22): pictures, PDFs, texts and Markdown, videos — decrypted in memory of
 * this app only (DriveVaultView). The screen is FLAG_SECURE (no screenshot, no picture in the recent apps) while it is
 * shown, the video's surface too; it closes when the vaults lock (the view model drops what it holds). "Mit anderer
 * App öffnen" is the one way out, and it says so first.
 */
sealed class VaultViewer {
    abstract val node: DNode
    abstract val name: String

    data class Loading(override val node: DNode, override val name: String, val progress: Float? = null) : VaultViewer()
    data class Picture(override val node: DNode, override val name: String, val bitmap: ImageBitmap) : VaultViewer()
    data class Pdf(override val node: DNode, override val name: String, val doc: DrivePdf) : VaultViewer()
    data class Text(override val node: DNode, override val name: String, val text: String, val markdown: Boolean) : VaultViewer()
    data class Video(override val node: DNode, override val name: String, val cipher: DriveVaultSession.Cipher) : VaultViewer()

    /** Nothing to show here: [why] `video_unsupported`, `office`, `other`, `too_large`, `decrypt`, `failed`, `offline`, … */
    data class Note(override val node: DNode, override val name: String, val why: String) : VaultViewer()
}

/** A picture's bytes → a bitmap of at most [maxSide] pixels (orientation of its EXIF applied by ImageDecoder). */
internal fun decodeVaultPicture(bytes: ByteArray, maxSide: Int = 4096): Bitmap {
    val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val side = maxOf(info.size.width, info.size.height)
        var sample = 1
        while (side / sample > maxSide) sample *= 2
        if (sample > 1) decoder.setTargetSampleSize(sample)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
    }
}

/** Messages of the viewer's "Mit anderer App öffnen" (null: not one of them). */
internal fun vaultViewText(context: Context, code: String): String? = when (code) {
    "vault_share_too_large" -> context.getString(R.string.drive_vault_open_with_too_large, DriveVaultView.SHARE_LIMIT / 1_048_576)
    "unsupported" -> context.getString(R.string.drive_vault_view_unsupported)
    "decrypt" -> context.getString(R.string.drive_vault_view_unreadable)
    DriveVaultSession.CODE_VERIFY -> context.getString(R.string.drive_vault_err_verify)
    else -> null
}

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** While shown: no screenshot, no screen recording of it, no picture in the recent apps. */
@Composable
private fun SecureWindow() {
    val activity = LocalContext.current.activity()
    DisposableEffect(activity) {
        val window = activity?.window
        val had = window != null && (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity?.setRecentsScreenshotEnabled(false)
        onDispose {
            if (!had) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity?.setRecentsScreenshotEnabled(true)
        }
    }
}

/** The viewer full screen over the vault page; [onUse] counts as use of the vault (its lock timer starts again). */
@Composable
internal fun DriveVaultViewerScreen(
    state: VaultViewer,
    onClose: () -> Unit,
    onUse: () -> Unit,
    onLock: () -> Unit,
    onOpenWith: () -> Unit,
) {
    SecureWindow()
    BackHandler(onBack = onClose)
    var menu by remember { mutableStateOf(false) }
    var askOpenWith by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            awaitPointerEventScope { while (true) { awaitPointerEvent(); onUse() } }
        },
    ) {
        when (state) {
            is VaultViewer.Loading -> DocLoading(stringResource(R.string.drive_vault_view_decrypting), state.progress)
            is VaultViewer.Picture -> ZoomablePicture(state.bitmap, state.name)
            is VaultViewer.Pdf -> DocPdfPages(state.doc, onUse)
            // not selectable: copying would put the plaintext into the clipboard
            is VaultViewer.Text -> DocTextPage(state.text, state.markdown, selectable = false)
            is VaultViewer.Video -> VaultVideo(state.cipher, onUse)
            is VaultViewer.Note -> NotePage(state, onOpenWith = { askOpenWith = true })
        }
        DocTopBar(state.name, stringResource(R.string.drive_vault_view_in_memory), onBack = onClose) {
            GcIconButton(GcIcons.Lock, stringResource(R.string.drive_vault_lock_now), onClick = onLock, tint = Color.White)
            Box {
                GcIconButton(GcIcons.MoreVertical, stringResource(R.string.drive_vault_view_more), onClick = { menu = true }, tint = Color.White)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.drive_vault_open_with)) },
                        leadingIcon = { Icon(GcIcons.External, contentDescription = null) },
                        onClick = { menu = false; askOpenWith = true },
                    )
                }
            }
        }
    }
    if (askOpenWith) {
        OpenWithDialog(onConfirm = { askOpenWith = false; onOpenWith() }, onDismiss = { askOpenWith = false })
    }
}

/** "Mit anderer App öffnen": what it means, before anything leaves the vault. */
@Composable
internal fun OpenWithDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(GcIcons.Alert, contentDescription = null) },
        title = { Text(stringResource(R.string.drive_vault_open_with_title)) },
        text = { Text(stringResource(R.string.drive_vault_open_with_text), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.drive_vault_open_with_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) } },
    )
}

@Composable
private fun ZoomablePicture(bitmap: ImageBitmap, name: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Image(
        bitmap,
        contentDescription = name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                })
            }
            .transformable(transform)
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun VaultVideo(cipher: DriveVaultSession.Cipher, onUse: () -> Unit) {
    val context = LocalContext.current
    val player = remember(cipher) {
        // no cache in front of the source: Media3 never writes the plaintext anywhere
        val source = ProgressiveMediaSource.Factory { DriveVaultVideoSource(cipher.file, cipher.content) }
            .createMediaSource(MediaItem.fromUri("gcvault://video"))
        ExoPlayer.Builder(context).build().apply { setMediaSource(source); prepare(); playWhenReady = true }
    }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val l = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    // watching counts as use of the vault (it would lock in the middle of a film otherwise)
    LaunchedEffect(playing) { while (playing) { onUse(); delay(30_000) } }
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                this.player = player
                // the video surface is a window of its own: secure as well, or screenshots would show it
                (videoSurfaceView as? SurfaceView)?.setSecure(true)
            }
        },
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(top = 56.dp),
    )
}

@Composable
private fun NotePage(state: VaultViewer.Note, onOpenWith: () -> Unit) {
    val kind = DriveVaultView.kindOf(state.name)
    val text = when (state.why) {
        "office" -> stringResource(R.string.drive_vault_view_office)
        "other" -> stringResource(R.string.drive_vault_view_other)
        "too_large" -> stringResource(R.string.drive_vault_view_too_large, DriveVaultView.limitMb(kind))
        "decrypt" -> stringResource(R.string.drive_vault_view_unreadable)
        "no_space" -> stringResource(R.string.drive_vault_view_no_space)
        "unsupported" -> stringResource(R.string.drive_vault_view_unsupported)
        "offline" -> stringResource(R.string.drive_vault_view_offline)
        else -> stringResource(R.string.drive_vault_view_failed)
    }
    DocNote(GcIcons.Lock, text) {
        if (state.why in setOf("office", "other", "too_large", "unsupported")) {
            GcOutlineButton(stringResource(R.string.drive_vault_open_with), onClick = onOpenWith, icon = GcIcons.External)
        }
    }
}

