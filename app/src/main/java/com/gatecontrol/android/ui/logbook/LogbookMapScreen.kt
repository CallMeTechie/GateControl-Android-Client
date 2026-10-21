package com.gatecontrol.android.ui.logbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.logbook.LogbookLogic
import com.gatecontrol.android.logbook.LogbookTrip
import com.gatecontrol.android.ui.adaptive.LocalInDetailPane
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * All trips of a month on the map: routes coloured by purpose (dashed where
 * only the straight line is known), the same route drawn once with its
 * count, the places; filter chips by purpose and the month switcher on top.
 * A tap on a route (or a place) shows it in the sheet with "n Fahrten
 * zeigen" (the list, only those trips: [onShowTrips]) and "Letzte Fahrt".
 * [onMonthChange]: the month belongs to the list beside it (wide windows);
 * null = the map switches its own.
 */
@Composable
fun LogbookMapScreen(
    vehicleId: Long?,
    month: String?,
    onBack: () -> Unit,
    onShowTrips: () -> Unit,
    onOpenTrip: (trip: LogbookTrip, vehicleId: Long?, month: String?) -> Unit,
    modifier: Modifier = Modifier,
    onMonthChange: ((String) -> Unit)? = null,
    viewModel: LogbookMapViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(vehicleId, month) { viewModel.show(vehicleId, month) }

    val data = ui.data
    val trips = data?.trips.orEmpty()
    val groups = remember(trips, ui.filter) { LogbookLogic.groups(trips.filter(ui.filter::matches)) }
    val places = remember(groups) { LogbookLogic.places(groups) }
    val selected = groups.firstOrNull { it.key == ui.selected }
    val lines = remember(groups, selected) {
        groups.map { g ->
            MapLine(g.key, g.line.points, mapColor(g.purpose), g.line.dashed, badge = if (g.count > 1) "${g.count}×" else null, emphasized = g.key == selected?.key)
        }
    }
    // A place stands for the most travelled route ending there.
    val placeGroup = remember(groups, places) {
        places.associate { p -> "p${p.id}" to groups.firstOrNull { g -> g.fromPlace == p.id || g.toPlace == p.id }?.key }
    }
    val dots = remember(places) { places.map { p -> MapDot("p${p.id}", p.point, mapColor(p.purpose)) } }
    val switchMonth: (String) -> Unit = { m -> onMonthChange?.invoke(m) ?: viewModel.setMonth(m) }
    val density = LocalDensity.current
    var sheetHeight by remember { mutableIntStateOf(0) }
    val shownMonth = data?.month ?: ui.load.month ?: month

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LogbookMapView(
            lines = lines,
            dots = dots,
            contentDescription = stringResource(R.string.logbook_map_desc, monthLabel(shownMonth)),
            modifier = Modifier.fillMaxSize(),
            fitKey = groups.map { it.key },
            padTopDp = 110,
            padBottomDp = 150,
            attributionBottom = with(density) { sheetHeight.toDp() },
            onLineTap = viewModel::select,
            onDotTap = { key -> placeGroup[key]?.let(viewModel::select) },
            onMapTap = { viewModel.select(null) },
        )

        Column(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!LocalInDetailPane.current) FloatingRoundButton(GcIcons.ChevronLeft, stringResource(R.string.logbook_back_to_list), onBack)
                FloatingPill(Modifier.weight(1f)) {
                    MonthSwitcher(
                        label = listOfNotNull(monthLabel(shownMonth), data?.vehicle?.name).joinToString(" · "),
                        onPrevious = shownMonth?.let(LogbookLogic::previous)?.let { m -> { switchMonth(m) } },
                        onNext = data?.let { LogbookLogic.next(it.month, it.currentMonth) }?.let { m -> { switchMonth(m) } },
                        framed = false,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            FilterChips(total = trips.size, filter = ui.filter, onFilter = viewModel::setFilter)
        }

        Sheet(Modifier.align(Alignment.BottomCenter).onSizeChanged { sheetHeight = it.height }) {
            when {
                data == null && ui.load.error == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                data == null -> LogbookErrorBanner(ui.load.error, onRetry = viewModel::retry)
                selected != null -> RouteDetails(
                    group = selected,
                    onShowTrips = { label ->
                        viewModel.showInList(selected.trips, label)
                        onShowTrips()
                    },
                    onLatest = { onOpenTrip(selected.latest, data.vehicleId, data.month) },
                )
                groups.isEmpty() -> SheetText(stringResource(R.string.logbook_map_empty))
                else -> SheetText(stringResource(R.string.logbook_map_hint))
            }
        }
    }
}

@Composable
private fun FloatingRoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Box(
        Modifier
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, extra.border, CircleShape),
    ) {
        GcIconButton(icon, contentDescription = description, onClick = onClick, size = 44.dp, iconSize = 20.dp, tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun FloatingPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val extra = GateControlTheme.extraColors
    Box(
        modifier
            .height(44.dp)
            .shadow(4.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, extra.border, RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Alle n · Arbeitsweg · Geschäftlich · Privat · Offen. */
@Composable
private fun FilterChips(total: Int, filter: LogbookLogic.MapFilter, onFilter: (LogbookLogic.MapFilter) -> Unit) {
    val label = stringResource(R.string.logbook_filter)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LogbookLogic.MapFilter.entries.forEach { f ->
            val text = when (f) {
                LogbookLogic.MapFilter.ALL -> stringResource(R.string.logbook_filter_all, total)
                LogbookLogic.MapFilter.OPEN -> purposeLabel(null)
                else -> purposeLabel(f.purpose)
            }
            Chip(text, dot = if (f == LogbookLogic.MapFilter.ALL) null else Color(mapColor(f.purpose)), selected = f == filter, onClick = { onFilter(f) })
        }
    }
}

@Composable
private fun Chip(text: String, dot: Color?, selected: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(17.dp)
    Row(
        Modifier
            .height(34.dp)
            .shadow(3.dp, shape)
            .clip(shape)
            .background(if (selected) scheme.onSurface else scheme.surface)
            .then(if (selected) Modifier else Modifier.border(1.dp, extra.border, shape))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) scheme.surface else scheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun Sheet(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val extra = GateControlTheme.extraColors
    Surface(
        modifier = modifier.widthIn(max = 640.dp).fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(extra.border2))
            content()
        }
    }
}

@Composable
private fun SheetText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.faint)
}

/** The chosen route: "A ↔ B", purpose · n Fahrten · km; "n Fahrten zeigen", "Letzte Fahrt". */
@Composable
private fun RouteDetails(group: LogbookLogic.RouteGroup, onShowTrips: (label: String) -> Unit, onLatest: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val unknown = stringResource(R.string.logbook_unknown_place)
    val label = stringResource(R.string.logbook_route_both, group.fromAddress ?: unknown, group.toAddress ?: unknown)
    val trips = pluralStringResource(R.plurals.logbook_trips, group.count, group.count)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PurposeSquare(group.purpose, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(purposeLabel(group.purpose), trips, stringResource(R.string.logbook_km, km(group.km))).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GcOutlineButton(
                text = stringResource(R.string.logbook_show_trips, trips),
                onClick = { onShowTrips(label) },
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
            )
            GcPrimaryButton(
                text = stringResource(R.string.logbook_latest_trip),
                onClick = onLatest,
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
            )
        }
    }
}
