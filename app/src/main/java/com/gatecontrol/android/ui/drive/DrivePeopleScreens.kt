package com.gatecontrol.android.ui.drive

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DFaceRef
import com.gatecontrol.android.drive.DPerson
import com.gatecontrol.android.drive.DPersonContact
import com.gatecontrol.android.drive.DrivePlacesJson
import com.gatecontrol.android.drive.DrivePlacesLogic
import com.gatecontrol.android.drive.faces.FaceModelState
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.ZoneId

/** A heading of the people pages with a muted note on the right. */
@Composable
private fun PeopleHead(title: String, note: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
    }
}

/** How far this phone (and the others) got: "Gesichter werden erkannt · 4.120 von 6.212 Fotos". */
@Composable
internal fun FacesProgress(done: Int?, total: Int?, chargingOnly: Boolean) {
    val x = GateControlTheme.extraColors
    GcCard(color = x.panel2, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(GcIcons.Refresh, contentDescription = null, tint = x.accentText, modifier = Modifier.size(16.dp))
            val t = total ?: 0
            val d = (done ?: 0).coerceAtMost(t)
            Text(
                if (t > 0 && d >= t) stringResource(R.string.drive_pp_progress_done, countText(t)) else stringResource(R.string.drive_pp_progress, countText(d), countText(t)),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        val t = total ?: 0
        if (t > 0) LinearProgressIndicator(progress = { ((done ?: 0).toFloat() / t).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
        Text(stringResource(if (chargingOnly) R.string.drive_pp_progress_charging else R.string.drive_pp_progress_wifi), style = MaterialTheme.typography.bodySmall, color = x.muted)
    }
}

/** "12 von 39 MB". */
private fun mb(bytes: Long): String = java.text.NumberFormat.getIntegerInstance().format((bytes + 500_000) / 1_000_000)

/**
 * The feature model while it is not on the phone yet: its download ("Erkennungsmodell wird geladen · 12 von 39 MB"),
 * waiting for WLAN (with "Jetzt über mobile Daten laden"), or what went wrong with "Erneut versuchen".
 */
@Composable
internal fun FaceModelCard(state: FaceModelState, onMobile: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    val failed = state.phase == FaceModelState.Phase.FAILED
    GcCard(modifier = modifier, color = if (failed) x.errorBg else x.panel2, borderColor = androidx.compose.ui.graphics.Color.Transparent, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.phase) {
            FaceModelState.Phase.LOADING -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(GcIcons.Download, contentDescription = null, tint = x.accentText, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.drive_pp_model_loading, mb(state.done), mb(state.total)), style = MaterialTheme.typography.titleSmall)
                }
                LinearProgressIndicator(progress = { (state.done.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
            }
            FaceModelState.Phase.FAILED -> {
                Text(
                    stringResource(if (state.error == "hash") R.string.drive_pp_model_failed_hash else R.string.drive_pp_model_failed),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.drive_retry), color = x.accentText, fontWeight = FontWeight.SemiBold) }
            }
            else -> {
                Text(stringResource(R.string.drive_pp_model_waiting, mb(state.total)), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onMobile) { Text(stringResource(R.string.drive_pp_model_mobile), color = x.accentText, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** A person in the grid: the face, the name (or "Benennen"), the count. */
@Composable
private fun PersonTile(p: DPerson, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (p.named || p.cover != null) PersonFace(p, 84.dp) else Box(Modifier.size(84.dp).clip(CircleShape).background(x.panel2), contentAlignment = Alignment.Center) {
            Icon(GcIcons.User, contentDescription = null, tint = x.faint, modifier = Modifier.size(34.dp))
        }
        Text(
            p.name ?: stringResource(R.string.drive_pp_name_it),
            style = MaterialTheme.typography.titleSmall,
            color = if (p.named) MaterialTheme.colorScheme.onBackground else x.warn,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(pluralStringResource(R.plurals.drive_photos_count, p.count, p.count), style = MaterialTheme.typography.bodySmall, color = x.muted)
    }
}

/**
 * "Personen" (Drive 1.16, `photoPeople`): how far the faces are, the named people, those without a name ("Wer ist
 * das?"), the hidden ones. The gear leads to "Orte und Personen".
 */
@Composable
fun DrivePeopleScreen(onBack: () -> Unit, onPerson: (Long) -> Unit, onSettings: () -> Unit, viewModel: DrivePeopleViewModel = hiltViewModel()) {
    val x = GateControlTheme.extraColors
    val people by viewModel.people.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle(FaceModelState(FaceModelState.Phase.READY))
    var naming by remember { mutableStateOf<Long?>(null) }
    var allUnnamed by rememberSaveable { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val ready = (people as? DriveLoad.Ready)?.value
    val groups = remember(ready) { DrivePlacesLogic.groups(ready?.people.orEmpty()) }
    Column(Modifier.fillMaxSize()) {
        val sub = if (ready == null) null else listOf(
            pluralStringResource(R.plurals.drive_pp_people_named, groups.named.size, groups.named.size),
            pluralStringResource(R.plurals.drive_pp_people_unnamed, groups.unnamed.size, groups.unnamed.size),
        ).joinToString(" · ")
        GcSubpageBar(stringResource(R.string.drive_pp_people), onBack = onBack, backDescription = stringResource(R.string.drive_back)) {
            GcIconButton(GcIcons.Settings, contentDescription = stringResource(R.string.drive_pp_people_settings), onClick = onSettings, tint = MaterialTheme.colorScheme.onBackground)
        }
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(start = 64.dp))
        when (val p = people) {
            DriveLoad.Loading -> DriveLoading()
            is DriveLoad.Error -> DriveErrorBox(p.code, onRetry = viewModel::load)
            is DriveLoad.Ready -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 4.dp)) {
                        if (!settings.people) {
                            GcCard(color = x.panel2) {
                                Text(stringResource(R.string.drive_pp_people_off), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = onSettings) { Text(stringResource(R.string.drive_pp_turn_on), color = x.accentText, fontWeight = FontWeight.SemiBold) }
                            }
                        } else if (model.phase != FaceModelState.Phase.READY) {
                            // first the feature model, then the faces
                            FaceModelCard(model, onMobile = { viewModel.loadModel(true) }, onRetry = { viewModel.loadModel(false) })
                        } else {
                            FacesProgress(p.value.done, p.value.total, settings.chargingOnly)
                        }
                    }
                }
                if (groups.named.isEmpty() && groups.unnamed.isEmpty() && settings.people) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.drive_pp_people_empty), color = x.muted, modifier = Modifier.padding(16.dp))
                    }
                }
                if (groups.named.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { PeopleHead(stringResource(R.string.drive_pp_named)) }
                    items(groups.named, key = { "n-${it.id}" }) { person -> PersonTile(person) { onPerson(person.id) } }
                }
                if (groups.unnamed.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { PeopleHead(stringResource(R.string.drive_pp_who), stringResource(R.string.drive_pp_who_hint)) }
                    val shown = if (allUnnamed) groups.unnamed else groups.unnamed.take(UNNAMED_FIRST)
                    items(shown, key = { "u-${it.id}" }) { person -> PersonTile(person) { naming = person.id } }
                    val more = groups.unnamed.size - shown.size
                    if (more > 0) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp).fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, x.border2, RoundedCornerShape(12.dp)).clickable(role = Role.Button) { allUnnamed = true },
                                contentAlignment = Alignment.Center,
                            ) { Text(pluralStringResource(R.plurals.drive_pp_more_unnamed, more, more), style = MaterialTheme.typography.titleSmall) }
                        }
                    }
                }
                if (groups.hidden.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.clickable(role = Role.Button) { showHidden = !showHidden }) {
                            PeopleHead(stringResource(R.string.drive_pp_hidden), groups.hidden.size.toString())
                            Text(stringResource(R.string.drive_pp_hidden_hint), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                    if (showHidden) {
                        items(groups.hidden, key = { "h-${it.id}" }) { person ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PersonTile(person) { onPerson(person.id) }
                                TextButton(onClick = { viewModel.unhide(person) }) { Text(stringResource(R.string.drive_pp_unhide), color = x.accentText) }
                            }
                        }
                    }
                }
            }
        }
    }
    naming?.let { id ->
        NamePersonSheet(
            personId = id,
            onDismiss = { naming = null; viewModel.load() },
            round = true,
        )
    }
}

