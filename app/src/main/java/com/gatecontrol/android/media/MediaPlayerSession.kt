package com.gatecontrol.android.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.gatecontrol.android.drive.DriveMediaLogic
import com.gatecontrol.android.media.player.MediaPlayers
import com.gatecontrol.android.media.player.PlaybackReporter
import com.gatecontrol.android.media.player.PlayerTrack
import com.gatecontrol.android.media.player.PlayerTracks
import com.gatecontrol.android.ui.media.MediaPlayerActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Why the player shows no picture. */
enum class MediaPlayerTrouble { NETWORK, SIGNED_OUT, FAILED, CANNOT_PLAY }

/** What the player's screen shows besides the picture. */
data class MediaPlayerUi(
    val queue: MediaQueue,
    val at: Int,
    val sourceName: String? = null,
    val loading: Boolean = true,
    val trouble: MediaPlayerTrouble? = null,
    val line: MediaPlayLine? = null,
    /** The sheet of a Jellyfin title; null for a share (its file's own tracks). */
    val choices: MediaPlayerChoices? = null,
    val share: Boolean = false,
    /** "Nächste Folge in 8 s". */
    val countdown: Int? = null,
    /** "Weiter ab 42:10" for a moment after opening. */
    val resumedAt: Long? = null,
    /** The note on the data volume, once, before the first playback on mobile data (question 28). */
    val mobileHint: Boolean = false,
    /** The video has ended (and nothing follows). */
    val ended: Boolean = false,
) {
    val title: MediaTitle get() = queue.items[at]
    val hasNext: Boolean get() = at < queue.items.size - 1
}

/**
 * The phone's player of Medien (stage 4c), for the whole app: one ExoPlayer and its queue, outside any activity — a
 * turn of the phone, picture-in-picture and a locked screen do not stop it ([MediaPlaybackService] gives it the media
 * session: lock screen, notification, headphone buttons). Opens each video through [PhoneMediaVideos] (direct or
 * converted, the limit on mobile data), applies the chosen tracks, a new choice of sound, subtitle or quality asks
 * the NAS again and goes on at the same place, reports the progress ([PlaybackReporter]) and goes on to the next
 * episode after "Nächste Folge in 10 s". Main thread only.
 */
