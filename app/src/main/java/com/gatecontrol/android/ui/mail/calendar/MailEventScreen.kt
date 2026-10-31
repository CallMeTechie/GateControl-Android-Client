package com.gatecontrol.android.ui.mail.calendar

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAttendee
import com.gatecontrol.android.mail.MEvent
import com.gatecontrol.android.mail.MOccurrence
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.mail.ErrorBox
import com.gatecontrol.android.ui.mail.MailHtmlView
import com.gatecontrol.android.ui.mail.mailContext
import com.gatecontrol.android.ui.mail.mailText
import com.gatecontrol.android.ui.mail.openExternal
import com.gatecontrol.android.ui.mail.openLink
import com.gatecontrol.android.ui.mail.toast
import com.gatecontrol.android.ui.mail.contacts.ContactAvatar
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.update.currentLocale
import java.time.Instant

/** Where an event leads. */
data class EventNav(
    val back: () -> Unit,
    /** "Bearbeiten" (the occurrence; the edit asks this one or the series before saving). */
    val edit: (eventId: Long, occurrence: String?) -> Unit,
    /** An attendee or the organizer: their contact, else "Als Kontakt speichern". */
    val contact: (contactId: Long) -> Unit,
    val saveContact: (email: String, name: String?) -> Unit,
    val mailTo: (String) -> Unit,
)

/** An event (contract D3 "Termin ansehen"). */
@Composable
fun MailEventScreen(nav: EventNav, viewModel: MailEventViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cals by viewModel.calendars.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val context = mailContext()
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        state.message?.let {
            toast(context, if (it == "cal_answered") context.getString(R.string.cal_answered) else mailText(context, it))
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(state.gone) { if (state.gone) nav.back() }
    val ev = state.event
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(title = stringResource(R.string.cal_event), onBack = nav.back, backDescription = stringResource(R.string.mail_back)) {
            if (ev != null && ev.canEdit) {
                GcIconButton(GcIcons.Edit, contentDescription = stringResource(R.string.cal_edit), onClick = { nav.edit(ev.id, ev.occ.occurrence) }, enabled = !offline)
            }
            if (ev != null && ev.canDelete) {
                GcIconButton(GcIcons.Trash, contentDescription = stringResource(R.string.mail_delete), onClick = { deleting = true }, enabled = !offline && !state.busy)
            }
        }
        when {
            ev != null -> EventBody(ev, cals?.calendar(ev.occ.calendarId)?.let { it.name to it.color }, viewModel, nav, offline, state.busy)
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            else -> ErrorBox(mailText(context, state.error), onRetry = { viewModel.load() })
        }
    }
    if (deleting && ev != null) DeleteDialog(ev, onDelete = { series, notify, comment -> deleting = false; viewModel.delete(series, notify, comment) }, onDismiss = { deleting = false })
}

