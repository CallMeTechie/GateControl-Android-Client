package com.gatecontrol.android.ui.mail.contacts

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MOccurrence
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.mail.ErrorBox
import com.gatecontrol.android.ui.mail.MailDot
import com.gatecontrol.android.ui.mail.accountColor
import com.gatecontrol.android.ui.mail.mailContext
import com.gatecontrol.android.ui.mail.mailText
import com.gatecontrol.android.ui.mail.mailTime
import com.gatecontrol.android.ui.mail.openExternal
import com.gatecontrol.android.ui.mail.toast
import com.gatecontrol.android.ui.mail.calendar.calColor
import com.gatecontrol.android.ui.mail.calendar.occurrenceWhen
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.update.currentLocale
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Where a contact leads. */
data class ContactNav(
    val back: () -> Unit,
    val edit: (Long) -> Unit,
    /** A new mail to these addresses. */
    val mailTo: (String) -> Unit,
    val openMail: (Long) -> Unit,
    /** "Alle Mails von …": the sender's page. */
    val sender: (email: String, name: String?) -> Unit,
    val event: (eventId: Long, occurrence: String?) -> Unit,
)

/**
 * A contact (mockup "Kontakt"): the photo, name, position · company and where it comes from; Mail, Anrufen,
 * Bearbeiten; the addresses, numbers, postal addresses (to the map), birthday, note and groups; the last mails and
 * the shared events. A read-only source has no "Bearbeiten" ("aus <Konto>, nur lesend").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MailContactScreen(nav: ContactNav, viewModel: MailContactViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val cals by viewModel.calendars.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    val locale = currentLocale()
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var pickMail by remember { mutableStateOf(false) }
    var pickCall by remember { mutableStateOf(false) }
    LaunchedEffect(state.gone) { if (state.gone) nav.back() }
    LaunchedEffect(state.message) {
        state.message?.let {
            toast(
                context,
                when (it) {
                    "main_phone" -> context.getString(R.string.con_main_phone_set)
                    "unlinked" -> context.getString(R.string.con_unlinked)
                    "pinned" -> context.getString(R.string.fav_pinned)
                    "unpinned" -> context.getString(R.string.fav_unpinned)
                    "fav_limit" -> context.getString(R.string.fav_limit, com.gatecontrol.android.mail.MailContactLogic.FAVORITES_MAX)
                    else -> mailText(context, it)
                },
            )
            viewModel.clearMessage()
        }
    }
    val startPins by viewModel.startPins.collectAsStateWithLifecycle()
    var unlinking by remember { mutableStateOf<com.gatecontrol.android.mail.MSource?>(null) }
    val c = state.card
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GcIconButton(GcIcons.Back, contentDescription = stringResource(R.string.mail_back), onClick = nav.back, tint = MaterialTheme.colorScheme.onBackground)
            Box(Modifier.weight(1f))
            // Mail 1.4.0: the star (a favourite; GateControl's own, also for read-only sources)
            if (c != null && c.favorite != null) {
                val on = c.favorite == true
                GcIconButton(
                    if (on) GcIcons.StarFilled else GcIcons.Star,
                    contentDescription = stringResource(if (on) R.string.fav_remove else R.string.fav_add),
                    onClick = viewModel::toggleFavorite,
                    enabled = !offline,
                    tint = if (on) extra.warn else MaterialTheme.colorScheme.onBackground,
                )
            }
            val pinnable = c != null && c.favorite == true
            if (c != null && (!c.readOnly || pinnable)) {
                Box {
                    GcIconButton(GcIcons.MoreVertical, contentDescription = stringResource(R.string.con_more), onClick = { menu = true }, tint = MaterialTheme.colorScheme.onBackground)
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (pinnable) {
                            val pinned = startPins.has(c.id)
                            DropdownMenuItem(
                                text = { Text(stringResource(if (pinned) R.string.fav_unpin else R.string.fav_pin)) },
                                leadingIcon = { Icon(GcIcons.Pin, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = { menu = false; viewModel.pinToStart(!pinned) },
                            )
                        }
                        if (!c.readOnly) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.con_delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(GcIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)) },
                                enabled = !offline,
                                onClick = { menu = false; deleting = true },
                            )
                        }
                    }
                }
            }
        }
        when {
            c != null -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ContactAvatar(c.label, c.emails.firstOrNull()?.value, c.id.takeIf { c.photo }, c.photoEtag, size = 96.dp)
                    Text(c.label, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
                    val job = listOfNotNull(c.title, c.department, c.company).joinToString(" · ")
                    if (job.isNotEmpty()) Text(job, style = MaterialTheme.typography.bodyMedium, color = extra.muted, textAlign = TextAlign.Center)
                    val book = books?.book(c.addressbookId)
                    if (c.sources.size > 1) {
                        // Mail 1.4.0: the GC contact with the cards linked to it ("verknüpft mit Outlook (…)")
                        val linked = c.sources.filter { it.id != c.id }.map { com.gatecontrol.android.mail.MailContactLogic.originLabel(it) }.filter { it.isNotBlank() }.distinct()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SourceDots(me, c.sources.mapNotNull { it.accountId }.distinct())
                            Text(stringResource(R.string.con_linked_with, linked.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = extra.muted, textAlign = TextAlign.Center)
                        }
                    } else {
                        val linked = c.linked.mapNotNull { l -> books?.book(l.addressbookId)?.name }.distinct()
                        val src = listOfNotNull(accountLabel(me, book?.accountId) ?: book?.name) + linked.map { stringResource(R.string.con_linked_with, it) }
                        if (src.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                me?.account(book?.accountId)?.let { MailDot(accountColor(it)) }
                                Text(src.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = extra.muted, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                // Mail 1.4.0: a linked card has another value — take it over or let it go (online only)
                if (!offline) {
                    c.conflicts.forEach { x -> ConflictBand(x, busy = state.busy, onAccept = { add -> viewModel.accept(x, add) }, onDismiss = { viewModel.dismiss(x) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile(GcIcons.Mail, stringResource(R.string.con_mail), enabled = c.emails.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        if (c.emails.size == 1) nav.mailTo(c.emails[0].value) else pickMail = true
                    }
                    ActionTile(GcIcons.Phone, stringResource(R.string.con_call), enabled = c.phones.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        // a chosen main number is called straight away (Mail 1.4.0)
                        val main = c.mainPhone?.takeIf { c.mainPhoneChosen }
                        if (c.phones.size == 1) call(context, c.phones[0].value) else if (main != null) call(context, main.value) else pickCall = true
                    }
                    ActionTile(GcIcons.Edit, stringResource(R.string.con_edit), enabled = !c.readOnly && !offline, modifier = Modifier.weight(1f)) { nav.edit(c.id) }
                }
                if (c.readOnly) {
                    Text(stringResource(R.string.con_read_only, accountLabel(me, books?.book(c.addressbookId)?.accountId) ?: books?.book(c.addressbookId)?.name ?: ""), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                    c.emails.forEach { e -> Field(stringResource(R.string.con_email) + " · " + typeName(e.type), e.value, link = true) { nav.mailTo(e.value) } }
                    c.phones.forEach { p ->
                        val main = c.favorite != null && c.phones.size > 1 && com.gatecontrol.android.mail.MailContactLogic.isMain(c, p)
                        val choose = !offline && com.gatecontrol.android.mail.MailContactLogic.canChooseMain(c, p)
                        Field(
                            typeName(p.type) + if (main) " · " + stringResource(R.string.con_main_phone) else "",
                            p.value,
                            link = true,
                            menu = if (choose) listOf(stringResource(R.string.con_main_phone_choose) to { viewModel.setMainPhone(p) }) else emptyList(),
                        ) { call(context, p.value) }
                    }
                    c.addresses.forEach { a ->
                        val text = listOf(a.street, listOf(a.postalCode, a.city).filter { it.isNotBlank() }.joinToString(" "), a.region, a.country).filter { it.isNotBlank() }.joinToString("\n")
                        Field(typeName(a.type) + " · " + stringResource(R.string.con_address), text, action = GcIcons.Map) { openExternal(context, "geo:0,0?q=" + Uri.encode(text.replace('\n', ' '))) }
                    }
                    c.birthday?.let { b -> birthdayText(b, locale)?.let { Field(stringResource(R.string.con_birthday), it) } }
                    c.urls.forEach { u -> Field(stringResource(R.string.con_web), u, link = true) { openExternal(context, u) } }
                    if (c.note.isNotBlank()) Field(stringResource(R.string.con_note), c.note)
                    val groups = books?.groups.orEmpty().filter { it.id in c.groups }
                    if (groups.isNotEmpty()) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.con_groups), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                groups.forEach { g ->
                                    val col = calColor(g.color, extra.accentText)
                                    Text(g.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = col,
                                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(col.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 6.dp))
                                }
                            }
                        }
                    }
                }
                // Mail 1.4.0: where the contact comes from, each linked card with "Verknüpfung lösen"
                if (c.sources.size > 1) {
                    GcSectionLabel(stringResource(R.string.con_sources), Modifier.padding(horizontal = 4.dp))
                    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                        c.sources.forEach { src ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SourceDots(me, listOfNotNull(src.accountId))
                                Column(Modifier.weight(1f)) {
                                    val label = com.gatecontrol.android.mail.MailContactLogic.originLabel(src).ifBlank { src.addressbook }
                                    Text(if (src.local) stringResource(R.string.con_source_gc) else label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(src.addressbook, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (src.id != c.id) {
                                    TextButton(onClick = { unlinking = src }, enabled = !offline && !state.busy) { Text(stringResource(R.string.con_unlink)) }
                                }
                            }
                        }
                    }
                }
                state.activity?.let { a ->
                    if (a.mails.isNotEmpty()) {
                        GcSectionLabel(stringResource(R.string.con_last_mails), Modifier.padding(horizontal = 4.dp))
                        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                            a.mails.forEach { m ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { nav.openMail(m.id) }.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    MailDot(accountColor(me?.account(m.accountId)))
                                    Column(Modifier.weight(1f)) {
                                        Text(m.subject.ifEmpty { stringResource(R.string.mail_no_subject) }, style = MaterialTheme.typography.bodyMedium, fontWeight = if (m.unread) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (m.snippet.isNotBlank()) Text(m.snippet, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(mailTime(m.date), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                                }
                            }
                            c.emails.firstOrNull()?.let { e ->
                                TextButton(onClick = { nav.sender(e.value, c.label) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.con_all_mails, c.name.given.ifBlank { c.label }), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    if (a.events.isNotEmpty()) {
                        GcSectionLabel(stringResource(R.string.con_shared_events), Modifier.padding(horizontal = 4.dp))
                        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                            a.events.forEach { o -> SharedEvent(o, cals?.calendar(o.calendarId)?.color, locale) { nav.event(o.eventId, o.occurrence) } }
                        }
                    }
                }
                Box(Modifier.height(24.dp))
            }
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            else -> ErrorBox(mailText(context, state.error), onRetry = { viewModel.load() })
        }
    }
    if (pickMail && c != null) Picker(c.emails.map { it.value }, onPick = { pickMail = false; nav.mailTo(it) }, onDismiss = { pickMail = false })
    if (pickCall && c != null) Picker(c.phones.map { it.value }, onPick = { pickCall = false; call(context, it) }, onDismiss = { pickCall = false })
    if (deleting && c != null) {
        DeleteContactDialog(c, onDelete = { linkedToo -> deleting = false; viewModel.delete(linkedToo) }, onDismiss = { deleting = false })
    }
    unlinking?.let { src ->
        AlertDialog(
            onDismissRequest = { unlinking = null },
            title = { Text(stringResource(R.string.con_unlink_title)) },
            text = { Text(stringResource(R.string.con_unlink_text, com.gatecontrol.android.mail.MailContactLogic.originLabel(src).ifBlank { src.addressbook })) },
            confirmButton = { TextButton(onClick = { unlinking = null; viewModel.unlink(src) }) { Text(stringResource(R.string.con_unlink), fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { unlinking = null }) { Text(stringResource(R.string.mail_cancel)) } },
        )
    }
}

private fun call(context: android.content.Context, number: String) {
    MailPimLogic.telUri(number)?.let { openExternal(context, it) }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Column(
        modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) extra.accentText else extra.faint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (enabled) MaterialTheme.colorScheme.onSurface else extra.faint)
    }
}

/** A field of the contact; [menu] (label → action) opens on a long press ("Als Hauptnummer"). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Field(label: String, value: String, link: Boolean = false, action: ImageVector? = null, menu: List<Pair<String, () -> Unit>> = emptyList(), onClick: (() -> Unit)? = null) {
    val extra = GateControlTheme.extraColors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .then(
                    when {
                        menu.isNotEmpty() -> Modifier.combinedClickable(
                            role = Role.Button,
                            onLongClickLabel = menu.first().first,
                            onLongClick = { open = true },
                            onClick = { onClick?.invoke() },
                        )
                        onClick != null -> Modifier.clickable(role = Role.Button, onClick = onClick)
                        else -> Modifier
                    },
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = extra.muted)
                if (menu.isNotEmpty()) {
                    Text(value, style = MaterialTheme.typography.bodyLarge, color = if (link) extra.accentText else MaterialTheme.colorScheme.onSurface)
                } else {
                    SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge, color = if (link) extra.accentText else MaterialTheme.colorScheme.onSurface) }
                }
            }
            if (action != null) Icon(action, contentDescription = null, tint = extra.muted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menu.forEach { (text, run) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; run() }) }
        }
    }
}

/**
 * A linked card has another value (Mail 1.4.0): "Outlook (…) hat eine andere Mobilnummer: 0171 … (bisher 0170 …)",
 * "Übernehmen" (replaces), "Zusätzlich" where it fits (adds), "Verwerfen" (the value stays away).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictBand(x: com.gatecontrol.android.mail.MCardConflict, busy: Boolean, onAccept: (add: Boolean) -> Unit, onDismiss: () -> Unit) {
    val logic = com.gatecontrol.android.mail.MailContactLogic
    val what = stringResource(
        when (logic.hintKind(x)) {
            "mobile" -> R.string.con_hint_mobile
            "phone" -> R.string.con_hint_phone
            "address" -> R.string.con_hint_address
            "company" -> R.string.con_hint_company
            "department" -> R.string.con_hint_department
            "title" -> R.string.con_hint_title
            else -> R.string.con_hint_birthday
        },
    )
    val origin = logic.originLabel(x.source).ifBlank { stringResource(R.string.con_hint_linked) }
    val text = stringResource(R.string.con_hint, origin, what, logic.valueText(x.value)) +
        (x.replaces?.let { " " + stringResource(R.string.con_hint_before, logic.valueText(it)) } ?: "")
    com.gatecontrol.android.ui.components.GcBanner(tone = com.gatecontrol.android.ui.components.GcTone.Warn, icon = GcIcons.Info) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onAccept(false) }, enabled = !busy) { Text(stringResource(R.string.con_hint_accept), fontWeight = FontWeight.Bold) }
                if (logic.canAdd(x)) TextButton(onClick = { onAccept(true) }, enabled = !busy) { Text(stringResource(R.string.con_hint_add)) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.con_hint_dismiss)) }
            }
        }
    }
}

@Composable
internal fun typeName(type: String): String = stringResource(
    when (type) {
        "work" -> R.string.con_type_work
        "home" -> R.string.con_type_home
        "mobile" -> R.string.con_type_mobile
        "fax" -> R.string.con_type_fax
        else -> R.string.con_type_other
    },
)

/** "14. März 1981 · wird 46" (without a year: "14. März"). */
@Composable
private fun birthdayText(b: String, locale: java.util.Locale): String? {
    val (m, d, y) = MailPimLogic.birthdayParts(b) ?: return null
    val date = runCatching { LocalDate.of(y ?: 2000, m, d) }.getOrNull() ?: return null
    val text = date.format(DateTimeFormatter.ofPattern(if (y != null) (if (locale.language == "de") "d. MMMM yyyy" else "MMMM d, yyyy") else (if (locale.language == "de") "d. MMMM" else "MMMM d"), locale))
    val turns = MailPimLogic.turns(b, LocalDate.now())
    return if (turns != null) text + " · " + stringResource(R.string.con_turns, turns) else text
}

@Composable
private fun SharedEvent(o: MOccurrence, color: String?, locale: java.util.Locale, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val zone = ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(o.start).atZone(zone).toLocalDate()
    val c = calColor(color)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(day.format(DateTimeFormatter.ofPattern("EEE", locale)).removeSuffix("."), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(c))
        Column(Modifier.weight(1f)) {
            Text(o.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(stringResource(R.string.cal_invite_open).takeIf { o.open }, occurrenceWhen(o, zone), o.location).joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = if (o.open) c else extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Picker(values: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column {
                values.forEach { v ->
                    Text(v, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onPick(v) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mail_cancel)) } },
    )
}
