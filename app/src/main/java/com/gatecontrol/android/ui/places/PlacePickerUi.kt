package com.gatecontrol.android.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.places.AddressHit
import com.gatecontrol.android.places.MyPlaces
import com.gatecontrol.android.places.PlaceDraft
import com.gatecontrol.android.places.PlaceGroup
import com.gatecontrol.android.places.PlaceIcons
import com.gatecontrol.android.places.PlaceOption
import com.gatecontrol.android.places.PlacePicking
import com.gatecontrol.android.places.PlaceSources
import com.gatecontrol.android.places.PlacesSupport
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.familie.LatLon
import com.gatecontrol.android.ui.familie.OsmMap
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.launch

/*
 * The one choice of a place for every form (Entwurf „Meine Orte“, Bilder 5–8): in the form at most three chips plus
 * „Alle Orte…“; the sheet with the search, „Zuletzt genutzt“, „In der Nähe“, A–Z with letter dividers and „Neu“.
 * Familie places stand among the own ones with the blue symbol. A hit of the address search asks „Als Ort speichern?“.
 */

/**
 * The choice of a place in a form. [options]: what the plugin of the form offers (its order and uses count); with
 * [withMine] „Meine Orte“ fill in and new places can be saved from the sheet (the plugin knows `profile:<id>`). Without
 * [oneOff] a point is never taken only for this form (Familie rules need a place). [onPick] gets the chosen place.
 */
@Composable
fun PlaceChooser(
    options: List<PlaceOption>,
    selected: PlaceOption?,
    onPick: (PlaceOption) -> Unit,
    modifier: Modifier = Modifier,
    withMine: Boolean = true,
    oneOff: Boolean = true,
    showSummary: Boolean = true,
    vm: PlacePickerViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val here by vm.here.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val canSave = withMine && state.support == PlacesSupport.SUPPORTED
    val all = if (withMine) PlaceSources.merged(options, state.places) else options
    val pick: (PlaceOption) -> Unit = { o ->
        vm.used(o)
        onPick(o)
    }
    PlacesNotices(vm.notices)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlaceChips(PlacePicking.chips(all, selected, here), selected?.key, onPick = pick, onAll = {
            vm.refreshHere()
            open = true
        })
        if (showSummary) selected?.let { PlaceSummary(it) }
    }
    if (open) {
        PlaceSheet(all, selected?.key, canSave, oneOff, vm, onPick = { o ->
            open = false
            pick(o)
        }, onDismiss = { open = false })
    }
}

