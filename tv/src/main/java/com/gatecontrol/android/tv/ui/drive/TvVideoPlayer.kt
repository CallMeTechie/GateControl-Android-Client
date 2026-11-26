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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gatecontrol.android.drive.DriveMediaLogic
import com.gatecontrol.android.media.player.MediaPlayers
import com.gatecontrol.android.media.player.PlaybackReporter
import com.gatecontrol.android.media.player.PlayerTrack
import com.gatecontrol.android.media.player.PlayerTrackMenu
import com.gatecontrol.android.media.player.PlayerTracks
import com.gatecontrol.android.media.cast.CastCmd
import com.gatecontrol.android.media.cast.CastReceiverRules
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.cast.TvCastPick
import com.gatecontrol.android.tv.cast.TvCastTracks
import com.gatecontrol.android.tv.cast.TvVideoRemote
import com.gatecontrol.android.tv.drive.TvDriveLogic
import com.gatecontrol.android.tv.player.TvPlayerAction
import com.gatecontrol.android.tv.player.TvPlayerKeys
import com.gatecontrol.android.tv.ui.components.TvPill
import com.gatecontrol.android.tv.ui.components.TvPillTone
import com.gatecontrol.android.tv.ui.home.TvIcons
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import kotlinx.coroutines.delay

/**
 * The player of Drive's videos (mockup 4 and 9) and of Medien's films and episodes: streams with Range from the own
 * server or the NAS, keys of the remote ([TvPlayerKeys]): OK / ⏯ play and pause, ◀ ▶ ⏪ ⏩ ±10 s, ⏭ ⏮ the next /
 * previous video, ▲ or ☰ the panel "Ton & Untertitel", ▼ the bar, Zurück closes. Where a video was left is kept
 * ([TvVideoSource.keep], Medien in Jellyfin through [TvVideoSource.report]). A source with its own panel
 * ([TvVideoSource.choices]: Jellyfin's streams and the quality) opens the video again at the same place on a choice.
 */
