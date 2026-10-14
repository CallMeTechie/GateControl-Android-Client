package com.gatecontrol.android.ui.familie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.TaskDraft
import com.gatecontrol.android.familie.TasksPayload
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.push.FamiliePushEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Aufgaben (mockup AppTasks): my tasks / all / children, done and snooze, parents edit. */
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val api: FamilieApi,
    private val repository: FamilieRepository,
) : ViewModel() {

    val profile: StateFlow<FamilieProfile?> = repository.profile

    private val _payload = MutableStateFlow<TasksPayload?>(null)
    val payload: StateFlow<TasksPayload?> = _payload.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** One-shot error of an action. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            repository.events.collect {
                if (it is FamiliePushEvent.TaskDue || it is FamiliePushEvent.TaskDone || it is FamiliePushEvent.Other) refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _busy.value = true
            if (repository.profile.value == null) repository.refreshProfile()
            when (val r = api.tasks()) {
                is FamilieResult.Ok -> {
                    _payload.value = r.value
                    _error.value = null
                }
                else -> _error.value = r.errorCode()
            }
            _busy.value = false
        }
    }

    private fun act(call: suspend () -> FamilieResult<Unit>) {
        viewModelScope.launch {
            val r = call()
            if (r is FamilieResult.Ok) refresh() else _message.value = r.errorCode()
        }
    }

    fun done(id: Int) = act { api.taskDone(id) }

    fun snooze(id: Int) = act { api.taskSnooze(id, 60) }

    fun save(id: Int?, draft: TaskDraft) = act { if (id == null) api.createTask(draft) else api.updateTask(id, draft) }

    fun delete(id: Int) = act { api.deleteTask(id) }

    fun clearMessage() {
        _message.value = null
    }
}