private const val UNNAMED_FIRST = 6

/**
 * One person (Drive 1.16): the face, name, photos and years, the link to a family member or contact; the unsure faces
 * ("Ist das auch …?"), who is often on the photos too ("Zusammen mit", a tap shows only photos with both), the places,
 * the photos by year. The menu: rename, link, merge with another person, hide.
 */
@Composable
fun DrivePersonScreen(onBack: () -> Unit, onPlace: (String, Long) -> Unit, viewModel: DrivePersonViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val x = GateControlTheme.extraColors
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val uncertain by viewModel.uncertain.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val with by viewModel.with.collectAsStateWithLifecycle()
    val id by viewModel.id.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val all by viewModel.all.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, peopleText(context, it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    val d = (detail as? DriveLoad.Ready)?.value
    val zone = ZoneId.systemDefault()
    val sections = remember(photos.items) {
        photos.items.groupBy { n -> DrivePlacesLogic.dayOf(n, zone)?.year }.entries.map { (year, list) ->
            PhotoSection(key = year?.toString() ?: "none", title = year?.toString() ?: "–", trailing = null, items = list)
        }
    }
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar("", onBack = onBack, backDescription = stringResource(R.string.drive_back)) {
            Box {
                GcIconButton(GcIcons.MoreVertical, contentDescription = stringResource(R.string.drive_pp_more_actions), onClick = { menu = true }, tint = MaterialTheme.colorScheme.onBackground)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.drive_pp_rename)) }, onClick = { menu = false; naming = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.drive_pp_link)) }, onClick = { menu = false; linking = true })
                    if (d?.person?.contact != null) DropdownMenuItem(text = { Text(stringResource(R.string.drive_pp_unlink)) }, onClick = { menu = false; viewModel.link(null) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.drive_pp_merge)) }, onClick = { menu = false; merging = true })
                    val hidden = d?.person?.hidden == true
                    DropdownMenuItem(text = { Text(stringResource(if (hidden) R.string.drive_pp_show else R.string.drive_pp_hide)) }, onClick = { menu = false; viewModel.setHidden(!hidden) })
                }
            }
        }
        when (val s = detail) {
            DriveLoad.Loading -> DriveLoading()
            is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = viewModel::load)
            is DriveLoad.Ready -> PhotoGrid(
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
                        val p = s.value.person
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                                PersonFace(p, 88.dp)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(p.name ?: stringResource(R.string.drive_pp_no_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        listOfNotNull(pluralStringResource(R.plurals.drive_photos_count, p.count, p.count), DrivePlacesLogic.years(s.value.yearFrom, s.value.yearTo)).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = x.muted,
                                    )
                                    p.contact?.let { c ->
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Icon(GcIcons.Link, contentDescription = null, tint = x.muted, modifier = Modifier.size(14.dp))
                                            Text(
                                                stringResource(if (c.kind == DrivePlacesJson.CONTACT_FAMILY) R.string.drive_pp_linked_family else R.string.drive_pp_linked_contact, c.name ?: p.name.orEmpty()),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = x.muted,
                                            )
                                        }
                                    }
                                    if (!p.named) {
                                        TextButton(onClick = { naming = true }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.drive_pp_name_it), color = x.warn, fontWeight = FontWeight.SemiBold) }
                                    }
                                }
                            }
                            if (uncertain.isNotEmpty()) UncertainCard(p, uncertain, s.value.uncertain, onAll = { viewModel.confirm(uncertain.mapNotNull { it.faceId }, true) }, onCheck = { checking = true })
                            if (s.value.together.isNotEmpty()) {
                                PeopleHead(stringResource(R.string.drive_pp_together), stringResource(R.string.drive_pp_together_hint))
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    s.value.together.forEach { t ->
                                        val n = t.name ?: stringResource(R.string.drive_pp_no_name)
                                        PersonChip(t.id, t.name, "$n · ${countText(t.count)}", selected = with == t.id, onClick = { viewModel.setWith(t.id) })
                                    }
                                }
                            }
                            if (s.value.places.isNotEmpty()) {
                                PeopleHead(stringResource(R.string.drive_pp_places))
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    s.value.places.forEach { pl -> TextChip("${pl.name} · ${countText(pl.count)}") { onPlace(pl.key, id) } }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                },
            )
        }
    }
    if (naming) {
        NamePersonSheet(personId = id, onDismiss = { naming = false; viewModel.load() }, round = false, onDone = { into -> viewModel.renamed(into) })
    }
    if (merging) {
        PersonPicker(
            title = stringResource(R.string.drive_pp_merge_pick),
            people = all.filter { it.named },
            onPick = { other -> merging = false; viewModel.mergeInto(other.id) },
            onDismiss = { merging = false },
        )
    }
    if (linking) {
        LinkSheet(personId = id, onPick = { c -> linking = false; viewModel.link(c) }, onDismiss = { linking = false })
    }
    if (checking) {
        CheckFacesDialog(
            name = d?.person?.name,
            faces = uncertain,
            onAnswer = { face, yes -> face.faceId?.let { viewModel.confirm(listOf(it), yes) } },
            onDismiss = { checking = false },
        )
    }
    viewing?.let { start ->
        PhotoViewer(items = photos.items, start = start, fetch = viewModel::fetch, onMore = viewModel::more, onClose = { viewing = null })
    }
}

