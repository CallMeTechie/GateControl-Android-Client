package com.gatecontrol.android.ui.door

import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.door.HomeCamera
import com.gatecontrol.android.door.HomeCameraKind
import com.gatecontrol.android.door.HomeCameraLogic
import com.gatecontrol.android.door.HomeClip
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.familie.FamilieFormat
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PictureBackground = Color(0xFF1E252C)
private val PillDark = Color(0x73000000)
private val LiveRed = Color(0xD9D23B3B)
private val HHMMSS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * "Kameras" of the door screen (home-network cameras of the door plugin): title with
 * "n online", a chip per camera the person sees, the live picture ("● LIVE · HD · über
 * VPN", name and time) or without VPN a still every 10 s, the buttons Standbild / Vollbild /
 * Ton and "Clips heute". Nothing when the server lists no camera for this person (older
 * GateControl or plugin, no right).
 */
@Composable
fun HomeCamerasSection(viewModel: HomeCameraViewModel, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    val sound by viewModel.sound.collectAsStateWithLifecycle()
    val playing by viewModel.playing.collectAsStateWithLifecycle()
    val stillBusy by viewModel.stillBusy.collectAsStateWithLifecycle()
    HomeCameraEvents(viewModel, snackbar)

    val camera = ui.camera
    if (!ui.loaded || ui.cameras.isEmpty() || camera == null) return
    val extra = GateControlTheme.extraColors

    // The live video: one player while the screen is in front and the server gave an HLS list.
    val wantVideo = active && live.hlsUrl != null && !live.videoFailed && live.cameraId == camera.id
    val player = rememberLivePlayer(wantVideo, viewModel)
    var firstFrame by remember(player) { mutableStateOf(false) }
    var videoHeight by remember(player) { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) viewModel.onVideoPlaying()
            }

            override fun onPlayerError(error: PlaybackException) {
                firstFrame = false
                viewModel.onVideoError()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoHeight = videoSize.height
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player, live.session) {
        val url = live.hlsUrl
        firstFrame = false
        if (player != null && url != null) {
            player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            player.prepare()
            player.playWhenReady = true
        }
    }
    val audio = live.live?.audio == true
    // Quiet while a clip plays over it.
    LaunchedEffect(player, sound, audio, playing) { player?.volume = if (sound && audio && playing == null) 1f else 0f }

    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val showingVideo = player != null && firstFrame

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GcSectionLabel(stringResource(R.string.door_home_title), Modifier.weight(1f))
            val online = HomeCameraLogic.onlineCount(ui.cameras)
            GcChip(stringResource(R.string.door_home_online, online), tone = if (online > 0) GcTone.Ok else GcTone.Neutral, small = true)
        }
        if (ui.cameras.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.cameras.forEach { cam ->
                    GcFilterChip(
                        text = if (cam.online) cam.name else stringResource(R.string.door_home_offline_chip, cam.name),
                        selected = cam.id == camera.id,
                        onClick = { viewModel.select(cam.id) },
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                    )
                }
            }
        }

        LivePicture(
            camera = camera,
            live = live,
            player = player.takeIf { !fullscreen },
            showingVideo = showingVideo,
            videoHeight = videoHeight,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)),
        )
        cameraProblem(camera, live)?.let { text ->
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                var menu by remember { mutableStateOf(false) }
                GcOutlineButton(
                    text = stringResource(R.string.door_home_btn_still),
                    onClick = { menu = true },
                    icon = GcIcons.Photo,
                    loading = stillBusy,
                    minHeight = 48.dp,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.door_home_still_save)) },
                        leadingIcon = { Icon(GcIcons.Download, contentDescription = null) },
                        onClick = {
                            menu = false
                            viewModel.takeStill(share = false)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.door_home_still_share)) },
                        leadingIcon = { Icon(GcIcons.Share, contentDescription = null) },
                        onClick = {
                            menu = false
                            viewModel.takeStill(share = true)
                        },
                    )
                }
            }
            GcOutlineButton(
                text = stringResource(R.string.door_home_btn_fullscreen),
                onClick = { fullscreen = true },
                icon = GcIcons.Expand,
                modifier = Modifier.weight(1f),
                minHeight = 48.dp,
            )
            val soundOn = sound && audio
            val soundState = stringResource(if (soundOn) R.string.door_home_sound_on else R.string.door_home_sound_off)
            GcOutlineButton(
                text = stringResource(R.string.door_home_btn_sound),
                onClick = viewModel::toggleSound,
                enabled = audio && player != null,
                icon = if (soundOn) GcIcons.Volume else GcIcons.VolumeOff,
                modifier = Modifier
                    .weight(1f)
                    .semantics { stateDescription = soundState },
                minHeight = 48.dp,
            )
        }
        val every = live.live?.every ?: 10
        Text(
            pluralStringResource(R.plurals.door_home_hint, every, every),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        // Plugin 1.7.0: this person's own push "Personen an der Kamera" (only when the camera pushes at all)
        val mine = camera.myPush
        if (mine != null && camera.push) {
            GcCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                GcSwitchRow(
                    label = stringResource(R.string.door_home_my_push),
                    description = stringResource(R.string.door_home_my_push_desc),
                    checked = mine,
                    onCheckedChange = { viewModel.setMyPush(camera.id, it) },
                )
            }
        }

        ClipsCard(camera, clips, viewModel)
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                LivePicture(
                    camera = camera,
                    live = live,
                    player = player,
                    showingVideo = showingVideo,
                    videoHeight = videoHeight,
                    modifier = Modifier.fillMaxSize(),
                )
                GcIconButton(
                    icon = GcIcons.Close,
                    contentDescription = stringResource(R.string.door_home_close),
                    onClick = { fullscreen = false },
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 12.dp).background(PillDark, RoundedCornerShape(24.dp)),
                )
            }
        }
    }

    playing?.let { clip -> ClipPlayerDialog(clip, viewModel, onClose = viewModel::closeClip) }
}

