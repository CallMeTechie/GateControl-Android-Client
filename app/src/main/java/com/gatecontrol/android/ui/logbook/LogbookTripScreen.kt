package com.gatecontrol.android.ui.logbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.logbook.LogbookLogic
import com.gatecontrol.android.logbook.LogbookTrip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.familie.FamilieLoading
import com.gatecontrol.android.ui.familie.appLocale
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * One trip of the logbook: its route on the map (A → B; "Strecke
 * berechnet" or "Luftlinie"), then day, start and destination with times
 * and odometer, distance, purpose (offen / privat / geschäftlich /
 * Arbeitsweg), the note (saved when the field is left or on "fertig"),
 * driver and duration, "Start und Ziel ändern". Without `canEdit` all of it
 * is read only.
 */
@Composable
fun LogbookTripScreen(
    tripId: Long,
    vehicleId: Long?,
    month: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogbookTripViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tripId, vehicleId, month) { viewModel.show(tripId, vehicleId, month) }
    LogbookNotices(viewModel.notice, snackbar)

    val trip = ui.trip
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (trip == null) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(start = 12.dp, top = 14.dp)) { RoundButton(GcIcons.ChevronLeft, stringResource(R.string.logbook_back_to_list), onBack) }
                Column(Modifier.padding(16.dp)) {
                    when {
                        ui.load.loading || (ui.load.error == null && ui.load.month == null) -> FamilieLoading()
                        ui.load.error != null -> LogbookErrorBanner(ui.load.error, onRetry = viewModel::retry)
                        else -> Text(stringResource(R.string.logbook_trip_not_found), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TripMap(
                    trip = trip,
                    expanded = expanded,
                    onBack = onBack,
                    onToggle = { expanded = !expanded },
                    modifier = if (expanded) Modifier.weight(1f) else Modifier.height(320.dp),
                )
                if (!expanded) {
                    Details(
                        trip = trip,
                        canEdit = ui.canEdit,
                        onPurpose = viewModel::setPurpose,
                        onNote = viewModel::saveNote,
                        onPlaces = viewModel::savePlaces,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, GateControlTheme.extraColors.border, CircleShape),
    ) {
        GcIconButton(icon, contentDescription = description, onClick = onClick, size = 44.dp, iconSize = 20.dp, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** The route with A and B, back and "Karte groß", and how sure the line is. */
@Composable
private fun TripMap(trip: LogbookTrip, expanded: Boolean, onBack: () -> Unit, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    val line = remember(trip) { LogbookLogic.line(trip) }
    val color = mapColor(trip.purpose)
    val lines = remember(line, color) { listOfNotNull(line?.let { MapLine("trip", it.points, color, it.dashed) }) }
    val dots = remember(trip, line, color) {
        listOfNotNull(
            (trip.start ?: line?.points?.first())?.let { MapDot("a", it, ring = INK, label = "A") },
            (trip.end ?: line?.points?.last())?.let { MapDot("b", it, ring = color, fill = color, label = "B", labelColor = 0xFFFFFFFF.toInt()) },
        )
    }
    val unknown = stringResource(R.string.logbook_unknown_place)
    Box(modifier.fillMaxWidth()) {
        if (lines.isEmpty() && dots.isEmpty()) {
            Box(Modifier.fillMaxSize().background(extra.panel2), contentAlignment = Alignment.Center) {
                Icon(GcIcons.Map, contentDescription = null, tint = extra.faint, modifier = Modifier.size(40.dp))
            }
        } else {
            LogbookMapView(
                lines = lines,
                dots = dots,
                contentDescription = stringResource(
                    R.string.logbook_trip_map_desc,
                    LogbookLogic.shortAddress(trip.startAddress) ?: unknown,
                    LogbookLogic.shortAddress(trip.endAddress) ?: unknown,
                ),
                modifier = Modifier.fillMaxSize(),
                fitKey = trip.id to expanded,
                padTopDp = 40,
                padBottomDp = if (expanded) 0 else 24,
                // The details overlap the map's lower edge by 18 dp.
                attributionBottom = if (expanded) 0.dp else 18.dp,
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp)) {
            RoundButton(GcIcons.ChevronLeft, stringResource(R.string.logbook_back_to_list), onBack)
            Box(Modifier.weight(1f))
            if (lines.isNotEmpty()) {
                RoundButton(
                    if (expanded) GcIcons.Shrink else GcIcons.Expand,
                    stringResource(if (expanded) R.string.logbook_map_small else R.string.logbook_map_large),
                    onToggle,
                )
            }
        }
        if (line != null) {
            Text(
                stringResource(if (line.dashed) R.string.logbook_route_line else R.string.logbook_route_road),
                style = MaterialTheme.typography.labelMedium,
                color = extra.muted,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = if (expanded) 12.dp else 30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, extra.border, RoundedCornerShape(15.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Details(
    trip: LogbookTrip,
    canEdit: Boolean,
    onPurpose: (String?) -> Unit,
    onNote: (String) -> Unit,
    onPlaces: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val locale = appLocale()
    var editPlaces by rememberSaveable { mutableStateOf(false) }
    val distance = LogbookLogic.distanceKm(trip)
    Column(
        modifier
            .fillMaxWidth()
            .offset(y = (-18).dp)
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(scheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(dayLabel(trip.date, locale), style = MaterialTheme.typography.headlineSmall, color = scheme.onBackground, modifier = Modifier.weight(1f))
            if (distance != null) {
                Text(stringResource(R.string.logbook_km, km(distance)), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onBackground)
            }
        }
        Ends(trip)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GcSectionLabel(stringResource(R.string.logbook_purpose))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PURPOSE_CHOICES.forEach { p ->
                    PurposeChoice(p, selected = trip.purpose == p, enabled = canEdit, onClick = { if (trip.purpose != p) onPurpose(p) }, modifier = Modifier.weight(1f))
                }
            }
        }
        NoteField(trip, canEdit, onNote)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoBox(stringResource(R.string.logbook_driver), trip.driver?.name ?: "–", Modifier.weight(1f))
            InfoBox(stringResource(R.string.logbook_duration), LogbookLogic.durationMinutes(trip)?.let { durationText(it) } ?: "–", Modifier.weight(1f))
        }
        if (canEdit) {
            GcOutlineButton(text = stringResource(R.string.logbook_edit_places), onClick = { editPlaces = true }, icon = GcIcons.Edit, minHeight = 44.dp)
        }
        if (trip.edited) {
            Text(stringResource(R.string.logbook_edited), style = MaterialTheme.typography.bodySmall, color = extra.faint)
        }
    }
    if (editPlaces) {
        PlacesDialog(trip, onSave = { a, b -> editPlaces = false; onPlaces(a, b) }, onDismiss = { editPlaces = false })
    }
}

/** A and B with address, "Abfahrt 09:05 · 23.418 km" and the time. */
@Composable
private fun Ends(trip: LogbookTrip) {
    val extra = GateControlTheme.extraColors
    val locale = appLocale()
    val unknown = stringResource(R.string.logbook_unknown_place)
    val color = purposeColors(trip.purpose).fg
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, extra.border, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EndRow(
            letter = "A",
            letterColor = MaterialTheme.colorScheme.onSurface,
            fill = null,
            address = trip.startAddress ?: unknown,
            sub = listOfNotNull(trip.startTime?.let { stringResource(R.string.logbook_depart, it) }, trip.startKm?.let { stringResource(R.string.logbook_km, odometer(it, locale)) }).joinToString(" · "),
            time = trip.startTime,
        )
        EndRow(
            letter = "B",
            letterColor = MaterialTheme.colorScheme.surface,
            fill = color,
            address = trip.endAddress ?: unknown,
            sub = listOfNotNull(trip.endTime?.let { stringResource(R.string.logbook_arrive, it) }, trip.endKm?.let { stringResource(R.string.logbook_km, odometer(it, locale)) }).joinToString(" · "),
            time = trip.endTime,
        )
    }
}

@Composable
private fun EndRow(letter: String, letterColor: Color, fill: Color?, address: String, sub: String, time: String?) {
    val extra = GateControlTheme.extraColors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .then(if (fill != null) Modifier.background(fill) else Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = letterColor)
        }
        Column(Modifier.weight(1f)) {
            Text(address, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        if (time != null) Text(time, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** One of the four purposes: icon over its name, the chosen one framed in its colour. */
@Composable
private fun PurposeChoice(purpose: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    val c = purposeColors(purpose)
    val shape = RoundedCornerShape(12.dp)
    val fg = if (selected) c.fg else MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (selected) c.bg else MaterialTheme.colorScheme.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.fg else extra.border, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .then(if (!enabled && !selected) Modifier.alpha(0.55f) else Modifier)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(purposeIcon(purpose), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(
            purposeLabel(purpose),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** "Anlass / Geschäftspartner": saved when the field is left or on "fertig". */
@Composable
private fun NoteField(trip: LogbookTrip, canEdit: Boolean, onSave: (String) -> Unit) {
    val focus = LocalFocusManager.current
    var text by rememberSaveable(trip.id, trip.note) { mutableStateOf(trip.note ?: "") }
    var focused by remember { mutableStateOf(false) }
    GcTextField(
        value = text,
        onValueChange = { text = it.take(200) },
        label = stringResource(R.string.logbook_note),
        placeholder = stringResource(R.string.logbook_note_hint),
        readOnly = !canEdit,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier.onFocusChanged { state ->
            if (focused && !state.hasFocus && canEdit) onSave(text)
            focused = state.hasFocus
        },
    )
}

@Composable
private fun InfoBox(label: String, value: String, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, extra.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = extra.muted)
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Start und Ziel ändern": the two addresses as text. */
@Composable
private fun PlacesDialog(trip: LogbookTrip, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var start by rememberSaveable { mutableStateOf(trip.startAddress ?: "") }
    var end by rememberSaveable { mutableStateOf(trip.endAddress ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.logbook_edit_places)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GcTextField(
                    value = start,
                    onValueChange = { start = it.take(160) },
                    label = stringResource(R.string.logbook_start),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                GcTextField(
                    value = end,
                    onValueChange = { end = it.take(160) },
                    label = stringResource(R.string.logbook_destination),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            GcPrimaryButton(
                text = stringResource(R.string.logbook_save),
                onClick = { onSave(start, end) },
                enabled = start.isNotBlank() && end.isNotBlank(),
                fillWidth = false,
                minHeight = 44.dp,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.logbook_cancel)) } },
    )
}
