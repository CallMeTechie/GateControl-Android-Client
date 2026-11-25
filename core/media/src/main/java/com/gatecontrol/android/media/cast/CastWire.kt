package com.gatecontrol.android.media.cast

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/*
 * "Auf GateControl TV / PC abspielen" (stage 4, question 23a): the messages between the phone (the controller) and
 * an own device with GateControl (the receiver: GateControl TV, the Windows app), version 1 of the cast contract
 * (sections 2 and 3). They travel through the plugin „Medien“ (`POST v1/cast/{session}/message`, received as the
 * realtime event or the silent push `media.cast`, see [CastEnvelopes]). Only the source, the title and the place go
 * along, never an access: the receiver plays from its own source of the plugin with the same id.
 */

/**
 * Where a title comes from: the plugin's source id ([id], `jf-1`, `sh-2`), the same on every device; the receiver plays
 * it from its own source with that id. The other fields only go along for logs and older readers. Never a token or
 * password (the plugin refuses such fields).
 */
data class CastSourceRef(
    val id: String,
    /** `jellyfin`, `smb`, `webdav` (or the plugin's `share`), for information only. */
    val kind: String? = null,
    /** Jellyfin's `ServerId`, for information only. */
    val serverId: String? = null,
    /** The server's or share's name as the controller shows it. */
    val name: String? = null,
)

/** The title to play. [id]: Jellyfin's item id; [path]: a share's file below its root (`Filme/Kobaltnacht.mkv`). */
data class CastItem(
    val id: String,
    val name: String,
    val kind: String = KIND_VIDEO,
    val path: String? = null,
    val seriesName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val runtimeMs: Long? = null,
) {
    companion object {
        const val KIND_MOVIE = "movie"
        const val KIND_EPISODE = "episode"
        const val KIND_VIDEO = "video"
    }
}

/** A sound track or subtitle the receiver offers; [id] is the receiver's own, the controller only echoes it. */
data class CastTrack(val id: String, val label: String, val selected: Boolean = false)

/** What the controller asks for. */
enum class CastCmd(val wire: String) {
    PLAY("play"),
    PAUSE("pause"),
    /** To [CastMessage.Command.positionMs]. */
    SEEK("seek"),
    /** By [CastMessage.Command.deltaMs] (negative: back). */
    SEEK_BY("seekBy"),
    /** The sound track [CastMessage.Command.track]. */
    AUDIO("audio"),
    /** The subtitle [CastMessage.Command.track], [CastProtocol.TRACK_OFF]: none. */
    SUBTITLE("subtitle"),
    STOP("stop"),
    /** Only an answer: the receiver sends its state now. */
    STATUS("status"),
    ;

    companion object {
        fun fromWire(v: String?): CastCmd? = entries.firstOrNull { it.wire == v }
    }
}

/** Where the receiver's playback is. */
enum class CastPhase(val wire: String) {
    LOADING("loading"),
    PLAYING("playing"),
    PAUSED("paused"),
    BUFFERING("buffering"),
    ENDED("ended"),
    STOPPED("stopped"),
    FAILED("failed"),
    ;

    /** The session is over: no more commands. */
    val final: Boolean get() = this == ENDED || this == STOPPED || this == FAILED

    companion object {
        fun fromWire(v: String?): CastPhase? = entries.firstOrNull { it.wire == v }
    }
}

sealed interface CastMessage {
    /** The session the controller made with [Play]; every later message names it. */
    val session: String

    /** Per sender and session, growing: a receiver drops what is not newer than the last it took. */
    val seq: Long

    /** The sender's clock (ms since 1970), only for logs and ages. */
    val at: Long

    /** "Start [item] of [source] at [positionMs]" (controller → receiver). */
    data class Play(
        override val session: String,
        override val seq: Long,
        override val at: Long,
        val source: CastSourceRef,
        val item: CastItem,
        val positionMs: Long,
        /** Jellyfin's stream index of the sound; null: the receiver's own choice. */
        val audio: Int? = null,
        /** Jellyfin's stream index of the subtitle, [CastProtocol.SUBTITLE_OFF]: none; null: the receiver's own choice. */
        val subtitle: Int? = null,
        /** The controller's name ("Pixel 8"), shown on the receiver. */
        val from: String? = null,
    ) : CastMessage

    /** A remote key (controller → receiver). */
    data class Command(
        override val session: String,
        override val seq: Long,
        override val at: Long,
        val cmd: CastCmd,
        val positionMs: Long? = null,
        val deltaMs: Long? = null,
        val track: String? = null,
    ) : CastMessage

