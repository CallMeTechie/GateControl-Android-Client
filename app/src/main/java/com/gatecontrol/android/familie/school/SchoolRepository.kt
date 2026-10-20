package com.gatecontrol.android.familie.school

import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.FamilieStorage
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
                _inbox.value = SchoolInbox(
                    messages = SchoolJson.messages(stored.messages),
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
        mutex.withLock {
            val inbox = SchoolInbox(
                messages = m.list,
                absences = a.list,
                messagesError = m.error,
                absencesError = a.error,
                savedAt = if (answered) now else current?.savedAt ?: 0L,
                owner = owner,
            )
            _inbox.value = inbox
            if (answered) {
                inboxRaw = StoredInbox(owner, now, m.raw, a.raw)
                storage.write(INBOX_FILE, inboxRaw)
            }
            inbox
        }
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
