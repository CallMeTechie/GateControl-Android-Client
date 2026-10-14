package com.gatecontrol.android.ui.familie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.shopping.ShoppingItem
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingOp
import com.gatecontrol.android.familie.shopping.ShoppingRepository
import com.gatecontrol.android.familie.shopping.ShoppingSnapshot
import com.gatecontrol.android.familie.shopping.ShoppingSync
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

/** One-shot feedback of the shopping screen. */
sealed interface ShoppingNotice {
    data object GoingSent : ShoppingNotice
    data class Error(val code: String) : ShoppingNotice
}

/** Einkaufsliste (mockup AppShopping): offline-first lists, edits go through the outbox. */
@HiltViewModel
class ShoppingViewModel @Inject constructor(
    private val repository: ShoppingRepository,
    familie: FamilieRepository,
) : ViewModel() {

    val profile: StateFlow<FamilieProfile?> = familie.profile

    val view: StateFlow<ShoppingSnapshot> = combine(repository.data, familie.profile) { data, p ->
        ShoppingLogic.view(data, checkedBy = p?.me?.userId?.toString())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppingLogic.view(repository.data.value))

    val sync: StateFlow<ShoppingSync> = repository.sync

    /** Changes not yet on the server. */
    val pending: StateFlow<Int> = repository.data.map { it.outbox.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.data.value.outbox.size)

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _notice = MutableStateFlow<ShoppingNotice?>(null)
    val notice: StateFlow<ShoppingNotice?> = _notice.asStateFlow()

    private val _going = MutableStateFlow(false)
    val going: StateFlow<Boolean> = _going.asStateFlow()

    init {
        repository.syncAsync()
    }

    fun select(listId: String?) {
        _selected.value = listId
    }

    fun refresh() = repository.syncAsync()

    private fun edit(op: ShoppingOp) {
        viewModelScope.launch { repository.edit(op) }
    }

    fun addItem(listId: String, text: String, qty: String?) {
        if (text.isBlank()) return
        viewModelScope.launch { repository.addItem(listId, text, qty) }
    }

    fun toggle(item: ShoppingItem) = edit(ShoppingOp(ShoppingOp.ITEM_CHECK, id = item.id, checked = !item.checked))

    fun editItem(item: ShoppingItem, text: String, qty: String) {
        if (text.isBlank()) return
        edit(ShoppingOp(ShoppingOp.ITEM_EDIT, id = item.id, text = text, qty = qty))
    }

    fun deleteItem(item: ShoppingItem) = edit(ShoppingOp(ShoppingOp.ITEM_DELETE, id = item.id))

    fun clearChecked(listId: String) = edit(ShoppingOp(ShoppingOp.CLEAR_CHECKED, listId = listId))

    fun addList(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { _selected.value = repository.addList(name) }
    }

    fun renameList(listId: String, name: String) {
        if (name.isBlank()) return
        edit(ShoppingOp(ShoppingOp.LIST_RENAME, id = listId, name = name))
    }

    fun deleteList(listId: String) {
        if (_selected.value == listId) _selected.value = null
        edit(ShoppingOp(ShoppingOp.LIST_DELETE, id = listId))
    }

    /** "Ich gehe einkaufen": the others get a push for this list. */
    fun going(listId: String) {
        if (_going.value) return
        _going.value = true
        viewModelScope.launch {
            // The list must exist on the server first.
            repository.sync()
            val r = repository.going(listId)
            _notice.value = if (r is FamilieResult.Ok) ShoppingNotice.GoingSent else ShoppingNotice.Error(r.errorCode() ?: "failed")
            _going.value = false
        }
    }

    fun clearNotice() {
        _notice.value = null
    }
}
