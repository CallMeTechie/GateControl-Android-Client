package com.gatecontrol.android.ui.logbook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.ui.adaptive.GcTwoPane

/**
 * The logbook on wide windows: the list on the left, beside it the
 * all-trips map of the list's month — or, once one is tapped, that trip
 * (its back returns to the map). [onBack] leaves the logbook.
 */
@Composable
fun LogbookTwoPane(vehicleId: Long?, onBack: () -> Unit) {
    val listViewModel: LogbookViewModel = hiltViewModel()
    val ui by listViewModel.ui.collectAsStateWithLifecycle()
    var tripId by rememberSaveable { mutableStateOf<Long?>(null) }
    val vehicle = ui.data?.vehicleId ?: ui.load.vehicleId
    val month = ui.data?.month ?: ui.load.month
    GcTwoPane(
        listWidth = 420.dp,
        list = {
            LogbookScreen(
                vehicleId = vehicleId,
                onBack = onBack,
                onOpenMap = { _, _ -> tripId = null },
                onOpenTrip = { trip, _, _ -> tripId = trip.id },
                selectedTripId = tripId,
                viewModel = listViewModel,
            )
        },
        detail = {
            val id = tripId
            // Until the list knows its month, the map waits (no second request for the same month).
            if (id == null && month != null) {
                LogbookMapScreen(
                    vehicleId = vehicle,
                    month = month,
                    onBack = {},
                    onShowTrips = {},
                    onOpenTrip = { trip, _, _ -> tripId = trip.id },
                    onMonthChange = listViewModel::setMonth,
                )
            } else if (id != null) {
                key(id) {
                    LogbookTripScreen(
                        tripId = id,
                        vehicleId = vehicle,
                        month = month,
                        onBack = { tripId = null },
                        viewModel = hiltViewModel(key = "logbook-trip-$id"),
                    )
                }
            }
        },
    )
}