/** "Ist das auch Lea?": the unsure faces, "Ja, alle" or one by one. */
@Composable
private fun UncertainCard(p: DPerson, faces: List<DFaceRef>, total: Int, onAll: () -> Unit, onCheck: () -> Unit) {
    val x = GateControlTheme.extraColors
    val n = maxOf(total, faces.size)
    GcCard(color = x.warnBg, borderColor = androidx.compose.ui.graphics.Color.Transparent, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        Text(if (p.named) stringResource(R.string.drive_pp_is_also, p.name!!) else stringResource(R.string.drive_pp_is_same), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            faces.take(4).forEach { f -> FaceCrop(f.item, f.box, 52.dp, "?", x.panel2) }
            val more = n - faces.take(4).size
            if (more > 0) Box(Modifier.size(52.dp).clip(CircleShape).background(x.panel2), contentAlignment = Alignment.Center) {
                Text("+$more", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = x.muted)
            }
        }
        Text(pluralStringResource(R.plurals.drive_pp_uncertain_text, n, n), style = MaterialTheme.typography.bodySmall, color = x.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GcPrimaryButton(stringResource(R.string.drive_pp_yes_all), onClick = onAll, modifier = Modifier.weight(1f))
            GcOutlineButton(stringResource(R.string.drive_pp_one_by_one), onClick = onCheck, modifier = Modifier.weight(1f))
        }
    }
}