    /** What the receiver plays now (receiver → controller). */
    data class State(
        override val session: String,
        override val seq: Long,
        override val at: Long,
        val phase: CastPhase,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val title: String? = null,
        val audio: List<CastTrack> = emptyList(),
        val subtitles: List<CastTrack> = emptyList(),
        /** Why it [CastPhase.FAILED] (or stopped): one of [CastProtocol]'s `ERROR_*`. */
        val error: String? = null,
        /** The receiver's name ("Wohnzimmer-TV"). */
        val device: String? = null,
    ) : CastMessage
}

/** The constants of version 1 of the protocol. */
object CastProtocol {
    const val VERSION = 1
    const val TYPE_PLAY = "cast.play"
    const val TYPE_COMMAND = "cast.cmd"
    const val TYPE_STATE = "cast.state"

    /** The subtitle "Aus" as a track id of [CastCmd.SUBTITLE]. */
    const val TRACK_OFF = "off"

    /** "No subtitle" in [CastMessage.Play.subtitle] (as Jellyfin's `SubtitleStreamIndex=-1`). */
    const val SUBTITLE_OFF = -1

    /** The source is not set up on the receiver (no Jellyfin server or share that matches). */
    const val ERROR_SOURCE_MISSING = "source_missing"

    /** The receiver's access to the source is gone: sign in again there ("Gerät anmelden", Quick Connect). */
    const val ERROR_SIGNED_OUT = "signed_out"

    /** The source does not have the title (any more). */
    const val ERROR_NOT_FOUND = "not_found"

    /** The receiver cannot reach the source now. */
    const val ERROR_OFFLINE = "offline"

    /** The receiver cannot play this kind of source or file. */
    const val ERROR_UNSUPPORTED = "unsupported"

    /** Another device took over the receiver ("stopped"). */
    const val ERROR_TAKEN_OVER = "taken_over"

    /** The person closed the player on the receiver itself ("stopped"). */
    const val ERROR_CLOSED = "closed"

    /** The receiver could not bring its player to the front (another app is open there): open GateControl on it. */
    const val ERROR_BLOCKED = "blocked"

    const val ERROR_FAILED = "failed"

    /** A message older than this (by the sender's time `at`) is not acted on: the receiver was offline. */
    const val PLAY_MAX_AGE_MS = 120_000L
    const val COMMAND_MAX_AGE_MS = 60_000L

    /** The plugin takes a message body of at most this many bytes (JSON, UTF-8); more: `too_large`. */
    const val MAX_BYTES = 3_000

    /** While it plays the receiver reports at least this often (otherwise only on a change). */
    const val HEARTBEAT_MS = 300_000L

    /** The controller waits this long for the first state after [CastMessage.Play]. */
    const val ANSWER_TIMEOUT_MS = 25_000L

    /** Without any state for this long a playing receiver counts as gone. */
    const val STALE_MS = 2 * HEARTBEAT_MS + 60_000L

    private val SESSION_RE = Regex("^[A-Za-z0-9_-]{8,40}$")
    private val SOURCE_RE = Regex("^[a-z]{1,8}-[A-Za-z0-9_-]{1,40}$")
    private val TRACK_RE = Regex("^[A-Za-z0-9:._-]{1,40}$")
    private val ERROR_RE = Regex("^[a-z_]{1,32}$")

    /** A new session id (24 random letters and digits). */
    fun newSession(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(24)

    fun validSession(s: String?): Boolean = s != null && SESSION_RE.matches(s)
    fun validTrack(s: String?): Boolean = s != null && TRACK_RE.matches(s)
    fun validError(s: String?): Boolean = s != null && ERROR_RE.matches(s)
    fun validSource(s: String?): Boolean = s != null && SOURCE_RE.matches(s)
}

/** [CastMessage] to and from its JSON (pure; the envelope is [CastEnvelopes]'). Unknown fields are ignored. */
object CastWire {
    private const val MAX_TEXT = 300
    private const val MAX_ID = 200
    private const val MAX_PATH = 1_000
    private const val MAX_TRACKS = 40
    private const val MAX_POSITION = 7L * 24 * 3_600_000

    fun encode(m: CastMessage): String = toJson(m).toString()

    /** The size the plugin counts ([CastProtocol.MAX_BYTES]). */
    fun bytes(m: CastMessage): Int = encode(m).toByteArray(Charsets.UTF_8).size

