package com.gatecontrol.android.ui.notizen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NNearNow
import com.gatecontrol.android.notizen.NPlace
import com.gatecontrol.android.notizen.NSpot
import com.gatecontrol.android.notizen.PlaceLogic
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.familie.LatLon
import com.gatecontrol.android.ui.familie.OsmMap
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** „beim Ankommen“ / „beim Verlassen“. */
@Composable
internal fun placeWhen(trigger: String): String = stringResource(if (trigger == NPlace.EXIT) R.string.notizen_pl_on_exit else R.string.notizen_pl_on_enter)

/** „Baumarkt Nord · beim Ankommen“ — the place of a task as its row and its page say it. */
@Composable
internal fun placeLine(place: NPlace): String = stringResource(R.string.notizen_pl_line, place.name, placeWhen(place.trigger))

/** The place a task hangs on, in its row (plugin 1.5.0, mockup „Orte aus dem getippten Text“): a pin and „Baumarkt Nord · beim Ankommen“. */
@Composable
internal fun NPlaceMark(place: NPlace) {
    val x = GateControlTheme.extraColors
    // declined, or waiting for the person whose phone is to watch it: written, but paler
    val quiet = place.consent != NPlace.ACCEPTED
    Row(
        Modifier
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, x.border2, RoundedCornerShape(14.dp))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(GcIcons.Pin, null, tint = if (quiet) x.faint else MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
        Text(
            placeLine(place),
            style = MaterialTheme.typography.labelMedium,
            color = if (quiet) x.muted else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What the choice of a place can do. */
internal class PlaceDialogActions(
    val query: (String) -> Unit,
    val search: () -> Unit,
    val select: (NSpot) -> Unit,
    val selectAny: () -> Unit,
    val trigger: (String) -> Unit,
    val remember: (Boolean) -> Unit,
    val map: () -> Unit,
    val save: () -> Unit,
    val remove: () -> Unit,
    val dismiss: () -> Unit,
    val openUrl: (String) -> Unit,
)

/**
 * „Welcher Baumarkt?“ / „Ort wählen“ (plugin 1.5.0, mockup „Eine Art von Ort“, „Eine Kette oder Marke“, „Ein eigener
 * Ort“, „Wenn die Suche nichts findet“, „Ohne Ortssuche“): the hits of the search — travel time and distance at the end,
 * estimated — „Jeder …“, „Meine Orte“, the map, „Beim Ankommen“ / „Beim Verlassen“ and „Als meinen Ort … merken“.
 * The search runs only on „Suchen“; where hits show, „© OpenStreetMap“ shows.
 */
@Composable
internal fun PlaceDialog(c: PlaceChoice, a: PlaceDialogActions) {
    val label = c.label
    AlertDialog(
        onDismissRequest = a.dismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (label != null) stringResource(R.string.notizen_pl_which, label) else stringResource(R.string.notizen_pl_choose)) },
        text = { PlaceDialogBody(c, a, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = a.save, enabled = c.any || c.selected != null) { Text(stringResource(R.string.notizen_save)) } },
        dismissButton = {
            Row {
                if (c.canRemove) TextButton(onClick = a.remove) { Text(stringResource(R.string.notizen_pl_remove)) }
                TextButton(onClick = a.dismiss) { Text(stringResource(R.string.notizen_cancel)) }
            }
        },
    )
}

/** What the choice shows between its title and its buttons. */
@Composable
internal fun PlaceDialogBody(c: PlaceChoice, a: PlaceDialogActions, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    val label = c.label
    Column(modifier.testTag("place-dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.searchOn) {
                    GcTextField(
                        value = c.query,
                        onValueChange = a.query,
                        label = stringResource(R.string.notizen_pl_search_label),
                        placeholder = stringResource(R.string.notizen_pl_search_ph),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { a.search() }),
                    )
                    GcOutlineButton(
                        stringResource(R.string.notizen_pl_search),
                        a.search,
                        enabled = !c.searching && (c.query.trim().length >= 2 || c.key != null),
                        icon = GcIcons.Search,
                        loading = c.searching,
                    )
                    // the server asks the map service one request after the other: this can take some seconds
                    if (c.searching) Text(stringResource(R.string.notizen_pl_searching), style = MaterialTheme.typography.bodySmall, color = x.muted)
                }
                problemText(c)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (c.problem == "none") x.muted else MaterialTheme.colorScheme.error) }
                val hits = c.results.orEmpty()
                hits.forEach { s -> SpotRow(s, c.selected?.uid == s.uid && !c.any, hitHint(s), c.lang) { a.select(s) } }
                if (c.anyOffered && label != null) {
                    ChoiceRow(c.any, stringResource(R.string.notizen_pl_any, label), stringResource(R.string.notizen_pl_any_hint), null, null, dashed = true, onClick = a.selectAny)
                }
                if (hits.isNotEmpty()) {
                    // the map service's licence: its name shows wherever its hits show, and opens its copyright page
                    val att = c.attribution
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.notizen_pl_approx), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.weight(1f, fill = false))
                        Text(
                            att?.text ?: stringResource(R.string.notizen_pl_osm),
                            style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { a.openUrl(att?.url ?: OSM_COPYRIGHT) }.padding(vertical = 6.dp),
                        )
                    }
                }
                val mine = PlaceChoices.mine(c)
                Text(stringResource(R.string.notizen_pl_mine), style = MaterialTheme.typography.labelLarge, color = x.muted, modifier = Modifier.padding(top = 6.dp))
                if (mine.isEmpty()) Text(stringResource(R.string.notizen_pl_mine_none), style = MaterialTheme.typography.bodySmall, color = x.muted)
                mine.forEach { s -> SpotRow(s, c.selected?.uid == s.uid && !c.any, mineHint(s), c.lang, distance = false) { a.select(s) } }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = a.map).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(GcIcons.Pin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.notizen_pl_map), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Icon(GcIcons.ChevronRight, null, tint = x.muted, modifier = Modifier.size(18.dp))
                }
                GcSegmented(
                    listOf(NPlace.ENTER to stringResource(R.string.notizen_pl_enter), NPlace.EXIT to stringResource(R.string.notizen_pl_exit)),
                    c.trigger,
                    a.trigger,
                )
                if (PlaceChoices.canRemember(c) && label != null) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).toggleable(c.remember, role = Role.Checkbox, onValueChange = a.remember),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(c.remember, null)
                        Text(stringResource(R.string.notizen_pl_remember, label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (c.searchOn) {
                    Text(stringResource(R.string.notizen_pl_search_privacy), style = MaterialTheme.typography.bodySmall, color = x.muted)
                } else {
                    Text(stringResource(R.string.notizen_pl_search_off), style = MaterialTheme.typography.bodySmall, color = x.muted)
                }
                Text(stringResource(R.string.notizen_pl_phone_hint), style = MaterialTheme.typography.bodySmall, color = x.muted)
    }
}

