package com.gatecontrol.android.ui.media

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaNet
import com.gatecontrol.android.media.MediaPhoneLogic
import com.gatecontrol.android.media.MediaPlay
import com.gatecontrol.android.media.MediaPlayLine
import com.gatecontrol.android.media.MediaPlayerChoices
import com.gatecontrol.android.media.MediaPlayerLogic
import com.gatecontrol.android.media.MediaPlayerTrouble
import com.gatecontrol.android.media.MediaPlayerUi
import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaQualitySetting
import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaTracks
import com.gatecontrol.android.media.player.PlayerTrack
import com.gatecontrol.android.media.player.PlayerTrackMenu
import com.gatecontrol.android.media.player.PlayerTracks
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.delay

/*
 * The player of Medien on the phone (mockups 4): upright the picture on top with the infos and the next episodes
 * below, turned in full screen. A tap shows the controls, a double tap left or right jumps 10 s, a swipe at the left
 * edge sets the brightness, at the right the volume (as the Drive player), the lock blocks all of it. The sheet
 * "Ton & Untertitel" has the sound, the subtitles and the quality. The info line says what the NAS does.
 */

/** What the player's screen can do. */
class MediaPlayerActions(
    val onClose: () -> Unit = {},
    val onPip: () -> Unit = {},
    val onFullscreen: (Boolean) -> Unit = {},
    val onAudio: (Int) -> Unit = {},
    val onSubtitle: (Int) -> Unit = {},
    val onQuality: (MediaQualitySetting) -> Unit = {},
    val onTrack: (PlayerTrack) -> Unit = {},
    val onSubtitlesOff: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPlayAt: (Int) -> Unit = {},
    val onCancelCountdown: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onMobileHintRead: () -> Unit = {},
)

/** The parts of the info line with their tone: the network, what the NAS does, the sound. */
@Composable
internal fun lineParts(line: MediaPlayLine): List<Pair<String, GcTone>> = buildList {
    val bw = line.measuredBps?.let { MediaQuality.label(it) }
    when (line.net) {
        MediaNet.HOME -> add(stringResource(R.string.media_player_net_home) to GcTone.Ok)
        MediaNet.AWAY -> add(listOfNotNull(stringResource(R.string.media_player_net_away), bw).joinToString(" · ") to GcTone.Info)
        MediaNet.CELLULAR -> add(listOfNotNull(stringResource(R.string.media_player_net_cellular), bw).joinToString(" · ") to GcTone.Warn)
        null -> Unit
    }
    when {
        line.share != null -> add(stringResource(R.string.media_player_line_share, line.share) to GcTone.Ok)
        line.converted -> add(
            (line.convertedBps?.let { stringResource(R.string.media_player_line_converted, MediaQuality.resolution(it), MediaQuality.label(it)) }
                ?: stringResource(R.string.media_player_line_converted_plain)) to GcTone.Warn,
        )
        else -> add((line.video?.let { stringResource(R.string.media_player_line_direct_video, it) } ?: stringResource(R.string.media_player_line_direct)) to GcTone.Ok)
    }
    line.audioTo?.let { add(stringResource(R.string.media_player_line_audio, it) to GcTone.Warn) }
}

/** The whole player: the picture (with its controls) and, upright, the infos below. */
@OptIn(UnstableApi::class)
@Composable
fun MediaPlayerScreen(
    ui: MediaPlayerUi,
    player: Player?,
    landscape: Boolean,
    pip: Boolean,
    mobileCap: Long?,
    actions: MediaPlayerActions,
) {
    var sheet by rememberSaveable { mutableStateOf(false) }
    var tracks by remember { mutableStateOf(player?.currentTracks ?: Tracks.EMPTY) }
    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose {}
        val l = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) {
                tracks = t
            }
        }
        p.addListener(l)
        tracks = p.currentTracks
        onDispose { p.removeListener(l) }
    }
    if (pip) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { if (player != null) PlayerSurface(player) }
        return
    }
    val menu = remember(tracks) { PlayerTracks.menu(PlayerTracks.of(tracks)) }
    if (landscape) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoArea(ui, player, landscape = true, onSheet = { sheet = true }, actions = actions)
        }
    } else {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(Modifier.fillMaxWidth().background(Color.Black).statusBarsPadding().aspectRatio(16f / 9f)) {
                VideoArea(ui, player, landscape = false, onSheet = { sheet = true }, actions = actions)
            }
            Infos(ui, onPlayAt = actions.onPlayAt, onSheet = { sheet = true })
        }
    }
    if (sheet) {
        TrackSheet(ui, menu, actions, onDismiss = { sheet = false })
    }
    if (ui.mobileHint) {
        MobileHint(mobileCap, actions.onMobileHintRead)
    }
}

