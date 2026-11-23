package com.gatecontrol.android.tv.ui.drive

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveMediaLogic
import com.gatecontrol.android.drive.DriveVideoPlaces
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.drive.TvDriveLogic
import com.gatecontrol.android.tv.drive.TvDriveMedia
import com.gatecontrol.android.tv.player.TvPlayerAction
import com.gatecontrol.android.tv.player.TvPlayerKeys
import com.gatecontrol.android.tv.player.TvTrack
import com.gatecontrol.android.tv.player.TvTrackMenu
import com.gatecontrol.android.tv.player.TvTracks
import com.gatecontrol.android.tv.ui.components.TvPill
import com.gatecontrol.android.tv.ui.components.TvPillTone
import com.gatecontrol.android.tv.ui.home.TvIcons
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import kotlinx.coroutines.delay

/**
 * The player of Drive's videos (mockup 4 and 9): streams with Range from the own server, keys of the remote
 * ([TvPlayerKeys]): OK / ⏯ play and pause, ◀ ▶ ⏪ ⏩ ±10 s, ⏭ ⏮ the next / previous video of the folder, ▲ or ☰ the panel
 * "Ton & Untertitel", ▼ the bar, Zurück closes. Where a video was left is kept ([DriveVideoPlaces]).
 */
@OptIn(UnstableApi::class)
@Composable
fun TvVideoPlayer(
    items: List<DNode>,
    at: Int,
    media: TvDriveMedia,
    places: DriveVideoPlaces,
    onAt: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val colors = GateControlTvTheme.colors
    val node = items[at]
    val factory = remember { media.sourceFactory() }
    val player = remember(factory) {
        factory?.let { ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(it)).build() }
    }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var trouble by remember(node.id) { mutableStateOf<String?>(null) }
    var resumedAt by remember(node.id) { mutableStateOf<Long?>(null) }
    var format by remember(node.id) { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf(false) }
    var touched by remember { mutableIntStateOf(0) }
    var controls by remember { mutableStateOf(true) }
    val root = remember { FocusRequester() }
    val cannotPlay = stringResource(R.string.tv_player_cannot_play, DriveMediaLogic.typeName(node))
    val network = stringResource(R.string.tv_player_network)
    val failed = stringResource(R.string.tv_viewer_failed)
    // the listener lives as long as the player: it reads the video shown now
    val nowNode by rememberUpdatedState(node)
    val nowAt by rememberUpdatedState(at)
    val nowOnAt by rememberUpdatedState(onAt)

    fun keep() {
        val p = player ?: return
        if (p.playbackState != Player.STATE_IDLE) places.keep(node, p.currentPosition, p.duration.takeIf { it != C.TIME_UNSET } ?: 0)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onTracksChanged(t: Tracks) {
                tracks = t
                format = player?.videoFormat?.let { f -> TvPlayerUi.formatLabel(f.height, f.sampleMimeType) }
            }

            override fun onPlayerError(error: PlaybackException) {
                trouble = when (DriveMediaLogic.trouble(error.errorCode)) {
                    DriveMediaLogic.Trouble.CANNOT_PLAY -> cannotPlay
                    DriveMediaLogic.Trouble.NETWORK -> network
                    DriveMediaLogic.Trouble.OTHER -> failed
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                // the end of a video: the next one of the folder, as the bar's "Nächstes Video"
                if (state == Player.STATE_ENDED) {
                    places.forget(nowNode)
                    if (nowAt < items.size - 1) nowOnAt(nowAt + 1)
                }
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    // the video of [node]: its stream first (the rights, the virus scan), then from where it was left
    LaunchedEffect(player, node.id) {
        val p = player ?: run {
            trouble = failed
            return@LaunchedEffect
        }
        p.stop()
        p.clearMediaItems()
        trouble = null
        when (val s = media.stream(node)) {
            is DResult.Ok -> {
                val item = MediaItem.Builder().setUri(s.value.url).apply { s.value.mime?.let(::setMimeType) }.build()
                val from = places.get(node)
                if (from != null) {
                    p.setMediaItem(item, from)
                    resumedAt = from
                } else {
                    p.setMediaItem(item)
                }
                p.prepare()
                p.playWhenReady = true
            }
            DResult.Offline -> trouble = network
            else -> trouble = failed
        }
    }
    // the bar's times while it shows (and where the video is, for "Weiter ab")
    LaunchedEffect(player, controls) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            position = p.currentPosition
            duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0
            buffered = p.bufferedPosition
            delay(if (controls) 500 else 5_000)
            if (!controls) keep()
        }
    }
    LaunchedEffect(touched, playing, panel) {
        controls = true
        if (!playing || panel) return@LaunchedEffect
        delay(TvPlayerKeys.CONTROLS_HIDE_MS)
        controls = false
    }
    LaunchedEffect(resumedAt) {
        if (resumedAt != null) {
            delay(5_000)
            resumedAt = null
        }
    }
    LaunchedEffect(panel) { if (!panel) root.requestSoon() }
    DisposableEffect(node.id) { onDispose { keep() } }

    val menu = remember(tracks) { TvTracks.menu(TvTracks.of(tracks)) }

    fun close() {
        keep()
        onClose()
    }

    fun act(action: TvPlayerAction, repeat: Int): Boolean {
        val p = player
        touched++
        when (action) {
            TvPlayerAction.TOGGLE -> p?.let { if (it.isPlaying) it.pause() else it.play() }
            TvPlayerAction.PLAY -> p?.play()
            TvPlayerAction.PAUSE -> p?.pause()
            TvPlayerAction.SEEK_BACK, TvPlayerAction.SEEK_FORWARD -> p?.let {
                val step = TvPlayerKeys.seekStep(repeat) * if (action == TvPlayerAction.SEEK_BACK) -1 else 1
                it.seekTo(TvPlayerKeys.seekTarget(it.currentPosition, step, it.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0))
                position = it.currentPosition
            }
            TvPlayerAction.NEXT -> if (at < items.size - 1) {
                keep()
                onAt(TvDriveLogic.step(at, 1, items.size))
            }
            TvPlayerAction.PREVIOUS -> {
                // as other players: back to the start first, then the video before
                val p0 = p?.currentPosition ?: 0
                if (p0 > 5_000 || at == 0) p?.seekTo(0) else {
                    keep()
                    onAt(TvDriveLogic.step(at, -1, items.size))
                }
            }
            TvPlayerAction.TRACKS -> if (menu.any) panel = true
            TvPlayerAction.SHOW_CONTROLS -> Unit
            TvPlayerAction.BACK -> if (controls && playing) controls = false else close()
            TvPlayerAction.STOP -> close()
        }
        return true
    }

    BackHandler(enabled = panel) { panel = false }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(root)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                // the panel takes the D-pad and OK itself; Zurück closes it (BackHandler)
                if (panel) return@onKeyEvent false
                val action = TvPlayerKeys.action(e.nativeKeyEvent.keyCode) ?: return@onKeyEvent false
                act(action, e.nativeKeyEvent.repeatCount)
            },
    ) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        isFocusable = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        trouble?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(40.dp))
        }
        if (controls || trouble != null) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.3f).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))))
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.42f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))),
            )
            Column(Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 28.dp, end = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(node.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (items.size > 1) TvPill("${at + 1} / ${items.size}", TvPillTone.Neutral, dot = false)
                    format?.let { TvPill(it, TvPillTone.Neutral, dot = false) }
                    resumedAt?.let { TvPill(stringResource(R.string.tv_player_resumed, TvPlayerKeys.time(it)), TvPillTone.Accent) }
                }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 30.dp)) {
                Scrub(position, buffered, duration)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(TvPlayerKeys.time(position), style = MaterialTheme.typography.labelMedium, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    Text(if (duration > 0) TvPlayerKeys.time(duration) else "–", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundIcon(TvIcons.Rewind, main = false)
                    RoundIcon(if (playing) TvIcons.Pause else TvIcons.Play, main = true)
                    RoundIcon(TvIcons.Forward, main = false)
                    Spacer(Modifier.weight(1f))
                    if (menu.any) Hint(stringResource(R.string.tv_player_tracks_hint, TvPlayerUi.textState(menu, stringResource(R.string.tv_player_subtitles_off))))
                    if (at < items.size - 1) Hint(stringResource(R.string.tv_player_next_hint))
                }
            }
        }
        if (panel) TracksPanel(menu, onAudio = { t -> player?.let { p -> p.trackSelectionParameters = TvTracks.choose(p.trackSelectionParameters, p.currentTracks, t) } }, onText = { t ->
            player?.let { p ->
                p.trackSelectionParameters = if (t == null) TvTracks.subtitlesOff(p.trackSelectionParameters) else TvTracks.choose(p.trackSelectionParameters, p.currentTracks, t)
            }
        }, onClose = { panel = false }, modifier = Modifier.align(Alignment.CenterEnd))
    }
    LaunchedEffect(Unit) { root.requestSoon() }
}

