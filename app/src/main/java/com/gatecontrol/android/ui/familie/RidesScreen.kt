package com.gatecontrol.android.ui.familie

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieMe
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.rides.Ride
import com.gatecontrol.android.familie.rides.RideDriver
import com.gatecontrol.android.familie.rides.RideLogic
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.LocalDateTime

/** Which form is open: a new ride (optionally asking right away), a child's request, or an edit. */
private sealed interface RideForm {
    data class New(val ask: Boolean) : RideForm

    data object Request : RideForm

    data class Edit(val ride: Ride) : RideForm
}

/**
 * Abholen & Fahrten (mockup AppPickup): "Offen" — rides somebody asked for
 * or that start within a day — with "Ich mache es" / "Kann nicht", my
 * rides with "Rückgängig", then this week's rides. Parents create and edit
 * rides (one-off or weekly); children ask "Bitte abholen". [highlight] is
 * the ride a notification was about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RidesScreen(onBack: () -> Unit, highlight: String? = null, viewModel: RidesViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val rides by viewModel.rides.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val offerPlace by viewModel.offerPlace.collectAsStateWithLifecycle()
    val askedAt by viewModel.askedAt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var form by remember { mutableStateOf<RideForm?>(null) }
    var confirmDelete by remember { mutableStateOf<Pair<Ride, Boolean>?>(null) }

    val noticeText = when (val n = notice) {
        is RidesNotice.Error -> familieErrorText(n.code)
        RidesNotice.Asked -> stringResource(R.string.familie_ride_asked)
        RidesNotice.Requested -> stringResource(R.string.familie_ride_requested)
        is RidesNotice.PlaceCreated -> stringResource(if (n.needsPermission) R.string.familie_ride_place_created_perm else R.string.familie_ride_place_created)
        null -> null
    }
    LaunchedEffect(noticeText) {
        if (noticeText != null) {
            Toast.makeText(context, noticeText, Toast.LENGTH_LONG).show()
            viewModel.clearNotice()
        }
    }

    val p = profile
    val me = p?.me
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(title = stringResource(R.string.familie_rides_title), onBack = onBack, backDescription = stringResource(R.string.common_back)) {
            if (me != null && RideLogic.canEdit(me)) {
                GcIconButton(GcIcons.Plus, contentDescription = stringResource(R.string.familie_ride_new), onClick = { form = RideForm.New(ask = false) }, tint = MaterialTheme.colorScheme.primary)
            }
        }
        PullToRefreshBox(isRefreshing = busy && rides != null, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (me != null && !me.isParent) {
                    GcPrimaryButton(text = stringResource(R.string.familie_ride_request), onClick = { form = RideForm.Request }, icon = GcIcons.Car)
                }
                val list = rides
                when {
                    list == null && error != null -> FamilieErrorBanner(error, onRetry = viewModel::refresh)
                    list == null || p == null || me == null -> FamilieLoading()
                    else -> {
                        if (error != null) FamilieErrorBanner(error)
                        val sections = RideLogic.sections(list, me, LocalDateTime.now())
                        if (sections.attention.isNotEmpty()) {
                            GcSectionLabel(stringResource(R.string.familie_rides_open_section), Modifier.padding(start = 4.dp, top = 4.dp))
                            sections.attention.forEach { ride ->
                                AttentionCard(
                                    ride = ride,
                                    p = p,
                                    highlighted = ride.id == highlight,
                                    askedAtMs = askedAt[ride.id] ?: FamilieFormat.parseInstant(ride.askedAt)?.toEpochMilli(),
                                    viewModel = viewModel,
                                    onEdit = { form = RideForm.Edit(ride) },
                                )
                            }
                        }
                        GcSectionLabel(stringResource(R.string.familie_rides_week), Modifier.padding(start = 4.dp, top = 4.dp))
                        if (sections.week.isEmpty() && sections.attention.isEmpty()) {
                            FamilieEmpty(GcIcons.Car, stringResource(R.string.familie_rides_none))
                        } else if (sections.week.isNotEmpty()) {
                            GcCard(contentPadding = PaddingValues(vertical = 2.dp), verticalArrangement = Arrangement.Top) {
                                sections.week.forEachIndexed { i, ride ->
                                    if (i > 0) GcRowDivider()
                                    WeekRow(ride, p, highlighted = ride.id == highlight, onTake = { viewModel.take(ride) }, onEdit = { form = RideForm.Edit(ride) })
                                }
                            }
                        }
                        if (RideLogic.canEdit(me)) {
                            AskButton(stringResource(R.string.familie_ride_ask), onClick = { form = RideForm.New(ask = true) })
                        }
                    }
                }
            }
        }
    }

    if (p != null) {
        when (val f = form) {
            is RideForm.New -> RideEditDialog(
                profile = p,
                ride = null,
                askDefault = f.ask,
                viewModel = viewModel,
                onDismiss = { form = null },
                onSave = { draft, _, _ ->
                    viewModel.create(draft)
                    form = null
                },
                onDelete = null,
            )
            RideForm.Request -> RideRequestDialog(
                profile = p,
                viewModel = viewModel,
                onDismiss = { form = null },
                onSave = { draft ->
                    viewModel.create(draft)
                    form = null
                },
            )
            is RideForm.Edit -> RideEditDialog(
                profile = p,
                ride = f.ride,
                askDefault = false,
                viewModel = viewModel,
                onDismiss = { form = null },
                onSave = { draft, series, driver ->
                    viewModel.update(f.ride, draft, series, driver)
                    form = null
                },
                onDelete = { series ->
                    confirmDelete = f.ride to series
                    form = null
                },
            )
            null -> Unit
        }
    }

    confirmDelete?.let { (ride, series) ->
        FamilieConfirmDialog(
            title = stringResource(if (series) R.string.familie_ride_delete_series else R.string.familie_ride_cancel_one),
            text = stringResource(if (series) R.string.familie_ride_series_text else R.string.familie_ride_delete_text, ride.title),
            confirmLabel = stringResource(if (series) R.string.familie_delete else R.string.familie_ride_cancel_one),
            destructive = true,
            onConfirm = {
                viewModel.delete(ride, series)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }

    val offer = offerPlace
    if (offer != null && p != null) {
        val note = stringResource(R.string.familie_ride_place_note, childNames(offer, p).ifBlank { offer.title })
        FamilieConfirmDialog(
            title = stringResource(R.string.familie_ride_place_offer_title),
            text = stringResource(R.string.familie_ride_place_offer_text, offer.place.ifBlank { offer.title }, note),
            confirmLabel = stringResource(R.string.familie_ride_place_offer_yes),
            onConfirm = { viewModel.createPlaceReminder(offer, note) },
            onDismiss = viewModel::dismissPlaceOffer,
        )
    }
}

/** "Tim, Lena" — the members a ride is for. */
fun childNames(ride: Ride, p: FamilieProfile): String = ride.childIds.mapNotNull { p.nameOf(it) }.joinToString(", ")