/** The picture of [player], fitted, with its subtitles. */
@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(player: Player) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

/** The picture with the gestures, the controls, the lock and the notes over it. */
@Composable
private fun BoxScope.VideoArea(ui: MediaPlayerUi, player: Player?, landscape: Boolean, onSheet: () -> Unit, actions: MediaPlayerActions) {
    val context = LocalContext.current
    var controls by remember { mutableStateOf(true) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var touched by remember { mutableIntStateOf(0) }
    var flash by remember { mutableStateOf<String?>(null) }
    var flashAt by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(player?.isPlaying == true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val back = stringResource(R.string.media_player_back_short)
    val ahead = stringResource(R.string.media_player_ahead_short)
    val nowPlayer by rememberUpdatedState(player)
    // "Helligkeit %1$d %%", formatted while swiping
    val brightnessFormat = stringResource(R.string.media_player_brightness)
    val volumeFormat = stringResource(R.string.media_player_volume)

    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            playing = p.isPlaying
            position = p.currentPosition
            duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0
            delay(if (controls) 500 else 2_000)
        }
    }
    LaunchedEffect(controls, playing, touched, locked) {
        if (!controls || !playing) return@LaunchedEffect
        delay(MediaPlayerLogic.CONTROLS_HIDE_MS)
        controls = false
    }
    LaunchedEffect(flashAt) {
        if (flash == null) return@LaunchedEffect
        delay(900)
        flash = null
    }
    BackHandler(enabled = locked) { controls = true; touched++ }

    fun seek(delta: Long) {
        val p = nowPlayer ?: return
        p.seekTo(MediaPlayerLogic.seekTarget(p.currentPosition, delta, p.duration.takeIf { it != C.TIME_UNSET } ?: 0))
        position = p.currentPosition
    }

    if (player != null) PlayerSurface(player)
    // the gestures over the whole picture (the controls lie on top)
    Box(
        Modifier.fillMaxSize()
            .pointerInput(locked) {
                if (locked) return@pointerInput
                var level = 0f
                var left = true
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        left = o.x < size.width / 2f
                        level = if (left) brightnessOf(context) else volumeOf(context)
                    },
                ) { change, dy ->
                    change.consume()
                    level = MediaPlayerLogic.swipe(level, dy, size.height.toFloat())
                    val percent = (level * 100).toInt()
                    flash = if (left) {
                        setBrightness(context, MediaPlayerLogic.brightness(level, 0f, 1f))
                        String.format(brightnessFormat, percent)
                    } else {
                        setVolume(context, level)
                        String.format(volumeFormat, percent)
                    }
                    flashAt++
                }
            }
            .pointerInput(locked) {
                detectTapGestures(
                    onTap = {
                        controls = !controls
                        touched++
                    },
                    onDoubleTap = { o ->
                        if (locked) return@detectTapGestures
                        when (MediaPlayerLogic.zone(o.x, size.width.toFloat())) {
                            -1 -> { seek(-MediaPlayerLogic.SEEK_STEP_MS); flash = back; flashAt++ }
                            1 -> { seek(MediaPlayerLogic.SEEK_STEP_MS); flash = ahead; flashAt++ }
                            else -> { controls = !controls; touched++ }
                        }
                    },
                )
            },
    )
    if (ui.loading && ui.trouble == null) CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center).size(40.dp))
    flash?.let { text ->
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
    if (locked) {
        if (controls) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundButton(GcIcons.LockOpen, stringResource(R.string.media_player_unlock), big = true) { locked = false; touched++ }
                Text(stringResource(R.string.media_player_locked), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    } else if (controls && ui.trouble == null && ui.countdown == null) {
        Controls(
            ui = ui,
            landscape = landscape,
            playing = playing,
            position = dragging?.let { (it * duration).toLong() } ?: position,
            duration = duration,
            onToggle = {
                touched++
                nowPlayer?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } }
                playing = !playing
            },
            onSeek = { seek(it); touched++ },
            onDrag = { dragging = it; touched++ },
            onDragDone = {
                val p = nowPlayer
                val to = dragging
                if (p != null && to != null && duration > 0) p.seekTo((to * duration).toLong())
                dragging = null
            },
            onSheet = onSheet,
            onLock = { locked = true; controls = false },
            actions = actions,
        )
    }
    ui.trouble?.let { Trouble(it, actions) }
    ui.countdown?.let { Countdown(ui, it, actions) }
    ui.resumedAt?.let { at ->
        if (!controls && !locked) {
            Text(
                stringResource(R.string.media_player_resumed, MediaPhoneLogic.clock(at)),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** The bars over the picture: top (close, title upright only in full screen, the sheet, PiP, lock), the middle, the bottom. */
@Composable
private fun BoxScope.Controls(
    ui: MediaPlayerUi,
    landscape: Boolean,
    playing: Boolean,
    position: Long,
    duration: Long,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onDrag: (Float) -> Unit,
    onDragDone: () -> Unit,
    onSheet: () -> Unit,
    onLock: () -> Unit,
    actions: MediaPlayerActions,
) {
    val shade = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.7f)))
    Box(Modifier.fillMaxSize().background(shade).testTag(CONTROLS_TAG))
    val pad = if (landscape) 16.dp else 8.dp
    Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = pad, vertical = pad), verticalAlignment = Alignment.Top) {
        RoundButton(GcIcons.ChevronDown, stringResource(R.string.media_player_close)) { actions.onClose() }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (landscape) {
                Text(ui.title.seriesName?.takeIf { ui.title.kind == MediaTitle.Kind.EPISODE } ?: ui.title.name, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subline(ui)?.let { Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                ui.line?.let { line ->
                    Text(lineParts(line).joinToString(" · ") { it.first }, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(LINE_TAG))
                }
            }
        }
        RoundButton(GcIcons.Captions, stringResource(R.string.media_player_tracks), modifier = Modifier.testTag(TRACKS_TAG)) { onSheet() }
        RoundButton(GcIcons.Pip, stringResource(R.string.media_player_pip)) { actions.onPip() }
        RoundButton(GcIcons.Lock, stringResource(R.string.media_player_lock)) { onLock() }
    }
    Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(if (landscape) 40.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(GcIcons.Back10, stringResource(R.string.media_player_back10)) { onSeek(-MediaPlayerLogic.SEEK_STEP_MS) }
        RoundButton(if (playing) GcIcons.Pause else GcIcons.Play, stringResource(if (playing) R.string.media_player_pause else R.string.media_player_play), big = true) { onToggle() }
        RoundButton(GcIcons.Ahead10, stringResource(R.string.media_player_ahead10)) { onSeek(MediaPlayerLogic.SEEK_STEP_MS) }
    }
    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = pad, vertical = if (landscape) 12.dp else 2.dp)) {
        Slider(
            value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = onDrag,
            onValueChangeFinished = onDragDone,
            enabled = duration > 0,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth().heightIn(max = 28.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(MediaPhoneLogic.clock(position), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text("/ " + MediaPhoneLogic.clock(duration), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            if (landscape) {
                ui.choices?.takeIf { it.qualities.isNotEmpty() }?.let { c ->
                    Pill(stringResource(R.string.media_player_quality_chip, qualityShort(c.quality))) { onSheet() }
                }
                if (ui.hasNext) Pill(stringResource(R.string.media_player_next), GcIcons.SkipNext) { actions.onNext() }
            }
            RoundButton(if (landscape) GcIcons.Shrink else GcIcons.Expand, stringResource(if (landscape) R.string.media_player_fullscreen_exit else R.string.media_player_fullscreen), small = true) {
                actions.onFullscreen(!landscape)
            }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, modifier: Modifier = Modifier, big: Boolean = false, small: Boolean = false, onClick: () -> Unit) {
    val size = when {
        big -> 64.dp
        small -> 36.dp
        else -> 44.dp
    }
    Box(
        modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = if (big) 0.45f else 0.25f)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(if (big) 32.dp else if (small) 18.dp else 22.dp))
    }
}

@Composable
private fun Pill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.16f)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/** "Staffel 2 · Folge 4 · Jellyfin · DS918+" / "2025 · 1 Std. 48 Min. · DS918+". */
@Composable
private fun subline(ui: MediaPlayerUi): String? {
    val t = ui.title
    val parts = buildList {
        val season = t.season
        val number = t.episode
        if (t.kind == MediaTitle.Kind.EPISODE && season != null && number != null) add(stringResource(R.string.media_player_episode, season, number))
        else {
            t.year?.let { add(it.toString()) }
            t.runtimeMs?.takeIf { it > 0 }?.let { add(runtimeText(it)) }
        }
        val src = if (ui.share) ui.sourceName else ui.sourceName?.let { "Jellyfin · $it" }
        src?.let { add(it) }
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

/** Upright below the picture: the title, the info line, the text and the next episodes. */
@Composable
private fun Infos(ui: MediaPlayerUi, onPlayAt: (Int) -> Unit, onSheet: () -> Unit) {
    val x = GateControlTheme.extraColors
    val t = ui.title
    val next = MediaPlayerLogic.nextUp(ui.queue, ui.at)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "head") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (t.kind == MediaTitle.Kind.EPISODE && t.seriesName != null) "${t.seriesName} · ${t.name}" else t.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subline(ui)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted) }
            }
        }
        ui.line?.let { line ->
            item(key = "line") {
                val parts = lineParts(line)
                val limit = line.limitBps
                LazyRow(Modifier.testTag(LINE_TAG), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // the network last upright: what plays first (as the mockup)
                    items(parts.drop(1) + parts.take(1), key = { it.first }) { (text, tone) -> GcChip(text = text, tone = tone) }
                    if (limit != null) item(key = "limit") { GcChip(text = stringResource(R.string.media_mobile_limit_chip, MediaQuality.label(limit)), tone = GcTone.Warn) }
                }
            }
        }
        if (ui.choices != null || ui.share) {
            item(key = "tracks") {
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onSheet).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(GcIcons.Captions, contentDescription = null, tint = x.muted, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.media_player_tracks), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(GcIcons.ChevronRight, contentDescription = null, tint = x.faint, modifier = Modifier.size(18.dp))
                }
            }
        }
        t.overview?.takeIf { it.isNotBlank() }?.let { text ->
            item(key = "overview") { Text(text, style = MaterialTheme.typography.bodyMedium, color = x.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        }
        if (next.isNotEmpty()) {
            item(key = "next-head") { MediaRowHead(stringResource(R.string.media_player_up_next)) }
            items(next, key = { "n:" + it.second.id }) { (i, e) -> NextRow(e) { onPlayAt(i) } }
        }
    }
}