/** Texts of the player, kept apart from the composables (unit-tested). */
internal object TvPlayerUi {
    /** "4K · HEVC" from the video's height and codec; null when neither is known. */
    fun formatLabel(height: Int, mime: String?): String? {
        val res = when {
            height >= 2000 -> "4K"
            height >= 1000 -> "1080p"
            height >= 700 -> "720p"
            height > 0 -> "${height}p"
            else -> null
        }
        val codec = when (mime) {
            "video/hevc" -> "HEVC"
            "video/avc" -> "H.264"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/av01" -> "AV1"
            "video/mp4v-es" -> "MPEG-4"
            else -> null
        }
        return listOfNotNull(res, codec).joinToString(" · ").ifEmpty { null }
    }

    /** What the bar says about subtitles: the chosen one's language or label, else [off]. */
    fun textState(menu: TvTrackMenu, off: String): String {
        val t = menu.text.firstOrNull { it.selected } ?: return off
        return TvTracks.name(t, menu.text.indexOf(t) + 1, "")
    }
}

@Composable
private fun Scrub(position: Long, buffered: Long, duration: Long) {
    val colors = GateControlTvTheme.colors
    BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
        val w = maxWidth
        val pos = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val buf = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(50)))
        Box(Modifier.align(Alignment.CenterStart).width(w * buf).height(4.dp).background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(50)))
        Box(Modifier.align(Alignment.CenterStart).width(w * pos).height(4.dp).background(colors.accent, RoundedCornerShape(50)))
        Box(Modifier.align(Alignment.CenterStart).offset(x = w * pos - 7.dp).size(14.dp).background(Color.White, CircleShape))
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, main: Boolean) {
    val size = if (main) 40.dp else 32.dp
    Box(
        Modifier.size(size).background(if (main) Color.White else Color.White.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (main) Color.Black else Color.White, modifier = Modifier.size(size / 2))
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(7.dp)).padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

/** The panel "Ton & Untertitel" on the right (mockup 9): ▲ ▼ choose, OK takes it, Zurück closes. */
@Composable
private fun TracksPanel(
    menu: TvTrackMenu,
    onAudio: (TvTrack) -> Unit,
    onText: (TvTrack?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    Column(
        modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(colors.panel.copy(alpha = 0.96f))
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.tv_player_tracks), style = MaterialTheme.typography.titleMedium, color = colors.text)
        if (menu.audio.size > 1) {
            Text(stringResource(R.string.tv_player_audio), style = MaterialTheme.typography.labelMedium, color = colors.muted, modifier = Modifier.padding(top = 10.dp))
            val fallback = stringResource(R.string.tv_player_track)
            menu.audio.forEachIndexed { i, t ->
                Choice(TvTracks.name(t, i + 1, fallback), t.selected, onClick = { onAudio(t) }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
        if (menu.text.isNotEmpty()) {
            Text(stringResource(R.string.tv_player_subtitles), style = MaterialTheme.typography.labelMedium, color = colors.muted, modifier = Modifier.padding(top = 10.dp))
            Choice(
                stringResource(R.string.tv_player_subtitles_off),
                menu.textOff,
                onClick = { onText(null) },
                modifier = if (menu.audio.size <= 1) Modifier.focusRequester(first) else Modifier,
            )
            val fallback = stringResource(R.string.tv_player_subtitle)
            menu.text.forEachIndexed { i, t -> Choice(TvTracks.name(t, i + 1, fallback), t.selected, onClick = { onText(t) }) }
        }
        Spacer(Modifier.height(10.dp))
        Choice(stringResource(R.string.tv_player_close_panel), false, onClick = onClose)
    }
    LaunchedEffect(Unit) { first.requestSoon() }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(7.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(36.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) colors.accentBg else Color.Transparent,
            contentColor = if (selected) colors.accentText else colors.text,
            focusedContainerColor = colors.panel2,
            focusedContentColor = colors.text,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, colors.focus), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (selected) Text("✓", style = MaterialTheme.typography.labelLarge)
        }
    }
}
