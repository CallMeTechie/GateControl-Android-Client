package com.gatecontrol.android.ui.familie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieOverview
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.familie.school.SchoolRepository
import com.gatecontrol.android.familie.school.SchoolSnapshot
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.push.FamiliePushEvent
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingRepository
import com.gatecontrol.android.familie.shopping.ShoppingSnapshot
import com.gatecontrol.android.layout.FamilieLayout
import com.gatecontrol.android.layout.LayoutAccess
import com.gatecontrol.android.layout.TabLayout
import com.gatecontrol.android.layout.TabLayoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val presenceStore: com.gatecontrol.android.familie.presence.PresenceStore,
    private val tabLayout: TabLayoutRepository,
    private val plugins: PluginDirectory,
    private val school: SchoolRepository,
) : ViewModel() {

    /** The Schule plugin is listed: the card "Stundenplan" is available. */
    val schule: StateFlow<Boolean> = plugins.state.map { it.schule }
        .stateIn(viewModelScope, SharingStarted.Eagerly, plugins.state.value.schule)

    /** This week's and next week's timetable for the card (offline: the stored one). */
    val schoolSnapshot: StateFlow<SchoolSnapshot?> = school.snapshot
    val schoolError: StateFlow<String?> = school.error

    /** The tab's card order and hidden cards (from the account where the server keeps it). */
    val layout: StateFlow<TabLayout> = tabLayout.layout

    /** Where the order lives and whether this person may change it. */
    val layoutAccess: StateFlow<LayoutAccess> = tabLayout.access

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
            // On, and whenever the plugin shows up: the stored timetable at once, then a fresh one.
            plugins.state.map { it.schule }.distinctUntilChanged().collect { on ->
                if (on) {
                    school.cached()
                    school.refresh(maxAgeMs = SCHOOL_MAX_AGE_MS)
                }
            }
        }
        viewModelScope.launch { school.pushes.collect { if (plugins.state.value.schule) school.refresh() } }
        viewModelScope.launch {
            repository.events.collect { event ->
                // Something changed that the overview shows.
                if (event !is FamiliePushEvent.LocateRequest) refreshOverview()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { tabLayout.refresh() }
        if (plugins.state.value.schule) viewModelScope.launch { school.refresh(maxAgeMs = SCHOOL_MAX_AGE_MS) }
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
            is FamilieResult.Ok -> {
                _overview.value = r.value
                // 1.2: my presence consent as the server has it (set in the portal or on another phone).
                r.value.presenceShare?.let { if (it != presenceStore.share.value) presenceStore.setShare(it) }
            }
            else -> Unit
        }
    }

    fun taskDone(taskId: Int) {
        viewModelScope.launch {
            val r = api.taskDone(taskId)
            if (r is FamilieResult.Ok) refreshOverview() else _message.value = r.errorCode()
        }
    }

    /**
     * Arranged on "Familie anordnen": [order] of the cards and the ones
     * [hidden] among [available]; cards hidden before that this device does
     * not show stay hidden.
     */
    fun saveArrangement(order: List<String>, hidden: Set<String>, available: List<String>) {
        viewModelScope.launch {
            val previous = tabLayout.current()
            tabLayout.saveFamilie(
                order = TabLayout.keepForeign(order, previous.familie),
                hidden = FamilieLayout.hiddenToStore(hidden, previous.familieHidden, available),
            )
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        /** The start screen asks on every resume; a timetable this fresh is kept. */
        const val SCHOOL_MAX_AGE_MS = 60_000L
    }
}