@Composable
private fun EventBody(ev: MEvent, calendar: Pair<String, String>?, viewModel: MailEventViewModel, nav: EventNav, offline: Boolean, busy: Boolean) {
    val extra = GateControlTheme.extraColors
    val context = mailContext()
    val zone = viewModel.zone
    val o = ev.occ
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                o.title.ifBlank { stringResource(R.string.cal_no_title) },
                style = MaterialTheme.typography.headlineSmall,
                textDecoration = if (o.cancelled) TextDecoration.LineThrough else null,
                modifier = Modifier.semantics { heading() },
            )
            if (o.cancelled) Text(stringResource(R.string.cal_cancelled), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            calendar?.let { (name, color) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(calColor(color)))
                    Text(name, style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            }
        }
        GcCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
            InfoRow(GcIcons.Clock, occurrenceWhen(o, zone), sub = otherZoneText(o, viewModel))
            ev.recurrence?.let { r ->
                val start = MailPimLogic.date(o.startDate) ?: Instant.ofEpochMilli(o.start).atZone(zone).toLocalDate()
                InfoRow(GcIcons.Refresh, ruleText(r.rule, start))
            }
            o.location?.let { loc ->
                InfoRow(GcIcons.Map, loc, action = stringResource(R.string.cal_open_map)) {
                    openExternal(context, "geo:0,0?q=" + Uri.encode(loc))
                }
            }
            o.online?.let { on ->
                InfoRow(GcIcons.Link, stringResource(R.string.cal_online_meeting, providerName(on.provider)), action = stringResource(R.string.cal_join)) { openLink(context, on.url) }
            }
            if (ev.reminders.isNotEmpty()) {
                InfoRow(GcIcons.Bell, ev.reminders.map { reminderText(it, o.allDay) }.joinToString(", "))
            }
            if (o.busy == "free") InfoRow(GcIcons.Eye, stringResource(R.string.cal_free))
            if (o.private) InfoRow(GcIcons.Lock, stringResource(R.string.cal_private))
        }
        if (ev.canRespond) {
            RespondBlock(o.myResponse, enabled = !offline && !busy, onRespond = viewModel::respond)
        }
        if (ev.organizer != null || ev.attendees.isNotEmpty()) {
            val (yes, all) = MailPimLogic.acceptedOf(ev.attendees)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.cal_attendees) + if (all > 0) " · " + stringResource(R.string.cal_accepted_of, yes, all) else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = extra.muted,
                    modifier = Modifier.semantics { heading() },
                )
                GcCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.Top) {
                    ev.organizer?.let { org ->
                        PersonRow(org.label, org.email, stringResource(R.string.cal_resp_organizer), null) { org.contactId?.let(nav.contact) ?: nav.saveContact(org.email, org.name.ifBlank { null }) }
                    }
                    ev.attendees.filter { it.email != ev.organizer?.email }.forEach { a ->
                        PersonRow(a.label, a.email, if (a.role == MAttendee.ROLE_RESOURCE) stringResource(R.string.cal_resource) else responseText(a.response), a.response) {
                            a.contactId?.let(nav.contact) ?: nav.saveContact(a.email, a.name.ifBlank { null })
                        }
                    }
                }
            }
        }
        if (ev.descriptionHtml != null) {
            MailHtmlView(ev.descriptionHtml, loader = { null }, onLink = { openLink(context, it) }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
        } else if (ev.description.isNotBlank()) {
            SelectionContainer { Text(ev.description, style = MaterialTheme.typography.bodyMedium) }
        }
        if (ev.readOnly) Text(stringResource(R.string.cal_read_only_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        Box(Modifier.height(24.dp))
    }
}

@Composable
private fun otherZoneText(o: MOccurrence, viewModel: MailEventViewModel): String? {
    if (o.allDay) return null
    val z = MailPimLogic.otherZone(o.timeZone, viewModel.zone, o.start) ?: return null
    return "${clock(o.start, z, currentLocale())}–${clock(o.end, z, currentLocale())} · ${z.id} (${MailPimLogic.offsetLabel(z, o.start)})"
}

@Composable
internal fun providerName(p: String): String = when (p) {
    "teams" -> "Teams"
    "zoom" -> "Zoom"
    "meet" -> "Google Meet"
    "webex" -> "Webex"
    else -> stringResource(R.string.cal_online_other)
}

@Composable
private fun InfoRow(icon: ImageVector, text: String, sub: String? = null, action: String? = null, onClick: (() -> Unit)? = null) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = action, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = extra.muted, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        if (action != null) Text(action, style = MaterialTheme.typography.labelLarge, color = extra.accentText, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun PersonRow(name: String, email: String, state: String?, response: String?, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ContactAvatar(name, email, size = 28.dp)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            if (name != email) Text(email, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1)
        }
        if (state != null) {
            Text(
                state,
                style = MaterialTheme.typography.bodySmall,
                color = when (response) {
                    MOccurrence.RESPONSE_ACCEPTED -> extra.accentText
                    MOccurrence.RESPONSE_DECLINED -> MaterialTheme.colorScheme.error
                    MOccurrence.RESPONSE_TENTATIVE -> extra.warn
                    else -> extra.muted
                },
            )
        }
    }
}

