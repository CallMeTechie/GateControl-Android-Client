package com.gatecontrol.android.notify

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.gatecontrol.android.notify.model.PushTopic
import com.gatecontrol.android.notify.model.QuietHours
import com.gatecontrol.android.notify.model.QuietInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** How the device keeps the push channel open. */
enum class PushMode(val wire: String) {
    /** Own foreground service, also without VPN (recommended). */
    ALWAYS("always"),

    /** Only while the tunnel is up; no own service. */
    VPN_ONLY("vpn_only");

    companion object {
        fun fromWire(value: String?): PushMode = entries.firstOrNull { it.wire == value } ?: ALWAYS
    }
}

/** One acknowledgement (`POST /api/v1/client/push/ack`) waiting to be sent. */
data class PendingAck(val seq: Long, val state: String, val action: String? = null) {
    fun encode(): String = "$seq|$state|${action.orEmpty()}"

    companion object {
        fun decode(raw: String): PendingAck? {
            val parts = raw.split('|')
            if (parts.size != 3) return null
            val seq = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return PendingAck(seq, parts[1], parts[2].ifEmpty { null })
        }
    }
}

/** User settings of the notification center on this device. */
data class PushSettings(
    val enabled: Boolean = false,
    val mode: PushMode = PushMode.ALWAYS,
    /** The user picked a mode at least once (first enable proposes "always"). */
    val modeChosen: Boolean = false,
    /** Push was switched on at least once: only then the server hears about this device. */
    val everEnabled: Boolean = false,
    /** Topics switched off on this device (sent to the server). */
    val mutedTopics: Set<String> = emptySet(),
    /** Topic → epoch ms until which it is muted locally ("1 h stumm"). */
    val localMutes: Map<String, Long> = emptyMap(),
) {
    fun isLocallyMuted(topic: String, now: Long): Boolean = (localMutes[topic] ?: 0L) > now
}

/**
 * Push state in the app's DataStore (`gatecontrol_settings`, keys `push_*`):
 * the settings, the last processed `seq` (resume via `Last-Event-ID`),
 * deliveries not yet acknowledged, the last topic list and what the server
 * last accepted as device preferences.
 */
@Singleton
class PushPrefsStore @Inject constructor(private val dataStore: DataStore<Preferences>) {

    private companion object {
        val ENABLED = booleanPreferencesKey("push_enabled")
        val MODE = stringPreferencesKey("push_mode")
        val MODE_CHOSEN = booleanPreferencesKey("push_mode_chosen")
        val EVER_ENABLED = booleanPreferencesKey("push_ever_enabled")
        val MUTED_TOPICS = stringSetPreferencesKey("push_muted_topics")
        val LOCAL_MUTES = stringPreferencesKey("push_local_mutes")
        val LAST_SEQ = longPreferencesKey("push_last_seq")
        val PENDING_ACKS = stringSetPreferencesKey("push_pending_acks")
        val SYNCED_PREFS = stringPreferencesKey("push_synced_prefs")
        val TOPICS = stringPreferencesKey("push_topics")
        val SERVER = stringPreferencesKey("push_server")
        val QUIET = stringPreferencesKey("push_quiet")
        const val QUIET_NONE = "none"

        const val MAX_PENDING_ACKS = 1000
        val gson = Gson()
        val mutesType = object : TypeToken<Map<String, Long>>() {}.type
        val topicsType = object : TypeToken<List<PushTopic>>() {}.type
    }

    val settings: Flow<PushSettings> = dataStore.data.map { p ->
        PushSettings(
            enabled = p[ENABLED] ?: false,
            mode = PushMode.fromWire(p[MODE]),
            modeChosen = p[MODE_CHOSEN] ?: false,
            everEnabled = p[EVER_ENABLED] ?: false,
            mutedTopics = p[MUTED_TOPICS].orEmpty(),
            localMutes = parseMutes(p[LOCAL_MUTES]),
        )
    }.distinctUntilChanged()

    /** Last topic list from `hello` (for the settings screen while offline). */
    val topics: Flow<List<PushTopic>> = dataStore.data.map { p -> parseTopics(p[TOPICS]) }.distinctUntilChanged()

    /** Quiet hours as the server last reported them (unknown until one did). */
    val quiet: Flow<QuietInfo> = dataStore.data.map { p -> parseQuiet(p[QUIET]) }.distinctUntilChanged()

    suspend fun current(): PushSettings = settings.first()

    /** Remembers the quiet hours of `hello` / `GET push/prefs`; an unknown value changes nothing. */
    suspend fun saveQuiet(info: QuietInfo) {
        if (!info.known) return
        val raw = info.hours?.let { gson.toJson(it) } ?: QUIET_NONE
        dataStore.edit { it[QUIET] = raw }
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit {
            it[ENABLED] = enabled
            if (enabled) it[EVER_ENABLED] = true
        }
    }