private const val OSM_COPYRIGHT = "https://www.openstreetmap.org/copyright"

@Composable
private fun problemText(c: PlaceChoice): String? = when (c.problem) {
    "none" -> stringResource(R.string.notizen_pl_none, c.label ?: c.query, c.radiusKm)
    "off" -> null // said below, in place of the privacy sentence
    "down" -> stringResource(R.string.notizen_pl_search_down)
    "busy" -> stringResource(R.string.notizen_pl_search_busy)
    "near" -> stringResource(R.string.notizen_pl_search_near)
    "offline" -> stringResource(R.string.notizen_pl_search_offline)
    else -> null
}

/** The line under a hit: its address, and „gemerkt“ / „3-mal gewählt“. */
@Composable
private fun hitHint(s: NSpot): String = listOfNotNull(
    s.address,
    if (s.fav) stringResource(R.string.notizen_pl_saved) else if (s.uses > 0) stringResource(R.string.notizen_pl_used, s.uses) else null,
).joinToString(" · ")

/** The line under one of „Meine Orte“: „Ort der Familie“, „gemerkt für ‚Baumarkt‘“, its address. */
@Composable
private fun mineHint(s: NSpot): String = when {
    s.family -> stringResource(R.string.notizen_pl_family)
    else -> listOfNotNull(
        s.address,
        s.favFor.firstOrNull()?.let { stringResource(R.string.notizen_pl_fav_for, it.label) } ?: if (s.named) stringResource(R.string.notizen_pl_saved) else null,
    ).joinToString(" · ")
}

