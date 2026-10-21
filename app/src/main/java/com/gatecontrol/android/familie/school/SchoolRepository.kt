package com.gatecontrol.android.familie.school

import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.FamilieStorage
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** The loaded timetable range ([from] … [to]) and when it came ([savedAt], epoch ms). */
data class SchoolSnapshot(
    val timetable: SchoolTimetable,
    val from: String,
    val to: String,
    val savedAt: Long,
    /** Login the data belongs to ([FamilieStorage.ownerKey]). */
    val owner: String,
)

/**
 * The timetable of this week and next (the Familie card and the screen's
 * default range), in memory and in `files/familie/school.json` for offline
 * starts — as the JSON text the plugin sent, read back through
 * [SchoolJson] (no reflective models in the file). Another login, a plugin
 * that is gone (404) or no access (403) drops it. Other ranges (a push
 * about a later day) are loaded without being kept.
 *
 * Messages and absences (plugin 1.4+, [inbox]) are kept the same way in
 * `files/familie/school-inbox.json`. A route an older plugin does not
 * know (404) or that the login may not use (403) reads as "not offered":
 * its list is dropped and the screen hides that tab, without an error.
 *
 * Opening a message marks it read at once on the device ([markRead]: the
 * "neu" badge and the card's count go immediately) and then on the server
 * (`POST app/messages/<id>/read`, plugin 1.5+; an older plugin's 404 is
 * ignored). Until the server's list says it is read, the local mark stays
 * (kept in the file too), and a failed call is sent again with the next load.
 */
