package com.gatecontrol.android.ui.drive

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DBbox
import com.gatecontrol.android.drive.DCluster
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DrivePlacesLogic
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A box around one point (a place picked by name): about a town. */
private fun boxAround(lat: Double, lon: Double): DBbox = DrivePlacesLogic.clamp(DBbox(lon - 0.12, lat - 0.08, lon + 0.12, lat + 0.08))

/** "214 Fotos · Juli 2025 – März 2026" of a place (its count and time). */
@Composable
private fun placeLine(count: Int, from: String?, to: String?): String {
    val span = DrivePlacesLogic.span(from, to, LocalConfiguration.current.locales[0] ?: Locale.ROOT)
    val photos = pluralStringResource(R.plurals.drive_photos_count, count, count)
    return listOfNotNull(photos, span).joinToString(" · ")
}

/**
 * "Karte" (Drive 1.16, `photoPlaces`): the photos as bundles on OpenStreetMap; moving and zooming load the bundles of
 * the view (throttled). A bundle tapped shows its photos below with the time and who is on them; "Alle ansehen" opens
 * the place.
 */
@Composable
fun DrivePhotoMapScreen(onBack: () -> Unit, onPlace: (String?, DBbox?) -> Unit, onPerson: (Long) -> Unit, viewModel: DriveMapViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val x = GateControlTheme.extraColors
    val clusters by viewModel.clusters.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val cls by viewModel.cls.collectAsStateWithLifecycle()
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    var center by remember { mutableStateOf<org.osmdroid.util.GeoPoint?>(null) }
    var fit by remember { mutableStateOf<DBbox?>(null) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { viewModel.start() }
    // the first view: all places
    LaunchedEffect(places.isNotEmpty()) {
        if (fit == null && center == null) DrivePlacesLogic.around(places.map { it.lat to it.lon })?.let { fit = it }
    }
    val mapDesc = stringResource(R.string.drive_pp_map_desc)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PhotoMap(
            clusters = clusters,
            selected = selected?.cluster,
            fit = fit ?: DBbox(-20.0, 30.0, 40.0, 65.0),
            center = center,
            interactive = true,
            onCluster = viewModel::select,
            onMoved = viewModel::moved,
            contentDescription = mapDesc,
            modifier = Modifier.fillMaxSize(),
        )
        Column(Modifier.statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.shadow(6.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)) {
                    GcIconButton(GcIcons.Back, contentDescription = stringResource(R.string.drive_back), onClick = onBack, size = 44.dp, tint = MaterialTheme.colorScheme.onSurface)
                }
                MapSearchField(q, onChange = { q = it }, modifier = Modifier.weight(1f))
            }
            // "Ort suchen": the places whose names fit, a tap shows one
            val hits = remember(q, places) {
                val f = DrivePlacesLogic.fold(q)
                if (f.isEmpty()) emptyList() else places.filter { p -> DrivePlacesLogic.fold(p.name).let { it.startsWith(f) || it.contains(" $f") } || DrivePlacesLogic.fold(p.region.orEmpty()).startsWith(f) }.take(5)
            }
            if (hits.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        hits.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                                    q = ""
                                    center = null
                                    fit = boxAround(p.lat, p.lon)
                                    viewModel.select(DCluster(p.lat, p.lon, p.count, p.key, p.cover))
                                }.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(GcIcons.Pin, contentDescription = null, tint = x.blue, modifier = Modifier.size(18.dp))
                                Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(countText(p.count), style = MaterialTheme.typography.bodySmall, color = x.muted)
                            }
                        }
                    }
                }
            }
            // Drive 1.13: private and work photos apart
            if (me?.has(DMe.FEATURE_PHOTO_CLASSES) == true) {
                val label = when (cls) {
                    "private" -> stringResource(R.string.drive_work_private)
                    "work" -> stringResource(R.string.drive_work_work)
                    else -> stringResource(R.string.drive_pp_cls_all)
                }
                Box(Modifier.shadow(6.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface)) {
                    GcFilterChip(label, selected = cls != null, onClick = { viewModel.setClass(when (cls) { null -> "private"; "private" -> "work"; else -> null }) })
                }
            }
        }
        val here = remember { lastLocation(context) }
        if (here != null) {
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).shadow(6.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                GcIconButton(GcIcons.Crosshair, contentDescription = stringResource(R.string.drive_pp_my_location), onClick = { fit = null; center = lastLocation(context) ?: here }, size = 44.dp, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        // the sheet: the chosen bundle, else a hint
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.background,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(x.border2))
                val s = selected
                if (s == null) {
                    Text(
                        stringResource(if (places.isEmpty()) R.string.drive_pp_map_empty else R.string.drive_pp_map_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = x.muted,
                    )
                } else {
                    val summary = places.firstOrNull { it.key != null && it.key == s.cluster.key }
                    val name = summary?.name ?: s.items.mapNotNull { it.geo?.place?.name }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                        ?: stringResource(R.string.drive_pp_map_area)
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(placeLine(summary?.count ?: s.cluster.count, summary?.from, summary?.to), style = MaterialTheme.typography.bodySmall, color = x.muted)
                        }
                        Box(
                            Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary)
                                .clickable(role = Role.Button) { onPlace(s.cluster.key, if (s.cluster.key == null) viewModel.filterOf(s.cluster).bbox else null) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(stringResource(R.string.drive_pp_see_all), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                    }
                    if (s.loading) {
                        Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    } else {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.items.take(8).forEachIndexed { i, n -> Thumb(n, 64) { viewing = i } }
                        }
                        val who = DrivePlacesLogic.peopleCounts(s.items).mapNotNull { (id, _) -> people[id]?.let { id to it } }
                        if (who.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.drive_pp_on_photos), style = MaterialTheme.typography.bodySmall, color = x.muted)
                                who.forEach { (id, n) -> PersonChip(id, n, n, selected = false, onClick = { onPerson(id) }) }
                            }
                        }
                    }
                }
            }
        }
    }
    viewing?.let { start ->
        PhotoViewer(items = selected?.items.orEmpty(), start = start, fetch = viewModel::fetch, onMore = null, onClose = { viewing = null })
    }
}

