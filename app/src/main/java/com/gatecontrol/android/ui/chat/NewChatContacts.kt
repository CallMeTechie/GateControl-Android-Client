package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PowerOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatBookContact
import com.gatecontrol.android.chat.ChatBookPhone
import com.gatecontrol.android.chat.ChatBridgeChat
import com.gatecontrol.android.chat.ChatBridges
import com.gatecontrol.android.chat.ChatConvBridge
import com.gatecontrol.android.chat.ChatGcContact
import com.gatecontrol.android.chat.ChatNewChatContacts
import com.gatecontrol.android.chat.ChatNewContacts
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatWaContact
import com.gatecontrol.android.profile.AvatarRing
import com.gatecontrol.android.ui.components.GcAvatar
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.theme.GateControlTheme

/*
 * "Neuer Chat" with contacts (Messenger 1.18, draft "Neuer Chat mit Kontakten", approved 15.11.): the three sections
 * GateControl → WhatsApp → "Aus deinem Adressbuch" (empty ones fall away), "+49 … bei WhatsApp prüfen" for a search
 * that looks like a number, and at the bottom the sentence on privacy and "Nicht dabei? Nummer eingeben".
 */

/** The state of a tapped address book contact, shown in its row ([key] the number tapped). */
sealed interface BookRow {
    val key: String

    data class Checking(override val key: String) : BookRow
    data class NotOn(override val key: String) : BookRow

    /** Too many checks: the next from [untilMs]; [checks] the brake per hour. */
    data class RateLimited(override val key: String, val untilMs: Long, val checks: Int) : BookRow
    data class Unreachable(override val key: String) : BookRow
    data class Failed(override val key: String, val code: String) : BookRow
}

/** "Neuer Chat" with contacts. The search and the answer may name numbers: not in `toString()`. */
data class ContactsUi(
    val network: String = ChatBridges.WHATSAPP,
    val query: String = "",
    /** The last answer of `GET contacts/new-chat`; null before the first (GateControl from `GET people` meanwhile). */
    val data: ChatNewChatContacts? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val moreWa: Boolean = false,
    val moreBook: Boolean = false,
    /** The contact whose numbers are open ("Welche Nummer?"). */
    val pick: Long? = null,
    val rows: Map<Long, BookRow> = emptyMap(),
    /** The WhatsApp contact whose chat is being made. */
    val startingRef: String? = null,
) {
    val searching: Boolean get() = ChatNewContacts.query(query).isNotEmpty()

    /** WhatsApp is there but signed out or not reachable: the address book is greyed. */
    val offline: Boolean get() = data?.whatsapp?.ok == false

    override fun toString(): String = "ContactsUi(loading=$loading, data=$data, rows=${rows.size}, error=$error)"
}

/** What the screen calls. */
class ContactsActions(
    val onAccount: (ChatGcContact) -> Unit,
    val onContact: (ChatWaContact) -> Unit,
    val onBook: (ChatBookContact) -> Unit,
    val onNumber: (ChatBookContact, ChatBookPhone) -> Unit,
    val onNumberHit: (com.gatecontrol.android.chat.ChatNumberHit) -> Unit,
    val onShowAll: (whatsapp: Boolean) -> Unit,
    val onReload: () -> Unit,
    val onRelink: () -> Unit,
    val onEnterNumber: () -> Unit,
)

/** GateControl's accounts while the first answer is on its way: `GET people`, filtered here. */
internal fun accountsOf(ui: ContactsUi, people: List<ChatPerson>): List<ChatGcContact> {
    ui.data?.let { return it.gatecontrol }
    val q = ChatNewContacts.query(ui.query)
    return people.filter { q.isEmpty() || (it.name ?: "").contains(q, ignoreCase = true) }.map { ChatGcContact(it, null) }
}

/** Nothing to show for a search: "Niemand mit „…“ gefunden". */
internal fun nothingFound(ui: ContactsUi, accounts: List<ChatGcContact>): Boolean {
    val d = ui.data ?: return false
    if (!ui.searching || ui.loading || accounts.isNotEmpty() || d.number != null) return false
    val wa = d.whatsapp
    return (wa == null || (wa.ok && wa.items.isEmpty())) && d.addressBook?.items.isNullOrEmpty()
}

