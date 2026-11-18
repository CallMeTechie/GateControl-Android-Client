package com.gatecontrol.android.ui.places

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.places.AddressHit
import com.gatecontrol.android.places.MyPlace
import com.gatecontrol.android.places.MyPlaces
import com.gatecontrol.android.places.PlaceDraft
import com.gatecontrol.android.places.PlaceIcons
import com.gatecontrol.android.places.PlaceOption
import com.gatecontrol.android.places.PlacePicking
import com.gatecontrol.android.places.PlaceUse
import com.gatecontrol.android.places.PlaceVisibility
import com.gatecontrol.android.places.PlacesState
import com.gatecontrol.android.places.PlacesSupport
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.familie.LatLon
import com.gatecontrol.android.ui.familie.OsmMap
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.launch

/*
 * Profil › Meine Orte (Entwurf „Meine Orte“, Bilder 1–4): the list of the own places and, read only, the Familie places;
 * a place with its map and what uses it; the editor with the map first. In the app's design: cards with rows, the
 * square symbols, chips and the segmented control.
 */

// ─── the list (Bild 1, 4) ──────────────────────────────────────────

@Composable
fun MyPlacesScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, onSet: (Long) -> Unit, vm: MyPlacesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val familie by vm.familie.collectAsStateWithLifecycle()
    val here by vm.here.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.load()
        onPauseOrDispose { }
    }
    PlacesNotices(vm.notices)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            GcSubpageBar(title = stringResource(R.string.places_title), onBack = onBack, backDescription = stringResource(R.string.common_back))
            MyPlacesContent(state, familie, here, refreshing, onOpen = { onOpen(it.id) }, onSet = { onSet(it.id) }, modifier = Modifier.weight(1f))
        }
        if (state.available) {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(GcIcons.Plus, contentDescription = null) },
                text = { Text(stringResource(R.string.places_add)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("places-add"),
            )
        }
    }
}

/** The list itself (drawn in the tests without a view model). */
@Composable
fun MyPlacesContent(
    state: PlacesState,
    familie: List<PlaceOption>,
    here: Pair<Double, Double>?,
    refreshing: Boolean,
    onOpen: (MyPlace) -> Unit,
    onSet: (MyPlace) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = GateControlTheme.extraColors
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp).testTag("places-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            state.support == PlacesSupport.UNSUPPORTED || state.support == PlacesSupport.NO_USER -> {
                GcBanner(tone = GcTone.Info) {
                    Text(
                        stringResource(if (state.support == PlacesSupport.NO_USER) R.string.profile_no_user else R.string.places_unsupported),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                return@Column
            }
            state.list == null -> {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    if (refreshing || state.support == PlacesSupport.UNKNOWN && !state.offline) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(stringResource(R.string.places_err_offline), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                    }
                }
                return@Column
            }
        }
        val places = MyPlaces.sorted(state.places)
        val missing = places.count { !it.hasPoint }
        if (state.offline) {
            Text(stringResource(R.string.places_offline), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (missing > 0) {
            GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert, modifier = Modifier.testTag("places-missing")) {
                Text(pluralStringResource(R.plurals.places_missing, missing, missing), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
        GcSectionLabel(stringResource(R.string.places_section_mine, places.size), Modifier.padding(start = 4.dp))
        if (places.isEmpty()) {
            Text(stringResource(R.string.places_empty), style = MaterialTheme.typography.bodyMedium, color = extra.muted, modifier = Modifier.padding(horizontal = 4.dp))
        } else {
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                places.forEachIndexed { i, p ->
                    if (i > 0) GcRowDivider()
                    MyPlaceRow(p, here, onOpen = { onOpen(p) }, onSet = { onSet(p) })
                }
            }
        }
        if (familie.isNotEmpty()) {
            GcSectionLabel(stringResource(R.string.places_section_familie), Modifier.padding(start = 4.dp, top = 4.dp))
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                familie.forEachIndexed { i, f ->
                    if (i > 0) GcRowDivider()
                    GcListRow(
                        title = f.name,
                        description = listOfNotNull(
                            stringResource(R.string.places_familie_kept),
                            f.radiusM?.let { stringResource(R.string.places_radius_short, it) },
                        ).joinToString(" · "),
                        leading = { PlaceIconSquare(f.icon, familie = true, missing = !f.hasPoint) },
                        trailing = { distanceLabel(PlacePicking.distanceOf(f, here), f.radiusM)?.let { DistanceText(it) } },
                    )
                }
            }
        }
        GcBanner(tone = GcTone.Info, icon = GcIcons.Info) {
            Text(stringResource(R.string.places_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DistanceText(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = GateControlTheme.extraColors.muted)
}

/** One of „Meine Orte“: symbol, name with its tag, the line, the distance — or „Setzen“ when it has no point. */
@Composable
private fun MyPlaceRow(p: MyPlace, here: Pair<Double, Double>?, onOpen: () -> Unit, onSet: () -> Unit) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp).testTag("place-${p.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PlaceIconSquare(p.icon, missing = !p.hasPoint)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                when {
                    !p.hasPoint -> PlaceTag(stringResource(R.string.places_no_point), warn = true)
                    p.visibility != PlaceVisibility.SELF -> PlaceTag(stringResource(visibilityLabel(p.visibility, short = true)))
                }
            }
            Text(
                if (p.hasPoint) placeLine(ctx, p) else usesText(ctx, p.usedBy)?.let { pluralStringResource(R.plurals.places_waiting, p.usedCount, it) } ?: stringResource(R.string.places_no_point_line),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!p.hasPoint) {
            TextButton(onClick = onSet, modifier = Modifier.testTag("place-set-${p.id}")) { Text(stringResource(R.string.places_set)) }
        } else {
            distanceLabel(here?.let { h -> PlacePicking.distanceM(h.first, h.second, p.lat!!, p.lon!!) }, p.radiusM)?.let { DistanceText(it) }
        }
    }
}

