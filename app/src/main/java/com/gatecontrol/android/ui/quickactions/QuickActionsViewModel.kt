package com.gatecontrol.android.ui.quickactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionRepository
import com.gatecontrol.android.quickactions.QuickActionResult
import com.gatecontrol.android.quickactions.QuickActionsLoad
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Quick actions screen and the start tab's buttons and widgets. */
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

    private val _adjusting = MutableStateFlow<Set<String>>(emptySet())
    /** Keys (`<action key>/<control id>`) of widget controls whose value is on its way to the server. */
    val adjusting: StateFlow<Set<String>> = _adjusting.asStateFlow()

    private val _results = MutableSharedFlow<Pair<QuickActionItem, QuickActionResult>>(extraBufferCapacity = 4)
    val results: SharedFlow<Pair<QuickActionItem, QuickActionResult>> = _results.asSharedFlow()

    private val pending = HashMap<String, Job>()

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    fun togglePin(item: QuickActionItem) {
        viewModelScope.launch { repository.togglePin(item) }
    }

    fun setWidget(item: QuickActionItem, asWidget: Boolean) {
        viewModelScope.launch { repository.setPinWidget(item, asWidget) }
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

    /**
     * A widget control changed to [value]: sent after [DEBOUNCE_MS] without
     * another change of the same control, so stepping "+ + +" is one request.
     * Only a failure is reported ([results]); success shows in the widget.
     */
    fun control(item: QuickActionItem, controlId: String, value: Any) {
        val key = "${item.key}/$controlId"
        pending.remove(key)?.cancel()
        _adjusting.update { it + key }
        pending[key] = viewModelScope.launch {
            try {
                delay(DEBOUNCE_MS)
                val result = repository.control(item, controlId, value)
                if (!result.ok) _results.emit(item to result)
            } finally {
                if (pending[key] == this.coroutineContext[Job]) pending.remove(key)
                _adjusting.update { it - key }
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 600L
    }
}
