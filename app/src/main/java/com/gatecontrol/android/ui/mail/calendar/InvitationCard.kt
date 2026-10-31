package com.gatecontrol.android.ui.mail.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MInvitation
import com.gatecontrol.android.mail.MOccurrence
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.mail.ErrorBox
import com.gatecontrol.android.ui.mail.mailContext
import com.gatecontrol.android.ui.mail.mailText
import com.gatecontrol.android.ui.mail.toast
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.update.currentLocale
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Where an invitation leads. */
data class InviteNav(
    val event: (eventId: Long) -> Unit,
    val contact: (contactId: Long) -> Unit,
    val saveContact: (email: String, name: String?) -> Unit,
)

/** The invitation of a mail above its text (contract D3 "Einladungskarte"). */
@Composable
fun MailInvitationCard(inv: MInvitation, nav: InviteNav, enabled: Boolean = true) {
    val viewModel: MailInviteViewModel = hiltViewModel(key = "invite-${inv.id}")
    LaunchedEffect(inv) { viewModel.set(inv) }
    InvitationBody(viewModel, nav, enabled)
}

/** An invitation opened from a push (a GateControl person's invitation). */
@Composable
fun MailInvitationScreen(onBack: () -> Unit, nav: InviteNav, viewModel: MailInviteViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = mailContext()
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(title = stringResource(R.string.cal_invitation), onBack = onBack, backDescription = stringResource(R.string.mail_back))
        when {
            state.invitation != null -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(state.invitation!!.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
                Text(state.invitation!!.organizer.label, style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(bottom = 12.dp))
                InvitationBody(viewModel, nav, true)
            }
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            else -> ErrorBox(mailText(context, state.error), onRetry = null)
        }
    }
}

@Composable
private fun InvitationBody(viewModel: MailInviteViewModel, nav: InviteNav, enabled: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cals by viewModel.calendars.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    val zone = viewModel.zone
    val inv = state.invitation ?: return
    LaunchedEffect(state.message) {
        state.message?.let {
            toast(context, if (it == "cal_answered") context.getString(R.string.cal_answered) else mailText(context, it))
            viewModel.clearMessage()
        }
    }
    val can = enabled && !offline && !state.busy
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GcCard(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.cal_invitation) + if (inv.title.isNotBlank()) ": " + inv.title else "", style = MaterialTheme.typography.labelMedium, color = extra.muted)
                Text(
                    dayText(MailPimLogic.date(inv.startDate) ?: Instant.ofEpochMilli(inv.start).atZone(zone).toLocalDate(), currentLocale()) + " · " +
                        whenText(inv.start, inv.end, inv.allDay, inv.startDate, inv.endDate, zone),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val sub = listOfNotNull(inv.location, (if (lang() == "en") inv.recurrenceEn else inv.recurrenceDe), inv.online?.let { providerName(it.provider) })
                if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
            when {
                inv.state == MInvitation.STATE_OUTDATED -> Text(stringResource(R.string.cal_inv_outdated), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                inv.method == MInvitation.METHOD_CANCEL -> {
                    Text(stringResource(R.string.cal_inv_cancelled), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    if (inv.state != MInvitation.STATE_APPLIED && inv.eventId != null) {
                        GcOutlineButton(stringResource(R.string.cal_inv_remove), onClick = viewModel::apply, enabled = can, loading = state.busy)
                    }
                }
                inv.method == MInvitation.METHOD_REPLY -> {
                    val who = inv.attendees.firstOrNull { it.email.equals(inv.from.email, true) }
                    Text(stringResource(R.string.cal_inv_reply, inv.from.label, responseText(who?.response).lowercase()), style = MaterialTheme.typography.bodyMedium)
                    inv.comment?.let { Text("„$it“", style = MaterialTheme.typography.bodySmall, color = extra.muted) }
                }
                inv.method == MInvitation.METHOD_COUNTER -> {
                    val ps = inv.proposedStart
                    val pe = inv.proposedEnd
                    Text(
                        stringResource(R.string.cal_inv_counter, inv.from.label) + if (ps != null && pe != null) ": " + whenText(ps, pe, false, null, null, zone) else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    inv.comment?.let { Text("„$it“", style = MaterialTheme.typography.bodySmall, color = extra.muted) }
                    if (inv.state == MInvitation.STATE_OPEN) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GcPrimaryButton(stringResource(R.string.cal_inv_take), onClick = viewModel::apply, enabled = can, modifier = Modifier.weight(1f))
                            GcOutlineButton(stringResource(R.string.cal_decline), onClick = { viewModel.dismiss(decline = true) }, enabled = can, modifier = Modifier.weight(1f))
                        }
                    }
                }
                else -> RequestPart(inv, state.calendarId, state.changing, can, viewModel, cals?.calendars.orEmpty().filter { it.id in inv.calendarChoices })
            }
        }
        if (inv.attendees.isNotEmpty() && inv.method != MInvitation.METHOD_REPLY) {
            val (yes, all) = MailPimLogic.acceptedOf(inv.attendees)
            GcCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.Top) {
                Text(stringResource(R.string.cal_attendees) + " · " + stringResource(R.string.cal_accepted_of, yes, all), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                PersonRow(inv.organizer.label, inv.organizer.email, stringResource(R.string.cal_resp_organizer), null) {
                    inv.organizer.contactId?.let(nav.contact) ?: nav.saveContact(inv.organizer.email, inv.organizer.name.ifBlank { null })
                }
                inv.attendees.filter { !it.email.equals(inv.organizer.email, true) }.forEach { a ->
                    PersonRow(a.label, a.email, responseText(a.response), a.response) { a.contactId?.let(nav.contact) ?: nav.saveContact(a.email, a.name.ifBlank { null }) }
                }
            }
        }
        inv.eventId?.let { id ->
            TextButton(onClick = { nav.event(id) }) { Text(stringResource(R.string.cal_inv_open_event)) }
        }
    }
}

