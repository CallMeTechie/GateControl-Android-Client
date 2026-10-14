package com.gatecontrol.android.familie.shopping

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.FamilieStorage
import com.gatecontrol.android.support.CrashReports
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import com.google.gson.reflect.TypeToken
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Where the device's copy stands against the server. */
sealed interface ShoppingSync {
    data object Idle : ShoppingSync
    data object Syncing : ShoppingSync
    /** Everything sent and the server's state loaded. */
    data object Synced : ShoppingSync
    /** Server unreachable: changes wait in the outbox. */
    data object Offline : ShoppingSync
    data class Failed(val code: String) : ShoppingSync
}

/**
 * Offline-first shopping lists: the server snapshot and an outbox of ops
 * (client UUIDs) in `files/familie/shopping.json`. Edits apply at once
 * (optimistic) and are sent shortly after (debounced); sync also runs when
 * a screen opens, on pull-to-refresh, when the VPN connects and — while
 * changes wait — as a WorkManager job as soon as there is a network.
 * After the outbox replay the server's state wins.
 */
@Singleton
class ShoppingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: FamilieApi,
    private val storage: FamilieStorage,
    tunnelManager: TunnelManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val mutex = Mutex()
    private val syncMutex = Mutex()
    private var debounceJob: Job? = null

    private val _data = MutableStateFlow(ShoppingData())
    /** The stored state (server snapshot + outbox). */
    val data: StateFlow<ShoppingData> = _data.asStateFlow()

    private val _sync = MutableStateFlow<ShoppingSync>(ShoppingSync.Idle)
    val sync: StateFlow<ShoppingSync> = _sync.asStateFlow()

    @Volatile private var loaded = false

    init {
        scope.launch { load() }
        // VPN back: the server may be reachable again.
        scope.launch {
            tunnelManager.state.map { it is TunnelState.Connected }.distinctUntilChanged().drop(1).collect { up ->
                if (up && _data.value.outbox.isNotEmpty()) sync()
            }
        }
    }

    private suspend fun load(): ShoppingData = mutex.withLock {
        if (!loaded) {
            val stored = storage.read<ShoppingData>(FILE, object : TypeToken<ShoppingData>() {}.type)
            _data.value = stored?.takeIf { it.owner == storage.ownerKey() && valid(it) } ?: ShoppingData(owner = storage.ownerKey())
            loaded = true
        } else if (_data.value.owner != storage.ownerKey()) {
            // Another login: its lists are not ours.
            _data.value = ShoppingData(owner = storage.ownerKey())
            storage.write(FILE, _data.value)
        }
        _data.value
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun valid(d: ShoppingData): Boolean =
        d.server != null && d.outbox != null && d.server.lists != null && d.server.items != null &&
            d.server.lists.all { it != null && it.id != null && it.name != null } &&
            d.server.items.all { it != null && it.id != null && it.listId != null && it.text != null } &&
            d.outbox.all { it != null && it.op != null && it.op.op != null }

    private suspend fun update(transform: (ShoppingData) -> ShoppingData) {
        load()
        mutex.withLock {
            _data.value = transform(_data.value)
            storage.write(FILE, _data.value)
        }
    }

    // ─── Edits (optimistic, then sent) ───────────────────────────────

    /** Queues [op]; sent after a short pause so a burst of edits goes in one call. */
    suspend fun edit(op: ShoppingOp) {
        update { ShoppingLogic.enqueue(it, op) }
        scheduleSync()
    }

    suspend fun addList(name: String): String {
        val id = UUID.randomUUID().toString()
        edit(ShoppingOp(ShoppingOp.LIST_ADD, id = id, name = name))
        return id
    }

    suspend fun addItem(listId: String, text: String, qty: String?) {
        edit(ShoppingOp(ShoppingOp.ITEM_ADD, id = UUID.randomUUID().toString(), listId = listId, text = text, qty = qty?.takeIf { it.isNotBlank() }))
    }

    private fun scheduleSync() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            sync()
        }
    }

    // ─── Sync ────────────────────────────────────────────────────────

    /** Sends the outbox, then loads what changed on the server. */
    suspend fun sync(): ShoppingSync = syncMutex.withLock {
        load()
        _sync.value = ShoppingSync.Syncing
        val result = runSync()
        _sync.value = result
        if (result == ShoppingSync.Offline && _data.value.outbox.isNotEmpty()) enqueueWork(context)
        result
    }

    private suspend fun runSync(): ShoppingSync = ShoppingSyncEngine.run(
        current = { _data.value },
        update = { transform -> update(transform) },
        remote = object : ShoppingSyncEngine.Remote {
            override suspend fun ops(ops: List<ShoppingOp>) = api.shoppingOps(ops)
            override suspend fun fetch(since: String?) = api.shopping(since)
        },
        nowMs = System.currentTimeMillis(),
    )

    /** The stored state, loaded if needed (background work, e.g. a geofence notification). */
    suspend fun current(): ShoppingData = load()

    fun syncAsync() {
        scope.launch { sync() }
    }

    /** App start: send what is still waiting. */
    fun syncIfPending() {
        scope.launch {
            if (load().outbox.isNotEmpty()) sync()
        }
    }

    /** "Ich gehe einkaufen": tells the others (at most every 15 min per list). */
    suspend fun going(listId: String): FamilieResult<Unit> = api.shoppingGoing(listId)

    companion object {
        private const val FILE = "shopping.json"
        private const val DEBOUNCE_MS = 1_500L
        private const val WORK_NAME = "familie-shopping-sync"

        /** Runs the sync once a network is there (survives the app being closed). */
        fun enqueueWork(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<ShoppingSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(10, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            }.onFailure { Timber.w("Shopping: sync job not scheduled (%s)", it.javaClass.simpleName) }
        }
    }
}

