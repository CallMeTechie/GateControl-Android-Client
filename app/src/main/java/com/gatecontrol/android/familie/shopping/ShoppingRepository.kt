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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first shopping lists of the Familie plugin: the server snapshot
 * and an outbox of ops (client UUIDs) in `files/familie/shopping.json`,
 * kept by [ShoppingStore] (shared with the lists of „Notizen & Aufgaben“).
 * Edits apply at once (optimistic) and are sent shortly after (debounced);
 * sync also runs when a screen opens, on pull-to-refresh, when the VPN
 * connects and — while changes wait — as a WorkManager job as soon as
 * there is a network. After the outbox replay the server's state wins.
 */
@Singleton
class ShoppingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: FamilieApi,
    storage: FamilieStorage,
    tunnelManager: TunnelManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    private val store = ShoppingStore(
        scope = scope,
        files = ShoppingStore.files(storage, FILE),
        remote = object : ShoppingSyncEngine.Remote {
            override suspend fun ops(ops: List<ShoppingOp>) = api.shoppingOps(ops)
            override suspend fun fetch(since: String?) = api.shopping(since)
        },
        onWaiting = { enqueueWork(context) },
    )

    /** The stored state (server snapshot + outbox). */
    val data: StateFlow<ShoppingData> = store.data

    val sync: StateFlow<ShoppingSync> = store.sync

    init {
        // VPN back: the server may be reachable again.
        scope.launch {
            tunnelManager.state.map { it is TunnelState.Connected }.distinctUntilChanged().drop(1).collect { up ->
                if (up && data.value.outbox.isNotEmpty()) sync()
            }
        }
    }

    // ─── Edits (optimistic, then sent) ───────────────────────────────

    /** Queues [op]; sent after a short pause so a burst of edits goes in one call. */
    suspend fun edit(op: ShoppingOp) = store.edit(op)

    suspend fun addList(name: String): String {
        val id = UUID.randomUUID().toString()
        edit(ShoppingOp(ShoppingOp.LIST_ADD, id = id, name = name))
        return id
    }

    suspend fun addItem(listId: String, text: String, qty: String?) {
        edit(ShoppingOp(ShoppingOp.ITEM_ADD, id = UUID.randomUUID().toString(), listId = listId, text = text, qty = qty?.takeIf { it.isNotBlank() }))
    }

    // ─── Sync ────────────────────────────────────────────────────────

    /** Sends the outbox, then loads what changed on the server. */
    suspend fun sync(): ShoppingSync = store.sync()

    /** The stored state, loaded if needed (background work, e.g. a geofence notification). */
    suspend fun current(): ShoppingData = store.current()

    fun syncAsync() = store.syncAsync()

    /** App start: send what is still waiting. */
    fun syncIfPending() = store.syncIfPending()

    /** "Ich gehe einkaufen": tells the others (at most every 15 min per list). */
    suspend fun going(listId: String): FamilieResult<Unit> = api.shoppingGoing(listId)

    /**
     * B3 "<Name> bitten": the list must be on the server first; afterwards the
     * lists are fetched again so the assignee shows.
     */
    suspend fun assign(listId: String, userId: Int?, notify: String, dueAt: String? = null): FamilieResult<FamilieApi.DeliveryResult> {
        sync()
        val r = api.shoppingAssign(listId, userId, dueAt, notify)
        if (r is FamilieResult.Ok) sync()
        return r
    }

    companion object {
        private const val FILE = "shopping.json"
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