@Singleton
class SchoolRepository @Inject constructor(
    private val api: FamilieApi,
    private val storage: FamilieStorage,
) {
    private data class Stored(
        val owner: String? = null,
        val from: String? = null,
        val to: String? = null,
        val savedAt: Long = 0L,
        val json: String? = null,
    )

    private data class StoredInbox(
        val owner: String? = null,
        val savedAt: Long = 0L,
        val messages: String? = null,
        val absences: String? = null,
        /** Messages opened on this device that the server still calls new (null in files of older app versions). */
        val readIds: List<String>? = null,
    )

    private val mutex = Mutex()
    /** One load of the usual range at a time: a second caller then finds it fresh. */
    private val loading = Mutex()
    @Volatile private var loaded = false

    private val _snapshot = MutableStateFlow<SchoolSnapshot?>(null)
    val snapshot: StateFlow<SchoolSnapshot?> = _snapshot.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Error code of the last load of the usual range, null after a success. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _pushes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** A Schule push arrived: open screens reload. */
    val pushes: SharedFlow<Unit> = _pushes.asSharedFlow()

    /** The stored range of this login (no network); null when there is none. */
    suspend fun cached(): SchoolSnapshot? = mutex.withLock {
        val owner = storage.ownerKey()
        if (!loaded) {
            val stored = storage.read<Stored>(FILE, object : TypeToken<Stored>() {}.type)
            _snapshot.value = stored?.takeIf { it.owner == owner }?.let { s ->
                val tt = SchoolJson.timetable(s.json) ?: return@let null
                val from = SchoolJson.date(s.from) ?: return@let null
                val to = SchoolJson.date(s.to) ?: return@let null
                SchoolSnapshot(tt, from, to, s.savedAt, owner)
            }
            loaded = true
        }
        _snapshot.value?.takeIf { it.owner == owner } ?: run {
            _snapshot.value = null
            null
        }
    }

    /**
     * Loads this week and next ([today]'s range). With [maxAgeMs] a range
     * loaded less than that ago is kept as it is (the start screen asks on
     * every resume).
     */
    suspend fun refresh(today: LocalDate = LocalDate.now(), maxAgeMs: Long = 0L): FamilieResult<SchoolTimetable> = loading.withLock {
        val current = cached()
        val from = SchoolLogic.windowStart(today)
        val to = SchoolLogic.windowEnd(from)
        if (current != null && maxAgeMs > 0 && current.from == from.toString() &&
            System.currentTimeMillis() - current.savedAt in 0 until maxAgeMs
        ) {
            return@withLock FamilieResult.Ok(current.timetable)
        }
        when (val r = api.schoolTimetable(from.toString(), to.toString())) {
            is FamilieResult.Ok -> {
                val now = System.currentTimeMillis()
                val owner = storage.ownerKey()
                mutex.withLock {
                    _snapshot.value = SchoolSnapshot(r.value.timetable, from.toString(), to.toString(), now, owner)
                    _error.value = null
                    storage.write(FILE, Stored(owner, from.toString(), to.toString(), now, r.value.raw))
                }
                FamilieResult.Ok(r.value.timetable)
            }
            is FamilieResult.Failed -> {
                _error.value = r.code
                // Plugin gone or no access (any more): nothing to keep.
                if (r.http == 404 || r.http == 403) clear()
                r
            }
            FamilieResult.Offline -> {
                _error.value = FamilieApi.CODE_OFFLINE
                FamilieResult.Offline
            }
            FamilieResult.NotConfigured -> {
                _error.value = FamilieApi.CODE_OFFLINE
                FamilieResult.NotConfigured
            }
        }
    }

    /** Another range (a push about a later week): from the server, not kept. */
    suspend fun load(from: LocalDate): FamilieResult<SchoolTimetable> =
        when (val r = api.schoolTimetable(from.toString(), SchoolLogic.windowEnd(from).toString())) {
            is FamilieResult.Ok -> FamilieResult.Ok(r.value.timetable)
            is FamilieResult.Failed -> r
            FamilieResult.Offline -> FamilieResult.Offline
            FamilieResult.NotConfigured -> FamilieResult.NotConfigured
        }

    suspend fun changes(days: Int = 7): FamilieResult<List<SchoolChange>> = api.schoolChanges(days)

    private val inboxLoading = Mutex()
    @Volatile private var inboxLoaded = false
    /** The answers behind [inbox] as they came (written to the file again with the other part). */
    private var inboxRaw = StoredInbox()
    /** Messages opened on this device that the server's list still calls new ([markRead]). */
    private var readIds: Set<String> = emptySet()

    private val _inbox = MutableStateFlow<SchoolInbox?>(null)
    /** Messages and absences of this login (plugin 1.4+); null before the first load. */
    val inbox: StateFlow<SchoolInbox?> = _inbox.asStateFlow()

    /** The stored messages and absences of this login (no network); null when there are none. */
    suspend fun cachedInbox(): SchoolInbox? = mutex.withLock {
        val owner = storage.ownerKey()
        if (!inboxLoaded) {
            val stored = storage.read<StoredInbox>(INBOX_FILE, object : TypeToken<StoredInbox>() {}.type)
                ?.takeIf { it.owner == owner }
            if (stored != null) {
                inboxRaw = stored
                readIds = stored.readIds.orEmpty().toSet()
                _inbox.value = SchoolInbox(
                    messages = SchoolLogic.withLocalRead(SchoolJson.messages(stored.messages), readIds),
                    absences = SchoolJson.absences(stored.absences),
                    savedAt = stored.savedAt,
                    owner = owner,
                )
            }
            inboxLoaded = true
        }
        _inbox.value?.takeIf { it.owner == owner } ?: run {
            _inbox.value = null
            inboxRaw = StoredInbox()
            readIds = emptySet()
            null
        }
    }

    /**
     * Loads the messages of the last [MESSAGE_DAYS] and the absences of the
     * last [ABSENCE_DAYS] days. With [maxAgeMs] an inbox loaded without
     * errors less than that ago is kept as it is. A failed part keeps its
     * last list and tells its error; 404 / 403 drop the part (not offered).
     */
    suspend fun refreshInbox(maxAgeMs: Long = 0L): SchoolInbox = inboxLoading.withLock {
        val current = cachedInbox()
        if (current != null && maxAgeMs > 0 && current.messagesError == null && current.absencesError == null &&
            System.currentTimeMillis() - current.savedAt in 0 until maxAgeMs
        ) {
            return@withLock current
        }
        val m = part(api.schoolMessages(MESSAGE_DAYS), current?.messages, inboxRaw.messages)
        val a = part(api.schoolAbsences(ABSENCE_DAYS), current?.absences, inboxRaw.absences)
        val owner = storage.ownerKey()
        val answered = m.answered || a.answered
        val now = System.currentTimeMillis()
        val (inbox, retry) = mutex.withLock {
            // A fresh list from the server: keep the local mark only where it still says "new".
            if (m.answered) readIds = SchoolLogic.pendingLocalRead(readIds, m.list)
            val inbox = SchoolInbox(
                messages = SchoolLogic.withLocalRead(m.list, readIds),
                absences = a.list,
                messagesError = m.error,
                absencesError = a.error,
                savedAt = if (answered) now else current?.savedAt ?: 0L,
                owner = owner,
            )
            _inbox.value = inbox
            if (answered) {
                inboxRaw = StoredInbox(owner, now, m.raw, a.raw, readIds.toList().takeIf { it.isNotEmpty() })
                storage.write(INBOX_FILE, inboxRaw)
            }
            inbox to if (m.answered) SchoolLogic.readToRetry(readIds, m.list) else emptyList()
        }
        // `read` calls that did not get through before (offline): once more, quietly.
        retry.forEach { id -> sendRead(id) }
        if (retry.isEmpty()) inbox else _inbox.value ?: inbox
    }

    /**
     * The person opened message [id]: read on the device at once (optimistic,
     * also when the call fails), then `POST app/messages/<id>/read`. A 404
     * (older plugin, or a message that is gone) and other trouble are not
     * told; the mark stays until the server's list agrees.
     */
    suspend fun markRead(id: String) {
        val unread = mutex.withLock {
            val inbox = _inbox.value ?: return@withLock false
            val m = inbox.messages?.firstOrNull { it.id == id } ?: return@withLock false
            // Read already (by the server's list or on this device), or an older plugin that does not call it new: nothing to send.
            if (!m.isNew && (m.read || !m.extended)) return@withLock false
            readIds = readIds + id
            _inbox.value = inbox.copy(messages = SchoolLogic.withLocalRead(inbox.messages, readIds))
            persistInbox()
            true
        }
        if (unread) sendRead(id)
    }

    private suspend fun sendRead(id: String) {
        when (val r = api.schoolMessageRead(id)) {
            is FamilieResult.Ok -> r.value.raw?.let { applyMessage(it) }
            // 404: plugin before 1.5 or the message is gone - nothing to tell. Other trouble: again with the next load.
            else -> Unit
        }
    }

    /**
     * Sends the read confirmation for message [id] to the school (only on
     * the person's explicit tap); the message as the plugin now has it on success.
     */
    suspend fun confirm(id: String): FamilieResult<SchoolMessage?> = when (val r = api.schoolMessageConfirm(id)) {
        is FamilieResult.Ok -> {
            mutex.withLock {
                // Confirming also reads it.
                readIds = readIds + id
                _inbox.value?.let { inbox -> _inbox.value = inbox.copy(messages = SchoolLogic.withLocalRead(inbox.messages, readIds)) }
                persistInbox()
            }
            r.value.raw?.let { applyMessage(it) }
            FamilieResult.Ok(_inbox.value?.messages?.firstOrNull { it.id == id } ?: r.value.message)
        }
        is FamilieResult.Failed -> r
        FamilieResult.Offline -> FamilieResult.Offline
        FamilieResult.NotConfigured -> FamilieResult.NotConfigured
    }

    /** A message from `read` / `confirm` replaces its entry in the list (and in the stored answer). */
    private suspend fun applyMessage(message: JsonObject) = mutex.withLock {
        val raw = SchoolJson.replaceMessage(inboxRaw.messages, message) ?: return@withLock
        if (raw == inboxRaw.messages) return@withLock
        val list = SchoolJson.messages(raw) ?: return@withLock
        inboxRaw = inboxRaw.copy(messages = raw)
        _inbox.value?.let { inbox -> _inbox.value = inbox.copy(messages = SchoolLogic.withLocalRead(list, readIds)) }
        persistInbox()
    }

    /** Writes the stored answers with the local read marks (under [mutex]; nothing before a first answer). */
    private fun persistInbox() {
        if (inboxRaw.owner == null) return
        inboxRaw = inboxRaw.copy(readIds = readIds.toList().takeIf { it.isNotEmpty() })
        storage.write(INBOX_FILE, inboxRaw)
    }

    /** One part of the inbox after a load: the list to show, its JSON and the error to tell. */
    private class Part<T>(val list: List<T>?, val raw: String?, val error: String?, val answered: Boolean)

    private fun <T> part(r: FamilieResult<SchoolListFetch<T>>, old: List<T>?, oldRaw: String?): Part<T> = when (r) {
        is FamilieResult.Ok -> Part(r.value.items, r.value.raw, null, answered = true)
        // Older plugin (route unknown) or not for this login: not offered, silently.
        is FamilieResult.Failed -> if (r.http == 404 || r.http == 403) Part(null, null, null, answered = true) else Part(old, oldRaw, r.code, answered = false)
        FamilieResult.Offline, FamilieResult.NotConfigured -> Part(old, oldRaw, FamilieApi.CODE_OFFLINE, answered = false)
    }

    /** A Schule push arrived (FamiliePushHandler). */
    fun onPush() {
        _pushes.tryEmit(Unit)
    }

    suspend fun clear() = mutex.withLock {
        _snapshot.value = null
        storage.delete(FILE)
        _inbox.value = null
        inboxRaw = StoredInbox()
        readIds = emptySet()
        storage.delete(INBOX_FILE)
    }

    companion object {
        private const val FILE = "school.json"
        private const val INBOX_FILE = "school-inbox.json"

        /** Days of messages loaded (`app/messages?days=`). */
        const val MESSAGE_DAYS = 30

        /** Days of absences loaded (`app/absences?days=`). */
        const val ABSENCE_DAYS = 60
    }
}