/** Sends waiting shopping changes once there is a network again. */
class ShoppingSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun shoppingRepository(): ShoppingRepository
    }

    override suspend fun doWork(): Result {
        val repo = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).shoppingRepository()
        return when (repo.sync()) {
            ShoppingSync.Offline -> if (runAttemptCount < 8) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }
}

/**
 * One sync round, free of Android so it is unit-tested: send the outbox in
 * order (≤ 200 ops per call; a batch the server can never accept is
 * dropped), then load the server's state — everything, or what changed
 * since the last sync. The server's answer wins over the device's copy;
 * ops queued meanwhile stay in the outbox and show on top of it.
 */
object ShoppingSyncEngine {

    interface Remote {
        suspend fun ops(ops: List<ShoppingOp>): FamilieResult<com.gatecontrol.android.familie.FamilieJson.OpsResult>
        suspend fun fetch(since: String?): FamilieResult<ShoppingSnapshot>
    }

    suspend fun run(
        current: () -> ShoppingData,
        update: suspend ((ShoppingData) -> ShoppingData) -> Unit,
        remote: Remote,
        nowMs: Long,
    ): ShoppingSync {
        while (true) {
            val batch = ShoppingLogic.nextBatch(current())
            if (batch.isEmpty()) break
            val sent = batch.map { it.seq }
            when (val r = remote.ops(batch.map { it.op })) {
                is FamilieResult.Ok -> {
                    if (r.value.rejected.isNotEmpty()) Timber.d("Shopping: %d ops rejected by the server", r.value.rejected.size)
                    update { ShoppingLogic.afterSend(it, sent) }
                }
                is FamilieResult.Failed -> {
                    if (r.http == 400 || r.http == 413) {
                        update { ShoppingLogic.afterSend(it, sent) }
                        continue
                    }
                    return ShoppingSync.Failed(r.code)
                }
                FamilieResult.Offline -> return ShoppingSync.Offline
                FamilieResult.NotConfigured -> return ShoppingSync.Idle
            }
        }
        val data = current()
        val full = ShoppingLogic.needsFull(data, nowMs)
        return when (val r = remote.fetch(if (full) null else data.server.serverTime)) {
            is FamilieResult.Ok -> {
                update { d -> d.copy(server = ShoppingLogic.merge(d.server, r.value, full), lastSyncMs = nowMs) }
                ShoppingSync.Synced
            }
            is FamilieResult.Failed -> ShoppingSync.Failed(r.code)
            FamilieResult.Offline -> ShoppingSync.Offline
            FamilieResult.NotConfigured -> ShoppingSync.Idle
        }
    }
}