@Composable
private fun NextRow(e: MediaTitle, onPlay: () -> Unit) {
    val x = GateControlTheme.extraColors
    val picture = rememberMediaPicture(e, backdrop = false)
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onPlay).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(10.dp)).background(x.panel2)) {
            if (picture != null) androidx.compose.foundation.Image(picture, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Icon(GcIcons.Play, contentDescription = stringResource(R.string.media_play), tint = Color.White, modifier = Modifier.align(Alignment.Center).size(20.dp))
            if (e.resumable) MediaProgressBar(e.progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = 6.dp, vertical = 4.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text((e.episode?.let { "$it. " }.orEmpty()) + e.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            e.runtimeMs?.takeIf { it > 0 }?.let { Text(runtimeText(it), style = MaterialTheme.typography.bodySmall, color = x.muted) }
        }
    }
}

/** "Nächste Folge in 8 s" with "Jetzt abspielen" and "Abbrechen". */
@Composable
private fun BoxScope.Countdown(ui: MediaPlayerUi, seconds: Int, actions: MediaPlayerActions) {
    val next = ui.queue.items.getOrNull(ui.at + 1) ?: return
    Column(
        Modifier.align(Alignment.BottomEnd).padding(12.dp).widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(14.dp).testTag(COUNTDOWN_TAG),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.media_player_next_in, seconds), color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(MediaPlayerLogic.name(next), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions.onNext) { Text(stringResource(R.string.media_player_next_now), color = Color.White) }
            TextButton(onClick = actions.onCancelCountdown) { Text(stringResource(R.string.media_player_next_cancel), color = Color.White.copy(alpha = 0.8f)) }
        }
    }
}

