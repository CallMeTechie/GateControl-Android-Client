package com.gatecontrol.android.media

import com.gatecontrol.android.data.EncryptedStorage
import com.gatecontrol.android.media.jellyfin.JellyfinJson.bool
import com.gatecontrol.android.media.jellyfin.JellyfinJson.objects
import com.gatecontrol.android.media.jellyfin.JellyfinJson.obj
import com.gatecontrol.android.media.jellyfin.JellyfinJson.str
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Where the store keeps its values: the app's encrypted storage (core/data), a map in tests. */
interface MediaSecrets {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** The sound and subtitle chosen for a series (by language: the streams differ from episode to episode). */
data class MediaTrackChoice(val audioLanguage: String?, val subtitleLanguage: String?, val subtitlesOff: Boolean)

/** Where a file of a share was left ("Weiterschauen" of a share lives on the device, decision 11). */
data class ShareProgress(val positionMs: Long, val durationMs: Long, val played: Boolean, val at: Long)

/** The progress of share files, by a key of the share and the file's path. */
interface ShareProgressStore {
    fun progress(key: String): ShareProgress?
    fun all(prefix: String): Map<String, ShareProgress>
    fun setProgress(key: String, progress: ShareProgress)
}

/**
 * The sources of Medien › Quellen with their access, the TV's own device id for the servers, and the sound and
 * subtitle chosen per series ("merkt sich GateControl je Serie"). Everything in the encrypted storage, like the
 * app's other secrets (AES-GCM key in the Android Keystore, no plaintext fallback).
 */
@Singleton
class MediaSourceStore internal constructor(private val secrets: MediaSecrets) : ShareProgressStore {

    @Inject
    constructor(storage: EncryptedStorage) : this(
        object : MediaSecrets {
            override fun get(key: String): String? = storage.getString(key, "").ifEmpty { null }
            override fun put(key: String, value: String) = storage.commitBatch(key to value)
            override fun remove(key: String) = storage.remove(key)
        },
    )

    private val _sources = MutableStateFlow(read())
    val sources: StateFlow<List<MediaSource>> = _sources.asStateFlow()

    /** The id the servers know this device by (Jellyfin's `DeviceId`), made once. */
    val deviceId: String
        get() = synchronized(this) {
            secrets.get(KEY_DEVICE)?.takeIf { it.length in 8..64 } ?: UUID.randomUUID().toString().also { secrets.put(KEY_DEVICE, it) }
        }

    fun source(id: String): MediaSource? = _sources.value.firstOrNull { it.id == id }

    /**
     * Adds [source], or replaces the one of the same server and person (signed in again) or the same share. A share
     * set up as SMB and as WebDAV (the same folder name) is paired: at home SMB, away WebDAV.
     */
    @Synchronized
    fun save(source: MediaSource): MediaSource {
        val list = _sources.value
        val same = list.firstOrNull { it.id == source.id }
            ?: list.firstOrNull { source.serverId != null && it.serverId == source.serverId && it.userId == source.userId }
            ?: list.firstOrNull { source.kind.share && it.kind == source.kind && it.address == source.address }
        var kept = if (same != null) source.copy(id = same.id, alwaysOriginal = same.alwaysOriginal, pairWith = same.pairWith) else source
        var next = if (same != null) list.map { if (it.id == same.id) kept else it } else list + kept
        if (kept.kind.share && kept.pairWith == null) {
            val partner = partnerOf(kept, next)
            if (partner != null) {
                kept = kept.copy(pairWith = partner.id)
                next = next.map {
                    when (it.id) {
                        kept.id -> kept
                        partner.id -> it.copy(pairWith = kept.id)
                        else -> it
                    }
                }
            }
        }
        write(next)
        return kept
    }

    /** Removes [id]; its partner (SMB ↔ WebDAV) stays alone, its files' progress stays with the partner. */
    @Synchronized
    fun remove(id: String) = write(_sources.value.filterNot { it.id == id }.map { if (it.pairWith == id) it.copy(pairWith = null) else it })

    @Synchronized
    fun setAlwaysOriginal(id: String, on: Boolean) = write(_sources.value.map { if (it.id == id) it.copy(alwaysOriginal = on) else it })

    /** Signed out by the server (401): the access is dropped, the source stays to sign in again. */
    @Synchronized
    fun dropToken(id: String) = write(_sources.value.map { if (it.id == id) it.copy(token = null) else it })

    /** The list as the server has it now ([com.gatecontrol.android.media.server.MediaServerMerge]); [gone] lose their downloads. */
    @Synchronized
    fun replaceAll(list: List<MediaSource>, gone: Collection<String> = emptyList()) {
        write(list)
        if (gone.isNotEmpty()) markGone(gone)
    }

    /** Signed in (again) at a source of the server: its own access, and who it is. */
    @Synchronized
    fun setAccess(id: String, token: String, userId: String, userName: String?) =
        write(_sources.value.map { if (it.id == id) it.copy(token = token, userId = userId, userName = userName) else it })