/** "heute 16:00 · Sporthalle Nord". */
@Composable
fun rideWhen(ride: Ride): String = listOf(relativeDay(ride.date) + " " + ride.time, ride.place).filter { it.isNotBlank() }.joinToString(" · ")

/** Who drives, as a line ("Anna fährt", "du fährst", "noch niemand eingetragen", "abgesagt"). */
@Composable
fun rideDriverLine(ride: Ride, me: FamilieMe): String = when {
    ride.isCancelled -> stringResource(R.string.familie_ride_cancelled)
    ride.driverId == me.userId -> stringResource(R.string.familie_ride_you_drive_plain)
    else -> when (val d = ride.driver) {
        is RideDriver.Member -> stringResource(R.string.familie_ride_driver_member, d.name)
        is RideDriver.Label -> stringResource(R.string.familie_ride_driver_member, d.label)
        null -> stringResource(R.string.familie_ride_nobody)
    }
}

@Composable
private fun AttentionCard(
    ride: Ride,
    p: FamilieProfile,
    highlighted: Boolean,
    askedAtMs: Long?,
    viewModel: RidesViewModel,
    onEdit: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val me = p.me
    val mine = ride.driverId == me.userId
    val firstChild = ride.childIds.firstOrNull()?.let { p.member(it) ?: p.everyone.firstOrNull { m -> m.userId == it } }
    GcCard(
        borderColor = when {
            highlighted -> MaterialTheme.colorScheme.primary
            mine -> extra.accentText
            else -> extra.warn
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FamilieAvatar(firstChild?.name ?: ride.title, firstChild?.color, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ride.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onSurface)
                val meta = listOfNotNull(
                    rideWhen(ride),
                    ride.requestedBy?.takeIf { it.userId != me.userId }?.let { stringResource(R.string.familie_ride_meta_requested, it.name) },
                ).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
            if (RideLogic.canEdit(me)) {
                GcIconButton(GcIcons.Edit, contentDescription = stringResource(R.string.familie_ride_edit_cd, ride.title), onClick = onEdit, iconSize = 18.dp)
            }
        }
        if (ride.isOpen) {
            val canTake = RideLogic.canTake(ride, me)
            val canDecline = RideLogic.canDecline(ride, me)
            if (canTake || canDecline) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canTake) GcPrimaryButton(text = stringResource(R.string.familie_ride_take), onClick = { viewModel.take(ride) }, modifier = Modifier.weight(1f))
                    if (canDecline) GcOutlineButton(text = stringResource(R.string.familie_ride_decline), onClick = { viewModel.decline(ride) }, modifier = Modifier.weight(1f))
                }
            }
            val status = listOfNotNull(
                ride.declined.takeIf { it.isNotEmpty() }?.let { list -> stringResource(R.string.familie_ride_declined_by, list.joinToString(", ") { it.name }) },
                ride.askedAt?.let { stringResource(R.string.familie_ride_asked_at, relativeStamp(it)) },
            ).joinToString(" · ")
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = extra.muted)
            if (RideLogic.canAsk(ride, System.currentTimeMillis(), askedAtMs)) {
                TextButton(onClick = { viewModel.ask(ride) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(
                        stringResource(if (ride.askedAt != null) R.string.familie_ride_ask_again else R.string.familie_ride_ask),
                        color = extra.accentText,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        } else if (mine) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(extra.accentBg).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(GcIcons.Check, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.familie_ride_you_drive, childNames(ride, p).ifBlank { ride.title }),
                    style = MaterialTheme.typography.titleSmall,
                    color = extra.accentText,
                    modifier = Modifier.weight(1f),
                )
                if (RideLogic.canRelease(ride, me)) {
                    TextButton(onClick = { viewModel.release(ride) }) {
                        Text(stringResource(R.string.familie_ride_release), color = extra.accentText, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            RideLogic.reminderTime(ride)?.let {
                Text(stringResource(R.string.familie_ride_reminder_at, it), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
        if (ride.note.isNotBlank()) Text(ride.note, style = MaterialTheme.typography.bodySmall, color = extra.muted)
    }
}

@Composable
private fun WeekRow(ride: Ride, p: FamilieProfile, highlighted: Boolean, onTake: () -> Unit, onEdit: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val me = p.me
    val day = FamilieFormat.parseDate(ride.date)
    val names = weekdayNames()
    val editable = RideLogic.canEdit(me) && !ride.isCancelled
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (highlighted) Modifier.background(extra.accentBg) else Modifier)
            .then(if (editable) Modifier.clickable(role = Role.Button, onClick = onEdit) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            day?.let { names[it.dayOfWeek.value - 1] } ?: ride.date,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = extra.muted,
            modifier = Modifier.width(42.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                ride.title + " " + ride.time,
                style = MaterialTheme.typography.titleSmall.copy(textDecoration = if (ride.isCancelled) TextDecoration.LineThrough else null),
                color = if (ride.isCancelled) extra.muted else MaterialTheme.colorScheme.onSurface,
            )
            val nobody = ride.isOpen && ride.driver == null
            Text(
                listOfNotNull(
                    rideDriverLine(ride, me),
                    ride.place.takeIf { it.isNotBlank() },
                    if (ride.isSeries) stringResource(R.string.familie_ride_series_badge) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (nobody) FontWeight.SemiBold else null),
                color = if (nobody) extra.warn else extra.muted,
            )
        }
        if (RideLogic.canTake(ride, me)) {
            GcOutlineButton(
                text = stringResource(R.string.familie_ride_take_short),
                onClick = onTake,
                fillWidth = false,
                minHeight = 36.dp,
            )
        }
    }
}

/** The mockup's "Wer kann …? fragen" button under the week. */
@Composable
private fun AskButton(text: String, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .border(1.dp, extra.border2, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(GcIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
