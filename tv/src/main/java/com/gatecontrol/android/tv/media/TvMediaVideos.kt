package com.gatecontrol.android.tv.media

import android.content.Context
import android.content.res.Resources
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.gatecontrol.android.media.MediaAudioFate
import com.gatecontrol.android.media.MediaGate
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaLibrary
import com.gatecontrol.android.media.MediaPlan
import com.gatecontrol.android.media.MediaPlay
import com.gatecontrol.android.media.MediaProgress
import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaQualitySetting
import com.gatecontrol.android.media.MediaQueue
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaRoute
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaTracks
import com.gatecontrol.android.media.MediaVersion
import com.gatecontrol.android.media.PlayMethod
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import com.gatecontrol.android.media.player.PlaybackEvent
import com.gatecontrol.android.media.player.MediaPlayers
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.ui.drive.TvChoice
import com.gatecontrol.android.tv.ui.drive.TvChoiceKind
import com.gatecontrol.android.tv.ui.drive.TvChoices
import com.gatecontrol.android.tv.ui.drive.TvOpen
import com.gatecontrol.android.tv.ui.drive.TvVideoSource
import java.util.Locale

/**
 * Films and episodes of a media source in the player (mockup 9): direct or converted on the NAS by the quality
 * (automatically by the measured bandwidth, or fixed), the sound and subtitle chosen (remembered per series), the
 * line "Direkt abgespielt · 4K HDR · DTS durchgereicht" / "Von der NAS umgerechnet: 1080p · 8 Mbit/s", the progress to
 * Jellyfin and "Nächste Folge in 10 s".
 */
