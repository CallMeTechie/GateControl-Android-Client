package com.gatecontrol.android.ui.familie

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.notizen.NotizenRepository
import com.gatecontrol.android.notizen.TasksHome
import com.gatecontrol.android.push.PushNavTarget
import com.gatecontrol.android.push.PushNavigation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Decides once where the family's tasks open ([TasksHome]): Familie says where its tasks live (`home` of its `GET
 * tasks`); the hub's family list needs the hub's `GET me` (`familyTasks`). Without an answer: Familie's page.
 */
@HiltViewModel
class TasksEntryViewModel @Inject constructor(
    private val api: FamilieApi,
    private val hub: NotizenRepository,
) : ViewModel() {

    private val _target = MutableStateFlow<TasksHome.Target?>(null)
    /** null: not decided yet. */
    val target: StateFlow<TasksHome.Target?> = _target.asStateFlow()

    init {
        viewModelScope.launch {
            val home = withTimeoutOrNull(READY_MS) { (api.tasks() as? FamilieResult.Ok)?.value?.home }
            _target.value = if (!TasksHome.moved(home)) {
                TasksHome.Familie
            } else {
                // never held up for long: without an answer the Familie page opens (it works on its copy)
                TasksHome.target(home, withTimeoutOrNull(READY_MS) { hub.refreshMe()?.value })
            }
        }
    }

    private companion object {
        const val READY_MS = 6_000L
    }
}

/**
 * „Alle Aufgaben“ of the Familie tab (the card „Heute“, a push, a link): Familie's own page — or, after the move to
 * „Notizen & Aufgaben“ (Familie 1.24 `home: notizen`), the family's list of the hub in the tab „Aufgaben“.
 */
@Composable
fun TasksEntry(onBack: () -> Unit, vm: TasksEntryViewModel = hiltViewModel()) {
    val target by vm.target.collectAsStateWithLifecycle()
    when (val t = target) {
        null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        TasksHome.Familie -> TasksScreen(onBack = onBack)
        is TasksHome.Hub -> {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            // this page gives way to the list in „Aufgaben“ (the tab first, so Back leads there)
            LaunchedEffect(t) {
                onBack()
                PushNavigation.request(PushNavTarget.Notizen(TasksHome.dest(t)))
            }
        }
    }
}
