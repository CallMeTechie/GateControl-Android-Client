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

/**
 * The sources of Medien › Quellen with their access, the TV's own device id for the servers, and the sound and
 * subtitle chosen per series ("merkt sich GateControl je Serie"). Everything in the encrypted storage, like the
 * app's other secrets (AES-GCM key in the Android Keystore, no plaintext fallback).
 */
@Singleton
class MediaSourceStore internal constructor(private val secrets: MediaSecrets) {

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

    /** Adds [source], or replaces the one of the same server and person (signed in again). */
    @Synchronized
    fun save(source: MediaSource): MediaSource {
        val list = _sources.value
        val same = list.firstOrNull { it.id == source.id }
            ?: list.firstOrNull { source.serverId != null && it.serverId == source.serverId && it.userId == source.userId }
        val kept = if (same != null) source.copy(id = same.id, alwaysOriginal = same.alwaysOriginal) else source
        val next = if (same != null) list.map { if (it.id == same.id) kept else it } else list + kept
        write(next)
        return kept
    }

    @Synchronized
    fun remove(id: String) = write(_sources.value.filterNot { it.id == id })

    @Synchronized
    fun setAlwaysOriginal(id: String, on: Boolean) = write(_sources.value.map { if (it.id == id) it.copy(alwaysOriginal = on) else it })

    /** Signed out by the server (401): the access is dropped, the source stays to sign in again. */
    @Synchronized
    fun dropToken(id: String) = write(_sources.value.map { if (it.id == id) it.copy(token = null) else it })

    /** The TV was unpaired (the encrypted storage is cleared): nothing of the sources is left in memory either. */
    @Synchronized
    fun forgetAll() {
        secrets.remove(KEY_SOURCES)
        secrets.remove(KEY_TRACKS)
        _sources.value = emptyList()
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
        private const val MAX_TRACK_CHOICES = 200
        const val MAX_SOURCES = 8

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
                val address = s.str("address")?.let(MediaAddress::normalize) ?: return@mapNotNull null
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
                )
            }.distinctBy { it.id }.take(MAX_SOURCES)
        }
    }
}