internal fun LazyListScope.contactSections(ui: ContactsUi, accounts: List<ChatGcContact>, netName: String, a: ContactsActions) {
    val d = ui.data
    if (nothingFound(ui, accounts)) {
        item(key = "ncc-empty") { Empty(ui, netName, d?.whatsapp != null, d?.numberEntry == true, a.onEnterNumber) }
        return
    }
    // ── GateControl ──
    if (accounts.isNotEmpty()) {
        item(key = "ncc-gc") { Caption(stringResource(R.string.chat_ncc_sec_gc, accounts.size), icon = Icons.Outlined.Shield) }
        items(accounts, key = { "ncc-gc-${it.person.userId}" }) { c ->
            ContactRow(
                avatar = { ChatPersonAvatar(c.person, 44.dp) },
                name = ChatText.name(LocalContext.current, c.person),
                sub = null,
                right = { if (c.conversationId != null) Pill(stringResource(R.string.chat_ncc_open)) },
                onClick = { a.onAccount(c) },
            )
        }
    }
    // ── WhatsApp ──
    val wa = d?.whatsapp
    // the first answer is on its way (not when it failed: the error line above says so)
    val waLoading = d == null && ui.error == null
    if (waLoading || wa != null) {
        item(key = "ncc-wa") {
            val head = if (wa != null && wa.ok && !ui.searching) stringResource(R.string.chat_ncc_sec_wa_n, netName, wa.total) else netName
            Caption(head, mark = ui.network, spinner = waLoading || (ui.loading && wa != null))
        }
    }
    when {
        waLoading -> items(3, key = { "ncc-sk-$it" }) { Skeleton() }
        wa == null -> Unit
        wa.state == ChatNewContacts.STATE_UNREACHABLE -> item(key = "ncc-wa-down") {
            Card(
                GateControlTheme.extraColors.errorBg, Icons.Outlined.PowerOff, MaterialTheme.colorScheme.error,
                stringResource(R.string.chat_ncc_wa_down_title, netName), stringResource(R.string.chat_ncc_wa_down_text),
                stringResource(R.string.chat_ncc_retry), a.onReload,
            )
        }
        wa.state == ChatNewContacts.STATE_LOGGED_OUT -> item(key = "ncc-wa-out") {
            Card(
                GateControlTheme.extraColors.warnBg, Icons.AutoMirrored.Outlined.Logout, GateControlTheme.extraColors.warn,
                stringResource(R.string.chat_ncc_wa_out_title, netName), stringResource(R.string.chat_ncc_wa_out_text, netName),
                stringResource(R.string.chat_ncc_relink), a.onRelink,
            )
        }
        else -> {
            val list = ChatNewContacts.visible(wa.items, ui.searching, ui.moreWa)
            items(list, key = { "ncc-wa-${it.ref}" }) { c ->
                ContactRow(
                    avatar = {
                        Box {
                            ContactAvatar(c.avatarMediaId, c.name, 44.dp)
                            BridgeMark(ChatConvBridge(ui.network, netName, ChatBridgeChat.OK), 44.dp, Modifier.align(Alignment.BottomEnd))
                        }
                    },
                    name = c.name,
                    sub = if (c.alsoInAddressBook) stringResource(R.string.chat_ncc_also_book) else stringResource(R.string.chat_ncc_wa_contact, netName),
                    right = {
                        when {
                            ui.startingRef == c.ref -> Spinner()
                            c.conversationId != null -> Pill(stringResource(R.string.chat_ncc_open))
                        }
                    },
                    onClick = { a.onContact(c) },
                )
            }
            if (ChatNewContacts.hasMore(wa.items, ui.searching, ui.moreWa)) {
                item(key = "ncc-wa-more") { More(stringResource(R.string.chat_ncc_all_wa, wa.items.size, netName)) { a.onShowAll(true) } }
            }
        }
    }
    // ── the address book ──
    val book = d?.addressBook
    if (book != null && book.items.isNotEmpty()) {
        item(key = "ncc-book") {
            Caption(
                if (ui.searching) stringResource(R.string.chat_ncc_sec_book) else stringResource(R.string.chat_ncc_sec_book_n, book.total),
                icon = Icons.AutoMirrored.Outlined.MenuBook,
            )
        }
        if (ui.offline) {
            item(key = "ncc-book-off") {
                Note(
                    stringResource(if (wa?.state == ChatNewContacts.STATE_LOGGED_OUT) R.string.chat_ncc_book_off_out else R.string.chat_ncc_book_off_down, netName),
                    GateControlTheme.extraColors.muted,
                )
            }
        }
        val list = ChatNewContacts.visible(book.items, ui.searching, ui.moreBook)
        list.forEach { c ->
            item(key = "ncc-book-${c.contactId}") { BookContactRow(ui, c, netName, a) }
        }
        if (ChatNewContacts.hasMore(book.items, ui.searching, ui.moreBook)) {
            item(key = "ncc-book-more") { More(stringResource(R.string.chat_ncc_all_book, book.items.size)) { a.onShowAll(false) } }
        }
    }
    // ── a search that looks like a number ──
    d?.number?.let { n ->
        item(key = "ncc-number") {
            ContactRow(
                avatar = { IconAvatar(Icons.Outlined.Dialpad) },
                name = if (n.conversationId != null) n.show else stringResource(R.string.chat_ncc_check_number, n.show, netName),
                sub = null,
                right = { if (n.conversationId != null) Pill(stringResource(R.string.chat_ncc_open)) },
                onClick = { a.onNumberHit(n) },
                modifier = Modifier.testTag("ncc-number"),
            )
        }
    }
}

