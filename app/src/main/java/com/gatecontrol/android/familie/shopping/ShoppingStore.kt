package com.gatecontrol.android.familie.shopping

import com.gatecontrol.android.familie.FamilieJson
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.FamilieStorage
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

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
 * The device's copy of shopping lists and its outbox, the same for the lists
 * of the Familie plugin and those of „Notizen & Aufgaben“ (same protocol:
 * `GET shopping[?since]`, `POST shopping/ops` with ids the device makes).
 * Edits apply at once and wait in the outbox; [sync] sends them in order
 * and loads the server's state, which wins. Kept per login in a file
 * ([Files]); another login starts empty.
 *
 * [listsComplete] and [dropOn]: see [ShoppingSyncEngine.run]. [onWaiting]:
 * the server could not be reached while changes wait (schedule a job).
 * [onAnswer]: every accepted `POST shopping/ops` (what it completed).
 */
class ShoppingStore(
    private val scope: CoroutineScope,
    private val files: Files,
    private val remote: ShoppingSyncEngine.Remote,
    private val listsComplete: Boolean = false,
    private val dropOn: Set<Int> = ShoppingSyncEngine.DROP_ON,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onWaiting: () -> Unit = {},
    private val onAnswer: suspend (FamilieJson.OpsResult) -> Unit = {},
) {
    /** Where the copy is kept. */
    interface Files {
        /** Login (server + token) the stored data belongs to. */
        fun ownerKey(): String
        fun read(): ShoppingData?
        fun write(data: ShoppingData)
    }

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
    }

    private suspend fun load(): ShoppingData = mutex.withLock {
        if (!loaded) {
            val stored = files.read()
            _data.value = stored?.takeIf { it.owner == files.ownerKey() && valid(it) } ?: ShoppingData(owner = files.ownerKey())
            loaded = true
        } else if (_data.value.owner != files.ownerKey()) {
            // Another login: its lists are not ours.
            _data.value = ShoppingData(owner = files.ownerKey())
            files.write(_data.value)
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
            files.write(_data.value)
        }
    }

    /** Queues [op]; sent after a short pause so a burst of edits goes in one call. */
    suspend fun edit(op: ShoppingOp) {
        update { ShoppingLogic.enqueue(it, op) }
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            sync()
        }
    }

    /** Sends the outbox, then loads what changed on the server. */
    suspend fun sync(): ShoppingSync = syncMutex.withLock {
        load()
        _sync.value = ShoppingSync.Syncing
        val result = ShoppingSyncEngine.run(
            current = { _data.value },
            update = { transform -> update(transform) },
            remote = remote,
            nowMs = clock(),
            listsComplete = listsComplete,
            dropOn = dropOn,
            onAnswer = onAnswer,
        )
        _sync.value = result
        if (result == ShoppingSync.Offline && _data.value.outbox.isNotEmpty()) onWaiting()
        result
    }

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

    /** Everything of this copy goes (the plugin is gone). */
    suspend fun clear() {
        update { ShoppingData(owner = files.ownerKey()) }
        _sync.value = ShoppingSync.Idle
    }

    companion object {
        private const val DEBOUNCE_MS = 1_500L

        /** The copy as a file of [storage] (`files/familie/<name>`). */
        fun files(storage: FamilieStorage, name: String): Files = object : Files {
            override fun ownerKey(): String = storage.ownerKey()
            override fun read(): ShoppingData? = storage.read<ShoppingData>(name, object : TypeToken<ShoppingData>() {}.type)
            override fun write(data: ShoppingData) = storage.write(name, data)
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

    /** A whole batch refused with one of these is never accepted later: it leaves the outbox. */
    val DROP_ON = setOf(400, 413)

    interface Remote {
        suspend fun ops(ops: List<ShoppingOp>): FamilieResult<FamilieJson.OpsResult>
        suspend fun fetch(since: String?): FamilieResult<ShoppingSnapshot>
    }

    /**
     * [listsComplete] (the hub): a `since` answer names every list still —
     * missing ones go ([ShoppingLogic.merge]), and for one the device did
     * not know everything is loaded once more (its entries are older than
     * `since`). [onAnswer]: an accepted batch (the hub says what „alles
     * abgehakt“ completed).
     */
    suspend fun run(
        current: () -> ShoppingData,
        update: suspend ((ShoppingData) -> ShoppingData) -> Unit,
        remote: Remote,
        nowMs: Long,
        listsComplete: Boolean = false,
        dropOn: Set<Int> = DROP_ON,
        onAnswer: suspend (FamilieJson.OpsResult) -> Unit = {},
    ): ShoppingSync {
        while (true) {
            val batch = ShoppingLogic.nextBatch(current())
            if (batch.isEmpty()) break
            val sent = batch.map { it.seq }
            when (val r = remote.ops(batch.map { it.op })) {
                is FamilieResult.Ok -> {
                    if (r.value.rejected.isNotEmpty()) Timber.d("Shopping: %d ops rejected by the server", r.value.rejected.size)
                    update { ShoppingLogic.afterSend(it, sent) }
                    onAnswer(r.value)
                }
                is FamilieResult.Failed -> {
                    if (r.http in dropOn) {
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
        var full = ShoppingLogic.needsFull(data, nowMs)
        var since = if (full) null else data.server.serverTime
        while (true) {
            when (val r = remote.fetch(since)) {
                is FamilieResult.Ok -> {
                    if (!full && listsComplete && ShoppingLogic.unknownLists(current().server, r.value).isNotEmpty()) {
                        full = true
                        since = null
                        continue
                    }
                    val all = full
                    update { d -> d.copy(server = ShoppingLogic.merge(d.server, r.value, all, listsComplete), lastSyncMs = nowMs) }
                    return ShoppingSync.Synced
                }
                is FamilieResult.Failed -> return ShoppingSync.Failed(r.code)
                FamilieResult.Offline -> return ShoppingSync.Offline
                FamilieResult.NotConfigured -> return ShoppingSync.Idle
            }
        }
    }
}