    /**
     * [m] made small enough for the plugin ([max] bytes): a state loses tracks from the end (the chosen ones stay, the
     * subtitle `off` too), a start its extras and then a shorter name. Null when even that is too big.
     */
    fun fit(m: CastMessage, max: Int = CastProtocol.MAX_BYTES): CastMessage? {
        if (bytes(m) <= max) return m
        return when (m) {
            is CastMessage.State -> {
                var s: CastMessage.State = m
                while (bytes(s) > max) {
                    val audio = s.audio.droppable()
                    val subtitles = s.subtitles.droppable()
                    s = when {
                        subtitles >= 0 && (audio < 0 || s.subtitles.size >= s.audio.size) -> s.copy(subtitles = s.subtitles.filterIndexed { i, _ -> i != subtitles })
                        audio >= 0 -> s.copy(audio = s.audio.filterIndexed { i, _ -> i != audio })
                        s.title != null -> s.copy(title = null)
                        else -> return null
                    }
                }
                s
            }
            is CastMessage.Play -> {
                var p = m.copy(from = null, source = m.source.copy(name = null, serverId = null), item = m.item.copy(seriesName = null))
                while (bytes(p) > max) {
                    if (p.item.name.length <= 20) return null
                    p = p.copy(item = p.item.copy(name = p.item.name.take(p.item.name.length / 2)))
                }
                p
            }
            is CastMessage.Command -> null
        }
    }

    /** The last track that may go (not chosen, not `off`); -1: none. */
    private fun List<CastTrack>.droppable(): Int = indexOfLast { !it.selected && it.id != CastProtocol.TRACK_OFF }

    fun toJson(m: CastMessage): JsonObject {
        val o = JsonObject()
        o.addProperty("v", CastProtocol.VERSION)
        o.addProperty("session", m.session)
        o.addProperty("seq", m.seq)
        o.addProperty("at", m.at)
        when (m) {
            is CastMessage.Play -> {
                o.addProperty("type", CastProtocol.TYPE_PLAY)
                o.add("source", JsonObject().apply {
                    addProperty("id", m.source.id)
                    m.source.kind?.let { addProperty("kind", it) }
                    m.source.serverId?.let { addProperty("serverId", it) }
                    m.source.name?.let { addProperty("name", it) }
                })
                o.add("item", JsonObject().apply {
                    addProperty("id", m.item.id)
                    addProperty("name", m.item.name)
                    addProperty("kind", m.item.kind)
                    m.item.path?.let { addProperty("path", it) }
                    m.item.seriesName?.let { addProperty("seriesName", it) }
                    m.item.season?.let { addProperty("season", it) }
                    m.item.episode?.let { addProperty("episode", it) }
                    m.item.runtimeMs?.let { addProperty("runtimeMs", it) }
                })
                o.addProperty("positionMs", m.positionMs)
                m.audio?.let { o.addProperty("audio", it) }
                m.subtitle?.let { o.addProperty("subtitle", it) }
                m.from?.let { o.addProperty("from", it) }
            }
            is CastMessage.Command -> {
                o.addProperty("type", CastProtocol.TYPE_COMMAND)
                o.addProperty("cmd", m.cmd.wire)
                m.positionMs?.let { o.addProperty("positionMs", it) }
                m.deltaMs?.let { o.addProperty("deltaMs", it) }
                m.track?.let { o.addProperty("track", it) }
            }
            is CastMessage.State -> {
                o.addProperty("type", CastProtocol.TYPE_STATE)
                o.addProperty("state", m.phase.wire)
                o.addProperty("positionMs", m.positionMs)
                o.addProperty("durationMs", m.durationMs)
                m.title?.let { o.addProperty("title", it) }
                o.add("audio", tracks(m.audio))
                o.add("subtitles", tracks(m.subtitles))
                m.error?.let { o.addProperty("error", it) }
                m.device?.let { o.addProperty("device", it) }
            }
        }
        return o
    }

    private fun tracks(list: List<CastTrack>) = JsonArray().apply {
        list.take(MAX_TRACKS).forEach { t ->
            add(JsonObject().apply {
                addProperty("id", t.id)
                addProperty("label", t.label)
                addProperty("selected", t.selected)
            })
        }
    }

    /** A message of version 1; null when [json] is none (another type, a newer version, a broken field). */
    fun parse(json: String?): CastMessage? {
        if (json == null || json.length > 16_000) return null
        return parse(runCatching { JsonParser.parseString(json) }.getOrNull())
    }

    /** As [parse] for a body that is already JSON (the plugin's `body`). */
    fun parse(e: JsonElement?): CastMessage? {
        val o = e?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (o.long("v") != CastProtocol.VERSION.toLong()) return null
        val session = o.str("session")?.takeIf(CastProtocol::validSession) ?: return null
        val seq = o.long("seq")?.takeIf { it >= 0 } ?: return null
        val at = o.long("at")?.takeIf { it >= 0 } ?: return null
        return when (o.str("type")) {
            CastProtocol.TYPE_PLAY -> play(o, session, seq, at)
            CastProtocol.TYPE_COMMAND -> command(o, session, seq, at)
            CastProtocol.TYPE_STATE -> state(o, session, seq, at)
            else -> null
        }
    }