@Composable
private fun SpotRow(s: NSpot, selected: Boolean, hint: String, lang: String, distance: Boolean = true, onClick: () -> Unit) {
    ChoiceRow(
        selected, s.name, hint.takeIf { it.isNotBlank() },
        s.minutes?.takeIf { distance }?.let { stringResource(R.string.notizen_pl_min, it) },
        s.distanceM?.takeIf { distance }?.let { PlaceLogic.distanceText(it, lang) },
        onClick = onClick,
    )
}

/** One choice of the dialog: a radio mark, the name and its line, and — for a hit — the estimated minutes over the distance. */
@Composable
private fun ChoiceRow(selected: Boolean, title: String, hint: String?, top: String?, bottom: String?, dashed: Boolean = false, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (selected) scheme.primary.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (selected) scheme.primary else if (dashed) x.border else x.border2, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (selected) scheme.primary else x.faint, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.primary))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (top != null || bottom != null) {
            Column(horizontalAlignment = Alignment.End) {
                top?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)) }
                bottom?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted) }
            }
        }
    }
}

/**
 * „Ort auf der Karte wählen“: a tap sets the point, the place gets a name. The map is the one the Familie pages use
 * (OpenStreetMap tiles); nothing is searched here. [onHere]: „Aktuelle Position“ — null when the phone may not locate.
 */