@Composable
private fun BookContactRow(ui: ContactsUi, c: ChatBookContact, netName: String, a: ContactsActions) {
    val extra = GateControlTheme.extraColors
    val ctx = LocalContext.current
    val off = ui.offline
    val row = ui.rows[c.contactId]
    val one = ChatNewContacts.single(c)
    val picking = ui.pick == c.contactId && !off
    var sub = if (one != null) stringResource(R.string.chat_ncc_one_number, kindText(one), one.number) else stringResource(R.string.chat_ncc_numbers, c.phones.size)
    var dim = off
    var highlight = picking
    var right: (@Composable () -> Unit)? = if (c.conversationId != null) ({ Pill(stringResource(R.string.chat_ncc_open)) }) else null
    var after: (@Composable () -> Unit)? = null
    if (one != null && one.notOnNetwork && row == null) {
        dim = true
        right = { Pill(stringResource(R.string.chat_ncc_not_on, netName), PillTone.GRAY) }
    }
    when (row) {
        is BookRow.Checking -> {
            highlight = true
            sub = stringResource(R.string.chat_ncc_checking, netName)
            right = { Spinner() }
        }
        is BookRow.NotOn -> {
            val number = c.phones.firstOrNull { it.key == row.key }?.number.orEmpty()
            dim = true
            right = { Pill(stringResource(R.string.chat_ncc_not_on, netName), PillTone.GRAY) }
            after = { Note(stringResource(R.string.chat_ncc_none_text, number, netName, ChatNewContacts.firstName(c.name)), extra.muted) }
        }
        is BookRow.RateLimited -> {
            highlight = true
            right = { Pill(stringResource(R.string.chat_ncc_from, clockOf(ctx, row.untilMs)), PillTone.WARN) }
            after = { Note(pluralStringResource(R.plurals.chat_ncc_rate_text, row.checks, row.checks, netName), extra.warn) }
        }
        is BookRow.Unreachable -> after = { Note(stringResource(R.string.chat_ncc_unreach), MaterialTheme.colorScheme.error) }
        is BookRow.Failed -> after = {
            Note(
                if (row.code == "mail_missing") stringResource(R.string.chat_ncc_mail_missing) else ChatText.actionError(ctx, row.code),
                MaterialTheme.colorScheme.error,
            )
        }
        null -> Unit
    }
    if (picking) right = null
    Column {
        ContactRow(
            avatar = { GcAvatar(url = null, initials = ChatPerson.initialsOf(c.name), colorHex = null, ring = AvatarRing.None, size = 44.dp) },
            name = c.name,
            sub = sub,
            right = right,
            onClick = { a.onBook(c) },
            enabled = !off,
            dim = dim,
            highlight = highlight,
        )
        if (picking) Choice(c, netName) { a.onNumber(c, it) }
        after?.invoke()
    }
}