/** A request: the small day grid, the answers (or "Du hast zugesagt · Ändern"), a comment, a new time, the calendar. */
@Composable
private fun RequestPart(inv: MInvitation, calendarId: Long?, changing: Boolean, can: Boolean, viewModel: MailInviteViewModel, choices: List<com.gatecontrol.android.mail.MCalendar>) {
    val extra = GateControlTheme.extraColors
    var comment by remember { mutableStateOf("") }
    var proposing by remember { mutableStateOf(false) }
    var pickCal by remember { mutableStateOf(false) }
    if (!inv.allDay) DayGrid(inv, viewModel.zone)
    if (inv.conflicts.isNotEmpty()) {
        Text(stringResource(R.string.cal_inv_conflicts, inv.conflicts.joinToString(", ") { it.title }), style = MaterialTheme.typography.bodySmall, color = extra.warn)
    }
    val answered = inv.myResponse != null && inv.myResponse != MOccurrence.RESPONSE_NEEDS_ACTION && inv.state != MInvitation.STATE_OPEN
    if (answered && !changing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cal_you_answered, responseText(inv.myResponse)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = viewModel::change, enabled = can) { Text(stringResource(R.string.cal_change)) }
        }
        return
    }
    ResponseButtons(inv.myResponse, can) { viewModel.respond(it, comment.ifBlank { null }) }
    GcTextField(comment, { comment = it.take(2000) }, stringResource(R.string.cal_comment), placeholder = stringResource(R.string.cal_comment_hint), singleLine = false)
    TextButton(onClick = { proposing = true }, enabled = can, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.cal_inv_propose), color = extra.accentText, fontWeight = FontWeight.SemiBold)
    }
    if (choices.isNotEmpty()) {
        val current = choices.firstOrNull { it.id == calendarId } ?: choices.first()
        Box {
            Text(
                stringResource(R.string.cal_inv_into, current.name),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.clickable(role = Role.DropdownList, enabled = choices.size > 1) { pickCal = true }.padding(vertical = 6.dp),
            )
            DropdownMenu(expanded = pickCal, onDismissRequest = { pickCal = false }) {
                choices.forEach { c ->
                    DropdownMenuItem(text = { Text(c.name) }, onClick = { pickCal = false; viewModel.pickCalendar(c.id) })
                }
            }
        }
    }
    if (proposing) ProposeDialog(inv, viewModel.zone, onDismiss = { proposing = false }) { s, e ->
        proposing = false
        viewModel.respond(MOccurrence.RESPONSE_TENTATIVE, comment.ifBlank { null }, s, e)
    }
}