    /** A share's password from the server ([rev]: its version). */
    @Synchronized
    fun setPassword(id: String, user: String?, password: String?, rev: Int) =
        write(_sources.value.map { if (it.id == id) it.copy(userName = user ?: it.userName, password = password, secretRev = rev) else it })

    /** Long without contact to the server (maxOfflineDays): the NAS passwords go, fetched again at the next contact. */
    @Synchronized
    fun dropPasswords() = write(_sources.value.map { if (it.kind.share && it.managed) it.copy(password = null, secretRev = 0) else it })

    /**
     * Sources removed by the server (revoked, no access): their downloads go too. Kept until the app's downloads have
     * taken them ([takeGone]), also across a restart.
     */
    private val _gone = MutableStateFlow(readGone())
    val gone: StateFlow<Set<String>> = _gone.asStateFlow()

    @Synchronized
    fun markGone(ids: Collection<String>) {
        val next = (_gone.value + ids).toList().takeLast(MAX_GONE).toSet()
        secrets.put(KEY_GONE, JsonArray().apply { next.forEach { add(it) } }.toString())
        _gone.value = next
    }

    /** The ids of [markGone] handled (their downloads deleted). */
    @Synchronized
    fun takeGone(ids: Collection<String>) {
        val next = _gone.value - ids.toSet()
        if (next.isEmpty()) secrets.remove(KEY_GONE) else secrets.put(KEY_GONE, JsonArray().apply { next.forEach { add(it) } }.toString())
        _gone.value = next
    }

    private fun readGone(): Set<String> = runCatching {
        JsonParser.parseString(secrets.get(KEY_GONE) ?: return emptySet()).asJsonArray.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.toSet()
    }.getOrDefault(emptySet())

    /** Kept beside the sources: the state of the server's list (rev, last contact …), as [com.gatecontrol.android.media.server.MediaServerSources] writes it. */
    fun serverState(): String? = secrets.get(KEY_SERVER)

    fun setServerState(json: String?) = if (json == null) secrets.remove(KEY_SERVER) else secrets.put(KEY_SERVER, json)

    /** The TV was unpaired (the encrypted storage is cleared): nothing of the sources is left in memory either. */
    @Synchronized
    fun forgetAll() {
        secrets.remove(KEY_SERVER)
        secrets.remove(KEY_SOURCES)
        secrets.remove(KEY_TRACKS)
        secrets.remove(KEY_PROGRESS)
        progressCache = null
        _sources.value = emptyList()
    }

    // --- the progress of share files (Jellyfin keeps its own) ---

    private var progressCache: JsonObject? = null

    private fun progressJson(): JsonObject = progressCache
        ?: (runCatching { JsonParser.parseString(secrets.get(KEY_PROGRESS) ?: "{}") }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject())
            .also { progressCache = it }

    private fun progressOf(o: JsonObject?): ShareProgress? {
        o ?: return null
        val pos = o.get("p")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null
        return ShareProgress(pos, o.get("d")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0, o.bool("w") == true, o.get("t")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0)
    }

    @Synchronized
    override fun progress(key: String): ShareProgress? = runCatching { progressOf(progressJson().obj(key)) }.getOrNull()

