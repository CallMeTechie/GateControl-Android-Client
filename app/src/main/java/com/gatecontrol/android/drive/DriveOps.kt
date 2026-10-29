package com.gatecontrol.android.drive

import android.os.SystemClock
import com.gatecontrol.android.support.CrashReports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 *
 * A job shows from the moment it is asked for ([prepare]): until the server's answer with its id it is a placeholder
 * ("wird vorbereitet", [Job.pending]) with the chosen items waiting — Drive 1.12.2 answers at once, an older Drive
 * after up to 5 s. The start and "Rückgängig" run in this object's scope: a screen left meanwhile loses nothing.
 */
@Singleton
class DriveOps internal constructor(
    private val api: DriveApi,
    private val live: DriveLive,
    private val scope: CoroutineScope,
    /** Milliseconds since boot (tests: the virtual time). */
    private val clock: () -> Long,
) {
    @Inject constructor(api: DriveApi, live: DriveLive) : this(
        api,
        live,
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler),
        { SystemClock.elapsedRealtime() },
    )

    /**
     * A job followed here: the server's state ([op]) and what the app knows besides — the first item's name ([title])
     * and how many there were ([count]), the target's name ([target]). [local]: the app runs it itself. [pending]: asked
     * for, the server has not answered yet ("wird vorbereitet" — its id is the app's own).
     */
    data class Job(
        val op: DOp,
        val title: String? = null,
        val count: Int = op.total,
        val target: String? = null,
        val local: Boolean = false,
        val pending: Boolean = false,
    )

    private val _jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    val jobs: StateFlow<Map<String, Job>> = _jobs.asStateFlow()

    /** The jobs still running, the oldest first (the ring of the round button, the uploads sheet). */
    val running: StateFlow<List<Job>> = _jobs.map { m -> m.values.filter { it.op.running } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val lastEvent = ConcurrentHashMap<String, Long>()
    private val following = ConcurrentHashMap.newKeySet<String>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val localIds = AtomicLong(0)
    private val pendingIds = AtomicLong(0)
    @Volatile private var restored = false

    init {
        scope.launch {
            live.ops.collect { op ->
                lastEvent[op.id] = clock()
                update(op)
            }
        }
    }

    private fun update(op: DOp) {
        _jobs.update { m ->
            val known = m[op.id]
            val next = merged(known?.op, op) ?: return@update m
            m + (op.id to (known?.copy(op = next) ?: Job(next, next.label, next.items.takeIf { it > 0 } ?: next.total, next.targetName ?: DriveBulk.lastPart(next.targetPath))))
        }
    }

    /**
     * A job of [kind] over [count] items shown at once, before the server answers its start: the ring instead of the
     * plus, the sheet with [files] (the chosen items, waiting). Its id goes to [start] (or [undo]), which puts the
     * server's job in its place — or takes it away when the start fails.
     */
    fun prepare(kind: String, count: Int, title: String?, targetName: String?, files: List<DOpFile>? = null): String {
        val id = PENDING + pendingIds.incrementAndGet()
        val shown = files?.takeIf { it.isNotEmpty() }
        val op = DOp(id, kind, "running", 0, 0, 0, 0, items = count, files = shown, filesMore = (count - (shown?.size ?: count)).coerceAtLeast(0))
        _jobs.update { it + (id to Job(op, title, count, targetName, pending = true)) }
        return id
    }

    /**
     * Starts [kind] for [ids] (to [target]); a running job is followed until it ends. [pending]: the placeholder of
     * [prepare] it replaces (stopped meanwhile: the job is stopped right away).
     */
    suspend fun start(kind: String, ids: List<Long>, target: Long?, onConflict: String?, title: String?, targetName: String?, pending: String? = null): DResult<DOp> =
        scope.async {
            val r = try {
                api.startOp(kind, ids, target, onConflict)
            } catch (e: Throwable) {
                drop(pending)
                throw e
            }
            if (r is DResult.Ok) {
                val answer = r.value
                _jobs.update { m ->
                    // the live event may have told the end before the answer came
                    val op = merged(m[answer.id]?.op, answer) ?: m[answer.id]?.op ?: answer
                    (if (pending != null) m - pending else m) +
                        (op.id to Job(op, title ?: op.label, op.items.takeIf { n -> n > 0 } ?: ids.size, targetName ?: op.targetName ?: DriveBulk.lastPart(op.targetPath)))
                }
                if (pending != null && cancelled.remove(pending)) cancel(answer.id)
                if (_jobs.value[answer.id]?.op?.running == true) follow(answer.id)
            } else {
                drop(pending)
            }
            r
        }.await()

    /** A placeholder whose start failed: gone. */
    private fun drop(pending: String?) {
        if (pending == null) return
        cancelled.remove(pending)
        _jobs.update { it - pending }
    }

    /**
     * [next] over what is known of a job ([known]): an ended job never runs again nor ends otherwise (an event or an answer
     * that comes late); the items are merged (the event carries fewer). Null: [next] is older and changes nothing.
     */
    private fun merged(known: DOp?, next: DOp): DOp? {
        if (known == null) return next
        if (!known.running && next.running) return null
        return next.copy(
            // the end the app saw first stays (a job Drive 1.12.0 left running ends here as done, its event says cancelled)
            state = if (known.running) next.state else known.state,
            files = DriveBulk.mergeFiles(known.files, next.files),
            filesMore = if (next.files == null) known.filesMore else next.filesMore,
        )
    }

    /**
     * Asks for the job's state while it runs and no event came for a while. A job with all its files done that does not
     * change for a while has ended: Drive 1.12.0 could leave one so ([DriveBulk.stalledDone]) — it is stopped on the
     * server (what is done stays, "Rückgängig" works) and shown as done.
     */
    private fun follow(id: String) {
        if (!following.add(id)) return
        scope.launch {
            try {
                var seen: DOp? = null
                var changedAt = clock()
                while (true) {
                    delay(POLL_MS)
                    val job = _jobs.value[id] ?: break
                    if (!job.op.running) break
                    val now = clock()
                    if (seen == null || seen.done != job.op.done || seen.bytesDone != job.op.bytesDone || seen.total != job.op.total) {
                        seen = job.op
                        changedAt = now
                    }
                    if (!job.local && DriveBulk.stalledDone(job.op, now - changedAt)) {
                        when (val r = api.cancelOp(id)) {
                            is DResult.Ok -> {
                                val op = r.value
                                update(if (op.state == "cancelled" || op.state == "done") op.copy(state = "done") else op)
                            }
                            else -> changedAt = now
                        }
                        continue
                    }
                    if (now - (lastEvent[id] ?: Long.MIN_VALUE / 2) < POLL_MS) continue
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

    /** "Abbrechen": what is done stays done (a job still being prepared stops once the server answers). */
    suspend fun cancel(id: String) {
        val job = _jobs.value[id] ?: return
        if (job.local || job.pending) {
            cancelled.add(id)
            return
        }
        when (val r = api.cancelOp(id)) {
            is DResult.Ok -> update(r.value)
            else -> Unit
        }
    }

    /**
     * "Rückgängig": the server puts it back (it may run as a job of its own); shown at once ([prepare]) over [count]
     * items until the server answers.
     */
    suspend fun undo(id: String, count: Int = 0): DResult<DOp> =
        scope.async {
            val was = _jobs.value[id]
            val pending = prepare("undo", count.takeIf { it > 0 } ?: was?.count ?: 0, was?.title, was?.target)
            val r = try {
                api.undoOp(id)
            } catch (e: Throwable) {
                drop(pending)
                throw e
            }
            if (r is DResult.Ok) {
                _jobs.update { m ->
                    (m - pending) + (r.value.id to Job(merged(m[r.value.id]?.op, r.value) ?: m.getValue(r.value.id).op, was?.title, was?.count ?: r.value.total, was?.target))
                }
                if (cancelled.remove(pending)) cancel(r.value.id)
                if (_jobs.value[r.value.id]?.op?.running == true) follow(r.value.id)
            } else {
                drop(pending)
            }
            r
        }.await()

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

    internal companion object {
        /** The ids of placeholders ([prepare]) — never a server's (22 characters of base64url). */
        const val PENDING = "pending-"
        const val POLL_MS = 2_000L
        const val FRESH_MS = 90_000L
    }
}
