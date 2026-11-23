package com.gatecontrol.android.ui.drive

import android.content.pm.ActivityInfo
import android.graphics.ImageDecoder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.Window
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveMediaLogic
import com.gatecontrol.android.drive.DriveStream
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.drive.DriveVideoPlaces
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.scan.ScanLine
import com.gatecontrol.android.ui.scan.rememberScanAsk
import com.gatecontrol.android.ui.scan.scanClosedText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/*
 * The viewer of pictures and videos (Drive 1.21): the pages of PhotoViewer (DrivePhotosUi.kt) — a picture that zooms
 * (two fingers, a double tap; moved while zoomed; the original loaded once it is zoomed or when there is no preview)
 * and a video that plays right here with Media3 from the own server's media route (Range: only what plays is loaded,
 * over the app's OkHttp with the client token), with the player's controls, sound, full screen and the place it was
 * left. A file the virus scan holds back shows its reason; a marked one plays only after asking.
 *
 * ZoomableImage takes an ImageBitmap — any picture decoded elsewhere (e.g. from memory) can use it.
 */

/** What the pages of the viewer need from Drive: the stream of a video, the player's data sources, an original. */
@HiltViewModel
class DriveMediaViewModel @Inject constructor(
    private val api: DriveApi,
    private val transfers: DriveTransfers,
) : ViewModel() {
    val places: DriveVideoPlaces = DriveVideoPlaces.shared

    /**
     * The player of the video shown: here, not in the page — a fold, an unfold or a turn of the phone (the activity is
     * made anew) does not stop the film. Released when its page goes ([ReleaseWhenGone]) or with this view model.
     */
    internal val players = DriveKeptPlayer<ExoPlayer> { it.release() }

    override fun onCleared() {
        players.clear()
    }

    suspend fun stream(node: DNode): DResult<DriveStream> = api.stream(node)

    fun sourceFactory(): DataSource.Factory? = api.streamSourceFactory()

    /** A file as "Öffnen" fetches it (the copy here, else a download) — the PDF / text viewer (DriveDocViewer.kt) shows it. */
    suspend fun fetch(node: DNode): DResult<File> = transfers.fetch(node)

    /** How far the downloads are (file id → 0…1), for the viewer's bar. */
    val downloading = transfers.downloading

    /** The original of the picture [node], decoded to at most [DriveMediaLogic.ORIGINAL_SIDE_PX]; null when it cannot be. */
    suspend fun original(node: DNode): ImageBitmap? {
        if (node.size > DriveMediaLogic.ORIGINAL_MAX_BYTES || node.blocked) return null
        val file = (transfers.fetch(node) as? DResult.Ok<File>)?.value ?: return null
        return withContext(Dispatchers.IO) { decode(file) }
    }

    private fun decode(file: File): ImageBitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.setTargetSampleSize(DriveMediaLogic.sampleSize(info.size.width, info.size.height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }.asImageBitmap()
    } catch (e: Exception) {
        Timber.d("Drive: picture not decoded (%s)", e.javaClass.simpleName)
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

/**
 * A picture that zooms with two fingers or a double tap (up to [maxScale]) and moves while zoomed; one finger at 1×
 * is left to the pager (the next picture). [onZoomed] when it goes above or back to 1×, [onTap] a single tap.
 */
@Composable
internal fun ZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxScale: Float = 5f,
    onZoomed: (Boolean) -> Unit = {},
    onTap: () -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val zoomed by rememberUpdatedState(onZoomed)
    val tapped by rememberUpdatedState(onTap)
    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    fun set(s: Float, o: Offset) {
        val was = scale > 1.01f
        scale = s.coerceIn(1f, maxScale)
        offset = if (scale <= 1.01f) Offset.Zero else clamp(o, scale)
        val now = scale > 1.01f
        if (was != now) zoomed(now)
    }
    Box(
        modifier
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1.01f) {
                            val z = event.calculateZoom()
                            val pan = event.calculatePan()
                            val c = event.calculateCentroid(useCurrent = false)
                            val s = (scale * z).coerceIn(1f, maxScale)
                            // about the fingers' middle (from the middle of the box)
                            val mid = Offset(box.width / 2f, box.height / 2f)
                            val k = s / scale
                            val focus = if (c == Offset.Unspecified) Offset.Zero else c - mid
                            set(s, (offset - focus) * k + focus + pan)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { p ->
                        if (scale > 1.01f) set(1f, Offset.Zero) else {
                            val mid = Offset(box.width / 2f, box.height / 2f)
                            set(2.5f, (mid - p) * 1.5f)
                        }
                    },
                    onTap = { tapped() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}

/** The reason a blocked file does not open, in the middle of a page (the scan's own words). */
@Composable
internal fun BoxScope.HeldBack(node: DNode) {
    val context = LocalContext.current
    Column(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(GcIcons.Alert, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
        Text(node.scan?.let { scanClosedText(context, it) }.orEmpty(), color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A picture page: the large preview (the small one meanwhile), the original once zoomed or without a preview. */
@Composable
internal fun DriveImagePage(node: DNode, active: Boolean, media: DriveMediaViewModel, onZoomed: (Boolean) -> Unit, onTap: () -> Unit) {
    val large = rememberDrivePreview(node, large = true)
    val small = rememberDrivePreview(node)
    var zoomedIn by remember(node.id) { mutableStateOf(false) }
    var original by remember(node.id, node.rev) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(node.id, node.rev) { mutableStateOf(false) }
    // the original: only for the page shown, once zoomed (or when there is no preview at all) — data costs
    val want = active && !node.blocked && (zoomedIn || !node.preview) && original == null && !failed
    LaunchedEffect(want) {
        if (want) {
            original = media.original(node)
            if (original == null) failed = true
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (node.blocked) { HeldBack(node); return@Box }
        val picture = original ?: large ?: small
        if (picture != null) {
            ZoomableImage(picture, contentDescription = node.name, modifier = Modifier.fillMaxSize(), onZoomed = { zoomedIn = it; onZoomed(it) }, onTap = onTap)
        } else if (node.preview || want) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else {
            Icon(kindLook(DriveLogic.kind(node)).icon, contentDescription = node.name, tint = Color.White, modifier = Modifier.size(56.dp).align(Alignment.Center))
        }
    }
}

/** What the full screen of a viewer asks of the activity's orientation ([ViewerFullscreen]). */
internal object ViewerOrientation {
    /** The orientation to go back to: a landscape the viewer forced itself (before the activity was made anew) is none. */
    fun before(current: Int): Int =
        if (current == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else current

    /**
     * Whether to go back to it as the full screen ends ([forced]: it had turned to landscape): not while the activity is
     * made anew ([recreating]: a fold or unfold, a turn) under a viewer that comes back with it ([survivesRecreation]) —
     * that one forces landscape again at once, going back in between would turn the screen to and fro.
     */
    fun restore(forced: Boolean, recreating: Boolean, survivesRecreation: Boolean): Boolean =
        forced && !(recreating && survivesRecreation)
}

/**
 * Full screen of the viewer: the system bars hidden (swipe from the edge shows them for a moment) and, for a video wider
 * than high, landscape — back as it was when [on] goes or the viewer closes. [survivesRecreation]: the viewer is shown
 * again after the activity was made anew (its state outside the composition), landscape is kept over that.
 */
@Composable
internal fun ViewerFullscreen(on: Boolean, landscape: Boolean, survivesRecreation: Boolean = false) {
    val view = LocalView.current
    val window: Window? = (view.parent as? DialogWindowProvider)?.window
    val activity = LocalContext.current.findActivity()
    DisposableEffect(on, landscape, window) {
        val before = activity?.requestedOrientation?.let(ViewerOrientation::before)
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (on) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            if (landscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            val recreating = activity?.isChangingConfigurations == true
            if (before != null && ViewerOrientation.restore(on && landscape, recreating, survivesRecreation)) activity?.requestedOrientation = before
        }
    }
}

/**
 * The player's picture and controls (play / pause, seek, sound, the full screen button: [fullscreen] / [onFullscreen]),
 * the video fitted into the whole of [modifier] — one for every video of Drive: streamed ([DriveVideoPage]) and of a
 * vault (DriveVaultViewer.kt). [secure]: the video's surface is a window of its own and kept out of screenshots as
 * well (FLAG_SECURE of the screen does not reach it).
 */
@OptIn(UnstableApi::class)
@Composable
internal fun DriveVideoSurface(
    player: Player,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    secure: Boolean = false,
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                // before the surface is attached to the window: secure from its first frame
                if (secure) (videoSurfaceView as? SurfaceView)?.setSecure(true)
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        },
        update = { v ->
            v.player = player
            v.setFullscreenButtonState(fullscreen)
            v.setFullscreenButtonClickListener { onFullscreen(it) }
        },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

/** Which player a video page wants: the file, its version and the try (a new try, a new player). */
internal data class DriveVideoKey(val id: Long, val rev: Int, val attempt: Int)

private sealed interface VideoState {
    data object Loading : VideoState
    data object Ready : VideoState
    data class Refused(val code: String) : VideoState
    data object CannotPlay : VideoState
    data object Network : VideoState
}

/**
 * A video page: the preview with a play button until it is the page shown, then the player — streamed from the own
 * server (Range), the place it was left, the player's controls (play / pause, seek, sound) and full screen
 * ([fullscreen] / [onFullscreen]). A blocked file shows its reason; a marked one plays only after asking; a format the
 * device cannot play says so and offers [onOpenOther].
 */
@OptIn(UnstableApi::class)
@Composable
internal fun DriveVideoPage(
    node: DNode,
    active: Boolean,
    media: DriveMediaViewModel,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onOpenOther: () -> Unit,
    onLandscape: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val ask = rememberScanAsk()
    // kept over a fold, an unfold or a turn (the activity made anew), as the player is
    var allowed by rememberSaveable(node.id) { mutableStateOf(node.scan?.warn != true) }
    var attempt by rememberSaveable(node.id) { mutableIntStateOf(0) }
    var state by remember(node.id) { mutableStateOf<VideoState>(VideoState.Loading) }
    var noPicture by remember(node.id) { mutableStateOf(false) }
    var player by remember(node.id) { mutableStateOf<ExoPlayer?>(null) }
    val play = active && allowed && !node.blocked
    val key = DriveVideoKey(node.id, node.rev, attempt)
    val landscape by rememberUpdatedState(onLandscape)

    LaunchedEffect(play, key) {
        if (!play) { player = null; return@LaunchedEffect }
        // back after the activity was made anew: the player the view model kept, where it was
        media.players.peek(key)?.let { player = it; return@LaunchedEffect }
        state = VideoState.Loading
        when (val r = media.stream(node)) {
            is DResult.Ok -> {
                val factory = media.sourceFactory() ?: return@LaunchedEffect
                // the application's context: the player outlives this activity
                val app = context.applicationContext
                player = media.players.get(key) {
                    ExoPlayer.Builder(app).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
                        setMediaItem(MediaItem.Builder().setUri(r.value.url).build())
                        media.places.get(node)?.let { seekTo(it) }
                        prepare()
                        playWhenReady = true
                    }
                }
            }
            is DResult.Failed -> state = VideoState.Refused(r.code)
            DResult.Offline -> state = VideoState.Network
            DResult.NotConfigured -> state = VideoState.Refused("not_configured")
        }
    }
    ReleaseWhenGone(media.players, player) { p ->
        media.places.keep(node, p.currentPosition, p.duration.takeIf { it != C.TIME_UNSET } ?: 0L)
    }
    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose {}
        fun tracks(tracks: Tracks) {
            // a picture the device cannot decode (often HEVC): only the sound plays — say so
            val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            noPicture = video.isNotEmpty() && video.none { it.isSupported }
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) state = VideoState.Ready
                if (playbackState == Player.STATE_ENDED) media.places.forget(node)
            }

            override fun onPlayerError(error: PlaybackException) {
                state = when (DriveMediaLogic.trouble(error.errorCode)) {
                    DriveMediaLogic.Trouble.CANNOT_PLAY -> VideoState.CannotPlay
                    DriveMediaLogic.Trouble.NETWORK -> VideoState.Network
                    DriveMediaLogic.Trouble.OTHER -> VideoState.CannotPlay
                }
            }

            override fun onTracksChanged(tracks: Tracks) = tracks(tracks)

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                vaultVideoWide(videoSize)?.let { landscape(it) }
            }
        }
        p.addListener(listener)
        // a kept player (after a fold or a turn): what it knows already
        if (p.playbackState == Player.STATE_READY) state = VideoState.Ready
        p.playerError?.let { listener.onPlayerError(it) }
        tracks(p.currentTracks)
        vaultVideoWide(p.videoSize)?.let { landscape(it) }
        onDispose { p.removeListener(listener) }
    }
    Box(Modifier.fillMaxSize()) {
        if (node.blocked) { HeldBack(node); return@Box }
        val s = state
        val shown = player
        if (shown != null && s != VideoState.CannotPlay) {
            DriveVideoSurface(shown, fullscreen, onFullscreen, Modifier.fillMaxSize())
        } else if (s != VideoState.CannotPlay && s !is VideoState.Refused && s != VideoState.Network) {
            // the preview until it plays
            val poster = rememberDrivePreview(node, large = true) ?: rememberDrivePreview(node)
            if (poster != null) Image(poster, contentDescription = node.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            if (!allowed) {
                Column(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScanLine(node.scan)
                    TextButton(onClick = { ask(node.scan) { allowed = true } }) { Text(stringResource(R.string.drive_viewer_play_anyway), color = Color.White) }
                }
            } else if (active) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else {
                Box(Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Icon(GcIcons.Play, contentDescription = stringResource(R.string.drive_viewer_play), tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
        }
        val note = when {
            s == VideoState.CannotPlay -> stringResource(R.string.drive_viewer_cannot_play, DriveMediaLogic.typeName(node))
            s == VideoState.Network -> stringResource(R.string.drive_viewer_network)
            s is VideoState.Refused -> driveText(context, s.code)
            noPicture -> stringResource(R.string.drive_viewer_no_picture)
            else -> null
        }
        if (note != null) {
            val blocking = s == VideoState.CannotPlay || s == VideoState.Network || s is VideoState.Refused
            Column(
                Modifier.align(if (blocking) Alignment.Center else Alignment.BottomCenter).padding(24.dp).padding(bottom = if (blocking) 0.dp else 72.dp)
                    .widthIn(max = 420.dp).background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.medium).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(note, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                if (s == VideoState.Network) TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.drive_viewer_retry), color = Color.White) }
                if (s == VideoState.CannotPlay || noPicture) {
                    TextButton(onClick = onOpenOther) {
                        Text(stringResource(R.string.drive_viewer_open_other), color = Color.White)
                    }
                }
            }
        }
    }
}
