package com.gatecontrol.android.ui.logbook

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.logbook.LogbookApi
import com.gatecontrol.android.logbook.LogbookExportFile
import com.gatecontrol.android.logbook.LogbookListFilter
import com.gatecontrol.android.logbook.LogbookLogic
import com.gatecontrol.android.logbook.LogbookMonth
import com.gatecontrol.android.logbook.LogbookPurpose
import com.gatecontrol.android.logbook.LogbookTrip
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.familie.FamilieLoading
import com.gatecontrol.android.ui.familie.appLocale
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Logbook ("Fahrtenbuch", vehicles plugin 1.6+; opened by the logbook
 * quick action, no tab): one month of a vehicle — sums by purpose, a map
 * preview of all routes, the trips by day (newest first) with quick
 * purpose buttons for open ones. Top bar: vehicle chooser (more than one),
 * all-trips map, export (PDF/CSV of the month). [selectedTripId] marks the
 * trip shown beside the list on wide windows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogbookScreen(
    vehicleId: Long?,
    onBack: () -> Unit,
    onOpenMap: (vehicleId: Long?, month: String?) -> Unit,
    onOpenTrip: (trip: LogbookTrip, vehicleId: Long?, month: String?) -> Unit,
    modifier: Modifier = Modifier,
    selectedTripId: Long? = null,
    viewModel: LogbookViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var exportOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.start(vehicleId) }
    LogbookNotices(viewModel.notice, snackbar)

    val data = ui.data
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            LogbookTopBar(
                data = data,
                exporting = ui.exporting,
                onBack = onBack,
                onVehicle = viewModel::setVehicle,
                onMap = { onOpenMap(data?.vehicleId ?: ui.load.vehicleId, data?.month ?: ui.load.month) },
                onExport = { exportOpen = true },
            )
            PullToRefreshBox(isRefreshing = ui.load.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "month") {
                        val month = data?.month ?: ui.load.month
                        MonthSwitcher(
                            label = monthLabel(month),
                            onPrevious = month?.let { m -> LogbookLogic.previous(m)?.let { { viewModel.previousMonth() } } },
                            onNext = data?.let { d -> LogbookLogic.next(d.month, d.currentMonth)?.let { { viewModel.nextMonth() } } },
                        )
                    }
                    when {
                        data == null && ui.load.error == null -> item(key = "loading") { FamilieLoading() }
                        data == null -> item(key = "error") { LogbookErrorBanner(ui.load.error, onRetry = viewModel::retry) }
                        else -> monthContent(
                            data = data,
                            ui = ui,
                            selectedTripId = selectedTripId,
                            onOpenMap = { onOpenMap(data.vehicleId, data.month) },
                            onOpenTrip = { onOpenTrip(it, data.vehicleId, data.month) },
                            onPurpose = viewModel::setPurpose,
                            onClearFilter = viewModel::clearFilter,
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    if (exportOpen && data != null) {
        ExportDialog(
            month = data.month,
            onExport = { format -> exportOpen = false; viewModel.export(format) },
            onDismiss = { exportOpen = false },
        )
    }
}

/** The month's blocks: notes, sums, map preview, the trips by day. */
private fun androidx.compose.foundation.lazy.LazyListScope.monthContent(
    data: LogbookMonth,
    ui: LogbookViewModel.Ui,
    selectedTripId: Long?,
    onOpenMap: () -> Unit,
    onOpenTrip: (LogbookTrip) -> Unit,
    onPurpose: (LogbookTrip, String?) -> Unit,
    onClearFilter: () -> Unit,
) {
    if (ui.load.error != null) {
        item(key = "stale") {
            GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                Text(
                    if (ui.load.error == LogbookApi.CODE_OFFLINE) stringResource(R.string.logbook_offline_shown) else logbookErrorText(ui.load.error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    if (data.vehicleId == null) {
        item(key = "no-vehicle") {
            GcCard { Text(stringResource(R.string.logbook_no_vehicle), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted) }
        }
        return
    }
    if (data.moving) {
        item(key = "moving") { Hint(stringResource(R.string.logbook_moving)) }
    }
    item(key = "summary") { SummaryCard(data) }
    if (data.trips.any { LogbookLogic.line(it) != null }) {
        item(key = "map") { MapPreview(data, onOpenMap) }
    }
    if (!data.canEdit) {
        item(key = "read-only") { Hint(stringResource(R.string.logbook_read_only)) }
    }
    val filter = ui.filter
    if (filter != null) {
        item(key = "filter") { FilterChip(filter, onClearFilter) }
    }
    val trips = if (filter != null) data.trips.filter { it.id in filter.tripIds } else data.trips
    if (trips.isEmpty()) {
        item(key = "empty") { EmptyMonth() }
        return
    }
    LogbookLogic.byDay(trips).forEach { (date, dayTrips) ->
        item(key = "d:$date") {
            GcSectionLabel(dayLabel(date, appLocale()), Modifier.padding(start = 4.dp, top = 4.dp))
        }
        item(key = "c:$date") {
            DayCard(
                trips = dayTrips,
                canEdit = data.canEdit,
                saving = ui.saving,
                selectedTripId = selectedTripId,
                onOpen = onOpenTrip,
                onPurpose = onPurpose,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(horizontal = 4.dp))
}

/** Back, "Fahrtenbuch" with the vehicle (a chooser with more than one), map, export. */
@Composable
private fun LogbookTopBar(
    data: LogbookMonth?,
    exporting: Boolean,
    onBack: () -> Unit,
    onVehicle: (Long) -> Unit,
    onMap: () -> Unit,
    onExport: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    var chooser by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 8.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GcIconButton(GcIcons.Back, contentDescription = stringResource(R.string.common_back), onClick = onBack, tint = scheme.onBackground)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.logbook_title), style = MaterialTheme.typography.headlineSmall, color = scheme.onBackground, maxLines = 1)
            val vehicle = data?.vehicle
            if (vehicle != null) {
                val many = data.vehicles.size > 1
                val description = stringResource(R.string.logbook_choose_vehicle, vehicle.name)
                Box {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (many) {
                                    Modifier.clickable(role = Role.Button, onClick = { chooser = true }).semantics { contentDescription = description }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(vehicle.name, style = MaterialTheme.typography.bodyMedium, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (many) Icon(GcIcons.ChevronDown, contentDescription = null, tint = extra.muted, modifier = Modifier.size(14.dp))
                    }
                    DropdownMenu(expanded = chooser, onDismissRequest = { chooser = false }) {
                        data.vehicles.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(listOfNotNull(v.name, v.model?.takeIf { it != v.name }).joinToString(" · ")) },
                                onClick = { chooser = false; onVehicle(v.id) },
                                trailingIcon = if (v.id == data.vehicleId) ({ Icon(GcIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
                            )
                        }
                    }
                }
            }
        }
        if (data?.vehicleId != null) {
            GcIconButton(GcIcons.Map, contentDescription = stringResource(R.string.logbook_map_all), onClick = onMap, tint = scheme.onBackground)
            if (exporting) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp), color = extra.accentText)
                }
            } else {
                GcIconButton(GcIcons.Download, contentDescription = stringResource(R.string.logbook_export), onClick = onExport, tint = scheme.onBackground)
            }
        }
    }
}

