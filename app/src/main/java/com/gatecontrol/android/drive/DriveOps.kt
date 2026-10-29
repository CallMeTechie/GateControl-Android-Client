package com.gatecontrol.android.drive

import android.os.SystemClock
import com.gatecontrol.android.support.CrashReports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The jobs on several items at once (Drive 1.12, feature `bulk`): started here, they run on the server — also when the
 * app is closed — and the app follows them by the live event `op`, else by asking (`GET ops/<id>`) now and then. After a
 * restart the running ones are fetched again ([restore]). A copy is offered only when the server copies the bytes
 * itself (`copyServer`). Without Drive 1.12 the app runs a move or a delete itself, one call per item ([startLocal]);
 * it shows the same way. New items at a target are marked "neu hier" for a while.
 */
@Singleton
class DriveOps @Inject constructor(
    private val api: DriveApi,
    private val live: DriveLive,
) {
    /**
     * A job followed here: the server's state ([op]) and what the app knows besides — the first item's name ([title])
     * and how many there were ([count]), the target's name ([target]). [local]: the app runs it itself.
     */
    data class Job(
        val op: DOp,
        val title: String? = null,
        val count: Int = op.total,
        val target: String? = null,
        val local: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    private val _jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    val jobs: StateFlow<Map<String, Job>> = _jobs.asStateFlow()

    /** The jobs still running, the oldest first (a card each on top of the folder). */
    val running: StateFlow<List<Job>> = _jobs.map { m -> m.values.filter { it.op.running } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val lastEvent = ConcurrentHashMap<String, Long>()
    private val following = ConcurrentHashMap.newKeySet<String>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val localIds = AtomicLong(0)
    @Volatile private var restored = false

    init {
        scope.launch {
            live.ops.collect { op ->
                lastEvent[op.id] = SystemClock.elapsedRealtime()
                update(op)
            }
        }
    }

    private fun update(op: DOp) {
        _jobs.update { m ->
            val known = m[op.id]
            m + (op.id to (known?.copy(op = op) ?: Job(op, op.label, op.items.takeIf { it > 0 } ?: op.total, op.targetName ?: DriveBulk.lastPart(op.targetPath))))
        }
    }

    /** Starts [kind] for [ids] (to [target]); a running job is followed until it ends. */
    suspend fun start(kind: String, ids: List<Long>, target: Long?, onConflict: String?, title: String?, targetName: String?): DResult<DOp> {
        val r = api.startOp(kind, ids, target, onConflict)
        if (r is DResult.Ok) {
            val op = r.value
            _jobs.update { it + (op.id to Job(op, title ?: op.label, op.items.takeIf { n -> n > 0 } ?: ids.size, targetName ?: op.targetName ?: DriveBulk.lastPart(op.targetPath))) }
            if (op.running) follow(op.id)
        }
        return r
    }

    /** Asks for the job's state while it runs and no event came for a while. */
    private fun follow(id: String) {
        if (!following.add(id)) return
        scope.launch {
            try {
                while (true) {
                    delay(POLL_MS)
                    val job = _jobs.value[id] ?: break
                    if (!job.op.running) break
                    if (SystemClock.elapsedRealtime() - (lastEvent[id] ?: 0L) < POLL_MS) continue
                    when (val r = api.op(id)) {
                        is DResult.Ok -> update(r.value)
                        // gone on the server (restarted without it): it ended
                        is DResult.Failed -> if (r.http == 404) update(job.op.copy(state = "failed")) else delay(POLL_MS)
                        else -> delay(POLL_MS)
                    }
                }
            } finally {
                following.remove(id)
            }
        }
    }

    /** Waits until the job [id] ends; its last state. */
    suspend fun await(id: String): Job = _jobs.mapNotNull { it[id] }.first { !it.op.running }

    /** "Abbrechen": what is done stays done. */
    suspend fun cancel(id: String) {
        val job = _jobs.value[id] ?: return
        if (job.local) {
            cancelled.add(id)
            return
        }
        when (val r = api.cancelOp(id)) {
            is DResult.Ok -> update(r.value)
            else -> Unit
        }
    }

    /** "Rückgängig": the server puts it back (it may run as a job of its own). */
    suspend fun undo(id: String): DResult<DOp> {
        val r = api.undoOp(id)
        if (r is DResult.Ok) {
            val was = _jobs.value[id]
            _jobs.update { it + (r.value.id to Job(r.value, was?.title, was?.count ?: r.value.total, was?.target)) }
            if (r.value.running) follow(r.value.id)
        }
        return r
    }

    /** A finished job is no longer needed. */
    fun forget(id: String) {
        _jobs.update { it - id }
        lastEvent.remove(id)
        cancelled.remove(id)
    }

    /** The jobs still running on the server (once per app start; after a restart their cards come back). */
    fun restore() {
        if (restored) return
        restored = true
        scope.launch {
            if (live.current()?.has(DMe.FEATURE_BULK) != true) {
                restored = false
                return@launch
            }
            val list = api.runningOps().valueOrNull()
            if (list == null) {
                restored = false
                return@launch
            }
            list.forEach { op ->
                update(op)
                if (op.running) follow(op.id)
            }
        }
    }

    // ─── a job the app runs itself (without Drive 1.12) ─────────────

    /** Starts a job of [kind] over [count] items that the app runs itself; its id. */
    fun startLocal(kind: String, count: Int, title: String?, targetName: String?): String {
        val id = "local-" + localIds.incrementAndGet()
        _jobs.update { it + (id to Job(DOp(id, kind, "running", count, 0, 0, 0), title, count, targetName, local = true)) }
        return id
    }

    /** How far a job of the app got; false when the person stopped it ("Abbrechen"). */
    fun progressLocal(id: String, done: Int): Boolean {
        _jobs.update { m -> m[id]?.let { j -> m + (id to j.copy(op = j.op.copy(done = done))) } ?: m }
        return id !in cancelled
    }

    fun finishLocal(id: String, results: DOpResults) {
        val stopped = cancelled.remove(id)
        _jobs.update { m ->
            m[id]?.let { j -> m + (id to j.copy(op = j.op.copy(state = if (stopped) "cancelled" else "done", done = results.done, results = results))) } ?: m
        }
    }

    // ─── "neu hier" ─────────────────────────────────────────────────

    private val _fresh = MutableStateFlow<Set<Long>>(emptySet())
    /** Items that just arrived at a target (their ids), marked "neu hier" for a while. */
    val fresh: StateFlow<Set<Long>> = _fresh.asStateFlow()

    fun markFresh(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        _fresh.update { it + set }
        scope.launch {
            delay(FRESH_MS)
            _fresh.update { it - set }
        }
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val FRESH_MS = 90_000L
    }
}