@Singleton
@OptIn(UnstableApi::class)
class MediaPlayerSession @Inject constructor(
    @ApplicationContext private val app: Context,
    private val hub: MediaHub,
    private val settings: MediaPhoneSettings,
    private val network: MediaNetwork,
) : MediaPlayback {

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _ui = MutableStateFlow<MediaPlayerUi?>(null)

    /** Null: nothing plays (the screen closes, the media session goes). */
    val ui: StateFlow<MediaPlayerUi?> = _ui.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private var videos: PhoneMediaVideos? = null
    private var openJob: Job? = null
    private var tickJob: Job? = null
    private var countdownJob: Job? = null

    /** The tracks to choose once the player knows the file's tracks (played directly). */
    private var pending: PhoneOpen.Ready? = null

    private val reporter = PlaybackReporter { i, event, positionMs, paused, durationMs -> videos?.report(i, event, positionMs, paused, durationMs) }

    private val at: Int get() = _ui.value?.at ?: 0

    override fun play(context: Context, queue: MediaQueue) {
        start(queue)
        val intent = Intent(context, MediaPlayerActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Plays [queue] from its first title (another playback ends first). */
    fun start(queue: MediaQueue) {
        stopPlayer()
        val v = PhoneMediaVideos(queue, hub, quality = { settings.quality.value }, setQuality = hub::setQuality, mobileCap = { settings.mobileCap.value }, cellular = network::cellular)
        videos = v
        val source = v.source
        _ui.value = MediaPlayerUi(queue, queue.at, sourceName = source?.name, share = v.share)
        if (source == null) {
            _ui.update { it?.copy(loading = false, trouble = MediaPlayerTrouble.FAILED) }
            return
        }
        val p = build(source)
        _player.value = p
        tickJob = main.launch {
            while (isActive) {
                delay(PlaybackReporter.EVERY_MS)
                _player.value?.let { reporter.tick(it, at) }
            }
        }
        open(queue.at)
    }

    /**
     * The player: the source's bytes asked at each open (at home straight through the Wi-Fi, away through the tunnel;
     * a share's pair SMB or WebDAV), the sound of a film (focus, paused when headphones are pulled), awake over the
     * network with the screen off.
     */
    private fun build(source: MediaSource): ExoPlayer {
        val factory = DataSource.Factory { MediaPlayers.dataSource(hub, source).createDataSource() }
        val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
        return ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(MediaPlayerLogic.SEEK_STEP_MS)
            .setSeekForwardIncrementMs(MediaPlayerLogic.SEEK_STEP_MS)
            .build()
            .also { it.addListener(listener) }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val p = _player.value ?: return
            reporter.playingChanged(p, at, isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = _player.value ?: return
            when (playbackState) {
                Player.STATE_READY -> {
                    reporter.ready(p, at)
                    _ui.update { it?.copy(loading = false) }
                }
                Player.STATE_ENDED -> ended(p)
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _player.value?.let { reporter.stop(it, at) }
            val trouble = when (DriveMediaLogic.trouble(error.errorCode)) {
                DriveMediaLogic.Trouble.NETWORK -> MediaPlayerTrouble.NETWORK
                DriveMediaLogic.Trouble.CANNOT_PLAY -> MediaPlayerTrouble.CANNOT_PLAY
                DriveMediaLogic.Trouble.OTHER -> MediaPlayerTrouble.FAILED
            }
            _ui.update { it?.copy(loading = false, trouble = trouble) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val want = pending ?: return
            val p = _player.value ?: return
            if (tracks.groups.isEmpty()) return
            pending = null
            p.trackSelectionParameters = MediaPlayerTracks.apply(p.trackSelectionParameters, tracks, want)
        }
    }

    /** The end of a video: "Nächste Folge in 10 s" for an episode, else the next video of the queue, else the end. */
    private fun ended(p: Player) {
        reporter.ended(p, at)
        val ui = _ui.value ?: return
        val c = MediaPlayerLogic.countdownAfter(ui.queue, ui.at)
        when {
            c != null -> countdown(c)
            ui.hasNext -> open(ui.at + 1)
            else -> _ui.update { it?.copy(ended = true) }
        }
    }

    private fun countdown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = main.launch {
            var left = seconds
            while (left > 0) {
                _ui.update { it?.copy(countdown = left) }
                delay(1_000)
                left--
            }
            next()
        }
    }

    /** "Abbrechen" of the countdown: stays at the end. */
    fun cancelCountdown() {
        countdownJob?.cancel()
        _ui.update { it?.copy(countdown = null, ended = true) }
    }

    /**
     * Opens video [i] of the queue: from [from] (a new choice of sound, subtitle or quality: where it was), else where
     * it was left. [play]: whether it plays at once (a choice made while paused stays paused).
     */
    private fun open(i: Int, from: Long? = null, play: Boolean = true) {
        val v = videos ?: return
        val p = _player.value ?: return
        openJob?.cancel()
        countdownJob?.cancel()
        reporter.stop(p, at)
        p.stop()
        p.clearMediaItems()
        pending = null
        _ui.update { u -> u?.let { it.copy(at = i, loading = true, trouble = null, countdown = null, ended = false, line = if (from != null) it.line else null, resumedAt = null) } }
        openJob = main.launch {
            when (val r = v.open(i, from)) {
                is PhoneOpen.Ready -> {
                    val t = v.items[i]
                    val meta = MediaMetadata.Builder()
                        .setTitle(t.seriesName?.takeIf { t.kind == MediaTitle.Kind.EPISODE } ?: t.name)
                        .setSubtitle(if (t.kind == MediaTitle.Kind.EPISODE) MediaPlayerLogic.name(t) else _ui.value?.sourceName)
                        .setDisplayTitle(MediaPlayerLogic.name(t))
                        .build()
                    val item = MediaPlayers.item(r.plan, v.key(i)).buildUpon().setMediaMetadata(meta).build()
                    pending = r.takeIf { it.audioOrdinal != null || it.textOrdinal != null || it.textOff }
                    p.trackSelectionParameters = r.shareChoice?.let { c -> MediaPlayerTracks.prefer(p.trackSelectionParameters, c) }
                        ?: MediaPlayerTracks.fresh(p.trackSelectionParameters)
                    val start = r.startMs?.takeIf { it > 0 }
                    if (start != null) p.setMediaItem(item, start) else p.setMediaItem(item)
                    val hint = r.line.net == MediaNet.CELLULAR && !settings.mobileHintSeen
                    p.prepare()
                    // the note on the data volume first: it plays once it is read
                    p.playWhenReady = play && !hint
                    _ui.update {
                        it?.copy(
                            line = r.line,
                            choices = v.choices(i),
                            resumedAt = start?.takeIf { from == null },
                            mobileHint = hint,
                        )
                    }
                }
                PhoneOpen.Offline -> _ui.update { it?.copy(loading = false, trouble = MediaPlayerTrouble.NETWORK) }
                PhoneOpen.SignedOut -> _ui.update { it?.copy(loading = false, trouble = MediaPlayerTrouble.SIGNED_OUT) }
                PhoneOpen.Failed -> _ui.update { it?.copy(loading = false, trouble = MediaPlayerTrouble.FAILED) }
            }
        }
    }

    /** The note on the data volume was read: it is not shown again, the video plays. */
    fun mobileHintRead() {
        settings.setMobileHintSeen()
        _ui.update { it?.copy(mobileHint = false) }
        _player.value?.play()
    }

    /** The limit on mobile data now (the note's text); null: none. */
    val mobileCap: Long? get() = settings.mobileCap.value

    /** Asks the NAS again at the same place (a new sound, subtitle or quality; "Nochmal" after a fault). */
    private fun again() {
        val p = _player.value ?: return
        val pos = p.currentPosition.takeIf { it > 0 } ?: _ui.value?.let { ui -> videos?.startAt(ui.at) }
        open(at, from = pos ?: 0L, play = p.playWhenReady || p.playbackState == Player.STATE_IDLE)
    }

    fun retry() {
        val ui = _ui.value ?: return
        if (_player.value == null) {
            start(ui.queue.copy(at = ui.at))
            return
        }
        again()
    }

    fun chooseAudio(index: Int) {
        if (videos?.chooseAudio(at, index) == true) again()
    }

    fun chooseSubtitle(index: Int) {
        if (videos?.chooseSubtitle(at, index) == true) again()
    }

    fun chooseQuality(q: MediaQualitySetting) {
        if (videos?.chooseQuality(at, q) == true) again()
    }

    /** A share's file: one of its own tracks (no new request); the series keeps the language. */
    fun chooseTrack(track: PlayerTrack) {
        val p = _player.value ?: return
        p.trackSelectionParameters = PlayerTracks.choose(p.trackSelectionParameters, p.currentTracks, track)
        rememberShareTracks(p, chosen = track)
    }

    /** A share's file: subtitles off. */
    fun subtitlesOff() {
        val p = _player.value ?: return
        p.trackSelectionParameters = PlayerTracks.subtitlesOff(p.trackSelectionParameters)
        rememberShareTracks(p, chosen = null)
    }

    private fun rememberShareTracks(p: Player, chosen: PlayerTrack?) {
        val all = PlayerTracks.of(p.currentTracks)
        val audio = (chosen?.takeIf { it.kind == PlayerTrack.Kind.AUDIO } ?: all.firstOrNull { it.kind == PlayerTrack.Kind.AUDIO && it.selected })?.language
        val text = when {
            chosen == null -> null
            chosen.kind == PlayerTrack.Kind.TEXT -> chosen.language
            else -> all.firstOrNull { it.kind == PlayerTrack.Kind.TEXT && it.selected }?.language
        }
        val off = chosen == null || (chosen.kind == PlayerTrack.Kind.AUDIO && all.none { it.kind == PlayerTrack.Kind.TEXT && it.selected })
        videos?.rememberShare(at, audio, text, off)
    }

    /** The next video now ("Nächste Folge", the lock screen's ⏭). */
    fun next() {
        val ui = _ui.value ?: return
        if (ui.hasNext) open(ui.at + 1)
    }

    /** A video of "Nächste Folgen". */
    fun playAt(i: Int) {
        val ui = _ui.value ?: return
        if (i in ui.queue.items.indices && i != ui.at) open(i)
    }

    /** As other players: back to the start first, then the video before. */
    fun previous() {
        val p = _player.value ?: return
        if (p.currentPosition > MediaPlayerLogic.PREVIOUS_RESTART_MS || at == 0) p.seekTo(0) else open(at - 1)
    }

    fun pause() {
        _player.value?.pause()
    }

    /** Nothing plays any more: the progress goes to the source, the player and the media session go. */
    fun close() {
        stopPlayer()
        videos = null
        _ui.value = null
    }

    private fun stopPlayer() {
        openJob?.cancel()
        tickJob?.cancel()
        countdownJob?.cancel()
        pending = null
        val p = _player.value ?: return
        reporter.stop(p, at)
        p.removeListener(listener)
        _player.value = null
        p.release()
    }

    /**
     * The player as the media session shows it (lock screen, notification, headphones): ⏭ goes to the next episode of
     * the queue (the player itself holds one video at a time).
     */
    fun forSession(p: Player): Player = object : ForwardingPlayer(p) {
        private fun hasNext() = _ui.value?.hasNext == true

        override fun getAvailableCommands(): Player.Commands {
            val b = super.getAvailableCommands().buildUpon()
            if (hasNext()) b.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            return b.build()
        }

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        override fun seekToNext() {
            if (hasNext()) next() else super.seekToNext()
        }

        override fun seekToNextMediaItem() {
            if (hasNext()) next() else super.seekToNextMediaItem()
        }

        override fun stop() {
            super.stop()
            close()
        }
    }
}

/** Choosing a video's tracks in Media3 for the phone's player. */
object MediaPlayerTracks {
    /** The file's own sound / subtitle by place (Jellyfin's stream of that place), or subtitles off. */
    fun apply(params: TrackSelectionParameters, tracks: Tracks, want: PhoneOpen.Ready): TrackSelectionParameters {
        val all = PlayerTracks.of(tracks)
        var p = params
        want.audioOrdinal?.let { n -> all.filter { it.kind == PlayerTrack.Kind.AUDIO }.getOrNull(n)?.let { p = PlayerTracks.choose(p, tracks, it) } }
        if (want.textOff) {
            p = PlayerTracks.subtitlesOff(p)
        } else {
            want.textOrdinal?.let { n -> all.filter { it.kind == PlayerTrack.Kind.TEXT }.getOrNull(n)?.let { p = PlayerTracks.choose(p, tracks, it) } }
        }
        return p
    }

    /** Nothing chosen yet: the choices of the video before are gone (subtitles on again for Jellyfin's own). */
    fun fresh(params: TrackSelectionParameters): TrackSelectionParameters =
        params.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()

    /** A share's file: the series' languages first, subtitles off when it was so chosen. */
    fun prefer(params: TrackSelectionParameters, choice: MediaTrackChoice): TrackSelectionParameters {
        val b = params.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT)
        choice.audioLanguage?.let { b.setPreferredAudioLanguage(it) }
        if (choice.subtitlesOff) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            choice.subtitleLanguage?.let { b.setPreferredTextLanguage(it) }
        }
        return b.build()
    }
}
