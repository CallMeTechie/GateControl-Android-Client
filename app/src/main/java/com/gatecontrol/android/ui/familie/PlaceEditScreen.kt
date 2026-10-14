package com.gatecontrol.android.ui.familie

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamiliePlace
import com.gatecontrol.android.familie.PlaceDraft
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlin.math.roundToInt

/** Radius steps of the slider (50 m … 2 km). */
private val RADII = listOf(50, 75, 100, 150, 200, 300, 500, 750, 1000, 1500, 2000)

/**
 * Orts-Erinnerung anlegen / bearbeiten (mockup AppPlaceEdit): place by
 * current position, address search (Android Geocoder) or a tap on the map;
 * radius, arrive / leave, what happens (note, shopping list, tell someone),
 * for whom (a family member must agree), once or every time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceEditScreen(
    placeId: Int?,
    onDone: () -> Unit,
    viewModel: PlacesViewModel = hiltViewModel(key = "place-edit-${placeId ?: "new"}"),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val hits by viewModel.searchHits.collectAsStateWithLifecycle()
    val here by viewModel.here.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val extra = GateControlTheme.extraColors
    val locale = appLocale()
    val messageText = message?.let { familieErrorText(it) }

    LaunchedEffect(saved) { if (saved) onDone() }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            Toast.makeText(context, messageText, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    val existing = places?.firstOrNull { it.id == placeId }
    if (placeId != null && existing == null) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            GcSubpageBar(title = stringResource(R.string.familie_place_edit), onBack = onDone, backDescription = stringResource(R.string.common_back))
            if (places == null) FamilieLoading() else FamilieEmpty(GcIcons.Pin, stringResource(R.string.familie_error_not_found))
        }
        return
    }
    val p = profile
    val me = p?.me?.userId
    // Only the owner edits; the target may still delete it ("nicht mehr erinnern").
    val readOnly = existing != null && existing.ownerId != me

    var title by rememberSaveable { mutableStateOf(existing?.title.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(existing?.note.orEmpty()) }
    var lat by rememberSaveable { mutableStateOf(existing?.lat) }
    var lon by rememberSaveable { mutableStateOf(existing?.lon) }
    var radius by rememberSaveable { mutableIntStateOf(existing?.radius ?: 150) }
    var trigger by rememberSaveable { mutableStateOf(existing?.trigger ?: FamiliePlace.TRIGGER_ENTER) }
    var action by rememberSaveable { mutableStateOf(existing?.action ?: FamiliePlace.ACTION_NOTE) }
    var listId by rememberSaveable { mutableStateOf(existing?.listId) }
    var tell by rememberSaveable { mutableStateOf(existing?.tellUserIds ?: emptyList()) }
    var repeat by rememberSaveable { mutableStateOf(existing?.repeat ?: "always") }
    var active by rememberSaveable { mutableStateOf(existing?.active ?: true) }
    var target by rememberSaveable { mutableStateOf(existing?.targetUserId ?: me) }
    var askDelete by remember { mutableStateOf(false) }

    LaunchedEffect(here) {
        here?.let {
            lat = it.lat
            lon = it.lon
            viewModel.consumeHere()
        }
    }

    val valid = title.isNotBlank() && lat != null && lon != null &&
        (action != FamiliePlace.ACTION_LIST || listId != null) &&
        (action != FamiliePlace.ACTION_TELL || tell.isNotEmpty())

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(
            title = stringResource(if (existing == null) R.string.familie_place_new else R.string.familie_place_edit),
            onBack = onDone,
            backDescription = stringResource(R.string.common_back),
        ) {
            if (!readOnly) {
                TextButton(
                    onClick = {
                        val la = lat ?: return@TextButton
                        val lo = lon ?: return@TextButton
                        viewModel.save(
                            existing?.id,
                            PlaceDraft(
                                title = title,
                                note = note,
                                lat = la,
                                lon = lo,
                                radius = radius,
                                trigger = trigger,
                                action = action,
                                listId = listId,
                                tellUserIds = tell,
                                repeat = repeat,
                                active = active,
                                targetUserId = target,
                            ),
                        )
                    },
                    enabled = valid && !saving,
                ) { Text(stringResource(R.string.familie_save)) }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (readOnly) {
                Text(
                    stringResource(R.string.familie_place_readonly, memberName(p, existing.ownerId)),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                )
            }
            GcTextField(value = title, onValueChange = { title = it.take(80) }, label = stringResource(R.string.familie_place_title_label), placeholder = stringResource(R.string.familie_place_title_hint), enabled = !readOnly)

            // --- Ort -------------------------------------------------------
            FamiliePlacePicker(
                lat = lat,
                lon = lon,
                radiusM = radius,
                hits = hits,
                readOnly = readOnly,
                onPick = { la, lo, label ->
                    lat = la
                    lon = lo
                    if (label != null && title.isBlank()) title = label.substringBefore(',').take(80)
                },
                onHere = viewModel::locateHere,
                onSearch = { viewModel.search(it, locale) },
                onClearHits = viewModel::clearHits,
            )

            // --- Umkreis ---------------------------------------------------
            val idx = RADII.indexOfFirst { it >= radius }.let { if (it < 0) RADII.lastIndex else it }
            Text(stringResource(R.string.familie_place_radius, radius), style = MaterialTheme.typography.titleSmall, color = extra.muted)
            Slider(
                value = idx.toFloat(),
                onValueChange = { radius = RADII[it.roundToInt().coerceIn(0, RADII.lastIndex)] },
                valueRange = 0f..RADII.lastIndex.toFloat(),
                steps = RADII.size - 2,
                enabled = !readOnly,
            )

            // --- Wann ------------------------------------------------------
            Text(stringResource(R.string.familie_place_when), style = MaterialTheme.typography.titleSmall, color = extra.muted)
            GcSegmented(
                options = listOf(
                    FamiliePlace.TRIGGER_ENTER to stringResource(R.string.familie_place_on_enter),
                    FamiliePlace.TRIGGER_EXIT to stringResource(R.string.familie_place_on_exit),
                ),
                selected = trigger,
                onSelect = { if (!readOnly) trigger = it },
            )
            GcSegmented(
                options = listOf("always" to stringResource(R.string.familie_place_always), "once" to stringResource(R.string.familie_place_once)),
                selected = repeat,
                onSelect = { if (!readOnly) repeat = it },
            )

            // --- Was -------------------------------------------------------
            Text(stringResource(R.string.familie_place_what), style = MaterialTheme.typography.titleSmall, color = extra.muted)
            GcSegmented(
                options = listOf(
                    FamiliePlace.ACTION_NOTE to stringResource(R.string.familie_place_action_note),
                    FamiliePlace.ACTION_LIST to stringResource(R.string.familie_place_action_list),
                    FamiliePlace.ACTION_TELL to stringResource(R.string.familie_place_action_tell),
                ),
                selected = action,
                onSelect = { if (!readOnly) action = it },
            )
            when (action) {
                FamiliePlace.ACTION_LIST -> {
                    if (lists.isEmpty()) {
                        Text(stringResource(R.string.familie_place_no_lists), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        lists.forEach { l -> GcFilterChip(text = l.name, selected = l.id == listId, onClick = { if (!readOnly) listId = l.id }) }
                    }
                }
                FamiliePlace.ACTION_TELL -> {
                    Text(stringResource(R.string.familie_place_tell_who), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        p?.everyone?.filter { it.userId != target }?.forEach { m ->
                            GcFilterChip(text = m.name, selected = m.userId in tell, onClick = {
                                if (!readOnly) tell = if (m.userId in tell) tell - m.userId else tell + m.userId
                            })
                        }
                    }
                }
                else -> Unit
            }
            GcTextField(
                value = note,
                onValueChange = { note = it.take(300) },
                label = stringResource(R.string.familie_place_note),
                placeholder = stringResource(R.string.familie_place_note_hint),
                singleLine = false,
                enabled = !readOnly,
            )

            // --- Für wen ---------------------------------------------------
            if (p != null && p.everyone.size > 1) {
                Text(stringResource(R.string.familie_place_for_whom), style = MaterialTheme.typography.titleSmall, color = extra.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.everyone.forEach { m ->
                        GcFilterChip(
                            text = if (m.userId == me) stringResource(R.string.familie_me) else m.name,
                            selected = m.userId == target,
                            onClick = { if (!readOnly) target = m.userId },
                        )
                    }
                }
                if (target != me) {
                    Text(stringResource(R.string.familie_place_needs_consent, memberName(p, target)), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            }
            if (!readOnly) {
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                    GcSwitchRow(label = stringResource(R.string.familie_place_active), description = null, checked = active, onCheckedChange = { active = it })
                }
            }
            if (existing != null) {
                GcOutlineButton(
                    text = stringResource(if (readOnly) R.string.familie_place_remove_me else R.string.familie_delete),
                    onClick = { askDelete = true },
                    icon = GcIcons.Trash,
                )
            }
        }
    }

    if (askDelete && existing != null) {
        FamilieConfirmDialog(
            title = stringResource(R.string.familie_delete),
            text = stringResource(R.string.familie_place_delete_text, existing.title),
            confirmLabel = stringResource(R.string.familie_delete),
            destructive = true,
            onConfirm = {
                askDelete = false
                viewModel.delete(existing.id)
            },
            onDismiss = { askDelete = false },
        )
    }
}
