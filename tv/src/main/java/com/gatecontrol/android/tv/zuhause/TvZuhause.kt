package com.gatecontrol.android.tv.zuhause

import com.gatecontrol.android.familie.AlltagPayload
import com.gatecontrol.android.familie.FamilieEvent
import com.gatecontrol.android.familie.FamilieHomeApi
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.FamilieStorage
import com.gatecontrol.android.familie.shopping.ShoppingData
import com.gatecontrol.android.familie.shopping.ShoppingItem
import com.gatecontrol.android.familie.shopping.ShoppingOp
import com.gatecontrol.android.familie.shopping.ShoppingStore
import com.gatecontrol.android.familie.shopping.ShoppingSync
import com.gatecontrol.android.familie.shopping.ShoppingSyncEngine
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tv.tunnel.TvTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** How one source of the page answered. */
enum class TvSourceState {
    /** Not asked yet. */
    LOADING,
    OK,
    /** No answer (no network, VPN off): what was there stays. */
    OFFLINE,
    /** The plugin is not there, not on for this person, or refused. */
    MISSING,
}

/** The dates and the Alltag calendar (with the waste collection) as last loaded. */
data class TvCalendarData(
    val own: List<FamilieEvent> = emptyList(),
    val alltag: AlltagPayload? = null,
    val familie: TvSourceState = TvSourceState.LOADING,
    val alltagState: TvSourceState = TvSourceState.LOADING,
    /** The day they were loaded for (the next day loads again). */
    val day: LocalDate? = null,
)

/**
 * "Zuhause" of GateControl TV: the dates of the Familie plugin, the Alltag calendar with the waste collection and the
 * shopping lists of the Familie plugin with the same offline copy and outbox as the phone app ([ShoppingStore]):
 * ticking applies at once and is sent shortly after; without a connection it waits and goes once the VPN is back or
 * on the next attempt (every minute while something waits). The TV uses the rights of the person who paired it.
 */
@Singleton
class TvZuhause @Inject constructor(
    private val api: FamilieHomeApi,
    storage: FamilieStorage,
    tunnel: TvTunnel,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private var retryJob: Job? = null

    private val store = ShoppingStore(
        scope = scope,
        files = ShoppingStore.files(storage, FILE),
        remote = object : ShoppingSyncEngine.Remote {
            override suspend fun ops(ops: List<ShoppingOp>) = api.shoppingOps(ops)
            override suspend fun fetch(since: String?) = api.shopping(since)
        },
        onWaiting = ::retryLater,
    )

    /** The stored shopping lists (server snapshot + outbox). */
    val shopping: StateFlow<ShoppingData> = store.data

    val sync: StateFlow<ShoppingSync> = store.sync

    private val _calendar = MutableStateFlow(TvCalendarData())
    val calendar: StateFlow<TvCalendarData> = _calendar.asStateFlow()

    @Volatile
    private var lastLoad = 0L

    init {
        // VPN back: what waits goes now.
        scope.launch {
            tunnel.state.map { it is TunnelState.Connected }.distinctUntilChanged().drop(1).collect { up ->
                if (up) {
                    if (shopping.value.outbox.isNotEmpty()) store.sync()
                    if (_calendar.value.familie != TvSourceState.OK) refresh(force = true)
                }
            }
        }
        store.syncIfPending()
    }

    /** Loads the dates, the Alltag calendar and the lists; at most every [MIN_GAP_MS] unless [force]d or a new day. */
    suspend fun refresh(force: Boolean = false, today: LocalDate = LocalDate.now()) = loadMutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && _calendar.value.day == today && now - lastLoad < MIN_GAP_MS) return@withLock
        lastLoad = now
        coroutineScope {
            val dates = async { api.dates(today.toString(), TvZuhauseLogic.calendarEnd(today).toString()) }
            val alltag = async { api.alltagEvents(today.toString(), today.plusDays(TvZuhauseLogic.WASTE_DAYS.toLong()).toString()) }
            val lists = async { store.sync() }
            val old = _calendar.value
            val d = dates.await()
            val a = alltag.await()
            _calendar.value = TvCalendarData(
                own = (d as? FamilieResult.Ok)?.value ?: old.own.takeIf { d is FamilieResult.Offline }.orEmpty(),
                alltag = (a as? FamilieResult.Ok)?.value ?: old.alltag.takeIf { a is FamilieResult.Offline },
                familie = state(d),
                alltagState = state(a),
                day = today,
            )
            lists.await()
        }
    }

    fun refreshAsync(force: Boolean = false) {
        scope.launch { refresh(force) }
    }

    /** OK on an entry: ticked (or back again); sent shortly after. */
    fun check(item: ShoppingItem, checked: Boolean) {
        scope.launch { store.edit(ShoppingOp(ShoppingOp.ITEM_CHECK, id = item.id, checked = checked)) }
    }

    /** "Etwas hinzufügen": the server sorts it into the list as on the phone. */
    fun add(listId: String, text: String) {
        val t = TvZuhauseLogic.entryText(text) ?: return
        scope.launch { store.edit(ShoppingOp(ShoppingOp.ITEM_ADD, id = UUID.randomUUID().toString(), listId = listId, text = t)) }
    }

    /** Unpaired: nothing of this login stays. */
    fun clear() {
        scope.launch {
            store.clear()
            _calendar.value = TvCalendarData()
            lastLoad = 0L
        }
    }

    /** Offline with changes waiting: one more try in a minute (and again, while they wait). */
    private fun retryLater() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(RETRY_MS)
            store.sync()
        }
    }

    private fun state(r: FamilieResult<*>): TvSourceState = when (r) {
        is FamilieResult.Ok -> TvSourceState.OK
        FamilieResult.Offline, FamilieResult.NotConfigured -> TvSourceState.OFFLINE
        is FamilieResult.Failed -> TvSourceState.MISSING
    }

    private companion object {
        const val FILE = "tv-shopping.json"
        const val MIN_GAP_MS = 60_000L
        const val RETRY_MS = 60_000L
    }
}