@OptIn(UnstableApi::class)
@Composable
fun TvVideoPlayer(
    source: TvVideoSource,
    at: Int,
    onAt: (Int) -> Unit,
    onClose: () -> Unit,
    /** "Senden an …" in the panel (GateDrop); null: not offered. */
    onSend: (() -> Unit)? = null,
    /** A phone plays this here and controls it ("Auf GateControl TV abspielen"); null: none. */
    remote: TvVideoRemote? = null,
) {
    val context = LocalContext.current
    val key = source.key(at)
    val count = source.count
    val factory = remember { source.factory(context) }
    val player = remember(factory) { factory?.let { MediaPlayers.player(context, it) } }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var trouble by remember(key) { mutableStateOf<String?>(null) }
    var resumedAt by remember(key) { mutableStateOf<Long?>(null) }
    var format by remember(key) { mutableStateOf<String?>(null) }
    var info by remember(key) { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf(false) }
    var touched by remember { mutableIntStateOf(0) }
    var controls by remember { mutableStateOf(true) }
    // a choice of the source's panel: the same video again from where it is
    var reload by remember(key) { mutableIntStateOf(0) }
    var reloadFrom by remember(key) { mutableStateOf<Long?>(null) }
    // the file's own track to choose once the tracks are known (Medien, played directly)
    // (not keyed: the player's listener keeps writing the same states; reset when a video opens)
    var pending by remember { mutableStateOf<TvOpen.Ready?>(null) }
    // the progress to the source: started, every few seconds while it plays, stopped (Jellyfin: "Weiterschauen")
    val reporter = remember { PlaybackReporter { i, event, positionMs, paused, durationMs -> source.report(i, event, positionMs, paused, durationMs) } }
    // "Nächste Folge in 10 s"
    var countdown by remember { mutableStateOf<Int?>(null) }
    val root = remember { FocusRequester() }
    val cannotPlay = stringResource(R.string.tv_player_cannot_play, source.typeName(at))
    val network = stringResource(R.string.tv_player_network)
    val failed = stringResource(R.string.tv_viewer_failed)
    val signedOut = stringResource(R.string.tv_media_signed_out)
    val vpnBlocked = stringResource(R.string.tv_media_vpn_blocked) + "\n" + stringResource(R.string.tv_media_vpn_blocked_text)
    // the file's sound does not play here (DTS without an AV receiver): a note instead of silence
    // (not keyed: the player's listener writes it; reset when a video opens)
    var silent by remember { mutableStateOf<String?>(null) }
    // the listener lives as long as the player: it reads the video shown now
    val nowAt by rememberUpdatedState(at)
    val nowOnAt by rememberUpdatedState(onAt)

    fun keep() {
        val p = player ?: return
        if (p.playbackState != Player.STATE_IDLE) source.keep(at, p.currentPosition, p.duration.takeIf { it != C.TIME_UNSET } ?: 0)
    }

    fun stopReport() {
        val p = player ?: return
        reporter.stop(p, at)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                val p = player ?: return
                reporter.playingChanged(p, nowAt, isPlaying)
            }

            override fun onTracksChanged(t: Tracks) {
                tracks = t
                if (t.groups.isNotEmpty()) silent = PlayerTracks.silentAudio(t)
                format = player?.videoFormat?.let { f -> TvPlayerUi.formatLabel(f.height, f.sampleMimeType) }
                val want = pending ?: return
                val p = player ?: return
                if (t.groups.isEmpty()) return
                pending = null
                p.trackSelectionParameters = TvPlayerUi.apply(p.trackSelectionParameters, t, want)
            }

            override fun onPlayerError(error: PlaybackException) {
                trouble = when (DriveMediaLogic.trouble(error.errorCode)) {
                    DriveMediaLogic.Trouble.CANNOT_PLAY -> cannotPlay
                    DriveMediaLogic.Trouble.NETWORK -> network
                    DriveMediaLogic.Trouble.OTHER -> failed
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                val p = player ?: return
                if (state == Player.STATE_READY) reporter.ready(p, nowAt)
                // the end of a video: the next one (an episode after "Nächste Folge in 10 s")
                if (state == Player.STATE_ENDED) {
                    source.forget(nowAt)
                    reporter.ended(p, nowAt)
                    if (nowAt < count - 1) {
                        if (source.nextCountdownSeconds > 0) countdown = source.nextCountdownSeconds else nowOnAt(nowAt + 1)
                    }
                }
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    // the video at [at]: its stream first (Drive: the rights, the virus scan; Medien: direct or converted), then from
    // where it was left
    LaunchedEffect(player, key, reload) {
        val p = player ?: run {
            trouble = failed
            return@LaunchedEffect
        }
        val again = reloadFrom
        stopReport()
        p.stop()
        p.clearMediaItems()
        trouble = null
        countdown = null
        silent = null
        when (val s = source.open(at)) {
            is TvOpen.Ready -> {
                val item = s.item
                info = s.info
                pending = s.takeIf { it.audioOrdinal != null || it.textOrdinal != null || it.textOff }
                val from = again ?: source.startAt(at)
                if (from != null && from > 0) {
                    p.setMediaItem(item, from)
                    if (again == null) resumedAt = from
                } else {
                    p.setMediaItem(item)
                }
                p.prepare()
                p.playWhenReady = true
            }
            TvOpen.Offline -> trouble = network
            TvOpen.Failed -> trouble = failed
            TvOpen.SignedOut -> trouble = signedOut
            TvOpen.Vpn -> trouble = vpnBlocked
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
    // the progress to the source every few seconds while it plays (Jellyfin: "Weiterschauen" on every device)
    LaunchedEffect(player, key) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            delay(PlaybackReporter.EVERY_MS)
            reporter.tick(p, at)
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
    LaunchedEffect(countdown) {
        val c = countdown ?: return@LaunchedEffect
        if (c <= 0) {
            countdown = null
            onAt(at + 1)
            return@LaunchedEffect
        }
        delay(1_000)
        if (countdown == c) countdown = c - 1
    }
    LaunchedEffect(panel) { if (!panel) root.requestSoon() }
    DisposableEffect(key) {
        onDispose {
            keep()
            stopReport()
        }
    }

    val menu = remember(tracks) { PlayerTracks.menu(PlayerTracks.of(tracks)) }
    // the source's own panel, read again after each opening
    val choices = remember(key, reload, info, tracks) { source.choices(at) }

    // ─── the phone's remote ("Auf GateControl TV abspielen") ───
    val trackFallback = stringResource(R.string.tv_player_track)
    val subtitleFallback = stringResource(R.string.tv_player_subtitle)
    val subtitlesOff = stringResource(R.string.tv_player_subtitles_off)
    val nowChoices by rememberUpdatedState(choices)
    val nowMenu by rememberUpdatedState(menu)
    val nowTrouble by rememberUpdatedState(trouble)
    val nowCountdown by rememberUpdatedState(countdown)
    var remoteClosed by remember { mutableStateOf(false) }

    fun tellRemote(force: Boolean = false) {
        val r = remote ?: return
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: 0
        val phase = TvCastTracks.phase(
            trouble = nowTrouble != null,
            idle = p.playbackState == Player.STATE_IDLE,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            ended = p.playbackState == Player.STATE_ENDED,
            playWhenReady = p.playWhenReady,
            countdown = nowCountdown != null,
            last = nowAt >= count - 1,
        )
        val audio = TvCastTracks.audio(nowChoices, nowMenu) { t, n -> PlayerTracks.name(t, n, trackFallback) }
        val text = TvCastTracks.subtitles(nowChoices, nowMenu, subtitlesOff) { t, n -> PlayerTracks.name(t, n, subtitleFallback) }
        r.state(phase, p.currentPosition.coerceAtLeast(0), dur, source.name(nowAt), audio, text, force)
    }

    fun closeRemote(byRemote: Boolean) {
        val r = remote ?: return
        if (remoteClosed) return
        remoteClosed = true
        val p = player
        r.closed(p?.currentPosition?.coerceAtLeast(0) ?: position, p?.duration?.takeIf { it != C.TIME_UNSET } ?: duration, byRemote)
    }

    fun close(byRemote: Boolean = false) {
        keep()
        stopReport()
        closeRemote(byRemote)
        onClose()
    }

    if (remote != null) {
        // how it plays, once a second to the remote (it tells the phone only changes, jumps and a heartbeat)
        LaunchedEffect(player, key) {
            while (true) {
                tellRemote()
                delay(1_000)
            }
        }
        LaunchedEffect(player, remote) {
            remote.commands.collect { c ->
                val p = player ?: return@collect
                touched++
                when (c.cmd) {
                    CastCmd.PLAY -> {
                        countdown = null
                        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                        p.play()
                    }
                    CastCmd.PAUSE -> p.pause()
                    CastCmd.SEEK -> c.positionMs?.let { to -> p.seekTo(CastReceiverRules.seekTarget(to, 0, p.duration.takeIf { it != C.TIME_UNSET } ?: 0)) }
                    CastCmd.SEEK_BY -> c.deltaMs?.let { d -> p.seekTo(CastReceiverRules.seekTarget(p.currentPosition, d, p.duration.takeIf { it != C.TIME_UNSET } ?: 0)) }
                    CastCmd.AUDIO, CastCmd.SUBTITLE -> when (val pick = c.track?.let { TvCastTracks.pick(it, c.cmd == CastCmd.AUDIO, nowChoices, nowMenu) }) {
                        is TvCastPick.Choice -> if (source.choose(nowAt, pick.choice)) {
                            reloadFrom = p.currentPosition
                            reload++
                        }
                        is TvCastPick.Track -> p.trackSelectionParameters = PlayerTracks.choose(p.trackSelectionParameters, p.currentTracks, pick.track)
                        TvCastPick.SubtitlesOff -> p.trackSelectionParameters = PlayerTracks.subtitlesOff(p.trackSelectionParameters)
                        null -> Unit
                    }
                    CastCmd.STOP -> {
                        close(byRemote = true)
                        return@collect
                    }
                    CastCmd.STATUS -> Unit
                }
                position = p.currentPosition
                tellRemote(force = c.cmd == CastCmd.STATUS)
            }
        }
        // gone without "Stopp" (the TV's Zurück, another app): the phone hears it
        DisposableEffect(remote) { onDispose { closeRemote(byRemote = false) } }
    }

    fun act(action: TvPlayerAction, repeat: Int): Boolean {
        val p = player
        touched++
        countdown?.let {
            // "Nächste Folge in …": OK starts it now, Zurück stays at the end
            when (action) {
                TvPlayerAction.TOGGLE, TvPlayerAction.NEXT -> {
                    countdown = null
                    onAt(at + 1)
                }
                TvPlayerAction.BACK, TvPlayerAction.STOP -> countdown = null
                else -> Unit
            }
            return true
        }
        when (action) {
            TvPlayerAction.TOGGLE -> p?.let { if (it.isPlaying) it.pause() else it.play() }
            TvPlayerAction.PLAY -> p?.play()
            TvPlayerAction.PAUSE -> p?.pause()
            TvPlayerAction.SEEK_BACK, TvPlayerAction.SEEK_FORWARD -> p?.let {
                val step = TvPlayerKeys.seekStep(repeat) * if (action == TvPlayerAction.SEEK_BACK) -1 else 1
                it.seekTo(TvPlayerKeys.seekTarget(it.currentPosition, step, it.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0))
                position = it.currentPosition
            }
            TvPlayerAction.NEXT -> if (at < count - 1) {
                keep()
                onAt(TvDriveLogic.step(at, 1, count))
            }
            TvPlayerAction.PREVIOUS -> {
                // as other players: back to the start first, then the video before
                val p0 = p?.currentPosition ?: 0
                if (p0 > 5_000 || at == 0) p?.seekTo(0) else {
                    keep()
                    onAt(TvDriveLogic.step(at, -1, count))
                }
            }
            TvPlayerAction.TRACKS -> if (menu.any || onSend != null || choices != null) panel = true
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
        if (controls || trouble != null || countdown != null) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.3f).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))))
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.42f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))),
            )
            Column(Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 28.dp, end = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(source.name(at), style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (count > 1 && choices == null) TvPill("${at + 1} / $count", TvPillTone.Neutral, dot = false)
                    val line = info
                    if (line != null) TvPill(line, TvPillTone.Accent) else format?.let { TvPill(it, TvPillTone.Neutral, dot = false) }
                    resumedAt?.let { TvPill(stringResource(R.string.tv_player_resumed, TvPlayerKeys.time(it)), TvPillTone.Accent) }
                }
                silent?.let { Text(stringResource(R.string.tv_player_no_sound, it), style = MaterialTheme.typography.bodyMedium, color = Color.White) }
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
                    if (choices != null) {
                        Hint(stringResource(R.string.tv_player_tracks_short))
                        choices.quality0?.let { Hint(stringResource(R.string.tv_media_quality_hint, it.label)) }
                    } else if (menu.any) {
                        Hint(stringResource(R.string.tv_player_tracks_hint, TvPlayerUi.textState(menu, stringResource(R.string.tv_player_subtitles_off))))
                    }
                    if (at < count - 1) Hint(stringResource(if (source.nextCountdownSeconds > 0) R.string.tv_media_next_episode_hint else R.string.tv_player_next_hint))
                }
            }
        }
        countdown?.let { c ->
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 120.dp)
                    .background(GateControlTvTheme.colors.panel.copy(alpha = 0.96f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.tv_media_next_in, c), style = MaterialTheme.typography.titleSmall, color = GateControlTvTheme.colors.text)
                Text(source.name(at + 1), style = MaterialTheme.typography.bodySmall, color = GateControlTvTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.tv_media_next_keys), style = MaterialTheme.typography.labelMedium, color = GateControlTvTheme.colors.faint)
            }
        }
        if (panel) {
            val own = choices
            if (own != null) {
                SourcePanel(
                    own,
                    onChoose = { c ->
                        val p = player
                        if (source.choose(at, c)) {
                            reloadFrom = p?.currentPosition
                            reload++
                        }
                    },
                    onClose = { panel = false },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            } else {
                TracksPanel(menu, onAudio = { t -> player?.let { p -> p.trackSelectionParameters = PlayerTracks.choose(p.trackSelectionParameters, p.currentTracks, t) } }, onText = { t ->
                    player?.let { p ->
                        p.trackSelectionParameters = if (t == null) PlayerTracks.subtitlesOff(p.trackSelectionParameters) else PlayerTracks.choose(p.trackSelectionParameters, p.currentTracks, t)
                    }
                }, onClose = { panel = false }, onSend = onSend?.let { send -> { panel = false; player?.pause(); send() } }, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
    LaunchedEffect(Unit) { root.requestSoon() }
}

/** How often a playing video's place goes to the source (Jellyfin: "Fortschritt alle paar Sekunden"). */

/** The panel of a source with its own lists (Medien, mockup 9): Ton, Untertitel, Qualität; OK takes a line. */
@Composable
private fun SourcePanel(choices: TvChoices, onChoose: (TvChoice) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    Column(
        modifier
            .width(340.dp)
            .fillMaxHeight()
            .background(colors.panel.copy(alpha = 0.96f))
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.tv_player_tracks), style = MaterialTheme.typography.titleMedium, color = colors.text)
        var firstSet = false
        fun focus(): Modifier = if (!firstSet) {
            firstSet = true
            Modifier.focusRequester(first)
        } else {
            Modifier
        }
        for ((title, list) in listOf(
            R.string.tv_player_audio to choices.audio,
            R.string.tv_player_subtitles to choices.text,
            R.string.tv_media_quality to choices.quality,
        )) {
            if (list.isEmpty()) continue
            Text(stringResource(title), style = MaterialTheme.typography.labelMedium, color = colors.muted, modifier = Modifier.padding(top = 10.dp))
            for (c in list) {
                Choice(c.label, c.selected, onClick = { onChoose(c) }, note = c.note, modifier = focus())
            }
        }
        Spacer(Modifier.height(10.dp))
        Choice(stringResource(R.string.tv_player_close_panel), false, onClick = onClose, modifier = focus())
    }
    LaunchedEffect(Unit) { first.requestSoon() }
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

    /**
     * [params] with the file's own sound track / subtitle [want] names by place (Medien, played directly: Jellyfin's
     * stream of that place among the file's tracks of its kind), or subtitles off.
     */
    fun apply(params: androidx.media3.common.TrackSelectionParameters, tracks: Tracks, want: TvOpen.Ready): androidx.media3.common.TrackSelectionParameters {
        var p = params
        // by place among all the file's tracks; one the phone does not play is not forced (Media3 keeps one it plays)
        want.audioOrdinal?.let { n -> PlayerTracks.byOrdinal(tracks, PlayerTrack.Kind.AUDIO, n)?.let { p = PlayerTracks.choose(p, tracks, it) } }
        if (want.textOff) {
            p = PlayerTracks.subtitlesOff(p)
        } else {
            want.textOrdinal?.let { n -> PlayerTracks.byOrdinal(tracks, PlayerTrack.Kind.TEXT, n)?.let { p = PlayerTracks.choose(p, tracks, it) } }
        }
        return p
    }

    /** What the bar says about subtitles: the chosen one's language or label, else [off]. */
    fun textState(menu: PlayerTrackMenu, off: String): String {
        val t = menu.text.firstOrNull { it.selected } ?: return off
        return PlayerTracks.name(t, menu.text.indexOf(t) + 1, "")
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
    menu: PlayerTrackMenu,
    onAudio: (PlayerTrack) -> Unit,
    onText: (PlayerTrack?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSend: (() -> Unit)? = null,
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
        Text(stringResource(if (menu.any) R.string.tv_player_tracks else R.string.tv_send_to), style = MaterialTheme.typography.titleMedium, color = colors.text)
        if (menu.audio.size > 1) {
            Text(stringResource(R.string.tv_player_audio), style = MaterialTheme.typography.labelMedium, color = colors.muted, modifier = Modifier.padding(top = 10.dp))
            val fallback = stringResource(R.string.tv_player_track)
            menu.audio.forEachIndexed { i, t ->
                Choice(PlayerTracks.name(t, i + 1, fallback), t.selected, onClick = { onAudio(t) }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
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
            menu.text.forEachIndexed { i, t -> Choice(PlayerTracks.name(t, i + 1, fallback), t.selected, onClick = { onText(t) }) }
        }
        Spacer(Modifier.height(10.dp))
        if (onSend != null) {
            Choice(
                stringResource(R.string.tv_send_to),
                false,
                onClick = onSend,
                modifier = if (menu.audio.size <= 1 && menu.text.isEmpty()) Modifier.focusRequester(first) else Modifier,
            )
        }
        Choice(stringResource(R.string.tv_player_close_panel), false, onClick = onClose)
    }
    LaunchedEffect(Unit) { first.requestSoon() }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, note: String? = null) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(7.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(if (note != null) 52.dp else 36.dp),
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
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) Text("✓", style = MaterialTheme.typography.labelLarge)
        }
    }
}