/** "Ort suchen" on top of the map. */
@Composable
private fun MapSearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    val label = stringResource(R.string.drive_pp_map_search)
    Row(
        modifier.heightIn(min = 44.dp).shadow(6.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(GcIcons.Search, contentDescription = null, tint = x.muted, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(label, color = x.muted, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = value,
                onValueChange = { onChange(it.take(60)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
        if (value.isNotEmpty()) {
            GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.drive_pp_clear), onClick = { onChange("") }, size = 32.dp, iconSize = 16.dp)
        }
    }
}

/** A small square preview of a photo; a tap opens it. */
@Composable
internal fun Thumb(node: DNode, size: Int, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    val picture = rememberDrivePreview(node)
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(x.panel2).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = node.name },
    ) {
        if (picture != null) Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

/**
 * "Ort" (Drive 1.16): a small map on top, the place's name with its photos, days and districts, who is on the photos
 * (a tap filters), the photos by day with the district they were taken in.
 */
@Composable
fun DrivePlaceScreen(onBack: () -> Unit, viewModel: DrivePlaceViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val x = GateControlTheme.extraColors
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val districts by viewModel.districts.collectAsStateWithLifecycle()
    val names by viewModel.names.collectAsStateWithLifecycle()
    val onPhotos by viewModel.onPhotos.collectAsStateWithLifecycle()
    val filter by viewModel.people.collectAsStateWithLifecycle()
    val clusters by viewModel.clusters.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, photosText(context, it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    val zone = ZoneId.systemDefault()
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val dayFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
    val undated = stringResource(R.string.drive_photos_undated)
    val sections = remember(photos.items) {
        DrivePlacesLogic.byDay(photos.items, zone).mapIndexed { i, d ->
            PhotoSection(
                key = "${d.date ?: "none"}-$i",
                title = d.date?.format(dayFormat) ?: undated,
                trailing = listOfNotNull(d.district, d.items.size.toString()).joinToString(" · "),
                items = d.items,
            )
        }
    }
    // a town's key gives its summary; a district's key (from the suggestions) the district all its photos share
    val title = summary?.name
        ?: photos.items.mapNotNull { it.geo?.place }.takeIf { it.isNotEmpty() }?.let { pl -> pl.map { it.district }.distinct().singleOrNull() ?: pl.first().name }
        ?: stringResource(R.string.drive_pp_map_area)
    val count = summary?.count ?: photos.items.size
    val days = remember(photos.items) { DrivePlacesLogic.days(photos.items, zone) }
    val line = listOfNotNull(
        pluralStringResource(R.plurals.drive_photos_count, count, count),
        if (days > 0) pluralStringResource(R.plurals.drive_pp_days, days, days) else null,
        districts.sortedByDescending { it.count }.take(3).joinToString(", ") { it.name }.ifEmpty { null },
    ).joinToString(" · ")
    val people = (if (filter.isEmpty()) onPhotos else onPhotos.filter { it.first in filter }.ifEmpty { filter.map { it to 0 } })
        .mapNotNull { (id, n) -> names[id]?.let { Triple(id, it, n) } }

    Column(Modifier.fillMaxSize()) {
        PhotoGrid(
            pages = photos,
            selected = emptySet(),
            onOpen = { viewing = it },
            onToggle = {},
            onMore = viewModel::more,
            onRetry = viewModel::retry,
            empty = stringResource(R.string.drive_photos_empty),
            sections = sections,
            header = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(x.panel2)) {
                            val s = summary
                            PhotoMap(
                                clusters = clusters,
                                selected = null,
                                fit = if (s != null) boxAround(s.lat, s.lon) else DrivePlacesLogic.around(photos.items.mapNotNull { n -> n.geo?.let { it.lat to it.lon } }.take(200)),
                                interactive = false,
                                onCluster = {},
                                onMoved = { _, _ -> },
                                contentDescription = title,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(Modifier.padding(8.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)) {
                                GcIconButton(GcIcons.Back, contentDescription = stringResource(R.string.drive_pp_back_map), onClick = onBack, size = 44.dp, tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                Text(line, style = MaterialTheme.typography.bodySmall, color = x.muted)
                            }
                            if (busy) {
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp)) }
                            } else {
                                GcIconButton(GcIcons.Photo, contentDescription = stringResource(R.string.drive_pp_save_album), onClick = { viewModel.saveAsAlbum(title) }, tint = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                        if (people.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                people.forEach { (id, n, c) ->
                                    PersonChip(id, n, if (c > 0) "$n · ${countText(c)}" else n, selected = id in filter, onClick = { viewModel.togglePerson(id) })
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
            },
        )
    }
    viewing?.let { start ->
        PhotoViewer(items = photos.items, start = start, fetch = viewModel::fetch, onMore = viewModel::more, onClose = { viewing = null })
    }
}
