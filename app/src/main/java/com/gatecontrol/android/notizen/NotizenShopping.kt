package com.gatecontrol.android.notizen

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.FamilieStorage
import com.gatecontrol.android.familie.shopping.ShoppingCompleted
import com.gatecontrol.android.familie.shopping.ShoppingData
import com.gatecontrol.android.familie.shopping.ShoppingList
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingOp
import com.gatecontrol.android.familie.shopping.ShoppingSnapshot
import com.gatecontrol.android.familie.shopping.ShoppingStore
import com.gatecontrol.android.familie.shopping.ShoppingSync
import com.gatecontrol.android.familie.shopping.ShoppingSyncEngine
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The shopping lists of „Notizen & Aufgaben“ on this phone (plugin 1.4.0, `shoppingLists`): the same offline copy and
 * outbox as the lists of the Familie plugin ([ShoppingStore], same protocol and the same ids in both plugins) — ticking
 * works in the shop without a connection and is sent in order later. The hub names every list in each answer, so a
 * list left or no longer shared goes; and it says which tasks „alles abgehakt“ completed ([completed]).
 */
@Singleton
class NotizenShopping @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: NotizenApi,
    storage: FamilieStorage,
    private val repo: NotizenRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    private val _completed = MutableSharedFlow<List<ShoppingCompleted>>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** „Alles abgehakt“: the tasks the server completed with the tick just sent (for „Rückgängig“ on the open list). */
    val completed: SharedFlow<List<ShoppingCompleted>> = _completed.asSharedFlow()

    private val store = ShoppingStore(
        scope = scope,
        files = ShoppingStore.files(storage, FILE),
        remote = object : ShoppingSyncEngine.Remote {
            override suspend fun ops(ops: List<ShoppingOp>) = api.shoppingOps(ops).familie()
            override suspend fun fetch(since: String?) = api.shopping(since).familie()
        },
        listsComplete = true,
        // 403: this person may only read here — what waits is never accepted
        dropOn = ShoppingSyncEngine.DROP_ON + 403,
        onWaiting = { NotizenShoppingWorker.enqueue(context) },
        onAnswer = { answer ->
            // the numbers of „Einkauf 3/8“ at the tasks changed, and „alles abgehakt“ may have completed some
            repo.touched("tasks")
            if (answer.completed.isNotEmpty()) _completed.tryEmit(answer.completed)
        },
    )

    /** The stored state (the hub's snapshot + what waits). */
    val data: StateFlow<ShoppingData> = store.data

    val sync: StateFlow<ShoppingSync> = store.sync

    init {
        // the server says lists or entries changed (or the stream is back): the copy follows
        scope.launch {
            repo.changed.collectLatest { what ->
                if (what.isNotEmpty() && "shopping" !in what) return@collectLatest
                delay(300)
                if (repo.me.value?.shoppingLists == true) store.sync()
            }
        }
    }

    /** The plugin on the server has shopping lists (`GET me` → `shoppingLists`, the kept answer while offline). */
    suspend fun enabled(): Boolean = repo.meOrCached()?.shoppingLists == true

    /**
     * The hub's lists can be shown: the feature is there and the phone has a copy (or just got one). Without it —
     * an older plugin, or the hub never reached — the lists stay where they were.
     */
    suspend fun ready(): Boolean {
        if (!enabled()) return false
        if (store.current().lastSyncMs > 0) return true
        return sync() == ShoppingSync.Synced
    }

    /** Sends what waits, then loads what changed; nothing without the feature. */
    suspend fun sync(): ShoppingSync = if (enabled()) store.sync() else ShoppingSync.Idle

    fun syncAsync() {
        scope.launch { sync() }
    }

    suspend fun current(): ShoppingData = store.current()

    /** Queues [op] (shown at once, sent after a short pause). */
    suspend fun edit(op: ShoppingOp) = store.edit(op)

    /** A new list of mine in [area] → its id (the list can be used at once, also offline). */
    suspend fun addList(name: String, area: String, family: Boolean = false): String {
        val id = UUID.randomUUID().toString()
        edit(ShoppingOp(ShoppingOp.LIST_ADD, id = id, name = name, icon = "cart".takeIf { repo.me.value?.listIcons?.contains(it) == true }, area = NArea.of(area), family = true.takeIf { family }))
        return id
    }

    suspend fun addItem(listId: String, text: String, qty: String?) {
        edit(ShoppingOp(ShoppingOp.ITEM_ADD, id = UUID.randomUUID().toString(), listId = listId, text = text, qty = qty?.takeIf { it.isNotBlank() }))
    }

    /** „Ich gehe einkaufen“ (a family list): the list must be on the server first. */
    suspend fun going(uid: String): NResult<Unit> {
        sync()
        return api.shoppingGoing(uid)
    }

    /** „Kannst du einkaufen gehen?“ (a family list); afterwards the lists are loaded again so the request shows. */
    suspend fun assign(uid: String, userId: Int?, notify: String): NResult<NDelivery> {
        sync()
        val r = api.shoppingAssign(uid, userId, null, notify)
        if (r is NResult.Ok) sync()
        return r
    }

    /** „Rückgängig“ after „alles abgehakt“: the task is open again (no entry is unticked). */
    suspend fun undo(taskId: Long): NotizenRepository.Tick = repo.completeTask(taskId, false).first

    private fun <T> NResult<T>.familie(): FamilieResult<T> = when (this) {
        is NResult.Ok -> FamilieResult.Ok(value)
        is NResult.Failed -> FamilieResult.Failed(code, http)
        NResult.Offline -> FamilieResult.Offline
        NResult.NotConfigured -> FamilieResult.NotConfigured
    }

    companion object {
        private const val FILE = "hub-shopping.json"
    }
}