/** The row on the profile page that leads here: „Meine Orte“ with how many and how many lack a point. */
@Composable
fun ProfilePlacesRow(onOpen: () -> Unit, modifier: Modifier = Modifier, vm: MyPlacesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.support != PlacesSupport.SUPPORTED) return
    ProfilePlacesRowContent(state.places, onOpen, modifier)
}

@Composable
fun ProfilePlacesRowContent(places: List<MyPlace>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val missing = places.count { !it.hasPoint }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GcSectionLabel(stringResource(R.string.places_title), Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
            GcListRow(
                title = stringResource(R.string.places_title),
                description = listOfNotNull(
                    if (places.isEmpty()) stringResource(R.string.places_none_yet) else pluralStringResource(R.plurals.places_count, places.size, places.size),
                    pluralStringResource(R.plurals.places_count_missing, missing, missing).takeIf { missing > 0 },
                ).joinToString(" · "),
                onClick = onOpen,
                leading = { PlaceIconSquare(PlaceIcons.PIN, missing = missing > 0) },
                trailing = { GcChevron() },
                modifier = Modifier.testTag("profile-places"),
            )
        }
    }
}

// ─── a place (Bild 3) ───────────────────────────────────────────────

@Composable
fun PlaceDetailScreen(id: Long, onBack: () -> Unit, onEdit: (Long) -> Unit, vm: MyPlacesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val here by vm.here.collectAsStateWithLifecycle()
    val place = state.places.firstOrNull { it.id == id }
    var confirm by remember { mutableStateOf(false) }
    var inUse by remember { mutableStateOf<List<PlaceUse>?>(null) }
    LaunchedEffect(Unit) { if (state.list == null) vm.load() }
    PlacesNotices(vm.notices)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(title = place?.name ?: stringResource(R.string.places_title), onBack = onBack, backDescription = stringResource(R.string.common_back)) {
            if (place != null) {
                GcIconButton(GcIcons.Trash, stringResource(R.string.places_delete), onClick = { confirm = true }, enabled = !busy, tint = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("place-delete"))
            }
        }
        if (place == null) {
            Text(stringResource(R.string.places_err_not_found), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(16.dp))
            return@Column
        }
        PlaceDetailContent(place, here, onEdit = { onEdit(place.id) }, modifier = Modifier.weight(1f))
    }
    val p = place ?: return
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.places_delete_q, p.name)) },
            text = { Text(stringResource(R.string.places_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    vm.delete(p, force = false, onInUse = { inUse = it }, onDone = onBack)
                }) { Text(stringResource(R.string.places_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.places_cancel)) } },
        )
    }
    inUse?.let { uses ->
        PlaceInUseDialog(p.name, uses, onForce = {
            inUse = null
            vm.delete(p, force = true, onInUse = {}, onDone = onBack)
        }, onDismiss = { inUse = null })
    }
}