/** "Neue Zeit vorschlagen": a day, start and end; sent as a tentative answer with the proposal. */
@Composable
private fun ProposeDialog(inv: MInvitation, zone: ZoneId, onDismiss: () -> Unit, onPropose: (Long, Long) -> Unit) {
    val s0 = MailPimLogic.local(inv.start, zone)
    val e0 = MailPimLogic.local(inv.end, zone)
    var day by remember { mutableStateOf(s0.toLocalDate()) }
    var start by remember { mutableStateOf(s0.toLocalTime()) }
    var end by remember { mutableStateOf(e0.toLocalTime()) }
    val locale = currentLocale()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cal_inv_propose)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProposeRow(stringResource(R.string.cal_start), day, start, locale) { d, t -> day = d; t?.let { start = it } }
                ProposeRow(stringResource(R.string.cal_end), day, end, locale) { d, t -> day = d; t?.let { end = it } }
            }
        },
        confirmButton = {
            val a = MailPimLogic.ms(day, start, zone)
            val b = MailPimLogic.ms(day, end, zone)
            TextButton(enabled = b > a, onClick = { onPropose(a, b) }) { Text(stringResource(R.string.cal_inv_propose_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mail_cancel)) } },
    )
}

@Composable
private fun ProposeRow(label: String, day: LocalDate, time: LocalTime, locale: java.util.Locale, onPick: (LocalDate, LocalTime?) -> Unit) {
    // the pickers of the event form
    DateTimeRow(label, day, time, locale, onPick)
}

/** The day around the invitation (± 2 h): the own events and the invitation dashed. */
@Composable
private fun DayGrid(inv: MInvitation, zone: ZoneId) {
    val extra = GateControlTheme.extraColors
    val first = Instant.ofEpochMilli(inv.start).atZone(zone).let { (it.hour - 1).coerceAtLeast(0) }
    val last = Instant.ofEpochMilli((inv.end - 1).coerceAtLeast(inv.start)).atZone(zone).let { (it.hour + 2).coerceAtMost(24) }
    val hours = (last - first).coerceIn(2, 8)
    val slot = 30.dp
    val day = Instant.ofEpochMilli(inv.start).atZone(zone).toLocalDate()
    val base = day.atStartOfDay(zone).plusHours(first.toLong()).toInstant().toEpochMilli()
    val dashColor = calColor("#79B8FF")
    fun y(ms: Long) = slot * (((ms - base) / 60_000f) / 60f).coerceIn(0f, hours.toFloat())
    BoxWithConstraints(Modifier.fillMaxWidth().height(slot * hours)) {
        (0 until hours).forEach { h ->
            Box(Modifier.offset(y = slot * h).fillMaxWidth().height(1.dp).background(extra.border))
            Text((first + h).toString(), fontSize = 11.sp, color = extra.faint, modifier = Modifier.offset(y = slot * h + 2.dp))
        }
        inv.day.filter { !it.allDay }.forEach { o ->
            val top = y(o.start)
            val h = (y(o.end) - top).coerceAtLeast(14.dp)
            Box(
                Modifier.offset(x = 40.dp, y = top).width(maxWidth - 48.dp).height(h).clip(RoundedCornerShape(6.dp))
                    .background(calColor("#46E0A0").copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 2.dp),
            ) { Text(o.title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        val top = y(inv.start)
        Box(
            Modifier.offset(x = 40.dp, y = top).width(maxWidth - 48.dp).height((y(inv.end) - top).coerceAtLeast(16.dp))
                .drawBehind {
                    drawRoundRect(dashColor, cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
                }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) { Text(inv.title + " " + stringResource(R.string.cal_inv_new), fontSize = 11.sp, color = dashColor, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** The band of a mail whose Microsoft account lacks the calendar right: "Kalender freigeben" (sign in again). */
@Composable
fun InviteConsentCard(account: String, onRelogin: () -> Unit) {
    GcCard(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.Icon(GcIcons.Calendar, contentDescription = null, tint = GateControlTheme.extraColors.muted, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.cal_inv_consent, account), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        GcOutlineButton(stringResource(R.string.cal_inv_consent_do), onClick = onRelogin, icon = GcIcons.Key)
    }
}
