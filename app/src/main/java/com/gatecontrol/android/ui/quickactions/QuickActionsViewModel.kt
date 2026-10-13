package com.gatecontrol.android.ui.quickactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionRepository
import com.gatecontrol.android.quickactions.QuickActionResult
import com.gatecontrol.android.quickactions.QuickActionsLoad
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Quick actions screen and the start-screen card. */
@HiltViewModel
class QuickActionsViewModel @Inject constructor(
    private val repository: QuickActionRepository,
) : ViewModel() {

    val actions: StateFlow<List<QuickActionItem>> = repository.actions
    val pins: StateFlow<List<QuickActionItem>> = repository.pins
    val load: StateFlow<QuickActionsLoad> = repository.load

    private val _running = MutableStateFlow<String?>(null)
    /** Key of the action running right now. */
    val running: StateFlow<String?> = _running.asStateFlow()

    private val _results = MutableSharedFlow<Pair<QuickActionItem, QuickActionResult>>(extraBufferCapacity = 4)
    val results: SharedFlow<Pair<QuickActionItem, QuickActionResult>> = _results.asSharedFlow()

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    fun togglePin(item: QuickActionItem) {
        viewModelScope.launch { repository.togglePin(item) }
    }

    /** Runs [item] (the caller asked first when [QuickActionItem.confirm]). */
    fun run(item: QuickActionItem) {
        if (_running.value != null) return
        _running.value = item.key
        viewModelScope.launch {
            val result = repository.run(item)
            _running.value = null
            _results.emit(item to result)
        }
    }
}