@Composable
internal fun MapPointDialog(initial: LatLon?, title: String, onHere: (suspend () -> LatLon?)?, onDismiss: () -> Unit, onDone: (name: String, lat: Double, lon: Double) -> Unit) {
    val x = GateControlTheme.extraColors
    var lat by rememberSaveable { mutableStateOf(initial?.lat) }
    var lon by rememberSaveable { mutableStateOf(initial?.lon) }
    var name by rememberSaveable { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val la = lat
    val lo = lon
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OsmMap(
                    point = if (la != null && lo != null) LatLon(la, lo) else null,
                    contentDescription = stringResource(R.string.notizen_pl_map_cd),
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    zoom = if (la != null) 16.0 else 5.0,
                    onTap = { p -> lat = p.lat; lon = p.lon },
                )
                Text(stringResource(R.string.notizen_pl_map_hint), style = MaterialTheme.typography.bodySmall, color = x.muted)
                if (onHere != null) {
                    GcOutlineButton(stringResource(R.string.notizen_pl_map_here), { scope.launchHere(onHere) { p -> lat = p.lat; lon = p.lon } }, icon = GcIcons.Crosshair)
                }
                GcTextField(name, { name = it.take(80) }, stringResource(R.string.notizen_pl_pin_name))
            }
        },
        confirmButton = {
            TextButton(onClick = { if (la != null && lo != null) onDone(name.trim(), la, lo) }, enabled = la != null && lo != null && name.isNotBlank()) { Text(stringResource(R.string.notizen_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}

private fun CoroutineScope.launchHere(here: suspend () -> LatLon?, set: (LatLon) -> Unit) {
    launch { here()?.let(set) }
}

/**
 * „Gerade in der Nähe“ on top of „Mein Tag“ (plugin 1.5.0, mockup „Dasselbe in ‚Mein Tag‘“): the tasks whose place — or
 * another one of its kind — the phone is at right now, with „Nicht hier“. Made on the phone; the server learns nothing
 * of it, and the card says so.
 */
@Composable
internal fun NearNowCard(items: List<NNearNow>, lang: String, canWrite: Boolean, onOpen: (Long) -> Unit, onNotHere: (NNearNow) -> Unit, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val x = GateControlTheme.extraColors
    Column(modifier) {
        NSectionHeader(stringResource(R.string.notizen_pl_near_title), items.size)
        NCard {
            items.forEachIndexed { i, n ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onOpen(n.task.taskId) }.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(GcIcons.Pin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(n.task.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                n.fence.name.takeIf { it.isNotBlank() },
                                n.distanceM?.let { PlaceLogic.distanceText(it, lang) },
                                n.task.planned?.takeIf { n.task.nearby }?.let { stringResource(R.string.notizen_pl_near_planned, it) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = x.muted,
                        )
                    }
                    if (canWrite) TextButton(onClick = { onNotHere(n) }) { Text(stringResource(R.string.notizen_pl_not_here)) }
                }
                if (i < items.size - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
            }
        }
        Text(stringResource(R.string.notizen_pl_near_privacy), style = MaterialTheme.typography.bodySmall, color = x.faint, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
    }
}

/** Opens a page of the web (the map data's copyright) in the browser. */
internal fun openWeb(context: android.content.Context, url: String) {
    if (!url.startsWith("https://")) return
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * The choice of a place with everything around it, for a page that has a [PlaceChooser]: the dialog, the map, and the
 * question for the location before the first search (with its explanation, as the Familie pages ask). [onPicked]: what
 * the person chose; [onRemove]: „Ohne Ort“.
 */
@Composable
internal fun PlaceChoiceHost(chooser: PlaceChooser, onPicked: (PlacePicked) -> Unit, onRemove: () -> Unit) {
    val c by chooser.ui.collectAsStateWithLifecycle()
    if (!c.open) return
    val context = LocalContext.current
    var map by rememberSaveable { mutableStateOf(false) }
    /** Asked once per choice: who says no still gets a search (near one of „Meine Orte“). */
    var asked by rememberSaveable { mutableStateOf(false) }
    val anyName = stringResource(R.string.notizen_pl_any, c.label.orEmpty())
    // the search is better with a position (the hits nearest to the phone); without one it looks near one of my places
    val askLocation = com.gatecontrol.android.ui.familie.rememberLocationPermissionFlow(
        background = false,
        foregroundText = R.string.notizen_pl_perm_search_text,
        backgroundText = R.string.notizen_pl_perm_bg_text,
    ) { chooser.search() }
    if (map) {
        val sel = c.selected
        MapPointDialog(
            initial = if (sel?.lat != null && sel.lon != null) LatLon(sel.lat, sel.lon) else null,
            title = stringResource(R.string.notizen_pl_map),
            onHere = if (com.gatecontrol.android.location.DeviceLocator.hasPermission(context)) {
                { (com.gatecontrol.android.location.DeviceLocator.locate(context) as? com.gatecontrol.android.location.LocateResult.Fix)?.location?.let { LatLon(it.latitude, it.longitude) } }
            } else {
                null
            },
            onDismiss = { map = false },
            onDone = { name, lat, lon -> map = false; chooser.setPoint(name, lat, lon) },
        )
        return
    }
    PlaceDialog(
        c,
        PlaceDialogActions(
            query = chooser::setQuery,
            search = {
                if (asked || com.gatecontrol.android.location.DeviceLocator.hasPermission(context)) {
                    chooser.search()
                } else {
                    asked = true
                    askLocation()
                }
            },
            select = chooser::select,
            selectAny = chooser::selectAny,
            trigger = chooser::setTrigger,
            remember = chooser::setRemember,
            map = { map = true },
            save = { chooser.confirm { anyName }?.let(onPicked) },
            remove = { chooser.close(); onRemove() },
            dismiss = chooser::close,
            openUrl = { openWeb(context, it) },
        ),
    )
}
