package com.gatecontrol.android.ui.familie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.rides.DriverChoice
import com.gatecontrol.android.familie.rides.Ride
import com.gatecontrol.android.familie.rides.RideDraft
import com.gatecontrol.android.familie.rides.RideDriver
import com.gatecontrol.android.familie.rides.RideRepeat
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.LocalDate

private const val DRIVER_NONE = "none"
private const val DRIVER_MEMBER = "member"
private const val DRIVER_LABEL = "label"

private fun Ride.driverChoice(): DriverChoice = when (val d = driver) {
    is RideDriver.Member -> DriverChoice.Member(d.userId)
    is RideDriver.Label -> DriverChoice.Label(d.label)
    null -> DriverChoice.None
}

/**
 * Create ([ride] null) or edit a ride (parents): what, for whom, when,
 * where (text and / or the place picker), who drives (member or a label
 * such as "Familie Weber"), the driver's reminder, weekly repetition and
 * "ask right away" for a new one. A series occurrence is changed alone or
 * with the whole series. [onSave] gets the draft, the series flag and the
 * driver when it changed (edit).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RideEditDialog(
    profile: FamilieProfile,
    ride: Ride?,
    askDefault: Boolean,
    viewModel: RidesViewModel,
    onDismiss: () -> Unit,
    onSave: (RideDraft, Boolean, DriverChoice?) -> Unit,
    onDelete: ((Boolean) -> Unit)?,
    onReopen: (() -> Unit)? = null,
) {
    val extra = GateControlTheme.extraColors
    val onlyChild = profile.everyone.filter { it.isChild }.singleOrNull()?.userId
    val initialDriver = ride?.driverChoice() ?: DriverChoice.None

    var title by rememberSaveable { mutableStateOf(ride?.title.orEmpty()) }
    var childIds by rememberSaveable { mutableStateOf(ride?.childIds ?: listOfNotNull(onlyChild)) }
    var date by rememberSaveable { mutableStateOf(ride?.date ?: LocalDate.now().toString()) }
    var time by rememberSaveable { mutableStateOf(ride?.time) }
    var place by rememberSaveable { mutableStateOf(ride?.place.orEmpty()) }
    var lat by rememberSaveable { mutableStateOf(ride?.lat) }
    var lon by rememberSaveable { mutableStateOf(ride?.lon) }
    var driverKind by rememberSaveable {
        mutableStateOf(
            when (initialDriver) {
                is DriverChoice.Member -> DRIVER_MEMBER
                is DriverChoice.Label -> DRIVER_LABEL
                DriverChoice.None -> DRIVER_NONE
            },
        )
    }
    var driverUser by rememberSaveable { mutableStateOf((initialDriver as? DriverChoice.Member)?.userId) }
    var driverLabel by rememberSaveable { mutableStateOf((initialDriver as? DriverChoice.Label)?.label.orEmpty()) }
    var remind by rememberSaveable { mutableIntStateOf(ride?.remindMinutes ?: Ride.DEFAULT_REMIND) }
    var weekly by rememberSaveable { mutableStateOf(false) }
    var weekdays by rememberSaveable { mutableStateOf(ride?.rule?.weekdays ?: listOf(LocalDate.now().dayOfWeek.value)) }
    var endDate by rememberSaveable { mutableStateOf(ride?.rule?.endDate) }
    var note by rememberSaveable { mutableStateOf(ride?.note.orEmpty()) }
    var ask by rememberSaveable { mutableStateOf(askDefault) }
    var series by rememberSaveable { mutableStateOf(false) }

    val driver: DriverChoice = when (driverKind) {
        DRIVER_MEMBER -> driverUser?.let { DriverChoice.Member(it) } ?: DriverChoice.None
        DRIVER_LABEL -> driverLabel.trim().takeIf { it.isNotEmpty() }?.let { DriverChoice.Label(it) } ?: DriverChoice.None
        else -> DriverChoice.None
    }
    // The rule of a series is shown (and sent) when the whole series is edited.
    val editRule = ride?.isSeries == true && series && ride.rule != null
    val valid = title.isNotBlank() && time != null && childIds.isNotEmpty() &&
        ((!weekly && !editRule) || weekdays.isNotEmpty()) &&
        (driverKind != DRIVER_LABEL || driverLabel.isNotBlank()) &&
        (driverKind != DRIVER_MEMBER || driverUser != null)

    FamilieFormDialog(
        title = stringResource(if (ride == null) R.string.familie_ride_new else R.string.familie_ride_edit),
        onDismiss = onDismiss,
        saveEnabled = valid,
        onSave = {
            val t = time ?: return@FamilieFormDialog
            val draft = RideDraft(
                date = date,
                time = t,
                title = title,
                place = place,
                lat = lat,
                lon = lon,
                childIds = childIds,
                driver = driver,
                remindMinutes = remind,
                note = note,
                repeat = when {
                    ride == null && weekly -> RideRepeat(weekdays.sorted(), endDate)
                    editRule -> RideRepeat(weekdays.sorted(), endDate)
                    else -> null
                },
                ask = if (ride == null) ask && driver == DriverChoice.None else null,
            )
            onSave(draft, ride?.isSeries == true && series, if (ride != null && driver != initialDriver) driver else null)
        },
        onDelete = if (ride != null && onDelete != null && (!ride.isCancelled || (ride.isSeries && series))) ({ onDelete(ride.isSeries && series) }) else null,
    ) {
        if (ride?.isCancelled == true && onReopen != null && !series) {
            GcOutlineButton(text = stringResource(R.string.familie_ride_reopen), onClick = onReopen, icon = GcIcons.Refresh)
        }
        if (ride?.isSeries == true) {
            GcSegmented(
                options = listOf(false to stringResource(R.string.familie_ride_scope_one), true to stringResource(R.string.familie_ride_scope_series)),
                selected = series,
                onSelect = { series = it },
            )
        }
        GcTextField(
            value = title,
            onValueChange = { title = it.take(80) },
            label = stringResource(R.string.familie_ride_title_label),
            placeholder = stringResource(R.string.familie_ride_title_hint),
        )

        Text(stringResource(R.string.familie_ride_for), style = MaterialTheme.typography.titleSmall, color = extra.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            profile.everyone.sortedBy { !it.isChild }.forEach { m ->
                GcFilterChip(
                    text = m.name,
                    selected = m.userId in childIds,
                    onClick = { childIds = if (m.userId in childIds) childIds - m.userId else childIds + m.userId },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // The date of a series occurrence cannot move (the server refuses it).
            if (ride?.isSeries != true) {
                FamilieDateField(
                    label = stringResource(if (weekly) R.string.familie_task_first_date else R.string.familie_ride_date),
                    value = date,
                    onValueChange = { v -> if (v != null) date = v },
                    modifier = Modifier.weight(1f),
                )
            }
            FamilieTimeField(
                label = stringResource(R.string.familie_ride_time),
                value = time,
                onValueChange = { if (it != null) time = it },
                modifier = Modifier.weight(1f),
                clearable = false,
            )
        }

        RidePlaceFields(
            place = place,
            onPlace = { place = it },
            lat = lat,
            lon = lon,
            onPoint = { la, lo ->
                lat = la
                lon = lo
            },
            viewModel = viewModel,
        )

        Text(stringResource(R.string.familie_ride_driver), style = MaterialTheme.typography.titleSmall, color = extra.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GcFilterChip(text = stringResource(R.string.familie_ride_driver_none), selected = driverKind == DRIVER_NONE, onClick = { driverKind = DRIVER_NONE })
            profile.everyone.filter { !it.isChild || (it.age ?: 0) >= 18 }.forEach { m ->
                GcFilterChip(
                    text = m.name,
                    selected = driverKind == DRIVER_MEMBER && driverUser == m.userId,
                    onClick = {
                        driverKind = DRIVER_MEMBER
                        driverUser = m.userId
                    },
                )
            }
            GcFilterChip(text = stringResource(R.string.familie_ride_driver_other), selected = driverKind == DRIVER_LABEL, onClick = { driverKind = DRIVER_LABEL })
        }
        if (driverKind == DRIVER_LABEL) {
            GcTextField(
                value = driverLabel,
                onValueChange = { driverLabel = it.take(60) },
                label = stringResource(R.string.familie_ride_driver_label),
                placeholder = stringResource(R.string.familie_ride_driver_label_hint),
            )
        }

        Text(stringResource(R.string.familie_ride_remind), style = MaterialTheme.typography.titleSmall, color = extra.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Ride.REMIND_STEPS.forEach { m ->
                GcFilterChip(
                    text = if (m == 0) stringResource(R.string.familie_ride_remind_off) else stringResource(R.string.familie_ride_remind_min, m),
                    selected = remind == m,
                    onClick = { remind = m },
                )
            }
        }

        if (ride == null || editRule) {
            Text(stringResource(R.string.familie_ride_repeat), style = MaterialTheme.typography.titleSmall, color = extra.muted)
            if (ride == null) {
                GcSegmented(
                    options = listOf(false to stringResource(R.string.familie_ride_once), true to stringResource(R.string.familie_ride_weekly)),
                    selected = weekly,
                    onSelect = { weekly = it },
                )
            }
            if (weekly || editRule) {
                val names = weekdayNames()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { d ->
                        GcFilterChip(text = names[d - 1], selected = d in weekdays, onClick = { weekdays = if (d in weekdays) weekdays - d else weekdays + d })
                    }
                }
                FamilieDateField(label = stringResource(R.string.familie_ride_until), value = endDate, onValueChange = { endDate = it }, clearable = true)
            }
        }

        GcTextField(
            value = note,
            onValueChange = { note = it.take(200) },
            label = stringResource(R.string.familie_ride_note),
            singleLine = false,
        )

        if (ride == null && driver == DriverChoice.None) {
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                GcSwitchRow(
                    label = stringResource(R.string.familie_ride_ask_now),
                    description = stringResource(R.string.familie_ride_ask_now_desc),
                    checked = ask,
                    onCheckedChange = { ask = it },
                    icon = GcIcons.Bell,
                )
            }
        }
    }
}

/**
 * "Bitte abholen" (children): a ride for myself; the parents are asked
 * right away. No driver, no repetition.
 */