/** Snackbar texts and the share sheet of the camera buttons. */
@Composable
private fun HomeCameraEvents(viewModel: HomeCameraViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.event.collect { e ->
            when (e) {
                HomeCameraEvent.StillSaved -> snackbar.showSnackbar(resources.getString(R.string.door_home_still_saved))
                HomeCameraEvent.StillFailed -> snackbar.showSnackbar(resources.getString(R.string.door_home_still_failed))
                is HomeCameraEvent.Error -> error = e.code
                is HomeCameraEvent.Share -> {
                    val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, e.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(Intent.createChooser(send, resources.getString(R.string.door_home_still_share)))
                    } catch (_: ActivityNotFoundException) {
                        snackbar.showSnackbar(resources.getString(R.string.door_home_still_failed))
                    }
                }
            }
        }
    }
    val text = error?.let { doorErrorText(it) }
    LaunchedEffect(error) {
        if (text != null) {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
            error = null
        }
    }
}

/** A Media3 player for the live list while [enabled]; every load goes through the token check of the view model. */
@OptIn(UnstableApi::class)
@Composable
private fun rememberLivePlayer(enabled: Boolean, viewModel: HomeCameraViewModel): ExoPlayer? {
    val context = LocalContext.current
    val player = remember(enabled) {
        if (!enabled) null
        else viewModel.liveSourceFactory()?.let { factory ->
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .build()
                .apply { volume = 0f }
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    return player
}

/** The picture: the video once its first frame is there, else the newest still; pills and name · time on top. */
@OptIn(UnstableApi::class)
@Composable
private fun LivePicture(
    camera: HomeCamera,
    live: HomeCameraViewModel.LiveUi,
    player: Player?,
    showingVideo: Boolean,
    videoHeight: Int,
    modifier: Modifier = Modifier,
) {
    val still = live.still?.takeIf { live.cameraId == camera.id }
    val desc = stringResource(if (showingVideo) R.string.door_home_live_desc else R.string.door_home_still_desc, camera.name)
    Box(modifier.background(PictureBackground).semantics { contentDescription = desc }) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setKeepContentOnPlayerReset(true)
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!showingVideo) {
            if (still != null) {
                Image(still.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else if (live.error == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    Text(stringResource(R.string.door_home_connecting), color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        PictureOverlay(camera, live, showingVideo, videoHeight, still?.at)
    }
}

@Composable
private fun BoxScope.PictureOverlay(camera: HomeCamera, live: HomeCameraViewModel.LiveUi, showingVideo: Boolean, videoHeight: Int, stillAt: String?) {
    val l = live.live
    Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showingVideo) Pill(stringResource(R.string.door_home_live), LiveRed)
        Spacer(Modifier.weight(1f))
        val right = when {
            l == null -> null
            l.isLive && showingVideo -> {
                val q = stringResource(if (HomeCameraLogic.isHd(videoHeight.takeIf { it > 0 } ?: camera.height, camera.settings.stream)) R.string.door_home_hd else R.string.door_home_sd)
                val via = when (l.via) {
                    "vpn" -> stringResource(R.string.door_home_via_vpn)
                    "lan" -> stringResource(R.string.door_home_via_lan)
                    else -> null
                }
                listOfNotNull(q, via).joinToString(" · ")
            }
            !l.isLive -> stringResource(R.string.door_home_still_every, l.every)
            else -> null
        }
        right?.let { Pill(it, PillDark) }
    }
    // Name and time: the clock while the video runs, else the time of the still.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(showingVideo) {
        while (showingVideo) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val time = if (showingVideo) HHMMSS.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
    else FamilieFormat.parseInstant(stillAt)?.let { HHMMSS.format(it.atZone(ZoneId.systemDefault())) }
    Text(
        if (time != null) stringResource(R.string.door_home_name_time, camera.name, time) else camera.name,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(10.dp)
            .background(PillDark, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun Pill(text: String, background: Color) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier.background(background, RoundedCornerShape(999.dp)).padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** Why the picture may not come: the camera's state, the last error, a failed video. */
@Composable
private fun cameraProblem(camera: HomeCamera, live: HomeCameraViewModel.LiveUi): String? = when {
    !camera.online || (camera.state != null && camera.state != "ok") -> stringResource(
        when (camera.state) {
            "auth_failed" -> R.string.door_home_state_auth
            "not_allowed" -> R.string.door_home_state_not_allowed
            "no_stream" -> R.string.door_home_state_no_stream
            "error" -> R.string.door_home_state_error
            "tls_untrusted" -> R.string.door_home_state_tls
            else -> R.string.door_home_state_offline
        },
    )
    live.error != null -> doorErrorText(live.error)
    live.videoFailed -> stringResource(R.string.door_home_video_retry)
    else -> null
}

@StringRes
private fun kindRes(kind: String): Int = when (kind) {
    HomeCameraKind.PERSON -> R.string.door_home_kind_person
    HomeCameraKind.VEHICLE -> R.string.door_home_kind_vehicle
    HomeCameraKind.ANIMAL -> R.string.door_home_kind_animal
    else -> R.string.door_home_kind_motion
}

private fun kindTone(kind: String): GcTone = when (kind) {
    HomeCameraKind.PERSON -> GcTone.Warn
    HomeCameraKind.VEHICLE -> GcTone.Info
    else -> GcTone.Neutral
}

/** "Clips heute": thumbnail, kind and length, time; a tap plays the clip. */
@Composable
private fun ClipsCard(camera: HomeCamera, clips: HomeCameraViewModel.ClipsUi, viewModel: HomeCameraViewModel) {
    val extra = GateControlTheme.extraColors
    GcCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.door_home_clips_today), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(camera.name, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val list = clips.clips.takeIf { clips.cameraId == camera.id }.orEmpty()
        when {
            list.isEmpty() && clips.loading -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            list.isEmpty() && clips.error != null -> Text(doorErrorText(clips.error), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            list.isEmpty() -> Text(stringResource(R.string.door_home_clips_none), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            else -> list.forEach { clip -> ClipRow(clip, viewModel) }
        }
    }
}

@Composable
private fun ClipRow(clip: HomeClip, viewModel: HomeCameraViewModel) {
    val extra = GateControlTheme.extraColors
    val kind = stringResource(kindRes(clip.kind))
    val thumb by produceState(viewModel.peekThumb(clip), clip.id, clip.thumbPath) {
        if (value == null && clip.thumbPath != null) value = viewModel.thumb(clip)
    }
    val playable = clip.clipPath != null
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(if (playable) Modifier.clickable(role = Role.Button) { viewModel.openClip(clip) } else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.width(76.dp).aspectRatio(76f / 48f).clip(RoundedCornerShape(8.dp)).background(PictureBackground),
            contentAlignment = Alignment.Center,
        ) {
            thumb?.let {
                Image(it.asImageBitmap(), contentDescription = stringResource(R.string.door_home_clip_thumb_desc, kind), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (playable) Icon(GcIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            val length = clip.ms?.let { HomeCameraLogic.duration(it) } ?: stringResource(R.string.door_home_clip_no_video)
            Text(stringResource(R.string.door_home_clip_title, kind, length), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            val time = FamilieFormat.parseInstant(clip.startedAt)?.let { FamilieFormat.time(it) }
            val sub = listOfNotNull(time, if (clip.nobodyHome == true) stringResource(R.string.door_home_clip_nobody) else null).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        GcChip(kind, tone = kindTone(clip.kind), small = true)
    }
}

/** A clip, full screen with the player's controls (its own address only). */
@OptIn(UnstableApi::class)
@Composable
private fun ClipPlayerDialog(playing: HomeCameraViewModel.PlayingClip, viewModel: HomeCameraViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val player = remember(playing.url) {
        viewModel.clipSourceFactory(playing.url)?.let { factory ->
            ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    LaunchedEffect(player) {
        player?.setMediaItem(MediaItem.Builder().setUri(playing.url).setMimeType(MimeTypes.VIDEO_MP4).build())
        player?.prepare()
        player?.playWhenReady = true
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (player != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            useController = true
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { it.player = player },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val kind = stringResource(kindRes(playing.clip.kind))
            val time = FamilieFormat.parseInstant(playing.clip.startedAt)?.let { FamilieFormat.time(it) }
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = 32.dp, start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(playing.cameraName.takeIf { it.isNotBlank() }, kind, time).joinToString(" · "),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                GcIconButton(
                    icon = GcIcons.Close,
                    contentDescription = stringResource(R.string.door_home_close),
                    onClick = onClose,
                    tint = Color.White,
                    modifier = Modifier.background(PillDark, RoundedCornerShape(24.dp)),
                )
            }
        }
    }
}
