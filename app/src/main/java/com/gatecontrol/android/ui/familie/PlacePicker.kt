package com.gatecontrol.android.ui.familie

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieGeocoder
import com.gatecontrol.android.familie.GeoHit
import com.gatecontrol.android.location.DeviceLocator
import com.gatecontrol.android.location.LocateResult
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Address search and "Aktuelle Position" for [FamiliePlacePicker], owned by
 * a view model ([scope]); errors go to [onError] as Familie error codes.
 */
class PlaceLookup(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    private val _hits = MutableStateFlow<List<GeoHit>>(emptyList())
    val hits: StateFlow<List<GeoHit>> = _hits.asStateFlow()

    private val _here = MutableStateFlow<LatLon?>(null)
    /** Result of "Aktuelle Position", until consumed. */
    val here: StateFlow<LatLon?> = _here.asStateFlow()

    fun search(query: String, locale: Locale) {
        scope.launch {
            _hits.value = FamilieGeocoder.search(context, query, locale)
            if (_hits.value.isEmpty()) onError("no_hits")
        }
    }

    fun clearHits() {
        _hits.value = emptyList()
    }

    fun locateHere() {
        scope.launch {
            when (val r = DeviceLocator.locate(context)) {
                is LocateResult.Fix -> _here.value = LatLon(r.location.latitude, r.location.longitude)
                LocateResult.NoPermission -> onError("no_permission")
                LocateResult.LocationOff -> onError("location_off")
                LocateResult.NoFix -> onError("timeout")
            }
        }
    }

    fun consumeHere() {
        _here.value = null
    }
}

/**
 * The 1.0 place picker: OSM map (tap to pick), coordinates, "Aktuelle
 * Position", address search with hits. [onPick] gets the hit's label when
 * the point came from the search.
 */
@Composable
fun FamiliePlacePicker(
    lat: Double?,
    lon: Double?,
    hits: List<GeoHit>,
    onPick: (lat: Double, lon: Double, label: String?) -> Unit,
    onHere: () -> Unit,
    onSearch: (String) -> Unit,
    onClearHits: () -> Unit,
    modifier: Modifier = Modifier,
    radiusM: Int? = null,
    readOnly: Boolean = false,
    mapHeight: Dp = 240.dp,
) {
    val extra = GateControlTheme.extraColors
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OsmMap(
            point = if (lat != null && lon != null) LatLon(lat, lon) else null,
            radiusM = radiusM?.toDouble(),
            zoom = if (lat != null) 16.0 else 5.0,
            contentDescription = if (radiusM != null) stringResource(R.string.familie_place_map_cd, radiusM) else stringResource(R.string.familie_ride_map_cd),
            modifier = Modifier.fillMaxWidth().height(mapHeight),
            onTap = if (readOnly) null else ({ pt -> onPick(pt.lat, pt.lon, null) }),
        )
        if (lat == null || lon == null) {
            Text(stringResource(R.string.familie_place_pick_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        } else {
            Text(String.format(Locale.ROOT, "%.5f, %.5f", lat, lon), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        if (!readOnly) {
            GcOutlineButton(text = stringResource(R.string.familie_place_here), onClick = onHere, icon = GcIcons.Crosshair)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GcTextField(
                    value = query,
                    onValueChange = { query = it.take(120) },
                    label = stringResource(R.string.familie_place_search),
                    placeholder = stringResource(R.string.familie_place_search_hint),
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                )
                GcIconButton(
                    GcIcons.Search,
                    contentDescription = stringResource(R.string.familie_place_search),
                    onClick = { onSearch(query) },
                    enabled = query.isNotBlank(),
                    modifier = Modifier.padding(top = 26.dp),
                )
            }
            if (hits.isNotEmpty()) {
                GcCard(contentPadding = PaddingValues(vertical = 2.dp), verticalArrangement = Arrangement.Top) {
                    hits.forEachIndexed { i, h ->
                        if (i > 0) GcRowDivider()
                        GcListRow(title = h.label, onClick = {
                            onPick(h.lat, h.lon, h.label)
                            onClearHits()
                        })
                    }
                }
            }
        }
    }
}