/** Pure rules of the hub's shopping lists (unit-tested). */
object NotizenShoppingLogic {

    /** The lists of [area] the side menu shows, with their open entries (the device's view: what waits is counted). */
    fun menu(view: ShoppingSnapshot, area: String): List<Pair<ShoppingList, Int>> {
        val counts = ShoppingLogic.openCounts(view)
        return view.lists.filter { (it.area ?: NArea.PRIVATE) == area }.map { it to (counts[it.id] ?: 0) }
    }

    /** Whom a list is shared with, for the choice at a task: null — the family; empty — only me; else the names. */
    fun sharedWith(list: ShoppingList, me: String?): List<String>? = when {
        list.family -> null
        !list.shared -> emptyList()
        else -> (listOfNotNull(list.ownerName) + list.memberNames.orEmpty()).filter { it != me }.distinct()
    }

    /**
     * „Alles abgehakt“ as the phone sees it before the server answers: [op] ticks an entry and no open one is left in
     * its list afterwards (the list has at least one entry). The server decides; this only says when its answer is
     * worth waiting for.
     */
    fun completes(view: ShoppingSnapshot, op: ShoppingOp): Boolean {
        if (op.op != ShoppingOp.ITEM_CHECK || op.checked != true) return false
        val item = view.items.firstOrNull { it.id == op.id } ?: return false
        if (item.checked) return false
        return view.items.none { it.listId == item.listId && it.id != item.id && !it.checked }
    }

    /** The sentence's tasks: those of [completed] for [listId] (all when the list is not known). */
    fun completedFor(completed: List<ShoppingCompleted>, listId: String?): List<ShoppingCompleted> =
        completed.filter { listId == null || it.listId == listId }.distinctBy { it.taskId }
}

/**
 * Where the family's shopping list opens (contract § 9, pure): in the hub when Familie says its lists live in „Notizen
 * & Aufgaben“ (`home: notizen`), this app's hub has shopping lists and holds the list with the same id; else in the
 * Familie plugin, as before.
 */
object ShoppingHome {
    const val NOTIZEN = "notizen"

    sealed interface Target
    /** The page of the Familie plugin (its own lists, or its copy). */
    data object Familie : Target
    /** The hub's list [uid], to tick off. */
    data class Hub(val uid: String) : Target

    /** Familie's answer says the lists moved. */
    fun moved(home: String?): Boolean = home == NOTIZEN

    /**
     * [home]: `home.home` of Familie's `GET shopping` (null: an older plugin). [hubReady]: the hub has the feature and a
     * copy on this phone. [hub]: the hub's lists; [familie]: Familie's, in its order. [listId]: the list asked for
     * (null: the card's „Öffnen“ — the first family list both know).
     */
    fun target(home: String?, hubReady: Boolean, hub: List<ShoppingList>, familie: List<ShoppingList>, listId: String?): Target {
        if (!moved(home) || !hubReady) return Familie
        val known = hub.map { it.id }.toSet()
        val uid = listId?.takeIf { it in known } ?: familie.firstOrNull { listId == null && it.id in known }?.id
        return uid?.let { Hub(it) } ?: Familie
    }
}

/** Sends waiting changes of the hub's shopping lists once there is a network again. */
class NotizenShoppingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun notizenShopping(): NotizenShopping
    }

    override suspend fun doWork(): Result {
        val shopping = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).notizenShopping()
        return when (shopping.sync()) {
            ShoppingSync.Offline -> if (runAttemptCount < 8) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "notizen-shopping-sync"

        fun enqueue(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<NotizenShoppingWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(10, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            }.onFailure { Timber.w("Notizen: shopping sync job not scheduled (%s)", it.javaClass.simpleName) }
        }
    }
}
