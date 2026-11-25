package com.gatecontrol.android.media

import com.gatecontrol.android.media.player.PlaybackEvent

/** What opening a video of the queue gave. */
sealed interface PhoneOpen {
    /**
     * The stream of [plan], from [startMs] (null: the start). Played directly the file brings all its tracks: the
     * chosen sound / subtitle by their place ([audioOrdinal], [textOrdinal]) or subtitles off ([textOff]). A share's
     * file takes the series' languages instead ([shareChoice]).
     */
    data class Ready(
        val plan: MediaPlan,
        val line: MediaPlayLine,
        val startMs: Long?,
        val audioOrdinal: Int? = null,
        val textOrdinal: Int? = null,
        val textOff: Boolean = false,
        val shareChoice: MediaTrackChoice? = null,
    ) : PhoneOpen

    data object Offline : PhoneOpen
    data object SignedOut : PhoneOpen
    data object Failed : PhoneOpen
}

/**
 * The films and episodes of one [queue] in the phone's player (mockups 4): direct or converted on the NAS by the
 * quality (automatically by the measured bandwidth, or fixed) and, on mobile data, at most the limit of question 28;
 * the sound and subtitle chosen (remembered per series), the info line and the progress to the source (Jellyfin:
 * "Weiterschauen" on every device; a share: on the phone). No player here: [MediaPlayerSession] has it.
 */