@Composable
private fun BoxScope.Trouble(trouble: MediaPlayerTrouble, actions: MediaPlayerActions) {
    val text = stringResource(
        when (trouble) {
            MediaPlayerTrouble.NETWORK -> R.string.media_player_network
            MediaPlayerTrouble.SIGNED_OUT -> R.string.media_player_signed_out
            MediaPlayerTrouble.FAILED -> R.string.media_player_failed
            MediaPlayerTrouble.CANNOT_PLAY -> R.string.media_player_cannot_play
        },
    )
    Column(
        Modifier.align(Alignment.Center).padding(16.dp).widthIn(max = 420.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (trouble != MediaPlayerTrouble.SIGNED_OUT) TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.media_player_retry), color = Color.White) }
            TextButton(onClick = actions.onClose) { Text(stringResource(R.string.media_player_close), color = Color.White.copy(alpha = 0.8f)) }
        }
    }
}

/** The note on the data volume, once (question 28). */
@Composable
private fun MobileHint(cap: Long?, onRead: () -> Unit) {
    AlertDialog(
        onDismissRequest = onRead,
        title = { Text(stringResource(R.string.media_player_mobile_title)) },
        text = {
            Text(
                if (cap != null) stringResource(R.string.media_player_mobile_text, MediaQuality.label(cap), MediaPhoneLogic.gbPerHour(cap))
                else stringResource(R.string.media_player_mobile_text_none),
            )
        },
        confirmButton = { TextButton(onClick = onRead, modifier = Modifier.testTag(MOBILE_HINT_TAG)) { Text(stringResource(R.string.media_player_mobile_ok)) } },
    )
}

