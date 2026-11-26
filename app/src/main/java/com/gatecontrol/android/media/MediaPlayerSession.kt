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
import com.gatecontrol.android.media.offline.MediaOffline
import com.gatecontrol.android.media.offline.MediaOfflineDataSource
import com.gatecontrol.android.media.player.MediaPlayers
import com.gatecontrol.android.media.player.PlayAhead
import com.gatecontrol.android.media.player.PlaybackReporter
import com.gatecontrol.android.media.player.PlayerTrack
import com.gatecontrol.android.media.player.PlayerTracks
import com.gatecontrol.android.ui.media.MediaPlayerActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
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

/** Why the player shows no picture ([VPN]: away only through the VPN, it is off). */
enum class MediaPlayerTrouble { NETWORK, VPN, SIGNED_OUT, FAILED, CANNOT_PLAY }

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
    /** Played from a download on the phone (question 22 a): its file's own tracks in the sheet. */
    val offline: Boolean = false,
    /** „VPN wird verbunden …“: the app starts the VPN itself to play away (Netz-Regel). */
    val vpnConnecting: Boolean = false,
    /** With [MediaPlayerTrouble.VPN]: Android's VPN consent is missing (the screen asks for it once by itself). */
    val vpnConsent: Boolean = false,
    /** The file's sound does not play on this phone (e.g. "DTS"): the picture without sound, a note says so. */
    val silentCodec: String? = null,
    /** „Wie abspielen?“ (decision 89 b): away the original does not go through, the person chooses. */
    val awayAsk: MediaAwayFit? = null,
    /** „Auf dem Gerät mitgeladen“ (part 2, stage 1); null: not loading ahead (at home, converted, a download). */
    val ahead: MediaPlayAheadState? = null,
    /** The original's bit rate of what is loaded ahead (for "reicht für ca. 34 Min."). */
    val aheadBitrate: Long? = null,
    /** „Original mit Mitladen“: it starts once what is ahead carries the rest. */
    val aheadWait: Boolean = false,
    /** The wait left for that; null: not measured yet. */
    val aheadWaitMs: Long? = null,
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
    private val offline: MediaOffline,
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
    private var gateJob: Job? = null
    private var aheadJob: Job? = null

    /** „Beim Abspielen mitladen“ of this playback; null: off (or no room on the phone). */
    private var ahead: PlayAhead? = null

    /** „Wie abspielen?“ shown, waiting for the answer. */
    private var asking: CompletableDeferred<MediaAwayOriginal?>? = null

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
        val v = PhoneMediaVideos(
            queue,
            hub,
            quality = { settings.quality.value },
            setQuality = hub::setQuality,
            mobileCap = { settings.mobileCap.value },
            cellular = network::cellular,
            offline = offline,
            awayOriginal = { settings.awayOriginal.value },
            aheadBytes = { ahead?.limitBytes ?: 0L },
        )
        videos = v
        val source = v.source
        _ui.value = MediaPlayerUi(queue, queue.at, sourceName = source?.name, share = v.share)
        if (source == null) {
            _ui.update { it?.copy(loading = false, trouble = MediaPlayerTrouble.FAILED) }
            return
        }
        val p = build(source)
        _player.value = p
        gateJob = main.launch { hub.gate.collect { g -> _ui.update { it?.copy(vpnConnecting = g == MediaGate.CONNECTING) } } }
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
        val upstream = MediaPlayers.dataSource(hub, source)
        // away the original is loaded further ahead into a space of its own (only for this playback)
        val root = PlayAhead.root(app.cacheDir)
        val limit = MediaPlayAhead.limitBytes(settings.aheadGb.value, runCatching { root.apply { mkdirs() }.usableSpace }.getOrDefault(0L))
        val a = if (limit > 0) runCatching { PlayAhead(root, upstream, limit) }.getOrNull() else null
        ahead = a
        val net = a?.factory ?: upstream
        // a download is read from the phone (decrypted chunk by chunk), the rest from the NAS
        val factory = DataSource.Factory { MediaOfflineDataSource(offline.store::open, net.createDataSource()) }
        val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
        return ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setLoadControl(MediaPlayers.loadControl())
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
            // a sound the phone does not decode (DTS of a share or an original download): a note instead of silence
            if (tracks.groups.isNotEmpty()) _ui.update { it?.copy(silentCodec = PlayerTracks.silentAudio(tracks)) }
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
        _ui.update { u -> u?.let { it.copy(at = i, loading = true, trouble = null, countdown = null, ended = false, line = if (from != null) it.line else null, resumedAt = null, vpnConsent = false, silentCodec = null, awayAsk = null, aheadWait = false, aheadWaitMs = null) } }
        openJob = main.launch {
            when (val r = v.open(i, from, ask = ::ask)) {
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
                    val following = follow(r)
                    p.prepare()
                    // the note on the data volume first: it plays once it is read; „Original mit Mitladen“ once enough is ahead
                    p.playWhenReady = play && !hint && !(r.wait && following)
                    _ui.update {
                        it?.copy(
                            line = r.line,
                            choices = v.choices(i),
                            offline = r.offline,
                            resumedAt = start?.takeIf { from == null },
                            mobileHint = hint,
                            aheadWait = r.wait && following,
                            aheadBitrate = r.plan.version.bitrate,
                        )
                    }
                }
                PhoneOpen.Cancelled -> close()
                PhoneOpen.Offline -> _ui.update { it?.copy(loading = false, trouble = MediaPlayerTrouble.NETWORK) }
                is PhoneOpen.Vpn -> _ui.update { it?.copy(loading = false, trouble = MediaPlayerTrouble.VPN, vpnConsent = r.consent) }
                PhoneOpen.SignedOut -> _ui.update { it?.copy(loading = false, trouble = MediaPlayerTrouble.SIGNED_OUT) }
                PhoneOpen.Failed -> _ui.update { it?.copy(loading = false, trouble = MediaPlayerTrouble.FAILED) }
            }
        }
    }

    /** „Wie abspielen?“: shown, waited for (the player stays empty meanwhile). */
    private suspend fun ask(fit: MediaAwayFit): MediaAwayOriginal? {
        val d = CompletableDeferred<MediaAwayOriginal?>()
        asking = d
        _ui.update { it?.copy(awayAsk = fit, loading = false) }
        return try {
            d.await()
        } finally {
            asking = null
            _ui.update { it?.copy(awayAsk = null, loading = true) }
        }
    }

    /** The answer to „Wie abspielen?“; null: „Abbrechen“ (nothing plays). */
    fun answerAway(choice: MediaAwayOriginal?) {
        asking?.complete(choice)
    }

    /**
     * Loads ahead what [r] plays when it is the file itself (Jellyfin's original, a share's file) away, not converted
     * and not a download; on mobile data only ten minutes ahead. True when it does.
     */
    private fun follow(r: PhoneOpen.Ready): Boolean {
        val a = ahead ?: return false
        val away = r.line.net == MediaNet.AWAY || r.line.net == MediaNet.CELLULAR
        val file = !r.plan.hls && (r.plan.method == PlayMethod.DIRECT_PLAY || videos?.share == true)
        if (!away || !file || r.offline) {
            a.follow(null)
            _ui.update { it?.copy(ahead = null) }
            return false
        }
        a.follow(r.plan.url, MediaPlayAhead.aheadLimit(a.limitBytes, r.line.net == MediaNet.CELLULAR, r.plan.version.bitrate))
        if (aheadJob?.isActive != true) {
            aheadJob = main.launch {
                while (isActive) {
                    aheadTick()
                    delay(AHEAD_TICK_MS)
                }
            }
        }
        return true
    }

    /** Each second: the card's numbers; with „Original mit Mitladen“ the start once enough is ahead. */
    private fun aheadTick() {
        val a = ahead ?: return
        val p = _player.value ?: return
        val u = _ui.value ?: return
        if (!a.following) return
        val state = a.state()
        // played meanwhile (the controls' ▶): no more waiting
        if (u.aheadWait && p.isPlaying) _ui.update { it?.copy(aheadWait = false, aheadWaitMs = null) }
        if (!u.aheadWait || p.isPlaying) {
            _ui.update { it?.copy(ahead = state) }
            return
        }
        val bitrate = u.aheadBitrate ?: 0L
        val duration = p.duration.takeIf { it > 0 } ?: u.title.runtimeMs ?: 0L
        val remaining = (duration - p.currentPosition.coerceAtLeast(0)).coerceAtLeast(0)
        if (MediaPlayAhead.canStart(state.aheadBytes, remaining, bitrate, state.rateBps, state.complete)) {
            startNow()
            _ui.update { it?.copy(ahead = state) }
        } else {
            _ui.update { it?.copy(ahead = state, aheadWaitMs = MediaPlayAhead.waitLeftMs(state.aheadBytes, remaining, bitrate, state.rateBps)) }
        }
    }

    /** „Jetzt abspielen“ while it loads ahead (or enough is ahead now). */
    fun startNow() {
        _ui.update { it?.copy(aheadWait = false, aheadWaitMs = null) }
        if (_ui.value?.mobileHint != true) _player.value?.play()
    }

    /** The note on the data volume was read: it is not shown again, the video plays. */
    fun mobileHintRead() {
        settings.setMobileHintSeen()
        _ui.update { it?.copy(mobileHint = false) }
        if (_ui.value?.aheadWait != true) _player.value?.play()
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

    /**
     * The screen asked for Android's VPN consent ([MediaPlayerTrouble.VPN] with [MediaPlayerUi.vpnConsent]): given, the
     * VPN starts and the video opens again; refused, „Unterwegs nur über VPN“ stays.
     */
    fun vpnConsent(granted: Boolean) {
        main.launch {
            val gate = hub.consent(granted)
            if (gate == MediaGate.OPEN) retry() else _ui.update { it?.copy(trouble = MediaPlayerTrouble.VPN, vpnConsent = false, loading = false) }
        }
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
        gateJob?.cancel()
        countdownJob?.cancel()
        pending = null
        aheadJob?.cancel()
        asking?.complete(null)
        asking = null
        val p = _player.value
        if (p != null) {
            reporter.stop(p, at)
            p.removeListener(listener)
            _player.value = null
            p.release()
        }
        // after the player: what was loaded ahead goes with its key (nothing outlives the playback)
        ahead?.release()
        ahead = null
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

    private companion object {
        /** How often the card „Auf dem Gerät mitgeladen“ (and the wait of „Original mit Mitladen“) is updated. */
        const val AHEAD_TICK_MS = 1_000L
    }
}

/** Choosing a video's tracks in Media3 for the phone's player. */
object MediaPlayerTracks {
    /** The file's own sound / subtitle by place (Jellyfin's stream of that place), or subtitles off. */
    fun apply(params: TrackSelectionParameters, tracks: Tracks, want: PhoneOpen.Ready): TrackSelectionParameters {
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
