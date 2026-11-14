package com.gatecontrol.android.ui.notizen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.shopping.ShoppingCompleted
import com.gatecontrol.android.familie.shopping.ShoppingItem
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingOp
import com.gatecontrol.android.familie.shopping.ShoppingSnapshot
import com.gatecontrol.android.familie.shopping.ShoppingSync
import com.gatecontrol.android.notizen.NMe
import com.gatecontrol.android.notizen.NResult
import com.gatecontrol.android.notizen.NotizenRepository
import com.gatecontrol.android.notizen.NotizenShopping
import com.gatecontrol.android.notizen.NotizenShoppingLogic
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-shot feedback of a hub shopping list. */
sealed interface HubShopNotice {
    data object GoingSent : HubShopNotice
    /** „Kannst du einkaufen gehen?“ went out ([userId] null: nobody is asked any more). */
    data class AssignSent(val userId: Int?, val delivered: Boolean?, val code: String?) : HubShopNotice
    /** „Rückgängig“: the task is open again ([queued]: once there is a connection). */
    data class Undone(val queued: Boolean) : HubShopNotice
    /** [family]: an answer of the Familie plugin behind the hub (`too_soon`, `module_off`, `not_member` …). */
    data class Error(val code: String, val family: Boolean = false) : HubShopNotice
}

/**
 * A shopping list of „Notizen & Aufgaben“ (plugin 1.4.0, mockup „Abhaken wie gewohnt“): the phone's copy with what
 * waits on top ([NotizenShopping]); every change goes through its outbox, so the list works without a connection.
 */
@HiltViewModel
class NotizenShoppingViewModel @Inject constructor(
    private val hub: NotizenShopping,
    repo: NotizenRepository,
    familie: FamilieRepository,
) : ViewModel() {

    val me: StateFlow<NMe?> = repo.me

    /** The family's members for „Kannst du einkaufen gehen?“ (null without the Familie plugin). */
    val profile: StateFlow<FamilieProfile?> = familie.profile

    val view: StateFlow<ShoppingSnapshot> = combine(hub.data, repo.me) { data, me -> ShoppingLogic.view(data, checkedBy = me?.userId?.toString()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppingLogic.view(hub.data.value))

    val sync: StateFlow<ShoppingSync> = hub.sync

    /** Changes not yet on the server. */
    val pending: StateFlow<Int> = hub.data.map { it.outbox.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), hub.data.value.outbox.size)

    /** The copy was read from the phone (before: a list that is not there yet is not „gone“). */
    val loaded: StateFlow<Boolean> = hub.data.map { it.owner.isNotEmpty() || it.lastSyncMs > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _notice = MutableStateFlow<HubShopNotice?>(null)
    val notice: StateFlow<HubShopNotice?> = _notice.asStateFlow()

    private val _done = MutableStateFlow<List<ShoppingCompleted>>(emptyList())
    /** „Alles abgehakt“: the tasks the server completed with the last tick (shown with „Rückgängig“). */
    val done: StateFlow<List<ShoppingCompleted>> = _done.asStateFlow()

    private val _going = MutableStateFlow(false)
    val going: StateFlow<Boolean> = _going.asStateFlow()

    private val _assigning = MutableStateFlow(false)
    val assigning: StateFlow<Boolean> = _assigning.asStateFlow()

    init {
        viewModelScope.launch { repo.meOrCached() }
        hub.syncAsync()
        viewModelScope.launch { hub.completed.collect { _done.value = it } }
    }

    fun refresh() = hub.syncAsync()

    private fun edit(op: ShoppingOp) {
        // the last open entry: the server's answer says which task that completed — not after the usual pause
        val last = NotizenShoppingLogic.completes(view.value, op)
        viewModelScope.launch {
            hub.edit(op)
            if (last) hub.sync()
        }
    }

    fun addItem(listId: String, text: String, qty: String?) {
        if (text.isBlank()) return
        viewModelScope.launch { hub.addItem(listId, text, qty) }
    }

    fun toggle(item: ShoppingItem) = edit(ShoppingOp(ShoppingOp.ITEM_CHECK, id = item.id, checked = !item.checked))

    fun editItem(item: ShoppingItem, text: String, qty: String) {
        if (text.isBlank()) return
        edit(ShoppingOp(ShoppingOp.ITEM_EDIT, id = item.id, text = text, qty = qty))
    }

    fun deleteItem(item: ShoppingItem) = edit(ShoppingOp(ShoppingOp.ITEM_DELETE, id = item.id))

    fun clearChecked(listId: String) = edit(ShoppingOp(ShoppingOp.CLEAR_CHECKED, listId = listId))

    fun renameList(listId: String, name: String) {
        if (name.isBlank()) return
        edit(ShoppingOp(ShoppingOp.LIST_RENAME, id = listId, name = name))
    }

    fun deleteList(listId: String) = edit(ShoppingOp(ShoppingOp.LIST_DELETE, id = listId))

    /** „Rückgängig“ of „alles abgehakt“: the tasks are open again; no entry is unticked. */
    fun undo(tasks: List<ShoppingCompleted>) {
        _done.value = emptyList()
        viewModelScope.launch {
            val ticks = tasks.map { hub.undo(it.taskId) }
            _notice.value = when {
                ticks.any { it == NotizenRepository.Tick.FAILED || it == NotizenRepository.Tick.READ_ONLY } -> HubShopNotice.Error("failed")
                else -> HubShopNotice.Undone(queued = ticks.any { it == NotizenRepository.Tick.QUEUED })
            }
        }
    }

    fun clearDone() {
        _done.value = emptyList()
    }

    /** „Ich gehe einkaufen“ (a family list): the others get the Familie plugin's message. */
    fun going(listId: String) {
        if (_going.value) return
        _going.value = true
        viewModelScope.launch {
            val r = hub.going(listId)
            _notice.value = if (r is NResult.Ok) HubShopNotice.GoingSent else HubShopNotice.Error(r.code() ?: "failed", family = true)
            _going.value = false
        }
    }

    /** „<Name> bitten“ (a family list): [notify] direct | family | push. */
    fun assign(listId: String, userId: Int?, notify: String) {
        if (_assigning.value) return
        _assigning.value = true
        viewModelScope.launch {
            val r = hub.assign(listId, userId, notify)
            _notice.value = if (r is NResult.Ok) HubShopNotice.AssignSent(userId, r.value.ok, r.value.code) else HubShopNotice.Error(r.code() ?: "failed", family = true)
            _assigning.value = false
        }
    }

    fun clearNotice() {
        _notice.value = null
    }
}