/** Total km and trips, the bar by purpose, the three purpose sums. */
@Composable
private fun SummaryCard(data: LogbookMonth) {
    val extra = GateControlTheme.extraColors
    val s = data.summary
    val count = if (s.count > 0) s.count else data.trips.size
    val barDescription = stringResource(R.string.logbook_bar_desc, km(s.commute), km(s.business), km(s.private), km(s.open))
    GcCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(km(s.total), style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp), color = MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(R.string.logbook_km_trips, pluralStringResource(R.plurals.logbook_trips, count, count)),
                style = MaterialTheme.typography.bodyLarge,
                color = extra.muted,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        val shares = LogbookLogic.shares(s)
        Row(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(extra.panel2).semantics { contentDescription = barDescription },
        ) {
            shares.forEach { (purpose, share) ->
                Box(Modifier.weight(share.coerceAtLeast(0.005f)).fillMaxSize().background(purposeColors(purpose).bar))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogbookPurpose.ALL.forEach { purpose ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(purposeIcon(purpose), contentDescription = null, tint = purposeColors(purpose).fg, modifier = Modifier.size(14.dp))
                        Text(purposeLabel(purpose), style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(stringResource(R.string.logbook_km, km(s.km(purpose))), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                }
            }
        }
    }
}

/** All routes of the month, small and still; a tap opens the map. */
@Composable
private fun MapPreview(data: LogbookMonth, onOpen: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val groups = remember(data.trips) { LogbookLogic.groups(data.trips) }
    val lines = remember(groups) { groups.map { MapLine(it.key, it.line.points, mapColor(it.purpose), it.line.dashed) } }
    val dots = remember(groups) { LogbookLogic.places(groups).map { p -> MapDot("p${p.id}", p.point, mapColor(p.purpose)) } }
    val description = stringResource(R.string.logbook_map_open, monthLabel(data.month))
    Box(
        Modifier
            .fillMaxWidth()
            .height(176.dp)
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, extra.border, MaterialTheme.shapes.medium),
    ) {
        LogbookMapView(lines = lines, dots = dots, contentDescription = description, interactive = false, modifier = Modifier.fillMaxSize())
        // Over the whole map: the map itself gets no touch, the tap opens the big one.
        Box(Modifier.fillMaxSize().clickable(role = Role.Button, onClick = onOpen).semantics { contentDescription = description })
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, extra.border, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(GcIcons.Expand, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.logbook_map_button), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** "Lindenweg 4 ↔ Industriestraße 8 · 18 Fahrten" from the map, with a way back to all. */
@Composable
private fun FilterChip(filter: LogbookListFilter, onClear: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val count = filter.tripIds.size
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(extra.accentBg)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            listOfNotNull(filter.label, pluralStringResource(R.plurals.logbook_trips, count, count)).joinToString(" · "),
            style = MaterialTheme.typography.titleSmall,
            color = extra.accentText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.logbook_filter_clear), onClick = onClear, tint = extra.accentText, size = 44.dp, iconSize = 18.dp)
    }
}