/** The short name of a quality ("Automatisch", "Original", "720p · 4 Mbit/s"). */
@Composable
private fun qualityShort(q: MediaQualitySetting): String = when (q) {
    MediaQualitySetting.AUTO -> stringResource(R.string.media_player_quality_auto)
    MediaQualitySetting.ORIGINAL -> stringResource(R.string.media_player_quality_original)
    else -> stringResource(R.string.media_player_quality_fixed, MediaQuality.resolution(q.bps!!), MediaQuality.label(q.bps!!))
}

/** What "Automatisch" plays now: "zu Hause Original", "jetzt 720p · 3 Mbit/s", "jetzt Original". */
@Composable
private fun autoNow(line: MediaPlayLine?): String? {
    line ?: return null
    return when {
        line.net == MediaNet.HOME && !line.converted -> stringResource(R.string.media_player_quality_home)
        line.converted && line.convertedBps != null -> stringResource(R.string.media_player_quality_now, "${MediaQuality.resolution(line.convertedBps)} · ${MediaQuality.label(line.convertedBps)}")
        else -> stringResource(R.string.media_player_quality_now_original)
    }
}

/** The sheet "Ton & Untertitel" (mockup 4): sound, subtitles and quality; a share's file with its own tracks. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackSheet(ui: MediaPlayerUi, menu: PlayerTrackMenu, actions: MediaPlayerActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState()).testTag(SHEET_TAG)) {
            val c = ui.choices
            if (c != null) JellyfinChoices(ui, c, actions, onDismiss) else ShareChoices(menu, actions, onDismiss)
        }
    }
}

@Composable
private fun SheetHead(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = GateControlTheme.extraColors.muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun JellyfinChoices(ui: MediaPlayerUi, c: MediaPlayerChoices, actions: MediaPlayerActions, onDismiss: () -> Unit) {
    var qualityOpen by rememberSaveable { mutableStateOf(false) }
    if (c.audio.isNotEmpty()) {
        SheetHead(stringResource(R.string.media_audio))
        c.audio.forEach { s ->
            Choice(audioName(s), playerAudioNote(s, c), s.index == c.audioIndex) { onDismiss(); actions.onAudio(s.index) }
        }
    }
    SheetHead(stringResource(R.string.media_subtitles))
    Choice(stringResource(R.string.media_subtitles_off), null, c.subtitlesOff) { onDismiss(); actions.onSubtitle(MediaPlay.SUBTITLES_OFF) }
    c.subtitles.forEach { s ->
        val note = if (s.picture && c.canConvert) stringResource(R.string.media_subtitle_burn_in) else stringResource(R.string.media_player_direct)
        Choice(subtitleName(s), note, s.index == c.subtitleIndex) { onDismiss(); actions.onSubtitle(s.index) }
    }
    SheetHead(stringResource(R.string.media_player_quality))
    if (c.qualities.isEmpty()) {
        Text(stringResource(R.string.media_player_quality_only_original), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        return
    }
    val now = autoNow(ui.line)
    val head = listOfNotNull(qualityShort(c.quality), if (c.quality == MediaQualitySetting.AUTO) now else null).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { qualityOpen = !qualityOpen }.padding(horizontal = 20.dp, vertical = 8.dp).testTag(QUALITY_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(head, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(if (qualityOpen) GcIcons.ChevronUp else GcIcons.ChevronRight, contentDescription = null, tint = GateControlTheme.extraColors.faint, modifier = Modifier.size(18.dp))
    }
    ui.line?.limitBps?.let { Text(stringResource(R.string.media_mobile_limit_chip, MediaQuality.label(it)), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.warn, modifier = Modifier.padding(horizontal = 20.dp)) }
    if (qualityOpen) {
        c.qualities.forEach { q ->
            Choice(qualityShort(q), if (q == MediaQualitySetting.AUTO) now else null, q == c.quality) { onDismiss(); actions.onQuality(q) }
        }
    }
}

/** "NAS rechnet in AAC 5.1 um" or "direkt" (with the track's own title: "Hörfilm-Fassung · direkt"). */
@Composable
private fun playerAudioNote(s: MediaStream, c: MediaPlayerChoices): String {
    val n = MediaPhoneLogic.audioNote(s, c.caps)
    if (n.converted) {
        return if (c.canConvert) stringResource(R.string.media_player_audio_converts, n.channels.orEmpty()) else stringResource(R.string.media_audio_unplayable, n.codec ?: "?")
    }
    val own = s.title?.takeIf { s.language != null && it.isNotBlank() && !it.contains(MediaTracks.audioLabel(s), ignoreCase = true) }
    return listOfNotNull(own, stringResource(R.string.media_player_direct)).joinToString(" · ")
}

