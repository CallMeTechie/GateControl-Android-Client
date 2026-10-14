package com.gatecontrol.android.ui.familie

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.GeoHit
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.locate.FamiliePermissions
import com.gatecontrol.android.familie.places.GeofenceRegistrar
import com.gatecontrol.android.familie.push.FamiliePushEvent
import com.gatecontrol.android.familie.rides.DriverChoice
import com.gatecontrol.android.familie.rides.Ride
import com.gatecontrol.android.familie.rides.RideDraft
import com.gatecontrol.android.familie.rides.RideLogic
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

/** One-shot notices of the rides screen. */
sealed interface RidesNotice {
    /** [detail]: who drives a ride that was `already_taken`. */
    data class Error(val code: String?, val detail: String? = null) : RidesNotice

    data object Asked : RidesNotice

    data object Requested : RidesNotice

    /** The place reminder was created; [needsPermission]: geofences cannot run yet. */
    data class PlaceCreated(val needsPermission: Boolean) : RidesNotice
}

/** Abholen & Fahrten (mockup AppPickup): this week's rides, take / decline / give back, ask, create and edit. */
@HiltViewModel
class RidesViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val api: FamilieApi,
    private val repository: FamilieRepository,
    private val registrar: GeofenceRegistrar,
) : ViewModel() {

    val profile: StateFlow<FamilieProfile?> = repository.profile

    private val _rides = MutableStateFlow<List<Ride>?>(null)
    val rides: StateFlow<List<Ride>?> = _rides.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _notice = MutableStateFlow<RidesNotice?>(null)
    val notice: StateFlow<RidesNotice?> = _notice.asStateFlow()

    /** After "Ich mache es": offer a place reminder at the ride's place. */
    private val _offerPlace = MutableStateFlow<Ride?>(null)
    val offerPlace: StateFlow<Ride?> = _offerPlace.asStateFlow()

    /** Rides asked for on this phone (id → epoch ms), for the 5-minute pause. */
    private val _askedAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val askedAt: StateFlow<Map<String, Long>> = _askedAt.asStateFlow()

    private val lookup = PlaceLookup(context, viewModelScope) { _notice.value = RidesNotice.Error(it) }
    val searchHits: StateFlow<List<GeoHit>> = lookup.hits
    val here: StateFlow<LatLon?> = lookup.here

    init {
        refresh()
        viewModelScope.launch {
            repository.events.collect { if (it is FamiliePushEvent.Ride || (it is FamiliePushEvent.Other && it.topicKey == "rides")) refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _busy.value = true
            if (repository.profile.value == null) repository.refreshProfile()
            val today = LocalDate.now()
            when (val r = api.rides(today.toString(), today.plusDays(RANGE_DAYS).toString())) {
                is FamilieResult.Ok -> {
                    _rides.value = r.value
                    _error.value = null
                }
                else -> _error.value = r.errorCode()
            }
            _busy.value = false
        }
    }

    private fun act(onOk: suspend () -> Unit = {}, call: suspend () -> FamilieResult<*>) {
        viewModelScope.launch {
            _busy.value = true
            val r = call()
            _busy.value = false
            if (r is FamilieResult.Ok) {
                onOk()
                refresh()
            } else {
                _notice.value = RidesNotice.Error(r.errorCode(), (r as? FamilieResult.Failed)?.detail)
                // The ride may have changed meanwhile (taken by someone else …).
                refresh()
            }
        }
    }

    fun take(ride: Ride) = act(onOk = {
        if (ride.hasPosition && repository.profile.value?.modules?.places == true) _offerPlace.value = ride
    }) { api.takeRide(ride.id) }

    fun release(ride: Ride) = act { api.releaseRide(ride.id) }

    fun decline(ride: Ride) = act { api.declineRide(ride.id) }

    fun ask(ride: Ride) = act(onOk = {
        _askedAt.value = _askedAt.value + (ride.id to System.currentTimeMillis())
        _notice.value = RidesNotice.Asked
    }) { api.askRide(ride.id) }

    fun create(draft: RideDraft) = act(onOk = {
        if (draft.ask == true) _notice.value = RidesNotice.Requested
    }) { api.createRide(draft) }

    /**
     * Change a ride. A changed driver of one ride goes through `POST …/driver`
     * (it tells the family); the driver of a whole series is part of the PATCH.
     */
    fun update(ride: Ride, draft: RideDraft, series: Boolean, driver: DriverChoice?) = act {
        if (series) {
            api.updateRide(ride.id, draft.copy(driver = driver ?: DriverChoice.None), series = true, withDriver = driver != null)
        } else {
            val r = api.updateRide(ride.id, draft, series = false)
            if (r is FamilieResult.Ok && driver != null) api.setRideDriver(ride.id, driver) else r
        }
    }

    /** Cancel an occurrence (parents; a child its own one-off request), or remove the whole [series]. */
    fun delete(ride: Ride, series: Boolean) = act { api.deleteRide(ride.id, series) }

    /** A cancelled ride takes place after all. */
    fun reopen(ride: Ride) = act { api.reopenRide(ride.id) }

    fun dismissPlaceOffer() {
        _offerPlace.value = null
    }

    /** "Orts-Erinnerung an der Halle anlegen": arrive, 150 m, [note], once, for me. */
    fun createPlaceReminder(ride: Ride, note: String) {
        _offerPlace.value = null
        val me = repository.profile.value?.me ?: return
        val draft = RideLogic.placeReminder(ride, me, note) ?: return
        viewModelScope.launch {
            when (val r = api.createPlace(draft)) {
                is FamilieResult.Ok -> {
                    registrar.syncAsync()
                    _notice.value = RidesNotice.PlaceCreated(registrar.readiness() != FamiliePermissions.GeofenceReadiness.READY)
                }
                else -> _notice.value = RidesNotice.Error(r.errorCode())
            }
        }
    }

    fun search(query: String, locale: Locale) = lookup.search(query, locale)

    fun clearHits() = lookup.clearHits()

    fun locateHere() = lookup.locateHere()

    fun consumeHere() = lookup.consumeHere()

    fun clearNotice() {
        _notice.value = null
    }

    private companion object {
        /** "Diese Woche": today and the next 7 days (the server's default range). */
        const val RANGE_DAYS = 7L
    }
}
