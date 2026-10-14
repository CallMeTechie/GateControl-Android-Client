package com.gatecontrol.android.ui.familie

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamiliePlace
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.GeoHit
import com.gatecontrol.android.familie.PlaceDraft
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.locate.FamiliePermissions
import com.gatecontrol.android.familie.places.GeofenceRegistrar
import com.gatecontrol.android.familie.push.FamiliePushEvent
import com.gatecontrol.android.familie.shopping.ShoppingList
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** Orts-Erinnerungen (mockups AppPlaces, AppPlaceEdit): list, consent, create / edit; geofences follow. */
@HiltViewModel
class PlacesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: FamilieApi,
    private val repository: FamilieRepository,
    private val registrar: GeofenceRegistrar,
    shopping: ShoppingRepository,
) : ViewModel() {

    val profile: StateFlow<FamilieProfile?> = repository.profile

    private val _places = MutableStateFlow<List<FamiliePlace>?>(null)
    val places: StateFlow<List<FamiliePlace>?> = _places.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _readiness = MutableStateFlow(registrar.readiness())
    val readiness: StateFlow<FamiliePermissions.GeofenceReadiness> = _readiness.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _saved = MutableStateFlow(false)
    /** The edit screen saved or deleted: it closes. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val lookup = PlaceLookup(context, viewModelScope) { _message.value = it }
    val searchHits: StateFlow<List<GeoHit>> = lookup.hits

    /** Result of "Aktuelle Position". */
    val here: StateFlow<LatLon?> = lookup.here

    val lists: StateFlow<List<ShoppingList>> = shopping.data.map { ShoppingLogic.view(it).lists }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
        viewModelScope.launch {
            repository.events.collect { if (it is FamiliePushEvent.PlaceConsent || it is FamiliePushEvent.PlaceTell) refresh() }
        }
    }

    fun refresh() {
        _readiness.value = registrar.readiness()
        viewModelScope.launch {
            if (repository.profile.value == null) repository.refreshProfile()
            when (val r = registrar.syncFromServer()) {
                is FamilieResult.Ok -> {
                    _places.value = r.value
                    _error.value = null
                }
                else -> _error.value = r.errorCode()
            }
        }
    }

    /** After the permission flow: register what waited for it. */
    fun permissionsChanged() {
        _readiness.value = registrar.readiness()
        registrar.syncAsync()
    }

    private fun act(close: Boolean = false, call: suspend () -> FamilieResult<Unit>) {
        viewModelScope.launch {
            _saving.value = true
            val r = call()
            _saving.value = false
            if (r is FamilieResult.Ok) {
                if (close) _saved.value = true
                refresh()
            } else {
                _message.value = r.errorCode()
            }
        }
    }

    fun consent(id: Int, accept: Boolean) = act { api.placeConsent(id, accept) }

    fun delete(id: Int) = act(close = true) { api.deletePlace(id) }

    fun save(id: Int?, draft: PlaceDraft) = act(close = true) { if (id == null) api.createPlace(draft) else api.updatePlace(id, draft) }

    fun search(query: String, locale: Locale) = lookup.search(query, locale)

    fun clearHits() = lookup.clearHits()

    fun locateHere() = lookup.locateHere()

    fun consumeHere() = lookup.consumeHere()

    fun clearMessage() {
        _message.value = null
    }
}
