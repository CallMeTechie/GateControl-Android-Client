package com.gatecontrol.android.media

import com.gatecontrol.android.media.offline.MediaOffline
import com.gatecontrol.android.media.offline.OfflineItem
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
        /** A download on the phone (question 22 a): its file's own tracks, the progress kept here first. */
        val offline: Boolean = false,
        /** „Original mit Mitladen“: the player loads ahead first and starts once the rest goes through. */
        val wait: Boolean = false,
    ) : PhoneOpen

    /** „Abbrechen“ of „Wie abspielen?“: nothing plays. */
    data object Cancelled : PhoneOpen

    data object Offline : PhoneOpen

    /**
     * Away without the VPN (Netz-Regel): the app tried to start it itself and it did not come up, or Android's consent
     * is missing ([consent]: the player asks for it). „Unterwegs nur über VPN“, not „offline“.
     */
    data class Vpn(val consent: Boolean = false) : PhoneOpen
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
    /** The downloads: a ready one plays from the phone, also without a connection. */
    private val offline: MediaOffline? = null,
    /** What plays when the original does not go through away (decision 89 b); [MediaAwayOriginal.ASK]: asked. */
    private val awayOriginal: () -> MediaAwayOriginal = { MediaAwayOriginal.ASK },
    /** The space for loading ahead in this playback (bytes); 0: off, nothing is asked. */
    private val aheadBytes: () -> Long = { 0L },
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

    /** The videos played from a download. */
    private val local = HashMap<Int, OfflineItem>()

    /** Chosen in the player (or on the detail page for the first): per index, the sound and subtitle. */
    private val audio = HashMap<Int, Int?>()
    private val text = HashMap<Int, Int?>()

    /** The detail page's "Original" / "Angepasst" for the first title; gone once a quality is chosen here. */
    private var firstOriginal: Boolean? = queue.original

    /** „Wie abspielen?“ answered for this queue: not asked again for the next episode. */
    private var awayChoice: MediaAwayOriginal? = null

    /** Per index, what „Wie abspielen?“ gave (a new sound or subtitle asks nothing again). */
    private val away = HashMap<Int, MediaAwayOriginal>()

    /** The quality was chosen in the player for these: nothing is asked. */
    private val chosenHere = HashSet<Int>()

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

    /**
     * The stream of video [i] from [from] (null: where it was left). Away, when the original does not go through and
     * loading ahead is on, [ask] asks „Wie abspielen?“ (decision 89 b) unless the answer is kept; null: „Abbrechen“.
     */
    suspend fun open(i: Int, from: Long? = null, ask: suspend (MediaAwayFit) -> MediaAwayOriginal? = { null }): PhoneOpen {
        val src = source ?: return PhoneOpen.Failed
        val lib = library ?: return PhoneOpen.Failed
        val title = items[i]
        offline?.ready(src.id, title.id)?.let { return openLocal(i, it, from) }
        hub.check(src)
        // away with the VPN off: the app starts it itself, as on opening Medien
        if (hub.route(src) == MediaRoute.BLOCKED) {
            when (hub.prepare(src)) {
                MediaGate.OPEN -> Unit
                MediaGate.CONSENT -> return PhoneOpen.Vpn(consent = true)
                MediaGate.CONNECTING, MediaGate.BLOCKED -> return PhoneOpen.Vpn()
            }
            if (hub.route(src) == MediaRoute.BLOCKED) return PhoneOpen.Vpn()
        }
        val link = hub.link.value
        val cell = cellular()
        // the file first: its bit rate decides the quality, its streams the series' sound and subtitle
        val detail = when (val d = lib.detail(title.id)) {
            is MediaResult.Ok -> d.value
            MediaResult.Unauthorized -> return PhoneOpen.SignedOut
            MediaResult.Offline -> return offline(src)
            else -> return PhoneOpen.Failed
        }
        val version = detail.version
        // the picture is one the phone's decoders do not take (H.264 10-Bit, say): converted at once, never copied
        // (packing it anew would hang the same); the waiting in the player stays as the net for what is not seen here
        if (!fallbacks.containsKey(i) && src.kind.canConvert) {
            com.gatecontrol.android.media.jellyfin.JellyfinProfile.videoProblem(version?.video, hub.caps())?.let { why ->
                fallbacks[i] = MediaFallback.CONVERT
                problems[i] = why
                timber.log.Timber.i("Medien: the phone does not play %s itself: converted at once", why)
            }
        }
        val tracks = title.seriesId?.let { hub.store.trackChoice(it) }
        val a = if (audio.containsKey(i)) audio[i] else version?.let { MediaTracks.pickAudio(it, tracks) }
        val s = if (text.containsKey(i)) text[i] else version?.let { MediaTracks.pickSubtitle(it, tracks) }
        var setting = when {
            i == queue.at && firstOriginal == true -> MediaQualitySetting.ORIGINAL
            i == queue.at && firstOriginal == false -> MediaQualitySetting.AUTO
            else -> quality()
        }
        val limit = MediaPlayerLogic.limit(src, cell, link.atHome, mobileCap())
        val start = from ?: startAt(i)
        // the original does not play here anyway (converted at once or after it hung): nothing to ask
        val choice = if (fallback(i) != MediaFallback.NONE) null else awayChoice(i, setting, link, version?.bitrate, (detail.title.runtimeMs ?: title.runtimeMs)?.minus(start ?: 0), src, limit, ask)
        if (askedAndCancelled) {
            askedAndCancelled = false
            return PhoneOpen.Cancelled
        }
        when (choice) {
            MediaAwayOriginal.CONVERTED -> setting = MediaQualitySetting.AUTO
            MediaAwayOriginal.WAIT, MediaAwayOriginal.NOW -> setting = MediaQualitySetting.ORIGINAL
            else -> Unit
        }
        val base = MediaQuality.cap(setting, link.atHome == true, link.bps, version?.bitrate, src.alwaysOriginal, src.kind.canConvert)
        val cap = MediaPlayerLogic.capped(base, limit)
        val plan = when (val p = lib.play(MediaPlay(title.id, version?.id, startMs = 0, audioIndex = a, subtitleIndex = MediaPlayerLogic.subtitleFor(version, s, fallback(i)), maxBitrate = cap, fallback = fallback(i)))) {
            is MediaResult.Ok -> p.value
            MediaResult.Unauthorized -> return PhoneOpen.SignedOut
            MediaResult.Offline -> return offline(src)
            else -> return PhoneOpen.Failed
        }
        plans[i] = plan
        audio[i] = plan.audioIndex
        text[i] = plan.subtitleIndex ?: s
        val net = MediaPlayerLogic.net(link.atHome, cell)
        if (share) {
            val via = if (lib.files()?.kind == MediaSourceKind.WEBDAV) "WebDAV" else "SMB"
            val line = MediaPlayerLogic.line(plan, net, link.bps, hub.caps(), canConvert = false, limitBps = null, askedBps = cap, shareVia = via)
            lines[i] = line
            return PhoneOpen.Ready(plan, line, start, shareChoice = tracks)
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
            wait = choice == MediaAwayOriginal.WAIT && !plan.converted && from == null,
        )
    }

    private var askedAndCancelled = false

    /**
     * What „Wie abspielen?“ says for video [i]: null when there is nothing to ask (home, it fits, a fixed step, off, the
     * mobile limit decides) or it was cancelled ([askedAndCancelled]). Kept for the index and for the rest of the queue.
     */
    private suspend fun awayChoice(
        i: Int,
        setting: MediaQualitySetting,
        link: MediaLink,
        originalBps: Long?,
        remainingMs: Long?,
        src: MediaSource,
        limit: Long?,
        ask: suspend (MediaAwayFit) -> MediaAwayOriginal?,
    ): MediaAwayOriginal? {
        away[i]?.let { return it }
        if (i in chosenHere) return null
        // on mobile data with a limit below the original: the limit decides (question 28)
        if (limit != null && (originalBps == null || originalBps > limit)) return null
        val fit = MediaPlayAhead.fit(link.atHome, link.bps, originalBps, remainingMs, setting, src.kind.canConvert, src.alwaysOriginal, aheadBytes()) ?: return null
        val kept = awayChoice ?: awayOriginal().takeIf { it != MediaAwayOriginal.ASK }
        // „mit Mitladen“ kept, but this one does not fit the space: asked
        val usable = kept?.takeIf { it != MediaAwayOriginal.WAIT || fit.waitMs != null }
        val c = usable ?: ask(fit)
        if (c == null) {
            askedAndCancelled = true
            return null
        }
        away[i] = c
        awayChoice = c
        return c
    }

    /** Not reachable: away the VPN went down meanwhile („Unterwegs nur über VPN“), else offline. */
    private fun offline(src: MediaSource): PhoneOpen = if (hub.route(src) == MediaRoute.BLOCKED) PhoneOpen.Vpn() else PhoneOpen.Offline

    /**
     * Video [i] from its download: the file as it is, the sound and subtitle chosen on the detail page by their place
     * (the original keeps all its tracks), else the series' languages.
     */
    private fun openLocal(i: Int, item: OfflineItem, from: Long?): PhoneOpen {
        val o = offline ?: return PhoneOpen.Failed
        val plan = o.localPlan(item)
        plans[i] = plan
        local[i] = item
        val line = MediaPlayLine(net = null, offline = item.quality.label, video = MediaQuality.videoLabel(plan.version.video))
        lines[i] = line
        val start = from ?: if (i == queue.at && queue.startMs != null) queue.startMs else o.startAt(item)
        val choice = items[i].seriesId?.let { hub.store.trackChoice(it) }
        val chosen = !item.quality.converted && (audio[i] != null || text[i] != null)
        if (!chosen) return PhoneOpen.Ready(plan, line, start, shareChoice = choice, offline = true)
        val subtitle = text[i]
        return PhoneOpen.Ready(
            plan = plan,
            line = line,
            startMs = start,
            audioOrdinal = MediaTracks.ordinal(plan.version, audio[i]),
            textOrdinal = MediaTracks.ordinal(plan.version, subtitle),
            textOff = subtitle == MediaPlay.SUBTITLES_OFF,
            offline = true,
        )
    }

    /** How video [i] is asked after it did not start (per index: a new sound or quality keeps it). */
    private val fallbacks = HashMap<Int, MediaFallback>()

    fun fallback(i: Int): MediaFallback = fallbacks[i] ?: MediaFallback.NONE

    /** Why video [i]'s picture does not play on the phone ("H.264 10-Bit (High 10)"); null: not known. */
    private val problems = HashMap<Int, String>()

    fun problem(i: Int): String? = problems[i] ?: plans[i]?.let { com.gatecontrol.android.media.jellyfin.JellyfinProfile.videoProblem(it.version.video, hub.caps()) }

    /**
     * Video [i] did not start (no picture, a fault): the next way to ask for it ([MediaPlayerLogic.nextFallback]),
     * kept for it; null when there is none (a share or a download plays only the file, everything is converted already).
     */
    fun fallBack(i: Int): MediaFallback? {
        if (share || local.containsKey(i) || source?.kind?.canConvert != true) return null
        val plan = plans[i] ?: return null
        val next = MediaPlayerLogic.nextFallback(plan, fallback(i)) ?: return null
        fallbacks[i] = next
        return next
    }

    /** „Nochmal“: video [i] from the file itself again. */
    fun resetFallback(i: Int) {
        fallbacks.remove(i)
        problems.remove(i)
    }

    /** The codecs of video [i] as last asked ("HEVC Main 10 · DTS 5.1"); null: not known. */
    fun codecs(i: Int): String? = plans[i]?.let { MediaPlayerLogic.codecs(it.version, it.audioIndex) }

    /** Whether video [i] plays from a download. */
    fun isLocal(i: Int): Boolean = local.containsKey(i)

    /** The sheet of a Jellyfin title (a share or a download: null, its file's own tracks are shown). */
    fun choices(i: Int): MediaPlayerChoices? {
        if (share || local.containsKey(i)) return null
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
        // chosen here: „Wie abspielen?“ is not asked again for this video, the quality decides
        away.remove(i)
        chosenHere += i
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
        local[i]?.let { item ->
            // watched from the phone: kept here, told to Jellyfin at the next contact
            val o = offline ?: return
            val length = durationMs.takeIf { it > 0 } ?: item.title.runtimeMs ?: 0
            // off the main thread: the encrypted index is written
            hub.later { o.record(item.id, positionMs, length, stopped = event == PlaybackEvent.STOP) }
            return
        }
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
