package com.gatecontrol.android.ui.familie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieOverview
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.push.FamiliePushEvent
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingRepository
import com.gatecontrol.android.familie.shopping.ShoppingSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Familie start screen (mockup Main): membership, overview cards, shopping lists from the device. */
@HiltViewModel
class FamilieHomeViewModel @Inject constructor(
    private val repository: FamilieRepository,
    private val api: FamilieApi,
    private val shopping: ShoppingRepository,
) : ViewModel() {

    val profile: StateFlow<FamilieProfile?> = repository.profile
    val profileError: StateFlow<String?> = repository.profileError

    private val _overview = MutableStateFlow<FamilieOverview?>(null)
    val overview: StateFlow<FamilieOverview?> = _overview.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _profileLoaded = MutableStateFlow(false)
    /** The first `GET me` (or the stored profile) is through. */
    val profileLoaded: StateFlow<Boolean> = _profileLoaded.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** Error code of the last action (task done), shown once. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Shopping lists as the device has them (offline too). */
    val shoppingView: StateFlow<ShoppingSnapshot> = shopping.data
        .map { ShoppingLogic.view(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppingSnapshot())

    init {
        viewModelScope.launch {
            if (repository.cachedProfile() != null) _profileLoaded.value = true
        }
        viewModelScope.launch {
            repository.events.collect { event ->
                // Something changed that the overview shows.
                if (event !is FamiliePushEvent.LocateRequest) refreshOverview()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            val p = repository.refreshProfile()
            _profileLoaded.value = true
            if (p is FamilieResult.Ok || repository.profile.value != null) {
                refreshOverview()
                if (repository.profile.value?.modules?.shopping == true) shopping.sync()
            }
            _loading.value = false
        }
    }

    private suspend fun refreshOverview() {
        when (val r = api.overview()) {
            is FamilieResult.Ok -> _overview.value = r.value
            else -> Unit
        }
    }

    fun taskDone(taskId: Int) {
        viewModelScope.launch {
            val r = api.taskDone(taskId)
            if (r is FamilieResult.Ok) refreshOverview() else _message.value = r.errorCode()
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
