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
 * restart the running ones are fetched again ([restore]). A copy whose bytes a device brings (`transfer: client`,
 * GateControl has no media copy yet) is pumped from here: lend files, fetch each, upload it unchanged, report it
 * ([pump]). Without Drive 1.12 the app runs a move or a delete itself, one call per item ([startLocal]); it shows the
 * same way. New items at a target are marked "neu hier" for a while.
 */
@Singleton
class DriveOps @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val api: DriveApi,
    private val live: DriveLive,
    private val network: DriveUploadNetwork,
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
        // the stop of this device is lifted, or WLAN is back: copies waiting for this device go on
        scope.launch { live.locked.collect { locked -> if (!locked) resumePumps() } }
        scope.launch { network.allowed.collect { ok -> if (ok) resumePumps() } }
    }

    private fun update(op: DOp) {
        _jobs.update { m ->
            val known = m[op.id]
            m + (op.id to (known?.copy(op = op) ?: Job(op, op.label, op.items.takeIf { it > 0 } ?: op.total, op.targetName ?: DriveBulk.lastPart(op.targetPath))))
        }
        if (op.needsTransfer) pump(op.id)
    }

    private fun resumePumps() = _jobs.value.values.filter { it.op.needsTransfer }.forEach { pump(it.op.id) }

    /** Starts [kind] for [ids] (to [target]); a running job is followed until it ends. */
    suspend fun start(kind: String, ids: List<Long>, target: Long?, onConflict: String?, title: String?, targetName: String?): DResult<DOp> {
        val r = api.startOp(kind, ids, target, onConflict)
        if (r is DResult.Ok) {
            val op = r.value
            _jobs.update { it + (op.id to Job(op, title ?: op.label, op.items.takeIf { n -> n > 0 } ?: ids.size, targetName ?: op.targetName ?: DriveBulk.lastPart(op.targetPath))) }
            if (op.running) follow(op.id)
            if (op.needsTransfer) pump(op.id)
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

    // ─── a copy whose bytes this device brings ──────────────────────

    private val pumping = ConcurrentHashMap.newKeySet<String>()

    /**
     * Brings the bytes of the copy [id]: a few files at a time are lent to this device, each is fetched (its signed
     * address), uploaded unchanged (a vault's file stays ciphertext) and reported; the server makes the file at the
     * target and checks it is the same. Until nothing is left, the job ends, it is stopped, or this device may not
     * upload now (stopped by the ransomware protection, "only in WLAN": it goes on when that changes).
     */
    private fun pump(id: String) {
        if (!pumping.add(id)) return
        scope.launch {
            try {
                var idle = 0
                while (true) {
                    val job = _jobs.value[id] ?: break
                    if (!job.op.needsTransfer || live.locked.value || network.waitsForWifi()) break
                    val lease = when (val r = api.leaseTransfers(id)) {
                        is DResult.Ok -> r.value
                        // ended, gone or not allowed: the job's own state tells the rest
                        is DResult.Failed -> {
                            api.op(id).valueOrNull()?.let(::update)
                            break
                        }
                        else -> {
                            delay(POLL_MS * 5)
                            continue
                        }
                    }
                    if (lease.transfers.isEmpty()) {
                        // all lent to other devices for now: wait a little, then ask again
                        if (lease.left == 0 || ++idle > IDLE_MAX) break
                        delay(POLL_MS * 5)
                        continue
                    }
                    idle = 0
                    for (t in lease.transfers) {
                        if (_jobs.value[id]?.op?.running != true) break
                        if (!transfer(id, t)) return@launch
                    }
                }
            } finally {
                pumping.remove(id)
            }
        }
    }

    /** One file of a copy: fetched, uploaded, reported. False when this device should stop for now. */
    private suspend fun transfer(id: String, t: DTransfer): Boolean {
        val dir = java.io.File(context.cacheDir, "drive-copy").apply { mkdirs() }
        val file = java.io.File(dir, "$id-${t.id}.part")
        try {
            val got = when (val r = api.serverFile(t.url)) {
                is DResult.Ok -> runCatching {
                    r.value.body()!!.use { b -> b.byteStream().use { inp -> file.outputStream().use { inp.copyTo(it, 256 * 1024) } } }
                }.isSuccess
                else -> false
            }
            if (!got) {
                api.transferFailed(id, t.id, "download").valueOrNull()?.let(::update)
                return true
            }
            when (val up = api.upload(file, t.mime)) {
                is DResult.Ok -> api.transferDone(id, t.id, up.value).let { r ->
                    when (r) {
                        is DResult.Ok -> update(r.value)
                        is DResult.Failed -> DriveJson.op(runCatching { r.body?.getAsJsonObject("op") }.getOrNull())?.let(::update)
                        else -> Unit
                    }
                }
                is DResult.Failed -> {
                    // this device is stopped (ransomware protection) or Drive is full: stop here, the job says the rest
                    if (up.code == DriveJson.DEVICE_LOCKED) return false
                    api.transferFailed(id, t.id, if (up.code == "quota") "quota" else "upload").valueOrNull()?.let(::update)
                    if (up.code == "quota") return false
                }
                // offline: the lease runs out, the file is lent again later
                else -> return false
            }
            return true
        } finally {
            file.delete()
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
        /** How often an empty lease is asked again before this device leaves the copy to the others. */
        const val IDLE_MAX = 12
    }
}