    private fun play(o: JsonObject, session: String, seq: Long, at: Long): CastMessage.Play? {
        val s = o.obj("source") ?: return null
        val source = CastSourceRef(
            id = s.str("id")?.takeIf(CastProtocol::validSource) ?: return null,
            kind = s.str("kind")?.let { line(it, 20) },
            serverId = s.str("serverId")?.let { line(it, MAX_ID) },
            name = s.str("name")?.let { line(it, MAX_TEXT) },
        )
        val i = o.obj("item") ?: return null
        // a share's file (the receiver needs it once it found its own share): never a way up or an absolute path
        val path = i.str("path")?.let { p -> line(p, MAX_PATH)?.takeIf(::safePath) ?: return null }
        val item = CastItem(
            id = i.str("id")?.let { line(it, MAX_PATH) } ?: return null,
            name = i.str("name")?.let { line(it, MAX_TEXT) } ?: return null,
            kind = i.str("kind")?.takeIf { it in setOf(CastItem.KIND_MOVIE, CastItem.KIND_EPISODE, CastItem.KIND_VIDEO) } ?: CastItem.KIND_VIDEO,
            path = path,
            seriesName = i.str("seriesName")?.let { line(it, MAX_TEXT) },
            season = i.long("season")?.toInt()?.takeIf { it in 0..9_999 },
            episode = i.long("episode")?.toInt()?.takeIf { it in 0..99_999 },
            runtimeMs = i.long("runtimeMs")?.takeIf { it in 1..MAX_POSITION },
        )
        return CastMessage.Play(
            session, seq, at, source, item,
            positionMs = (o.long("positionMs") ?: 0).coerceIn(0, MAX_POSITION),
            audio = o.long("audio")?.toInt()?.takeIf { it >= 0 },
            subtitle = o.long("subtitle")?.toInt()?.takeIf { it >= CastProtocol.SUBTITLE_OFF },
            from = o.str("from")?.let { line(it, 80) },
        )
    }

    private fun command(o: JsonObject, session: String, seq: Long, at: Long): CastMessage.Command? {
        val cmd = CastCmd.fromWire(o.str("cmd")) ?: return null
        val position = o.long("positionMs")?.coerceIn(0, MAX_POSITION)
        val delta = o.long("deltaMs")?.coerceIn(-MAX_POSITION, MAX_POSITION)
        val track = o.str("track")?.takeIf(CastProtocol::validTrack)
        when (cmd) {
            CastCmd.SEEK -> if (position == null) return null
            CastCmd.SEEK_BY -> if (delta == null) return null
            CastCmd.AUDIO, CastCmd.SUBTITLE -> if (track == null) return null
            else -> Unit
        }
        return CastMessage.Command(session, seq, at, cmd, position, delta, track)
    }

    private fun state(o: JsonObject, session: String, seq: Long, at: Long): CastMessage.State? {
        val phase = CastPhase.fromWire(o.str("state")) ?: return null
        return CastMessage.State(
            session, seq, at, phase,
            positionMs = (o.long("positionMs") ?: 0).coerceIn(0, MAX_POSITION),
            durationMs = (o.long("durationMs") ?: 0).coerceIn(0, MAX_POSITION),
            title = o.str("title")?.let { line(it, MAX_TEXT) },
            audio = readTracks(o.get("audio")),
            subtitles = readTracks(o.get("subtitles")),
            error = o.str("error")?.takeIf(CastProtocol::validError),
            device = o.str("device")?.let { line(it, 80) },
        )
    }

    private fun readTracks(e: JsonElement?): List<CastTrack> {
        if (e == null || !e.isJsonArray) return emptyList()
        return e.asJsonArray.asSequence().mapNotNull { t ->
            val x = t.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = x.str("id")?.takeIf(CastProtocol::validTrack) ?: return@mapNotNull null
            CastTrack(id, x.str("label")?.let { line(it, 120) } ?: id, x.bool("selected") == true)
        }.take(MAX_TRACKS).toList()
    }

    /** A share's relative path: no way up, no absolute path, no control characters. */
    internal fun safePath(p: String): Boolean {
        if (p.startsWith("/") || p.startsWith("\\") || p.contains('\\')) return false
        val parts = p.split('/')
        return parts.none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun line(s: String, max: Int): String? = s.trim().filter { !it.isISOControl() }.takeIf { it.isNotEmpty() && it.length <= max }

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.long(k: String): Long? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asJsonPrimitive?.let { p ->
        val d = p.asDouble
        if (d.isNaN() || d.isInfinite() || d != Math.floor(d) || kotlin.math.abs(d) > 9.0e15) null else d.toLong()
    }
    private fun JsonObject.bool(k: String): Boolean? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
}