@Composable
private fun ShareChoices(menu: PlayerTrackMenu, actions: MediaPlayerActions, onDismiss: () -> Unit) {
    val trackWord = stringResource(R.string.media_audio)
    val subtitleWord = stringResource(R.string.media_subtitle)
    if (menu.audio.isNotEmpty()) {
        SheetHead(stringResource(R.string.media_audio))
        menu.audio.forEachIndexed { n, t -> Choice(PlayerTracks.name(t, n + 1, trackWord), null, t.selected) { onDismiss(); actions.onTrack(t) } }
    }
    SheetHead(stringResource(R.string.media_subtitles))
    Choice(stringResource(R.string.media_subtitles_off), null, menu.textOff) { onDismiss(); actions.onSubtitlesOff() }
    menu.text.forEachIndexed { n, t -> Choice(PlayerTracks.name(t, n + 1, subtitleWord), null, t.selected) { onDismiss(); actions.onTrack(t) } }
    SheetHead(stringResource(R.string.media_player_quality))
    Text(stringResource(R.string.media_player_quality_only_original), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun Choice(title: String, note: String?, selected: Boolean, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primary else x.panel2), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimary))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted) }
        }
    }
}

/** The window's brightness now (0..1; the system's: read as half). */
private fun brightnessOf(context: Context): Float {
    val w = (context as? Activity)?.window ?: return 0.5f
    val b = w.attributes.screenBrightness
    return if (b < 0f) 0.5f else b
}

private fun setBrightness(context: Context, level: Float) {
    val w = (context as? Activity)?.window ?: return
    w.attributes = w.attributes.apply { screenBrightness = level }
}

/** The media volume now, 0..1. */
private fun volumeOf(context: Context): Float {
    val am = context.getSystemService(AudioManager::class.java) ?: return 0.5f
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun setVolume(context: Context, level: Float) {
    val am = context.getSystemService(AudioManager::class.java) ?: return
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, MediaPlayerLogic.volumeStep(level, max), 0) }
}

internal const val CONTROLS_TAG = "media-player-controls"
internal const val LINE_TAG = "media-player-line"
internal const val TRACKS_TAG = "media-player-tracks"
internal const val SHEET_TAG = "media-player-sheet"
internal const val QUALITY_TAG = "media-player-quality"
internal const val COUNTDOWN_TAG = "media-player-countdown"
internal const val MOBILE_HINT_TAG = "media-player-mobile-hint"