/** Zusagen / Mit Vorbehalt / Absagen with a comment (mockup "Einladung"); the own answer marked. */
@Composable
internal fun RespondBlock(mine: String?, enabled: Boolean, onRespond: (String, String?) -> Unit) {
    val extra = GateControlTheme.extraColors
    var comment by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mine != null && mine != MOccurrence.RESPONSE_NEEDS_ACTION && mine != MOccurrence.RESPONSE_ORGANIZER) {
            Text(stringResource(R.string.cal_you_answered, responseText(mine)), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        ResponseButtons(mine, enabled) { onRespond(it, comment.ifBlank { null }) }
        GcTextField(comment, { comment = it.take(2000) }, stringResource(R.string.cal_comment), placeholder = stringResource(R.string.cal_comment_hint), singleLine = false)
    }
}

@Composable
internal fun ResponseButtons(mine: String?, enabled: Boolean, onRespond: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val yes = mine == MOccurrence.RESPONSE_ACCEPTED
        val maybe = mine == MOccurrence.RESPONSE_TENTATIVE
        val no = mine == MOccurrence.RESPONSE_DECLINED
        val m = Modifier.weight(1f)
        if (yes || mine == null || mine == MOccurrence.RESPONSE_NEEDS_ACTION) {
            GcPrimaryButton(stringResource(R.string.cal_accept), onClick = { onRespond(MOccurrence.RESPONSE_ACCEPTED) }, enabled = enabled, modifier = m, minHeight = 48.dp)
        } else {
            GcOutlineButton(stringResource(R.string.cal_accept), onClick = { onRespond(MOccurrence.RESPONSE_ACCEPTED) }, enabled = enabled, modifier = m)
        }
        if (maybe) {
            GcPrimaryButton(stringResource(R.string.cal_tentative), onClick = { onRespond(MOccurrence.RESPONSE_TENTATIVE) }, enabled = enabled, modifier = m)
        } else {
            GcOutlineButton(stringResource(R.string.cal_tentative), onClick = { onRespond(MOccurrence.RESPONSE_TENTATIVE) }, enabled = enabled, modifier = m)
        }
        if (no) {
            GcPrimaryButton(stringResource(R.string.cal_decline), onClick = { onRespond(MOccurrence.RESPONSE_DECLINED) }, enabled = enabled, modifier = m)
        } else {
            GcOutlineButton(stringResource(R.string.cal_decline), onClick = { onRespond(MOccurrence.RESPONSE_DECLINED) }, enabled = enabled, modifier = m)
        }
    }
}

/** Delete: this occurrence or the series; with attendees whether they hear of it, and a comment. */
@Composable
private fun DeleteDialog(ev: MEvent, onDelete: (series: Boolean, notify: Boolean, comment: String?) -> Unit, onDismiss: () -> Unit) {
    var series by remember { mutableStateOf(ev.occ.occurrence == null) }
    val people = ev.attendees.any { !it.self }
    var notify by remember { mutableStateOf(true) }
    var comment by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cal_delete_event)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ev.occ.occurrence != null) {
                    ScopeChoice(series) { series = it }
                }
                if (people) {
                    Row(Modifier.fillMaxWidth().selectable(selected = notify, role = Role.Checkbox) { notify = !notify }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = notify, onCheckedChange = null)
                        Text(stringResource(if (ev.canRespond) R.string.cal_delete_tell_organizer else R.string.cal_delete_tell), modifier = Modifier.padding(start = 8.dp))
                    }
                    if (notify) GcTextField(comment, { comment = it.take(1000) }, stringResource(R.string.cal_comment), singleLine = false)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDelete(series, people && notify, comment.ifBlank { null }) }) { Text(stringResource(R.string.mail_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mail_cancel)) } },
    )
}

/** "Nur dieser Termin" / "Alle Termine der Serie". */
@Composable
internal fun ScopeChoice(series: Boolean, seriesEnabled: Boolean = true, onPick: (Boolean) -> Unit) {
    Column {
        listOf(false to R.string.cal_scope_one, true to R.string.cal_scope_series).forEach { (v, label) ->
            val enabled = !v || seriesEnabled
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(selected = series == v, enabled = enabled, role = Role.RadioButton) { onPick(v) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = series == v, onClick = null, enabled = enabled)
                Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