/** 409 IN_USE: who uses the place, and „Trotzdem löschen“. */
@Composable
fun PlaceInUseDialog(name: String, uses: List<PlaceUse>, onForce: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.places_in_use_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("place-in-use")) {
                Text(stringResource(R.string.places_in_use_text, name))
                uses.forEach { u -> Text("• " + pluginLabel(u) + ": " + useText(ctx, u), style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.places_in_use_after), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
            }
        },
        confirmButton = { TextButton(onClick = onForce) { Text(stringResource(R.string.places_delete_force), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.places_cancel)) } },
    )
}

@Composable
fun PlaceDetailContent(place: MyPlace, here: Pair<Double, Double>?, onEdit: () -> Unit, modifier: Modifier = Modifier, showMap: Boolean = true) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).testTag("place-detail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showMap && place.hasPoint) {
            OsmMap(
                point = LatLon(place.lat!!, place.lon!!),
                contentDescription = stringResource(R.string.places_map_cd, place.name),
                modifier = Modifier.fillMaxWidth().height(200.dp),
                zoom = 15.0,
                radiusM = place.radiusM.toDouble(),
            )
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!place.hasPoint) {
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                    Text(stringResource(R.string.places_no_point_detail), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                GcListRow(
                    title = place.address ?: place.name,
                    description = listOfNotNull(
                        stringResource(R.string.places_radius_line, place.radiusM),
                        stringResource(visibilityLabel(place.visibility, short = true)),
                        here?.takeIf { place.hasPoint }?.let { h -> distanceLabel(PlacePicking.distanceM(h.first, h.second, place.lat!!, place.lon!!), place.radiusM) },
                    ).joinToString(" · "),
                    leading = { PlaceIconSquare(place.icon, missing = !place.hasPoint) },
                    trailing = { TextButton(onClick = onEdit, modifier = Modifier.testTag("place-edit")) { Text(stringResource(if (place.hasPoint) R.string.places_change else R.string.places_set)) } },
                )
            }
            GcSectionLabel(stringResource(R.string.places_used_by), Modifier.padding(start = 4.dp))
            if (place.usedBy.isEmpty()) {
                Text(stringResource(R.string.places_used_none), style = MaterialTheme.typography.bodyMedium, color = extra.muted, modifier = Modifier.padding(horizontal = 4.dp))
            } else {
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    place.usedBy.forEachIndexed { i, u ->
                        if (i > 0) GcRowDivider()
                        GcListRow(
                            title = useText(ctx, u),
                            description = pluginLabel(u),
                            leading = { PlaceIconSquare(useIcon(u.kind)) },
                        )
                    }
                }
            }
            GcBanner(tone = GcTone.Info, icon = GcIcons.Info) {
                Text(stringResource(R.string.places_detail_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun useIcon(kind: String): String = when (kind) {
    MyPlaces.KIND_TASK -> PlaceIcons.SHOP
    MyPlaces.KIND_RULE -> PlaceIcons.HOME
    MyPlaces.KIND_REMINDER -> PlaceIcons.FAMILY
    else -> PlaceIcons.PIN
}

// ─── the editor (Bild 2) ────────────────────────────────────────────

@Composable
fun PlaceEditorScreen(id: Long?, onBack: () -> Unit, vm: MyPlacesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val finder by vm.finder.state.collectAsStateWithLifecycle()
    val here by vm.here.collectAsStateWithLifecycle()
    val editing = id?.let { i -> state.places.firstOrNull { it.id == i } }
    val limits = state.list?.limits
    var d by rememberSaveable(editing?.id, stateSaver = DraftSaver) {
        mutableStateOf(editing?.let(MyPlaces::draftOf) ?: PlaceDraft(radiusM = limits?.radiusDefault ?: MyPlaces.RADIUS_DEFAULT))
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (state.list == null) vm.load() }
    PlacesNotices(vm.notices)
    val taken = MyPlaces.nameTaken(d.name, state.places, editing?.id)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(
            title = stringResource(if (editing == null) R.string.places_new else R.string.places_edit),
            onBack = onBack,
            backDescription = stringResource(R.string.places_close),
        ) {
            TextButton(
                onClick = { vm.save(editing, d) { onBack() } },
                enabled = MyPlaces.canSave(d) && !taken && !busy,
                modifier = Modifier.testTag("place-save"),
            ) { Text(stringResource(R.string.places_save)) }
        }
        PlaceEditorContent(
            draft = d,
            onDraft = { d = it },
            finder = finder,
            family = state.list?.family == true,
            nameTaken = taken,
            searchOn = state.list?.search != MyPlaces.SEARCH_OFF,
            onSearch = { q -> vm.finder.search(q, here ?: d.lat?.let { la -> d.lon?.let { lo -> la to lo } }) },
            onClearHits = vm.finder::clear,
            onHere = {
                vm.locate { lat, lon ->
                    d = d.copy(lat = lat, lon = lon)
                    scope.launch { vm.addressOf(lat, lon)?.let { a -> d = d.copy(address = a) } }
                }
            },
            onTap = { lat, lon ->
                d = d.copy(lat = lat, lon = lon, address = null)
                scope.launch { vm.addressOf(lat, lon)?.let { a -> if (d.lat == lat && d.lon == lon) d = d.copy(address = a) } }
            },
            radiusMin = limits?.radiusMin ?: MyPlaces.RADIUS_MIN,
            radiusMax = limits?.radiusMax ?: MyPlaces.RADIUS_MAX,
            modifier = Modifier.weight(1f),
        )
    }
}

private val DraftSaver = androidx.compose.runtime.saveable.listSaver<PlaceDraft, Any?>(
    save = { listOf(it.name, it.icon, it.lat, it.lon, it.radiusM, it.address, it.visibility) },
    restore = { l -> PlaceDraft(l[0] as String, l[1] as String, l[2] as Double?, l[3] as Double?, l[4] as Int, l[5] as String?, l[6] as String) },
)

/** The editor's body (drawn in the tests without a view model). */
@Composable
fun PlaceEditorContent(
    draft: PlaceDraft,
    onDraft: (PlaceDraft) -> Unit,
    finder: FinderState,
    family: Boolean,
    nameTaken: Boolean,
    searchOn: Boolean,
    onSearch: (String) -> Unit,
    onClearHits: () -> Unit,
    onHere: () -> Unit,
    onTap: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
    radiusMin: Int = MyPlaces.RADIUS_MIN,
    radiusMax: Int = MyPlaces.RADIUS_MAX,
    showMap: Boolean = true,
) {
    val extra = GateControlTheme.extraColors
    var query by rememberSaveable { mutableStateOf("") }
    val d = draft
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).testTag("place-editor"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AddressSearchField(query, { query = it }, finder, onSearch = { onSearch(query) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        HitsCard(finder, searchOn) { h ->
            onDraft(d.copy(lat = h.lat, lon = h.lon, address = h.address, name = d.name.ifBlank { h.name.take(MyPlaces.NAME_MAX) }))
            onClearHits()
        }
        Box(Modifier.fillMaxWidth()) {
            if (showMap) {
                OsmMap(
                    point = if (MyPlaces.hasPoint(d.lat, d.lon)) LatLon(d.lat!!, d.lon!!) else null,
                    contentDescription = stringResource(R.string.places_map_pick_cd),
                    modifier = Modifier.fillMaxWidth().height(240.dp).testTag("place-editor-map"),
                    zoom = if (MyPlaces.hasPoint(d.lat, d.lon)) 15.0 else 5.0,
                    radiusM = if (MyPlaces.hasPoint(d.lat, d.lon)) d.radiusM.toDouble() else null,
                    onTap = { pt -> onTap(pt.lat, pt.lon) },
                )
            } else {
                Box(Modifier.fillMaxWidth().height(240.dp).background(extra.panel2))
            }
            HereButton(finder.locating, onHere, Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            d.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.testTag("place-editor-address")) }
            GcTextField(
                value = d.name,
                onValueChange = { onDraft(d.copy(name = it.take(MyPlaces.NAME_MAX))) },
                label = stringResource(R.string.places_name),
                placeholder = stringResource(R.string.places_name_hint),
                error = if (nameTaken) stringResource(R.string.places_err_duplicate) else null,
            )
            GcSectionLabel(stringResource(R.string.places_icon))
            IconPicker(d.icon) { onDraft(d.copy(icon = it)) }
            GcSectionLabel(stringResource(R.string.places_radius, d.radiusM))
            Slider(
                value = d.radiusM.toFloat(),
                onValueChange = { v -> onDraft(d.copy(radiusM = MyPlaces.snapRadius(v, radiusMin, radiusMax))) },
                valueRange = radiusMin.toFloat()..radiusMax.toFloat(),
                modifier = Modifier.testTag("place-radius"),
            )
            GcSectionLabel(stringResource(R.string.places_visibility))
            val options = buildList {
                add(PlaceVisibility.SELF to stringResource(R.string.places_vis_self))
                if (family || d.visibility == PlaceVisibility.FAMILY) add(PlaceVisibility.FAMILY to stringResource(R.string.places_vis_family))
                add(PlaceVisibility.ALL to stringResource(R.string.places_vis_all))
            }
            GcSegmented(options, d.visibility, { onDraft(d.copy(visibility = it)) })
            GcBanner(tone = if (MyPlaces.hasPoint(d.lat, d.lon)) GcTone.Ok else GcTone.Info, icon = GcIcons.Info) {
                Text(stringResource(R.string.places_editor_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The seven symbols as square toggles (one chosen). */
@Composable
private fun IconPicker(selected: String, onPick: (String) -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PlaceIcons.ALL.forEach { icon ->
            val sel = icon == selected
            val label = stringResource(placeIconLabel(icon))
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) extra.accentBg else androidx.compose.ui.graphics.Color.Transparent)
                    .border(1.dp, if (sel) scheme.primary else extra.border2, RoundedCornerShape(10.dp))
                    .selectable(sel, role = Role.RadioButton) { onPick(icon) }
                    .semantics { contentDescription = label }
                    .testTag("place-icon-$icon"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(placeIconVector(icon), contentDescription = null, tint = if (sel) scheme.primary else extra.muted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The round „◎“ on the map: the current position. */
@Composable
internal fun HereButton(locating: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    Box(
        modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, extra.border, RoundedCornerShape(12.dp))
            .clickable(enabled = !locating, role = Role.Button, onClickLabel = stringResource(R.string.places_here_btn), onClick = onClick)
            .testTag("place-here"),
        contentAlignment = Alignment.Center,
    ) {
        if (locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        else Icon(GcIcons.Crosshair, contentDescription = stringResource(R.string.places_here_btn), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
    }
}

/** The address search field: only on „Suchen“ / Enter (never per key), from 3 characters. */
@Composable
internal fun AddressSearchField(query: String, onQuery: (String) -> Unit, finder: FinderState, onSearch: () -> Unit, modifier: Modifier = Modifier, label: Int = R.string.places_search_hint) {
    GcTextField(
        value = query,
        onValueChange = { onQuery(it.take(MyPlaces.SEARCH_MAX)) },
        label = stringResource(label),
        modifier = modifier.fillMaxWidth().testTag("place-search"),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (PlacePicking.searchable(query)) onSearch() }),
        trailingIcon = {
            if (finder.searching) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            } else {
                GcIconButton(GcIcons.Search, stringResource(R.string.places_search), onClick = onSearch, enabled = PlacePicking.searchable(query))
            }
        },
    )
}

/** The hits of the address search (under the field), with the map service's name. */
@Composable
private fun HitsCard(finder: FinderState, searchOn: Boolean, onPick: (AddressHit) -> Unit) {
    val extra = GateControlTheme.extraColors
    val hits = finder.hits
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        finderProblem(finder)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (finder.problem == "none") extra.muted else MaterialTheme.colorScheme.error) }
        if (!hits.isNullOrEmpty()) {
            GcCard(contentPadding = PaddingValues(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                hits.forEachIndexed { i, h ->
                    if (i > 0) GcRowDivider()
                    GcListRow(title = h.name, description = h.address, onClick = { onPick(h) }, leading = { PlaceIconSquare(PlaceIcons.PIN) })
                }
            }
            Text(
                if (finder.viaDevice) stringResource(R.string.places_via_device) else finder.attribution ?: stringResource(R.string.places_osm),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        } else if (!searchOn && hits == null) {
            Text(stringResource(R.string.places_search_device_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
    }
}

@Composable
internal fun finderProblem(f: FinderState): String? = when (f.problem) {
    "none" -> stringResource(R.string.places_search_none)
    "busy" -> f.retryMin?.let { pluralStringResource(R.plurals.places_search_busy_min, it, it) } ?: stringResource(R.string.places_search_busy)
    "down" -> stringResource(R.string.places_search_down)
    "offline" -> stringResource(R.string.places_err_offline)
    "off" -> stringResource(R.string.places_search_off)
    else -> null
}

/** Shows the view model's one-shot notices as toasts. */
@Composable
internal fun PlacesNotices(notices: kotlinx.coroutines.flow.Flow<PlacesNotice>) {
    val ctx = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    LaunchedEffect(notices) {
        notices.collect { n ->
            val text = when (n) {
                is PlacesNotice.Res -> resources.getString(n.res, *n.args.toTypedArray())
                is PlacesNotice.Text -> n.text
            }
            android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