@Composable
private fun EmptyMonth() {
    val extra = GateControlTheme.extraColors
    GcCard(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(GcIcons.Logbook, contentDescription = null, tint = extra.faint, modifier = Modifier.size(32.dp).align(Alignment.CenterHorizontally))
        Text(
            stringResource(R.string.logbook_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.logbook_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The trips of one day in one card; an open trip gets the purpose buttons under its row. */
@Composable
private fun DayCard(
    trips: List<LogbookTrip>,
    canEdit: Boolean,
    saving: Set<Long>,
    selectedTripId: Long?,
    onOpen: (LogbookTrip) -> Unit,
    onPurpose: (LogbookTrip, String?) -> Unit,
) {
    val extra = GateControlTheme.extraColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extra.border),
    ) {
        Column {
            trips.forEachIndexed { index, trip ->
                if (index > 0) GcRowDivider()
                TripRow(trip, selected = trip.id == selectedTripId, onClick = { onOpen(trip) })
                if (trip.purpose == null && canEdit) {
                    QuickPurposes(busy = trip.id in saving, onPurpose = { onPurpose(trip, it) })
                }
            }
        }
    }
}

/** Purpose icon, "Start → Ziel", times and purpose (or note), km. */
@Composable
internal fun TripRow(trip: LogbookTrip, selected: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val unknown = stringResource(R.string.logbook_unknown_place)
    val from = LogbookLogic.shortAddress(trip.startAddress) ?: unknown
    val to = LogbookLogic.shortAddress(trip.endAddress) ?: unknown
    val times = if (trip.startTime != null && trip.endTime != null) stringResource(R.string.logbook_times, trip.startTime, trip.endTime) else trip.startTime
    val what = when {
        trip.purpose == null -> stringResource(R.string.logbook_purpose_missing)
        trip.note != null -> trip.note
        else -> purposeLabel(trip.purpose)
    }
    val distance = LogbookLogic.distanceKm(trip)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(extra.accentBg) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PurposeSquare(trip.purpose, description = if (trip.purpose == null) stringResource(R.string.logbook_purpose_missing) else purposeLabel(trip.purpose))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.logbook_route_to, from, to),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(times, what).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (distance != null) {
            Text(stringResource(R.string.logbook_km, km(distance)), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Privat / Geschäftlich / Arbeitsweg for a trip without a purpose. */
@Composable
private fun QuickPurposes(busy: Boolean, onPurpose: (String) -> Unit) {
    val extra = GateControlTheme.extraColors
    val label = stringResource(R.string.logbook_purpose_choose)
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PURPOSE_QUICK.forEach { purpose ->
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(shape)
                    .border(1.dp, extra.border2, shape)
                    .clickable(enabled = !busy, role = Role.Button, onClick = { onPurpose(purpose) })
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    purposeLabel(purpose),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (busy) extra.faint else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "Fahrtenbuch exportieren": PDF or CSV of the shown month. */
@Composable
private fun ExportDialog(month: String, onExport: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.logbook_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.logbook_export_text, monthLabel(month)), style = MaterialTheme.typography.bodyMedium)
                GcOutlineButton(text = stringResource(R.string.logbook_export_pdf), onClick = { onExport(LogbookApi.FORMAT_PDF) }, icon = GcIcons.File)
                GcOutlineButton(text = stringResource(R.string.logbook_export_csv), onClick = { onExport(LogbookApi.FORMAT_CSV) }, icon = GcIcons.Logs)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.logbook_cancel)) } },
    )
}