@Composable
fun RideRequestDialog(
    profile: FamilieProfile,
    viewModel: RidesViewModel,
    onDismiss: () -> Unit,
    onSave: (RideDraft) -> Unit,
) {
    val defaultTitle = stringResource(R.string.familie_ride_request_title_default, profile.me.name)
    var title by rememberSaveable { mutableStateOf(defaultTitle) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var time by rememberSaveable { mutableStateOf<String?>(null) }
    var place by rememberSaveable { mutableStateOf("") }
    var lat by rememberSaveable { mutableStateOf<Double?>(null) }
    var lon by rememberSaveable { mutableStateOf<Double?>(null) }
    var note by rememberSaveable { mutableStateOf("") }

    FamilieFormDialog(
        title = stringResource(R.string.familie_ride_request),
        onDismiss = onDismiss,
        saveEnabled = title.isNotBlank() && time != null,
        onSave = {
            val t = time ?: return@FamilieFormDialog
            onSave(
                RideDraft(
                    date = date,
                    time = t,
                    title = title,
                    place = place,
                    lat = lat,
                    lon = lon,
                    childIds = listOf(profile.me.userId),
                    note = note,
                    ask = true,
                ),
            )
        },
    ) {
        Text(stringResource(R.string.familie_ride_request_hint), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
        GcTextField(value = title, onValueChange = { title = it.take(80) }, label = stringResource(R.string.familie_ride_title_label))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FamilieDateField(label = stringResource(R.string.familie_ride_date), value = date, onValueChange = { v -> if (v != null) date = v }, modifier = Modifier.weight(1f))
            FamilieTimeField(label = stringResource(R.string.familie_ride_time), value = time, onValueChange = { if (it != null) time = it }, modifier = Modifier.weight(1f), clearable = false)
        }
        RidePlaceFields(
            place = place,
            onPlace = { place = it },
            lat = lat,
            lon = lon,
            onPoint = { la, lo ->
                lat = la
                lon = lo
            },
            viewModel = viewModel,
        )
        GcTextField(value = note, onValueChange = { note = it.take(200) }, label = stringResource(R.string.familie_ride_note), singleLine = false)
    }
}

/** Place as text, and optionally a point from the 1.0 place picker (map, position, address search). */
@Composable
private fun RidePlaceFields(
    place: String,
    onPlace: (String) -> Unit,
    lat: Double?,
    lon: Double?,
    onPoint: (Double?, Double?) -> Unit,
    viewModel: RidesViewModel,
) {
    val hits by viewModel.searchHits.collectAsStateWithLifecycle()
    val here by viewModel.here.collectAsStateWithLifecycle()
    val locale = appLocale()
    var showMap by rememberSaveable { mutableStateOf(lat != null) }

    LaunchedEffect(here) {
        here?.let {
            onPoint(it.lat, it.lon)
            viewModel.consumeHere()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GcTextField(
            value = place,
            onValueChange = { onPlace(it.take(80)) },
            label = stringResource(R.string.familie_ride_place),
            placeholder = stringResource(R.string.familie_ride_place_hint),
        )
        if (!showMap) {
            GcOutlineButton(text = stringResource(R.string.familie_ride_place_map), onClick = { showMap = true }, icon = GcIcons.Pin)
        } else {
            FamiliePlacePicker(
                lat = lat,
                lon = lon,
                hits = hits,
                onPick = { la, lo, label ->
                    onPoint(la, lo)
                    if (label != null && place.isBlank()) onPlace(label.substringBefore(',').take(80))
                },
                onHere = viewModel::locateHere,
                onSearch = { viewModel.search(it, locale) },
                onClearHits = viewModel::clearHits,
                mapHeight = 200.dp,
            )
            if (lat != null) {
                GcOutlineButton(
                    text = stringResource(R.string.familie_ride_place_map_clear),
                    onClick = {
                        onPoint(null, null)
                        showMap = false
                    },
                    icon = GcIcons.Close,
                )
            }
        }
    }
}