class PhoneMediaVideos(
    val queue: MediaQueue,
    private val hub: MediaHub,
    private val quality: () -> MediaQualitySetting,
    private val setQuality: (MediaQualitySetting) -> Unit,
    private val mobileCap: () -> Long?,
    private val cellular: () -> Boolean,
) {
    val source: MediaSource? = hub.store.source(queue.sourceId)

    /** Asked each time: a share's library changes with the network (at home SMB, away WebDAV). */
    private val library: MediaLibrary? get() = source?.let(hub::library)

    /** An SMB or WebDAV share: the original only, the file's own tracks in the sheet, progress on the phone. */
    val share: Boolean = source?.kind?.share == true

    val items: List<MediaTitle> get() = queue.items
    val count: Int get() = items.size

    private val plans = HashMap<Int, MediaPlan>()
    private val lines = HashMap<Int, MediaPlayLine>()

    /** Chosen in the player (or on the detail page for the first): per index, the sound and subtitle. */
    private val audio = HashMap<Int, Int?>()
    private val text = HashMap<Int, Int?>()

    /** The detail page's "Original" / "Angepasst" for the first title; gone once a quality is chosen here. */
    private var firstOriginal: Boolean? = queue.original

    init {
        // nothing chosen on the detail page ("Weiterschauen"): the series' choice, else the server's default
        queue.audioIndex?.let { audio[queue.at] = it }
        queue.subtitleIndex?.let { text[queue.at] = it }
    }

    fun key(i: Int): String = "m:${queue.sourceId}:${items[i].id}"

    fun plan(i: Int): MediaPlan? = plans[i]

    fun line(i: Int): MediaPlayLine? = lines[i]

    /** Where Jellyfin (or the phone, for a share) has it, or the detail page's "Von vorn"; the place for the first. */
    fun startAt(i: Int): Long? {
        if (i == queue.at && queue.startMs != null) return queue.startMs
        return items[i].positionMs.takeIf { items[i].resumable }
    }

    /** The stream of video [i] from [from] (null: where it was left). */
    suspend fun open(i: Int, from: Long? = null): PhoneOpen {
        val src = source ?: return PhoneOpen.Failed
        val lib = library ?: return PhoneOpen.Failed
        val title = items[i]
        hub.check(src)
        val link = hub.link.value
        val cell = cellular()
        // the file first: its bit rate decides the quality, its streams the series' sound and subtitle
        val detail = when (val d = lib.detail(title.id)) {
            is MediaResult.Ok -> d.value
            MediaResult.Unauthorized -> return PhoneOpen.SignedOut
            MediaResult.Offline -> return PhoneOpen.Offline
            else -> return PhoneOpen.Failed
        }
        val version = detail.version
        val choice = title.seriesId?.let { hub.store.trackChoice(it) }
        val a = if (audio.containsKey(i)) audio[i] else version?.let { MediaTracks.pickAudio(it, choice) }
        val s = if (text.containsKey(i)) text[i] else version?.let { MediaTracks.pickSubtitle(it, choice) }
        val setting = when {
            i == queue.at && firstOriginal == true -> MediaQualitySetting.ORIGINAL
            i == queue.at && firstOriginal == false -> MediaQualitySetting.AUTO
            else -> quality()
        }
        val base = MediaQuality.cap(setting, link.atHome == true, link.bps, version?.bitrate, src.alwaysOriginal, src.kind.canConvert)
        val limit = MediaPlayerLogic.limit(src, cell, link.atHome, mobileCap())
        val cap = MediaPlayerLogic.capped(base, limit)
        val plan = when (val p = lib.play(MediaPlay(title.id, version?.id, startMs = 0, audioIndex = a, subtitleIndex = s, maxBitrate = cap))) {
            is MediaResult.Ok -> p.value
            MediaResult.Unauthorized -> return PhoneOpen.SignedOut
            MediaResult.Offline -> return PhoneOpen.Offline
            else -> return PhoneOpen.Failed
        }
        plans[i] = plan
        audio[i] = plan.audioIndex
        text[i] = plan.subtitleIndex ?: s
        val net = MediaPlayerLogic.net(link.atHome, cell)
        val start = from ?: startAt(i)
        if (share) {
            val via = if (lib.files()?.kind == MediaSourceKind.WEBDAV) "WebDAV" else "SMB"
            val line = MediaPlayerLogic.line(plan, net, link.bps, hub.caps(), canConvert = false, limitBps = null, askedBps = cap, shareVia = via)
            lines[i] = line
            return PhoneOpen.Ready(plan, line, start, shareChoice = choice)
        }
        val line = MediaPlayerLogic.line(plan, net, link.bps, hub.caps(), src.kind.canConvert, limit, cap)
        lines[i] = line
        val direct = plan.method == PlayMethod.DIRECT_PLAY
        val subtitle = text[i]
        return PhoneOpen.Ready(
            plan = plan,
            line = line,
            startMs = start,
            audioOrdinal = if (direct) MediaTracks.ordinal(plan.version, plan.audioIndex) else null,
            textOrdinal = if (direct && plan.sideSubtitles.isEmpty()) MediaTracks.ordinal(plan.version, subtitle) else null,
            textOff = subtitle == MediaPlay.SUBTITLES_OFF || subtitle == null && plan.sideSubtitles.isEmpty() && direct,
        )
    }

    /** The sheet of a Jellyfin title (a share: null, its file's own tracks are shown). */
    fun choices(i: Int): MediaPlayerChoices? {
        if (share) return null
        val plan = plans[i] ?: return null
        val src = source ?: return null
        val canConvert = src.kind.canConvert
        val qualities = if (canConvert && !src.alwaysOriginal) MediaQualitySetting.entries.toList() else emptyList()
        return MediaPlayerChoices(
            audio = plan.version.audio,
            subtitles = plan.version.subtitles,
            audioIndex = plan.audioIndex,
            subtitleIndex = text[i],
            qualities = qualities,
            quality = currentQuality(i),
            canConvert = canConvert,
            caps = hub.caps(),
        )
    }

    /** The quality this video was asked with. */
    fun currentQuality(i: Int): MediaQualitySetting = when {
        i == queue.at && firstOriginal == true -> MediaQualitySetting.ORIGINAL
        i == queue.at && firstOriginal == false -> MediaQualitySetting.AUTO
        else -> quality()
    }

    /** Another sound: true when the video must be asked for again (the player then goes on at the same place). */
    fun chooseAudio(i: Int, index: Int): Boolean {
        val plan = plans[i] ?: return false
        if (index == plan.audioIndex) return false
        audio[i] = index
        remember(i, plan.version)
        return true
    }

    /** Another subtitle ([MediaPlay.SUBTITLES_OFF]: none): true when the video must be asked for again. */
    fun chooseSubtitle(i: Int, index: Int): Boolean {
        val plan = plans[i] ?: return false
        val now = text[i] ?: MediaPlay.SUBTITLES_OFF
        if (index == now) return false
        text[i] = index
        remember(i, plan.version)
        return true
    }

    /** Another quality: kept as the setting (Medien › Quellen), true when the video must be asked for again. */
    fun chooseQuality(i: Int, q: MediaQualitySetting): Boolean {
        if (q == currentQuality(i) && firstOriginal == null) return false
        firstOriginal = null
        setQuality(q)
        return plans.containsKey(i)
    }

    /** A share's file: the series keeps the languages chosen in the player. */
    fun rememberShare(i: Int, audioLanguage: String?, subtitleLanguage: String?, subtitlesOff: Boolean) {
        val seriesId = items.getOrNull(i)?.seriesId ?: return
        hub.store.setTrackChoice(seriesId, MediaTrackChoice(audioLanguage, subtitleLanguage, subtitlesOff))
    }

    /** The series keeps the sound and subtitle chosen ("merkt sich GateControl je Serie"). */
    private fun remember(i: Int, version: MediaVersion) {
        val seriesId = items[i].seriesId ?: return
        hub.store.setTrackChoice(seriesId, MediaTracks.choiceOf(version, audio[i], text[i]))
    }

    /** The progress of video [i] to its source, after the player is gone too. */
    fun report(i: Int, event: PlaybackEvent, positionMs: Long, paused: Boolean, durationMs: Long) {
        val lib = library ?: return
        val plan = plans[i] ?: return
        val p = MediaProgress(plan, positionMs.coerceAtLeast(0), paused, durationMs.coerceAtLeast(0))
        hub.later {
            when (event) {
                PlaybackEvent.START -> lib.started(p)
                PlaybackEvent.PROGRESS -> lib.progress(p)
                PlaybackEvent.STOP -> lib.stopped(p)
            }
        }
    }
}