/** The chips of the form: a place each (its symbol, blue for Familie) and „Alle Orte…“. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceChips(chips: List<PlaceOption>, selectedKey: String?, onPick: (PlaceOption) -> Unit, onAll: () -> Unit, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    FlowRow(modifier.testTag("place-chips"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { o ->
            val sel = o.key == selectedKey
            GcFilterChip(
                o.name,
                sel,
                { onPick(o) },
                modifier = Modifier.testTag("place-chip-${o.key}"),
                leading = {
                    Icon(
                        placeIconVector(o.icon),
                        contentDescription = null,
                        tint = when {
                            o.familie -> extra.blue
                            sel -> extra.accentText
                            else -> extra.muted
                        },
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        GcFilterChip(
            stringResource(R.string.places_all),
            false,
            onAll,
            modifier = Modifier.testTag("place-chip-all"),
            leading = { Icon(GcIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) },
        )
    }
}

/** Under the chips: „200 m · aus „Meine Orte“ · Lindenweg 12“. */
@Composable
fun PlaceSummary(o: PlaceOption) {
    val extra = GateControlTheme.extraColors
    Text(
        listOfNotNull(
            o.radiusM?.let { stringResource(R.string.places_radius_short, it) },
            stringResource(
                when {
                    o.familie -> R.string.places_from_familie
                    o.ref == null -> R.string.places_point_only
                    o.profileId != null -> R.string.places_from_mine
                    else -> R.string.places_point_only
                },
            ),
            o.address,
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = extra.muted,
        modifier = Modifier.testTag("place-summary"),
    )
}

/** What the sheet can do (also drawn in the tests without a view model). */
class PlaceSheetActions(
    val query: (String) -> Unit,
    val search: () -> Unit,
    val pick: (PlaceOption) -> Unit,
    val hit: (AddressHit) -> Unit,
    val newHere: () -> Unit,
    val newMap: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceSheet(
    options: List<PlaceOption>,
    selectedKey: String?,
    canSave: Boolean,
    oneOff: Boolean,
    vm: PlacePickerViewModel,
    onPick: (PlaceOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val here by vm.here.collectAsStateWithLifecycle()
    val finder by vm.finder.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<AddressHit?>(null) }
    var mapOpen by remember { mutableStateOf(false) }
    val radius = state.list?.limits?.radiusDefault ?: MyPlaces.RADIUS_DEFAULT
    ModalBottomSheet(
        onDismissRequest = {
            vm.finder.clear()
            onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        PlaceSheetContent(
            options = options,
            selectedKey = selectedKey,
            here = here,
            query = query,
            finder = finder,
            canSave = canSave,
            oneOff = oneOff,
            actions = PlaceSheetActions(
                query = { q ->
                    query = q
                    if (q.isBlank()) vm.finder.clear()
                },
                search = { vm.finder.search(query, here) },
                pick = { o ->
                    vm.finder.clear()
                    onPick(o)
                },
                hit = { h -> if (canSave) asking = h else onPick(PlacePicking.option(h, radius)) },
                newHere = {
                    vm.locate { lat, lon ->
                        scope.launch {
                            val address = vm.addressOf(lat, lon)
                            asking = AddressHit(name = "", address = address, lat = lat, lon = lon, osm = false)
                        }
                    }
                },
                newMap = { mapOpen = true },
            ),
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
    asking?.let { h ->
        SavePlaceDialog(
            hit = h,
            oneOff = oneOff,
            busy = busy,
            onSave = { name ->
                vm.create(PlaceDraft(name = name, lat = h.lat, lon = h.lon, address = h.address, radiusM = radius)) { o ->
                    asking = null
                    vm.finder.clear()
                    onPick(o)
                }
            },
            onOnlyHere = {
                asking = null
                vm.finder.clear()
                onPick(PlacePicking.option(h.copy(name = h.name.ifBlank { h.address ?: "" }), radius))
            },
            onDismiss = { asking = null },
        )
    }
    if (mapOpen) {
        MapPickDialog(
            canSave = canSave,
            oneOff = oneOff,
            busy = busy,
            locating = finder.locating,
            onHere = { set -> vm.locate { lat, lon -> set(lat, lon) } },
            onDone = { name, lat, lon, save ->
                if (save) {
                    vm.create(PlaceDraft(name = name, lat = lat, lon = lon, radiusM = radius)) { o ->
                        mapOpen = false
                        onPick(o)
                    }
                } else {
                    mapOpen = false
                    onPick(PlaceOption(ref = null, name = name, lat = lat, lon = lon, radiusM = radius))
                }
            },
            onDismiss = { mapOpen = false },
        )
    }
}

/**
 * The sheet's body: the search field, then — without a search — „Zuletzt genutzt“, „In der Nähe“, A–Z and „Neu“; with
 * one the matching places (own and Familie) and, after „Suchen“, the addresses.
 */
@Composable
fun PlaceSheetContent(
    options: List<PlaceOption>,
    selectedKey: String?,
    here: Pair<Double, Double>?,
    query: String,
    finder: FinderState,
    canSave: Boolean,
    oneOff: Boolean,
    actions: PlaceSheetActions,
    modifier: Modifier = Modifier,
) {
    val extra = GateControlTheme.extraColors
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("place-sheet")) {
        AddressSearchField(query, actions.query, finder, onSearch = actions.search, modifier = Modifier.padding(horizontal = 16.dp), label = R.string.places_pick_search)
        if (query.isBlank()) {
            val groups = PlacePicking.groups(options, here)
            if (groups.isEmpty()) {
                Text(stringResource(R.string.places_pick_none), style = MaterialTheme.typography.bodyMedium, color = extra.muted, modifier = Modifier.padding(16.dp))
            }
            groups.forEach { g ->
                SheetLabel(
                    when (g.kind) {
                        PlaceGroup.Kind.RECENT -> stringResource(R.string.places_group_recent)
                        PlaceGroup.Kind.NEAR -> stringResource(R.string.places_group_near)
                        else -> g.letter.orEmpty()
                    },
                )
                g.items.forEachIndexed { i, o ->
                    if (i > 0) GcRowDivider()
                    OptionRow(o, o.key == selectedKey, here, actions.pick)
                }
            }
            if (canSave || oneOff) {
                SheetLabel(stringResource(R.string.places_group_new))
                if (canSave) {
                    NewRow(GcIcons.Crosshair, stringResource(R.string.places_new_here), stringResource(R.string.places_new_here_sub), "place-new-here", actions.newHere)
                    GcRowDivider()
                }
                NewRow(
                    GcIcons.Pin,
                    stringResource(R.string.places_new_map),
                    stringResource(if (canSave && oneOff) R.string.places_new_map_sub else if (canSave) R.string.places_new_here_sub else R.string.places_point_only),
                    "place-new-map",
                    actions.newMap,
                )
            }
        } else {
            val local = PlacePicking.search(options, query, here)
            SheetLabel(stringResource(R.string.places_group_mine))
            if (local.isEmpty()) {
                Text(stringResource(R.string.places_search_local_none), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            local.forEachIndexed { i, o ->
                if (i > 0) GcRowDivider()
                OptionRow(o, o.key == selectedKey, here, actions.pick)
            }
            if (canSave || oneOff) {
                SheetLabel(stringResource(R.string.places_group_addresses))
                val hits = finder.hits?.let { PlacePicking.newHits(it, options) }
                when {
                    hits == null && !finder.searching -> {
                        Text(
                            stringResource(if (PlacePicking.searchable(query)) R.string.places_search_press else R.string.places_search_min),
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.muted,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        if (PlacePicking.searchable(query)) {
                            TextButton(onClick = actions.search, modifier = Modifier.padding(horizontal = 8.dp).testTag("place-search-addresses")) {
                                Text(stringResource(R.string.places_search_addresses))
                            }
                        }
                    }
                    hits != null -> {
                        finderProblem(finder)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = if (finder.problem == "none") extra.muted else MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                        hits.forEachIndexed { i, h ->
                            if (i > 0) GcRowDivider()
                            HitRow(h, here, finder.viaDevice) { actions.hit(h) }
                        }
                        if (hits.isNotEmpty()) {
                            Text(
                                if (finder.viaDevice) stringResource(R.string.places_via_device) else finder.attribution ?: stringResource(R.string.places_osm),
                                style = MaterialTheme.typography.bodySmall,
                                color = extra.muted,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                if (finder.hits == null) finderProblem(finder)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = GateControlTheme.extraColors.muted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** A place in the sheet: symbol (blue: Familie), name, „Meine Orte · 200 m“ / „Familie · 150 m“, distance or „hier“, ✓. */
@Composable
private fun OptionRow(o: PlaceOption, selected: Boolean, here: Pair<Double, Double>?, onPick: (PlaceOption) -> Unit) {
    val extra = GateControlTheme.extraColors
    val source = stringResource(if (o.familie) R.string.places_src_familie else if (o.ref == null) R.string.places_point_only else R.string.places_title)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .background(if (selected) extra.accentBg else Color.Transparent)
            .clickable(enabled = o.hasPoint, role = Role.Button) { onPick(o) }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("place-option-${o.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlaceIconSquare(o.icon, familie = o.familie, missing = !o.hasPoint)
        Column(Modifier.weight(1f)) {
            Text(o.name, style = MaterialTheme.typography.titleMedium, color = if (o.hasPoint) MaterialTheme.colorScheme.onSurface else extra.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(source, o.radiusM?.let { stringResource(R.string.places_radius_short, it) }, stringResource(R.string.places_no_point).takeIf { !o.hasPoint }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        distanceLabel(PlacePicking.distanceOf(o, here), o.radiusM)?.let {
            Text(it, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = extra.muted)
        }
        if (selected) Icon(GcIcons.Check, contentDescription = stringResource(R.string.places_selected), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun HitRow(h: AddressHit, here: Pair<Double, Double>?, viaDevice: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val dist = h.distanceM ?: here?.let { PlacePicking.distanceM(it.first, it.second, h.lat, h.lon) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp).testTag("place-hit"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(extra.panel2), contentAlignment = Alignment.Center) {
            Icon(GcIcons.Pin, contentDescription = null, tint = extra.muted, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(h.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(h.address?.takeIf { it != h.name }, if (viaDevice) null else stringResource(R.string.places_osm_short)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        dist?.let { distanceLabel(it, null) }?.let { Text(it, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = extra.muted) }
    }
}

@Composable
private fun NewRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, tag: String, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(extra.accentBg), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
    }
}

/** „Als Ort speichern?“ for a hit of the search (or the current position): yes saves it in „Meine Orte“, no uses it only here. */
@Composable
fun SavePlaceDialog(hit: AddressHit, oneOff: Boolean, busy: Boolean, onSave: (String) -> Unit, onOnlyHere: () -> Unit, onDismiss: () -> Unit) {
    var name by remember(hit) { mutableStateOf(hit.name.take(MyPlaces.NAME_MAX)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.places_save_q_title)) },
        text = { SavePlaceBody(hit, oneOff, name) { name = it } },
        confirmButton = { SavePlaceButtons(oneOff, MyPlaces.cleanName(name).isNotEmpty() && !busy, { onSave(MyPlaces.cleanName(name)) }, onOnlyHere, onDismiss) },
    )
}

/** The question's text, the address and the name of the new place. */
@Composable
fun SavePlaceBody(hit: AddressHit, oneOff: Boolean, name: String, onName: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("place-save-q")) {
        Text(stringResource(if (oneOff) R.string.places_save_q_text else R.string.places_save_q_text_only), style = MaterialTheme.typography.bodyMedium)
        hit.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted) }
        GcTextField(name, { onName(it.take(MyPlaces.NAME_MAX)) }, stringResource(R.string.places_name), placeholder = stringResource(R.string.places_name_hint))
    }
}

/** „Nur hier“ (or „Abbrechen“ where a point only for this form is not allowed) and „Speichern“. */
@Composable
fun SavePlaceButtons(oneOff: Boolean, canSave: Boolean, onSave: () -> Unit, onOnlyHere: () -> Unit, onDismiss: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (oneOff) TextButton(onClick = onOnlyHere) { Text(stringResource(R.string.places_only_here)) }
        else TextButton(onClick = onDismiss) { Text(stringResource(R.string.places_cancel)) }
        TextButton(onClick = onSave, enabled = canSave) { Text(stringResource(R.string.places_save)) }
    }
}

/**
 * „Auf der Karte wählen“: a tap sets the point, „◎“ the current position, a name; saved in „Meine Orte“ or (with
 * [oneOff]) only for this form. Full screen, below the status bar and above the keyboard.
 */
@Composable
fun MapPickDialog(
    canSave: Boolean,
    oneOff: Boolean,
    busy: Boolean,
    locating: Boolean,
    onHere: (set: (Double, Double) -> Unit) -> Unit,
    onDone: (name: String, lat: Double, lon: Double, save: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var lat by remember { mutableStateOf<Double?>(null) }
    var lon by remember { mutableStateOf<Double?>(null) }
    var name by remember { mutableStateOf("") }
    var save by remember { mutableStateOf(canSave) }
    val la = lat
    val lo = lon
    val ready = MyPlaces.hasPoint(la, lo) && MyPlaces.cleanName(name).isNotEmpty() && !busy && (save || oneOff)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding().testTag("place-map-pick")) {
            GcSubpageBar(title = stringResource(R.string.places_map_title), onBack = onDismiss, backDescription = stringResource(R.string.places_close)) {
                TextButton(onClick = { if (la != null && lo != null) onDone(MyPlaces.cleanName(name), la, lo, save && canSave) }, enabled = ready) {
                    Text(stringResource(R.string.places_use))
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    OsmMap(
                        point = if (la != null && lo != null) LatLon(la, lo) else null,
                        contentDescription = stringResource(R.string.places_map_pick_cd),
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        zoom = if (la != null) 16.0 else 5.0,
                        radiusM = if (la != null) MyPlaces.RADIUS_DEFAULT.toDouble() else null,
                        onTap = { p ->
                            lat = p.lat
                            lon = p.lon
                        },
                    )
                    HereButton(locating, { onHere { a, b -> lat = a; lon = b } }, Modifier.align(Alignment.BottomEnd).padding(12.dp))
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.places_map_hint), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
                    GcTextField(name, { name = it.take(MyPlaces.NAME_MAX) }, stringResource(R.string.places_name), placeholder = stringResource(R.string.places_name_hint))
                    if (canSave && oneOff) {
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, GateControlTheme.extraColors.border, RoundedCornerShape(12.dp))) {
                            GcSwitchRow(stringResource(R.string.places_map_save), stringResource(R.string.places_new_here_sub), save, { save = it })
                        }
                    }
                }
            }
        }
    }
}

/** A chosen one-off point for a form (its name, point, radius) — what an older plugin keeps instead of a reference. */
fun PlaceOption.isOneOff(): Boolean = ref == null

/** The icon of a Familie place for the chips of older plugins: „Zuhause“ gets the house. */
fun familieIcon(id: String?): String = if (id == "home") PlaceIcons.HOME else PlaceIcons.PIN
