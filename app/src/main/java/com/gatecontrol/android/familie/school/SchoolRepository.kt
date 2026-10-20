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

    /** A Schule push arrived (FamiliePushHandler). */
    fun onPush() {
        _pushes.tryEmit(Unit)
    }

    suspend fun clear() = mutex.withLock {
        _snapshot.value = null
        storage.delete(FILE)
    }

    companion object {
        private const val FILE = "school.json"
    }
}
