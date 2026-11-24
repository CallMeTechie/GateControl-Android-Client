package com.gatecontrol.android.tv.zuhause

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.shopping.ShoppingItem
import com.gatecontrol.android.familie.shopping.ShoppingList
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingSync
import com.gatecontrol.android.tv.TvSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

/** Where the shopping list stands against the server. */
enum class TvShoppingState { LOADING, OK, OFFLINE, MISSING }

/** What the page "Zuhause" and the row "Heute zu Hause" show. */
data class TvZuhauseUi(
    val today: LocalDate,
    val now: LocalTime,
    val waste: List<TvWaste> = emptyList(),
    val wasteState: TvSourceState = TvSourceState.LOADING,
    val days: List<TvCalendarDay> = emptyList(),
    val calendarState: TvSourceState = TvSourceState.LOADING,
    val lists: List<ShoppingList> = emptyList(),
    val list: ShoppingList? = null,
    val items: List<ShoppingItem> = emptyList(),
    val open: Int = 0,
    /** Open entries per list (the list choice). */
    val openByList: Map<String, Int> = emptyMap(),
    val shoppingState: TvShoppingState = TvShoppingState.LOADING,
    /** Changes not on the server yet. */
    val pending: Int = 0,
    /** The entry just ticked: "Rückgängig" for a few seconds. */
    val undo: ShoppingItem? = null,
) {
    val home: TvHomeToday
        get() = TvHomeToday(waste.firstOrNull(), TvZuhauseLogic.nextEntry(days, today, now), list, open)

    fun openOf(listId: String): Int = openByList[listId] ?: 0
}

/** The page "Zuhause" (Müllabfuhr, Kalender, Einkauf) and the start page's "Heute zu Hause". */
@HiltViewModel
class TvZuhauseViewModel @Inject constructor(
    private val zuhause: TvZuhause,
    private val settings: TvSettings,
) : ViewModel() {

    private val clock = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(CLOCK_MS)
        }
    }

    private val _undo = MutableStateFlow<ShoppingItem?>(null)
    private var undoJob: Job? = null

    val ui: StateFlow<TvZuhauseUi> = combine(
        combine(zuhause.calendar, zuhause.shopping, zuhause.sync, ::Triple),
        settings.shoppingList,
        _undo,
        clock,
    ) { (calendar, data, sync), wanted, undo, now ->
        val today = now.toLocalDate()
        val view = ShoppingLogic.view(data)
        val list = TvZuhauseLogic.chosenList(view, wanted)
        TvZuhauseUi(
            today = today,
            now = now.toLocalTime(),
            waste = calendar.alltag?.let { TvZuhauseLogic.waste(it.waste, today, now.hour) }.orEmpty(),
            wasteState = calendar.alltagState,
            days = TvZuhauseLogic.calendar(calendar.own, calendar.alltag, today),
            calendarState = when {
                calendar.familie == TvSourceState.OK || calendar.alltagState == TvSourceState.OK -> TvSourceState.OK
                calendar.familie == TvSourceState.OFFLINE || calendar.alltagState == TvSourceState.OFFLINE -> TvSourceState.OFFLINE
                else -> calendar.familie
            },
            lists = TvZuhauseLogic.lists(view),
            list = list,
            items = TvZuhauseLogic.items(view, list?.id),
            open = TvZuhauseLogic.open(view, list?.id),
            openByList = ShoppingLogic.openCounts(view),
            shoppingState = shoppingState(sync, data.server.lists.isNotEmpty() || data.lastSyncMs > 0),
            pending = data.outbox.size,
            undo = undo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TvZuhauseUi(LocalDate.now(), LocalTime.now()))

    /** The page or the start page shows: load (at most once a minute, each new day again). */
    fun refresh(force: Boolean = false) = zuhause.refreshAsync(force)

    /** OK on an entry: ticked, with "Rückgängig" for a few seconds; OK on a ticked one opens it again. */
    fun toggle(item: ShoppingItem) {
        zuhause.check(item, !item.checked)
        undoJob?.cancel()
        if (!item.checked) {
            _undo.value = item
            undoJob = viewModelScope.launch {
                delay(UNDO_MS)
                _undo.value = null
            }
        } else {
            _undo.value = null
        }
    }

    fun undo() {
        val item = _undo.value ?: return
        undoJob?.cancel()
        _undo.value = null
        zuhause.check(item, false)
    }

    fun add(text: String) {
        val list = ui.value.list ?: return
        zuhause.add(list.id, text)
    }

    fun chooseList(id: String) = settings.setShoppingList(id)

    private fun shoppingState(sync: ShoppingSync, known: Boolean): TvShoppingState = when (sync) {
        ShoppingSync.Synced -> TvShoppingState.OK
        ShoppingSync.Offline -> TvShoppingState.OFFLINE
        is ShoppingSync.Failed -> if (known) TvShoppingState.OFFLINE else TvShoppingState.MISSING
        ShoppingSync.Idle, ShoppingSync.Syncing -> if (known) TvShoppingState.OK else TvShoppingState.LOADING
    }

    private companion object {
        const val CLOCK_MS = 60_000L
        const val UNDO_MS = 5_000L
    }
}