/** The unsure faces one by one: "Ja" assigns it, "Nein" never suggests it again. */
@Composable
private fun CheckFacesDialog(name: String?, faces: List<DFaceRef>, onAnswer: (DFaceRef, Boolean) -> Unit, onDismiss: () -> Unit) {
    val face = faces.firstOrNull()
    if (face == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (name != null) stringResource(R.string.drive_pp_check_title, name) else stringResource(R.string.drive_pp_is_same)) },
        text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FaceCrop(face.item, face.box, 160.dp, "?", GateControlTheme.extraColors.panel2)
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(face, true) }) { Text(stringResource(R.string.drive_pp_yes)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) }
                TextButton(onClick = { onAnswer(face, false) }) { Text(stringResource(R.string.drive_pp_no)) }
            }
        },
    )
}

/** Picks a person (to merge with). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonPicker(title: String, people: List<DPerson>, onPick: (DPerson) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            items(people, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onPick(p) }.padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PersonFace(p, 40.dp)
                    Text(p.name.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(countText(p.count), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
                }
            }
        }
    }
}

/** "Mit Kontakt verknüpfen": a family member or a mail contact the app already has. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkSheet(personId: Long, onPick: (DPersonContact) -> Unit, onDismiss: () -> Unit) {
    val vm: DriveNameViewModel = hiltViewModel(key = "link-$personId")
    val state by vm.state.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    LaunchedEffect(personId) { vm.load(personId) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.drive_pp_link_pick), style = MaterialTheme.typography.titleLarge)
            GcTextField(value = q, onValueChange = { q = it.take(60) }, label = stringResource(R.string.drive_pp_name_label))
            val list = remember(q, state.family, state.contacts) { DrivePlacesLogic.nameOptions(q, state.family, state.contacts, emptyMap(), max = 30) }
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                list.forEach { o -> NameOptionRow(o) { onPick(DPersonContact(o.kind, o.id, o.name)) } }
            }
        }
    }
}

/** A name of the family or the contacts. */
@Composable
private fun NameOptionRow(o: DrivePlacesLogic.NameOption, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    val kind = stringResource(if (o.kind == DrivePlacesJson.CONTACT_FAMILY) R.string.drive_pp_sub_family else R.string.drive_pp_sub_contact)
    val sub = listOfNotNull(
        kind,
        o.sub,
        o.namedCount?.let { pluralStringResource(R.plurals.drive_pp_named_already, it, it) },
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PersonDot(o.id.hashCode().toLong(), o.name, 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(o.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * "Wer ist das?": the person's faces, a name typed or picked from family and contacts, "Sieht aus wie …" to merge,
 * "Ausblenden"; then a short note with "Rückgängig". As a [round] (from the people and the Photos page) also
 * "Überspringen" and "Nächste Person": each person comes at most once, at the end "Alle durchgesehen".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NamePersonSheet(personId: Long, onDismiss: () -> Unit, round: Boolean, onDone: (Long?) -> Unit = {}) {
    val context = LocalContext.current
    val x = GateControlTheme.extraColors
    val vm: DriveNameViewModel = hiltViewModel(key = "name-$personId")
    val state by vm.state.collectAsStateWithLifecycle()
    // the person on the sheet now: in a round the next one after each "Nächste Person"
    val current = state.id ?: personId
    var name by rememberSaveable(current) { mutableStateOf("") }
    var picked by remember(current) { mutableStateOf<DrivePlacesLogic.NameOption?>(null) }
    LaunchedEffect(personId) { vm.start(personId) }
    LaunchedEffect(state.person?.person?.name) { if (name.isEmpty()) state.person?.person?.name?.let { name = it } }
    LaunchedEffect(state.error) {
        state.error?.let {
            Toast.makeText(context, peopleText(context, it), Toast.LENGTH_SHORT).show()
            vm.clearError()
        }
    }
    LaunchedEffect(state.done) { state.done?.let { onDone(it.into) } }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val done = state.done
            val detail = state.person
            if (state.finished) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(x.accentBg), contentAlignment = Alignment.Center) {
                        Icon(GcIcons.Check, contentDescription = null, tint = x.accentText, modifier = Modifier.size(26.dp))
                    }
                    Text(stringResource(R.string.drive_pp_all_seen_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.drive_pp_all_seen_text), style = MaterialTheme.typography.bodySmall, color = x.muted, textAlign = TextAlign.Center, modifier = Modifier.width(300.dp))
                    GcPrimaryButton(stringResource(R.string.drive_back), onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            } else if (done == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val p = detail?.person
                    if (p != null) PersonFace(p, 56.dp) else Box(Modifier.size(56.dp).clip(CircleShape).background(x.panel2))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.drive_pp_who), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        if (detail != null) {
                            val often = detail.together.firstOrNull { !it.name.isNullOrBlank() }?.name
                            Text(
                                listOfNotNull(
                                    pluralStringResource(R.plurals.drive_photos_count, detail.person.count, detail.person.count),
                                    DrivePlacesLogic.years(detail.yearFrom, detail.yearTo),
                                    often?.let { stringResource(R.string.drive_pp_often_with, it) },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = x.muted,
                            )
                        }
                    }
                }
                GcTextField(
                    value = name,
                    onValueChange = { name = it.take(DrivePlacesJson.NAME_MAX); picked = null },
                    label = stringResource(R.string.drive_pp_name_label),
                    placeholder = stringResource(R.string.drive_pp_name_example),
                )
                val options = remember(name, state.family, state.contacts, state.named) { vm.options(name) }
                if (options.isNotEmpty()) {
                    Column {
                        Text(stringResource(R.string.drive_pp_name_sugs).uppercase(), style = MaterialTheme.typography.labelSmall, color = x.muted, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                        options.forEach { o -> NameOptionRow(o) { name = o.name; picked = o } }
                    }
                }
                state.similar?.let { sim ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(x.panel2).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PersonDot(sim.id, sim.name, 44.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.drive_pp_looks_like, sim.name.orEmpty()), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.drive_pp_looks_like_hint), style = MaterialTheme.typography.bodySmall, color = x.muted)
                        }
                        TextButton(onClick = { vm.merge(current, sim) }, enabled = !state.busy) { Text(stringResource(R.string.drive_pp_merge_short)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GcOutlineButton(stringResource(R.string.drive_pp_hide), onClick = { vm.hide(current) }, modifier = Modifier.weight(1f), enabled = !state.busy)
                    GcPrimaryButton(stringResource(R.string.drive_save), onClick = { vm.save(current, name, picked) }, modifier = Modifier.weight(1f), enabled = !state.busy && name.isNotBlank())
                }
                if (round) {
                    TextButton(onClick = vm::next, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 44.dp)) {
                        Text(stringResource(R.string.drive_pp_skip), color = x.muted)
                    }
                }
                if (state.busy) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp)) }
            } else {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(x.accentBg), contentAlignment = Alignment.Center) {
                        Icon(GcIcons.Check, contentDescription = null, tint = x.accentText, modifier = Modifier.size(26.dp))
                    }
                    val title = when (done.what) {
                        "merge" -> stringResource(R.string.drive_pp_merged_title, done.name.orEmpty())
                        "hide" -> stringResource(R.string.drive_pp_hidden_title)
                        else -> stringResource(R.string.drive_pp_saved_title, done.name.orEmpty())
                    }
                    val text = when (done.what) {
                        "merge" -> pluralStringResource(R.plurals.drive_pp_merged_text, done.count, done.name.orEmpty(), done.count)
                        "hide" -> pluralStringResource(R.plurals.drive_pp_hidden_text, done.count, done.count)
                        else -> pluralStringResource(R.plurals.drive_pp_saved_text, done.count, done.count, done.name.orEmpty())
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(text, style = MaterialTheme.typography.bodySmall, color = x.muted, textAlign = TextAlign.Center, modifier = Modifier.width(300.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val undo = done.undo
                        if (undo != null) GcOutlineButton(stringResource(R.string.drive_snack_undo), onClick = { undo(); name = "" }, modifier = Modifier.weight(1f))
                        GcPrimaryButton(
                            stringResource(if (round) R.string.drive_pp_next else R.string.drive_pp_close),
                            onClick = { if (round) vm.next() else onDismiss() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** A message of the people pages as a sentence. */
internal fun peopleText(context: android.content.Context, code: String): String = when (code) {
    "people_merged" -> context.getString(R.string.drive_pp_merged)
    "people_deleted" -> context.getString(R.string.drive_pp_faces_deleted)
    "places_filling" -> context.getString(R.string.drive_pp_fill_started)
    "feature_off" -> context.getString(R.string.drive_pp_people_off)
    else -> if (code.startsWith("places_deleted:")) {
        val n = code.substringAfter(':').toIntOrNull() ?: 0
        context.resources.getQuantityString(R.plurals.drive_pp_places_deleted, n, n)
    } else {
        photosText(context, code)
    }
}