class TvMediaVideos(
    private val queue: MediaQueue,
    private val media: MediaHub,
    private val res: Resources,
) : TvVideoSource {

    private val source: MediaSource? = media.store.source(queue.sourceId)

    /** Asked each time: a share's library changes with the network (at home SMB, away WebDAV). */
    private val library: MediaLibrary? get() = source?.let(media::library)

    /** An SMB or WebDAV share: the original only, the file's own tracks in the player's panel, progress on the TV. */
    private val share: Boolean = source?.kind?.share == true
    private val items: List<MediaTitle> get() = queue.items

    /** The plan of the video opened last, per index. */
    private val plans = HashMap<Int, MediaPlan>()

    /** Chosen in the player (or on the detail page for the first): per index, the sound and subtitle. */
    private val audio = HashMap<Int, Int?>()
    private val text = HashMap<Int, Int?>()

    /** The quality for this playback: the setting, or the detail page's "Original" / "Angepasst" for the first. */
    private var quality: MediaQualitySetting = media.quality.value
    private var firstOriginal: Boolean? = queue.original

    private val caps: JellyfinDeviceCaps by lazy { media.caps() }

    init {
        audio[queue.at] = queue.audioIndex
        text[queue.at] = queue.subtitleIndex
    }

    override val count: Int get() = items.size
    override fun key(i: Int) = "m:${queue.sourceId}:${items[i].id}"

    override fun name(i: Int): String {
        val t = items[i]
        return if (t.kind == MediaTitle.Kind.EPISODE && t.seriesName != null) {
            val se = listOfNotNull(t.season?.let { "S$it" }, t.episode?.let { "E$it" }).joinToString("")
            listOf(t.seriesName, se, t.name).filter { !it.isNullOrBlank() }.joinToString(" · ")
        } else {
            t.name
        }
    }

    override fun typeName(i: Int): String = plans[i]?.version?.container?.uppercase(Locale.ROOT) ?: "Video"

    @OptIn(UnstableApi::class)
    override fun factory(context: Context): DataSource.Factory? {
        if (library == null) return null
        // a share's file through its own connection (SMB, or WebDAV with its access), read at the player's place
        return MediaPlayers.dataSource(media, source!!)
    }

    /** Where Jellyfin has it (or the detail page's "Von vorn"); only for the first title of the queue. */
    override fun startAt(i: Int): Long? {
        if (i == queue.at && queue.startMs != null) return queue.startMs
        return items[i].positionMs.takeIf { items[i].resumable }
    }

    override suspend fun open(i: Int): TvOpen {
        val lib = library ?: return TvOpen.Failed
        val title = items[i]
        val src = source!!
        media.check(src)
        // away with the VPN off: the TV starts it itself, as on opening Medien
        if (media.route(src) == MediaRoute.BLOCKED && (media.prepare(src) != MediaGate.OPEN || media.route(src) == MediaRoute.BLOCKED)) return TvOpen.Vpn
        val link = media.link.value
        // the file first: its bit rate decides the quality, its streams the series' sound and subtitle
        val detail = when (val d = lib.detail(title.id)) {
            is MediaResult.Ok -> d.value
            MediaResult.Unauthorized -> return TvOpen.SignedOut
            MediaResult.Offline -> return if (media.route(src) == MediaRoute.BLOCKED) TvOpen.Vpn else TvOpen.Offline
            else -> return TvOpen.Failed
        }
        val version = detail.version
        val choice = title.seriesId?.let { media.store.trackChoice(it) }
        val a = if (audio.containsKey(i)) audio[i] else version?.let { MediaTracks.pickAudio(it, choice) }
        val s = if (text.containsKey(i)) text[i] else version?.let { MediaTracks.pickSubtitle(it, choice) }
        val setting = when {
            i == queue.at && firstOriginal == true -> MediaQualitySetting.ORIGINAL
            // "Angepasst (NAS rechnet um)": the step for the measured line
            i == queue.at && firstOriginal == false -> MediaQualitySetting.AUTO
            else -> quality
        }
        val cap = MediaQuality.cap(setting, link.atHome == true, link.bps, version?.bitrate, src.alwaysOriginal, src.kind.canConvert)
        val plan = when (val p = lib.play(MediaPlay(title.id, version?.id, startMs = 0, audioIndex = a, subtitleIndex = s, maxBitrate = cap))) {
            is MediaResult.Ok -> p.value
            MediaResult.Unauthorized -> return TvOpen.SignedOut
            MediaResult.Offline -> return if (media.route(src) == MediaRoute.BLOCKED) TvOpen.Vpn else TvOpen.Offline
            else -> return TvOpen.Failed
        }
        plans[i] = plan
        audio[i] = plan.audioIndex
        text[i] = plan.subtitleIndex ?: s
        if (share) {
            val via = if (lib.files()?.kind == com.gatecontrol.android.media.MediaSourceKind.WEBDAV) "WebDAV" else "SMB"
            return TvOpen.Ready(MediaPlayers.item(plan, key(i)), info = res.getString(R.string.tv_media_info_share, via))
        }
        val direct = plan.method == PlayMethod.DIRECT_PLAY
        val subtitle = text[i]
        return TvOpen.Ready(
            item = MediaPlayers.item(plan, key(i)),
            info = info(plan),
            // played directly the file brings all its tracks: the chosen ones by their place
            audioOrdinal = if (direct) MediaTracks.ordinal(plan.version, plan.audioIndex) else null,
            textOrdinal = if (direct && plan.sideSubtitles.isEmpty()) MediaTracks.ordinal(plan.version, subtitle) else null,
            textOff = subtitle == MediaPlay.SUBTITLES_OFF || subtitle == null && plan.sideSubtitles.isEmpty() && direct,
        )
    }

    /** "Direkt abgespielt · 4K HDR · DTS durchgereicht" or "Von der NAS umgerechnet: 1080p · 8 Mbit/s". */
    internal fun info(plan: MediaPlan): String {
        val v = plan.version
        return when (plan.method) {
            PlayMethod.TRANSCODE -> {
                val bps = plan.bitrate
                if (bps != null) {
                    res.getString(R.string.tv_media_info_converted, MediaQuality.resolution(bps), MediaQuality.label(bps))
                } else {
                    res.getString(R.string.tv_media_info_converted_plain)
                }
            }
            PlayMethod.DIRECT_PLAY, PlayMethod.DIRECT_STREAM -> {
                val parts = mutableListOf(res.getString(R.string.tv_media_info_direct))
                MediaQuality.videoLabel(v.video)?.let { parts += it }
                v.audio.firstOrNull { it.index == plan.audioIndex }?.let { a ->
                    if (MediaTracks.fate(a, caps) == MediaAudioFate.PASSTHROUGH) parts += res.getString(R.string.tv_media_info_passthrough, MediaTracks.codecLabel(a.codec))
                }
                parts.joinToString(" · ")
            }
        }
    }

    override fun choices(i: Int): TvChoices? {
        // a share's file: the player's own sound tracks and subtitles (no server lists them)
        if (share) return null
        val plan = plans[i] ?: return null
        val v = plan.version
        val converting = plan.converted
        val audioChoices = v.audio.map { s ->
            TvChoice(TvChoiceKind.AUDIO, s.index.toString(), trackName(s, res.getString(R.string.tv_player_track)) + " · " + MediaTracks.audioLabel(s), audioNote(s, converting), s.index == plan.audioIndex)
        }
        val chosenText = text[i]
        val off = TvChoice(TvChoiceKind.TEXT, OFF, res.getString(R.string.tv_media_subtitles_off), selected = chosenText == null || chosenText == MediaPlay.SUBTITLES_OFF)
        val textChoices = listOf(off) + v.subtitles.map { s ->
            TvChoice(TvChoiceKind.TEXT, s.index.toString(), trackName(s, res.getString(R.string.tv_player_subtitle)), subtitleNote(s), s.index == chosenText)
        }
        val canConvert = source?.kind?.canConvert == true && source.alwaysOriginal.not()
        val qualities = if (!canConvert) {
            emptyList()
        } else {
            MediaQualitySetting.entries.map { q ->
                TvChoice(TvChoiceKind.QUALITY, q.wire, qualityLabel(q), selected = q == quality)
            }
        }
        return TvChoices(audioChoices, textChoices, qualities)
    }

    private fun qualityLabel(q: MediaQualitySetting): String = when (q) {
        MediaQualitySetting.AUTO -> res.getString(R.string.tv_media_quality_auto)
        MediaQualitySetting.ORIGINAL -> res.getString(R.string.tv_media_quality_original)
        else -> "${MediaQuality.resolution(q.bps!!)} · ${MediaQuality.label(q.bps!!)}"
    }

    /** "Deutsch" (the stream's language), else its title, else "[fallback] n". */
    private fun trackName(s: MediaStream, fallback: String): String {
        val language = s.language?.let { MediaTracks.languageName(it) }
        return s.title?.takeIf { language == null } ?: language ?: "$fallback ${s.index}"
    }

    private fun audioNote(s: MediaStream, converting: Boolean): String? = when (MediaTracks.fate(s, caps)) {
        MediaAudioFate.PASSTHROUGH -> if (converting) null else res.getString(R.string.tv_media_audio_passthrough)
        MediaAudioFate.DECODED -> s.title?.takeIf { s.language != null }
        MediaAudioFate.CONVERTED -> res.getString(R.string.tv_media_audio_converted)
    }

    private fun subtitleNote(s: MediaStream): String {
        val codec = MediaTracks.codecLabel(s.codec) ?: "?"
        return if (s.picture) {
            res.getString(R.string.tv_media_subtitle_picture, codec)
        } else {
            res.getString(R.string.tv_media_subtitle_text, codec)
        }
    }

    override fun choose(i: Int, choice: TvChoice): Boolean {
        val plan = plans[i] ?: return false
        when (choice.kind) {
            TvChoiceKind.AUDIO -> {
                val idx = choice.id.toIntOrNull() ?: return false
                if (idx == plan.audioIndex) return false
                audio[i] = idx
            }
            TvChoiceKind.TEXT -> {
                val idx = if (choice.id == OFF) MediaPlay.SUBTITLES_OFF else choice.id.toIntOrNull() ?: return false
                if (idx == text[i]) return false
                text[i] = idx
            }
            TvChoiceKind.QUALITY -> {
                val q = MediaQualitySetting.fromWire(choice.id)
                if (q == quality && firstOriginal == null) return false
                quality = q
                firstOriginal = null
                media.setQuality(q)
                return true
            }
        }
        remember(i, plan.version)
        return true
    }

    /** The series keeps the sound and subtitle chosen ("merkt sich GateControl je Serie"). */
    private fun remember(i: Int, version: MediaVersion) {
        val seriesId = items[i].seriesId ?: return
        media.store.setTrackChoice(seriesId, MediaTracks.choiceOf(version, audio[i], text[i]))
    }

    override fun report(i: Int, event: PlaybackEvent, positionMs: Long, paused: Boolean, durationMs: Long) {
        val lib = library ?: return
        val plan = plans[i] ?: return
        val p = MediaProgress(plan, positionMs.coerceAtLeast(0), paused, durationMs.coerceAtLeast(0))
        media.later {
            when (event) {
                PlaybackEvent.START -> lib.started(p)
                PlaybackEvent.PROGRESS -> lib.progress(p)
                PlaybackEvent.STOP -> lib.stopped(p)
            }
        }
    }

    /** Episodes: "Nächste Folge in 10 s" (mockup 9); a film has none after it. */
    override val nextCountdownSeconds: Int get() = if (items.any { it.kind == MediaTitle.Kind.EPISODE }) NEXT_SECONDS else 0

    companion object {
        const val NEXT_SECONDS = 10
        private const val OFF = "off"
    }
}