    suspend fun setMode(mode: PushMode) {
        dataStore.edit {
            it[MODE] = mode.wire
            it[MODE_CHOSEN] = true
        }
    }

    suspend fun setTopicMuted(topic: String, muted: Boolean) {
        dataStore.edit {
            val cur = it[MUTED_TOPICS].orEmpty()
            it[MUTED_TOPICS] = if (muted) cur + topic else cur - topic
        }
    }

    /** Local "1 h stumm": no banner/sound for [topic] until [untilMs]; expired entries are dropped. */
    suspend fun muteLocally(topic: String, untilMs: Long, now: Long) {
        dataStore.edit {
            val cur = parseMutes(it[LOCAL_MUTES]).filterValues { v -> v > now }
            it[LOCAL_MUTES] = gson.toJson(cur + (topic to untilMs))
        }
    }

    suspend fun lastSeq(): Long? = dataStore.data.first()[LAST_SEQ]?.takeIf { it > 0 }

    /** Remembers [seq] as processed; never moves backwards. */
    suspend fun saveLastSeq(seq: Long) {
        dataStore.edit {
            val cur = it[LAST_SEQ] ?: 0L
            if (seq > cur) it[LAST_SEQ] = seq
        }
    }

    /** Acknowledgements the server has not confirmed yet (retried after the next connect). */
    suspend fun pendingAcks(): List<PendingAck> =
        dataStore.data.first()[PENDING_ACKS].orEmpty().mapNotNull(PendingAck::decode).sortedBy { it.seq }

    suspend fun addPendingAcks(acks: Collection<PendingAck>) {
        if (acks.isEmpty()) return
        dataStore.edit {
            val next = it[PENDING_ACKS].orEmpty() + acks.map(PendingAck::encode)
            // Bounded: the server forgets undelivered messages after its retention anyway.
            it[PENDING_ACKS] = if (next.size > MAX_PENDING_ACKS) {
                next.mapNotNull(PendingAck::decode).sortedBy { a -> a.seq }
                    .takeLast(MAX_PENDING_ACKS).map(PendingAck::encode).toSet()
            } else {
                next
            }
        }
    }

    suspend fun removePendingAcks(acks: Collection<PendingAck>) {
        if (acks.isEmpty()) return
        val drop = acks.map(PendingAck::encode).toSet()
        dataStore.edit { it[PENDING_ACKS] = it[PENDING_ACKS].orEmpty() - drop }
    }

    suspend fun syncedPrefs(): String? = dataStore.data.first()[SYNCED_PREFS]

    suspend fun setSyncedPrefs(value: String?) {
        dataStore.edit { if (value == null) it.remove(SYNCED_PREFS) else it[SYNCED_PREFS] = value }
    }

    suspend fun saveTopics(topics: List<PushTopic>) {
        dataStore.edit { it[TOPICS] = gson.toJson(topics) }
    }

    /**
     * Ties the stream state to [serverUrl]. Sequence numbers, pending acks
     * and topics belong to one server: when the setup points to another
     * one, they are dropped. Returns true when the server changed.
     */
    suspend fun bindServer(serverUrl: String): Boolean {
        var changed = false
        dataStore.edit {
            val prev = it[SERVER]
            if (prev != serverUrl) {
                changed = prev != null
                it[SERVER] = serverUrl
                it.remove(LAST_SEQ)
                it.remove(PENDING_ACKS)
                it.remove(SYNCED_PREFS)
                it.remove(TOPICS)
                it.remove(QUIET)
            }
        }
        return changed
    }

    private fun parseMutes(raw: String?): Map<String, Long> = try {
        if (raw.isNullOrBlank()) emptyMap() else gson.fromJson<Map<String, Long>>(raw, mutesType) ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    private fun parseQuiet(raw: String?): QuietInfo = when {
        raw.isNullOrBlank() -> QuietInfo.UNKNOWN
        raw == QUIET_NONE -> QuietInfo.NONE
        else -> try {
            gson.fromJson(raw, QuietHours::class.java)
                ?.takeIf { it.from.isNotEmpty() && it.to.isNotEmpty() }
                ?.let { QuietInfo(true, it) } ?: QuietInfo.UNKNOWN
        } catch (_: Exception) {
            QuietInfo.UNKNOWN
        }
    }

    private fun parseTopics(raw: String?): List<PushTopic> = try {
        if (raw.isNullOrBlank()) emptyList() else gson.fromJson<List<PushTopic>>(raw, topicsType).orEmpty()
    } catch (_: Exception) {
        emptyList()
    }
}
