package com.gatecontrol.android.ui.familie

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamiliePlace
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.locate.FamiliePermissions.GeofenceReadiness
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * Orts-Erinnerungen (mockup AppPlaces): my reminders, family reminders
 * (with consent), the device's state (Play services, location "all the
 * time"). The phone checks the places itself; only the reminder leaves it.
 */
@Composable
fun PlacesScreen(
    onBack: () -> Unit,
    onEdit: (Int?) -> Unit,
    viewModel: PlacesViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val extra = GateControlTheme.extraColors
    val messageText = message?.let { familieErrorText(it) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            Toast.makeText(context, messageText, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }
    val startPermission = rememberLocationPermissionFlow(
        background = true,
        foregroundText = R.string.familie_perm_places_text,
        backgroundText = R.string.familie_perm_places_bg_text,
        onResult = { viewModel.permissionsChanged() },
    )

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(title = stringResource(R.string.familie_places_title), onBack = onBack, backDescription = stringResource(R.string.common_back)) {
            GcIconButton(GcIcons.Plus, contentDescription = stringResource(R.string.familie_place_new), onClick = { onEdit(null) }, tint = MaterialTheme.colorScheme.primary)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GcBanner(tone = GcTone.Info, icon = GcIcons.Lock) {
                Text(stringResource(R.string.familie_places_info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
            when (readiness) {
                GeofenceReadiness.UNAVAILABLE -> GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                    Text(stringResource(R.string.familie_places_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
                GeofenceReadiness.NEED_LOCATION, GeofenceReadiness.NEED_BACKGROUND -> {
                    GcBanner(tone = GcTone.Warn, icon = GcIcons.Pin) {
                        Text(stringResource(R.string.familie_places_need_permission), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                    GcPrimaryButton(text = stringResource(R.string.familie_perm_grant), onClick = startPermission, icon = GcIcons.Pin)
                }
                GeofenceReadiness.READY -> Unit
            }

            val p = profile
            val list = places
            when {
                list == null && error != null -> FamilieErrorBanner(error, onRetry = viewModel::refresh)
                list == null || p == null -> FamilieLoading()
                else -> {
                    if (error != null) FamilieErrorBanner(error)
                    val me = p.me.userId
                    val pending = list.filter { it.targetUserId == me && it.ownerId != me && it.consent == FamiliePlace.CONSENT_PENDING }
                    pending.forEach { place -> ConsentCard(place, p, onAnswer = { viewModel.consent(place.id, it) }) }

                    val mine = list.filter { it.ownerId == me && it.targetUserId == me }
                    val family = list.filter { it.ownerId != it.targetUserId && it !in pending }
                    if (mine.isEmpty() && family.isEmpty() && pending.isEmpty()) {
                        FamilieEmpty(GcIcons.Pin, stringResource(R.string.familie_places_none))
                    }
                    if (mine.isNotEmpty()) {
                        GcSectionLabel(stringResource(R.string.familie_places_mine), Modifier.padding(start = 4.dp))
                        PlaceList(mine, p, readiness, onEdit)
                    }
                    if (family.isNotEmpty()) {
                        GcSectionLabel(stringResource(R.string.familie_places_family), Modifier.padding(start = 4.dp, top = 4.dp))
                        PlaceList(family, p, readiness, onEdit)
                    }
                    GcOutlineButton(text = stringResource(R.string.familie_place_new), onClick = { onEdit(null) }, icon = GcIcons.Plus)
                    Text(stringResource(R.string.familie_places_limit), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ConsentCard(place: FamiliePlace, p: FamilieProfile, onAnswer: (Boolean) -> Unit) {
    val owner = (place.ownerName ?: memberName(p, place.ownerId))
    GcCard(borderColor = MaterialTheme.colorScheme.primary) {
        Text(stringResource(R.string.familie_place_consent_title, owner, place.title), style = MaterialTheme.typography.titleMedium)
        Text(placeSummary(place, p), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
        Text(stringResource(R.string.familie_place_consent_text), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GcOutlineButton(text = stringResource(R.string.familie_action_decline), onClick = { onAnswer(false) }, modifier = Modifier.weight(1f))
            GcPrimaryButton(text = stringResource(R.string.familie_action_accept), onClick = { onAnswer(true) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PlaceList(list: List<FamiliePlace>, p: FamilieProfile, readiness: GeofenceReadiness, onEdit: (Int?) -> Unit) {
    val extra = GateControlTheme.extraColors
    GcCard(contentPadding = PaddingValues(vertical = 2.dp), verticalArrangement = Arrangement.Top) {
        list.forEachIndexed { i, place ->
            if (i > 0) GcRowDivider()
            val mineToWatch = place.targetUserId == p.me.userId
            val notHere = mineToWatch && readiness == GeofenceReadiness.UNAVAILABLE
            GcListRow(
                title = place.title,
                description = listOfNotNull(
                    placeSummary(place, p),
                    familyLine(place, p),
                    if (!place.active) stringResource(R.string.familie_place_inactive) else null,
                    if (notHere) stringResource(R.string.familie_place_not_on_device) else null,
                ).joinToString(" · "),
                leading = { Icon(actionIcon(place.action), contentDescription = null, tint = if (place.active) extra.accentText else extra.faint, modifier = Modifier.size(22.dp)) },
                onClick = { onEdit(place.id) },
            )
        }
    }
}

private fun actionIcon(action: String): ImageVector = when (action) {
    FamiliePlace.ACTION_LIST -> GcIcons.Cart
    FamiliePlace.ACTION_TELL -> GcIcons.Bell
    else -> GcIcons.Pin
}

/** "Beim Ankommen · 150 m · jedes Mal" */
@Composable
fun placeSummary(place: FamiliePlace, p: FamilieProfile?): String {
    val trigger = stringResource(if (place.trigger == FamiliePlace.TRIGGER_EXIT) R.string.familie_place_on_exit else R.string.familie_place_on_enter)
    val repeat = stringResource(if (place.repeat == "once") R.string.familie_place_once else R.string.familie_place_always)
    val action = when (place.action) {
        FamiliePlace.ACTION_LIST -> stringResource(R.string.familie_place_action_list)
        FamiliePlace.ACTION_TELL -> stringResource(
            R.string.familie_place_action_tell_who,
            place.tellUserIds.joinToString(", ") { memberName(p, it) }.ifBlank { "–" },
        )
        else -> place.note.ifBlank { stringResource(R.string.familie_place_action_note) }
    }
    return "$trigger · ${place.radius} m · $repeat · $action"
}

@Composable
private fun familyLine(place: FamiliePlace, p: FamilieProfile): String? {
    if (place.ownerId == place.targetUserId) return null
    val consent = when (place.consent) {
        FamiliePlace.CONSENT_ACCEPTED -> stringResource(R.string.familie_place_consent_accepted)
        FamiliePlace.CONSENT_DECLINED -> stringResource(R.string.familie_place_consent_declined)
        else -> stringResource(R.string.familie_place_consent_pending)
    }
    return if (place.ownerId == p.me.userId) {
        stringResource(R.string.familie_place_for, (place.targetName ?: memberName(p, place.targetUserId))) + " · " + consent
    } else {
        stringResource(R.string.familie_place_from, (place.ownerName ?: memberName(p, place.ownerId))) + " · " + consent
    }
}