/** Snackbars of the logbook screens; an export goes to the share sheet. */
@Composable
internal fun LogbookNotices(notices: Flow<LogbookNotice>, host: SnackbarHostState) {
    val context = LocalContext.current
    var current by remember { mutableStateOf<LogbookNotice?>(null) }
    LaunchedEffect(notices) { notices.collect { current = it } }
    val text = when (val n = current) {
        is LogbookNotice.SaveFailed -> if (n.code == LogbookApi.CODE_OFFLINE) logbookErrorText(n.code) else stringResource(R.string.logbook_save_failed)
        is LogbookNotice.ExportFailed -> if (n.code == LogbookApi.CODE_OFFLINE) logbookErrorText(n.code) else stringResource(R.string.logbook_export_failed)
        LogbookNotice.Saved -> stringResource(R.string.logbook_saved)
        is LogbookNotice.Export, null -> null
    }
    val failed = stringResource(R.string.logbook_export_failed)
    LaunchedEffect(current) {
        val n = current ?: return@LaunchedEffect
        if (n is LogbookNotice.Export) {
            if (!shareExport(context, n.file)) {
                host.currentSnackbarData?.dismiss()
                host.showSnackbar(failed)
            }
        } else if (text != null) {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(text)
        }
        current = null
    }
}

/**
 * Hands an export to the share sheet (save to files, mail, …) through the
 * FileProvider: written to cacheDir/export/ (the only shared cache folder),
 * replacing an older copy of the same name.
 */
private suspend fun shareExport(context: Context, file: LogbookExportFile): Boolean {
    val target = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "export").apply { mkdirs() }
            File(dir, file.name).also { it.writeBytes(file.bytes) }
        }.getOrNull()
    } ?: return false
    return try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", target)
        val send = Intent(Intent.ACTION_SEND)
            .setType(file.type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }
}