    @Synchronized
    override fun all(prefix: String): Map<String, ShareProgress> = runCatching {
        val o = progressJson()
        o.keySet().filter { it.startsWith(prefix) }.mapNotNull { k -> progressOf(o.obj(k))?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    /** Kept for the newest [MAX_PROGRESS] files; a file watched to its end is kept as "gesehen". */
    @Synchronized
    override fun setProgress(key: String, progress: ShareProgress) {
        val o = progressJson()
        o.remove(key)
        while (o.size() >= MAX_PROGRESS) {
            val oldest = o.keySet().minByOrNull { k -> o.obj(k)?.get("t")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0 } ?: break
            o.remove(oldest)
        }
        o.add(
            key,
            JsonObject().apply {
                addProperty("p", progress.positionMs.coerceAtLeast(0))
                addProperty("d", progress.durationMs.coerceAtLeast(0))
                addProperty("w", progress.played)
                addProperty("t", progress.at)
            },
        )
        secrets.put(KEY_PROGRESS, o.toString())
    }

    fun trackChoice(seriesId: String): MediaTrackChoice? {
        val o = runCatching { JsonParser.parseString(secrets.get(KEY_TRACKS) ?: return null) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        val c = o?.obj(seriesId) ?: return null
        return MediaTrackChoice(c.str("audio"), c.str("subtitle"), c.bool("off") == true)
    }

    @Synchronized
    fun setTrackChoice(seriesId: String, choice: MediaTrackChoice) {
        val o = runCatching { JsonParser.parseString(secrets.get(KEY_TRACKS) ?: "{}") }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        if (o.size() >= MAX_TRACK_CHOICES && !o.has(seriesId)) o.keySet().firstOrNull()?.let { o.remove(it) }
        o.add(
            seriesId,
            JsonObject().apply {
                choice.audioLanguage?.let { addProperty("audio", it) }
                choice.subtitleLanguage?.let { addProperty("subtitle", it) }
                addProperty("off", choice.subtitlesOff)
            },
        )
        secrets.put(KEY_TRACKS, o.toString())
    }

    private fun write(list: List<MediaSource>) {
        secrets.put(KEY_SOURCES, encode(list))
        _sources.value = list
    }

    private fun read(): List<MediaSource> = decode(secrets.get(KEY_SOURCES))

    companion object {
        private const val KEY_SOURCES = "media_sources_v1"
        private const val KEY_DEVICE = "media_device_id"
        private const val KEY_TRACKS = "media_track_choices_v1"
        private const val KEY_PROGRESS = "media_share_progress_v1"
        private const val KEY_GONE = "media_sources_gone_v1"
        private const val KEY_SERVER = "media_server_state_v1"
        private const val MAX_GONE = 64
        private const val MAX_TRACK_CHOICES = 200
        const val MAX_PROGRESS = 500
        const val MAX_SOURCES = 32

        /** The share of the other protocol with the same folder name that is not paired yet. */
        fun partnerOf(s: MediaSource, list: List<MediaSource>): MediaSource? {
            val other = when (s.kind) {
                MediaSourceKind.SMB -> MediaSourceKind.WEBDAV
                MediaSourceKind.WEBDAV -> MediaSourceKind.SMB
                MediaSourceKind.JELLYFIN -> return null
            }
            val folder = MediaAddress.folderName(s.kind, s.address) ?: return null
            return list.firstOrNull { it.id != s.id && it.kind == other && it.pairWith == null && MediaAddress.folderName(it.kind, it.address) == folder }
        }

        fun encode(list: List<MediaSource>): String = JsonObject().apply {
            add(
                "sources",
                JsonArray().apply {
                    list.take(MAX_SOURCES).forEach { s ->
                        add(
                            JsonObject().apply {
                                addProperty("id", s.id)
                                addProperty("kind", s.kind.wire)
                                addProperty("name", s.name)
                                addProperty("address", s.address)
                                s.userId?.let { addProperty("userId", it) }
                                s.userName?.let { addProperty("userName", it) }
                                s.token?.let { addProperty("token", it) }
                                s.serverId?.let { addProperty("serverId", it) }
                                addProperty("alwaysOriginal", s.alwaysOriginal)
                                s.password?.let { addProperty("password", it) }
                                s.pairWith?.let { addProperty("pairWith", it) }
                                if (s.managed) addProperty("managed", true)
                                s.remoteId?.let { addProperty("remoteId", it) }
                                s.deviceId?.let { addProperty("deviceId", it) }
                                s.deviceName?.let { addProperty("deviceName", it) }
                                s.linkedUserId?.let { addProperty("linkedUserId", it) }
                                s.linkedUserName?.let { addProperty("linkedUserName", it) }
                                s.awayAddress?.let { addProperty("awayAddress", it) }
                                s.certSha256?.let { addProperty("certSha256", it) }
                                if (s.secretRev != 0) addProperty("secretRev", s.secretRev)
                                if (!s.downloads) addProperty("downloads", false)
                                s.problem?.let { addProperty("problem", it) }
                            },
                        )
                    }
                },
            )
        }.toString()

        fun decode(text: String?): List<MediaSource> {
            val o = runCatching { JsonParser.parseString(text ?: return emptyList()) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return emptyList()
            return o.objects("sources").mapNotNull { s ->
                val kind = MediaSourceKind.fromWire(s.str("kind")) ?: return@mapNotNull null
                val address = s.str("address")?.let { MediaAddress.normalizeShare(kind, it) } ?: return@mapNotNull null
                MediaSource(
                    id = s.str("id") ?: return@mapNotNull null,
                    kind = kind,
                    name = s.str("name") ?: "Jellyfin",
                    address = address,
                    userId = s.str("userId"),
                    userName = s.str("userName"),
                    token = s.str("token"),
                    serverId = s.str("serverId"),
                    alwaysOriginal = s.bool("alwaysOriginal") == true,
                    // a password is taken as it is (spaces, control characters of a typed password are its own)
                    password = s.get("password")?.takeIf { it.isJsonPrimitive }?.asString,
                    pairWith = s.str("pairWith"),
                    managed = s.bool("managed") == true,
                    remoteId = s.str("remoteId"),
                    deviceId = s.str("deviceId"),
                    deviceName = s.str("deviceName"),
                    linkedUserId = s.str("linkedUserId"),
                    linkedUserName = s.str("linkedUserName"),
                    awayAddress = s.str("awayAddress")?.takeIf { it.startsWith("https://") },
                    certSha256 = s.str("certSha256"),
                    secretRev = s.get("secretRev")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0,
                    downloads = s.bool("downloads") != false,
                    problem = s.str("problem"),
                )
            }.distinctBy { it.id }.take(MAX_SOURCES).let { list ->
                // a partner that is gone (or not a share of the other kind) is no partner
                list.map { s -> if (s.pairWith != null && list.none { it.id == s.pairWith && it.pairWith == s.id && it.kind != s.kind && it.kind.share }) s.copy(pairWith = null) else s }
            }
        }
    }
}