/** "Welche Nummer?": mobile first, then work, home, other; landlines and numbers without the service greyed, still choosable. */
@Composable
private fun Choice(c: ChatBookContact, netName: String, onPick: (ChatBookPhone) -> Unit) {
    val extra = GateControlTheme.extraColors
    Column(
        Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(extra.panel2).padding(vertical = 6.dp),
    ) {
        Text(
            stringResource(R.string.chat_ncc_which).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = extra.faint,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
        ChatNewContacts.ordered(c.phones).forEach { p ->
            val grey = p.landline || p.notOnNetwork
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onPick(p) }
                    .padding(horizontal = 14.dp, vertical = 6.dp).alpha(if (grey) 0.55f else 1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(kindText(p), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                Text(p.number, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                when {
                    p.notOnNetwork -> Text(stringResource(R.string.chat_ncc_not_on, netName), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    p.landline -> Text(stringResource(R.string.chat_ncc_landline), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    else -> Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = extra.muted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun kindText(p: ChatBookPhone): String = stringResource(
    when (ChatNewContacts.kindOf(p)) {
        ChatNewContacts.KIND_MOBILE -> R.string.chat_ncc_kind_mobile
        ChatNewContacts.KIND_WORK -> R.string.chat_ncc_kind_work
        ChatNewContacts.KIND_HOME -> R.string.chat_ncc_kind_home
        else -> R.string.chat_ncc_kind_other
    },
)

/** Under the list: the sentence on privacy (with the WhatsApp section) and "Nicht dabei? Nummer eingeben". */
@Composable
internal fun ContactsFoot(ui: ContactsUi, accounts: List<ChatGcContact>, netName: String, onEnterNumber: () -> Unit) {
    val d = ui.data ?: return
    val extra = GateControlTheme.extraColors
    val entry = d.numberEntry && !nothingFound(ui, accounts)
    if (d.whatsapp == null && !entry) return
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        if (d.whatsapp != null) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = extra.faint, modifier = Modifier.padding(top = 2.dp).size(13.dp))
                Text(stringResource(R.string.chat_ncc_privacy, netName), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
        if (entry) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onEnterNumber)
                    .padding(horizontal = 16.dp, vertical = 8.dp).alpha(if (ui.offline) 0.55f else 1f).testTag("ncc-enter-number"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Dialpad, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.chat_ncc_not_here) + " " + stringResource(R.string.chat_ncc_enter_number),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = extra.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Empty(ui: ContactsUi, netName: String, withWa: Boolean, numberEntry: Boolean, onEnterNumber: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(extra.panel2), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = extra.muted)
        }
        Text(
            stringResource(R.string.chat_ncc_empty_title, ChatNewContacts.query(ui.query)),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            if (withWa) stringResource(R.string.chat_ncc_empty_text, netName) else stringResource(R.string.chat_ncc_empty_text_gc),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (numberEntry) {
            Spacer(Modifier.height(4.dp))
            GcPrimaryButton(text = stringResource(R.string.chat_ncc_enter_number), onClick = onEnterNumber, icon = Icons.Outlined.Dialpad, fillWidth = false)
        }
    }
}

@Composable
private fun ContactRow(
    avatar: @Composable () -> Unit,
    name: String,
    sub: String?,
    right: (@Composable () -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dim: Boolean = false,
    highlight: Boolean = false,
) {
    val extra = GateControlTheme.extraColors
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp)
            .then(if (highlight) Modifier.background(extra.panel2) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(if (dim) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        avatar()
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        right?.invoke()
    }
}

@Composable
private fun Caption(text: String, icon: ImageVector? = null, mark: String? = null, spinner: Boolean = false) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = extra.faint, modifier = Modifier.size(14.dp))
        if (mark != null) {
            Text(
                ChatBridgeChat.mark(ChatConvBridge(mark, text, ChatBridgeChat.OK)),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 3.dp),
            )
        }
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = extra.faint, modifier = Modifier.weight(1f, fill = false))
        if (spinner) Spinner(14.dp)
    }
}

@Composable
private fun Skeleton() {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(extra.panel2))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(140.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(extra.panel2))
            Box(Modifier.width(90.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(extra.panel2))
        }
    }
}

@Composable
private fun Card(bg: Color, icon: ImageVector, tint: Color, title: String, text: String, link: String, onLink: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(bg).padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = onLink, modifier = Modifier.padding(start = 0.dp)) { Text(link, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 8.dp))
}

@Composable
private fun More(text: String, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(start = 72.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = extra.accentText)
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun IconAvatar(icon: ImageVector) {
    val extra = GateControlTheme.extraColors
    Box(Modifier.size(44.dp).clip(CircleShape).background(extra.accentBg), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = extra.accentText)
    }
}

private enum class PillTone { ACCENT, GRAY, WARN }

@Composable
private fun Pill(text: String, tone: PillTone = PillTone.ACCENT) {
    val extra = GateControlTheme.extraColors
    val (bg, fg) = when (tone) {
        PillTone.ACCENT -> extra.accentBg to extra.accentText
        PillTone.GRAY -> extra.panel2 to extra.muted
        PillTone.WARN -> extra.warnBg to extra.warn
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = fg,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun Spinner(size: Dp = 20.dp) {
    CircularProgressIndicator(Modifier.size(size), strokeWidth = 2.dp, color = GateControlTheme.extraColors.accentText)
}

private fun clockOf(ctx: android.content.Context, ms: Long): String = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(ms))
