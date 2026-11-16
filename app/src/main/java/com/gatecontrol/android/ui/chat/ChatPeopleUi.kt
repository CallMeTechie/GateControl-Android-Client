package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatMergedPerson
import com.gatecontrol.android.chat.ChatNameOption
import com.gatecontrol.android.chat.ChatPeople
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatPersonCandidates
import com.gatecontrol.android.chat.ChatPersonSuggestion
import com.gatecontrol.android.profile.AvatarRing
import com.gatecontrol.android.ui.components.GcAvatar
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme

/*
 * Chats v2, Teil B (Messenger 1.21, `features.chat_people`): what the screens draw of a person — the logos of its ways
 * behind the name, the source chips, the line above the composer, the suggestion card, the dialog „Zu einer Person
 * zusammenführen?“, the choice of „Mit Person zusammenführen …“, „Wege von …“ and the question after linking WhatsApp.
 * In the app's design; the logos are those of Teil A ([ChatNetLogo]).
 */

/** The logos of a person's ways in a row (behind the name in the list, under it in the head), each read „über …“. */
@Composable
fun PersonWayLogos(p: ChatMergedPerson, size: Dp = 14.dp, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        p.kinds.forEach { w -> ChatNetLogo(w.way, size, label = stringResource(R.string.chat_pp_via, w.label)) }
    }
}

/** The names of the ways under the name in the head: „GateControl · WhatsApp“. */
fun personWaysLine(p: ChatMergedPerson): String = p.kinds.joinToString(" · ") { it.label }

/**
 * The picture of a person's entry: the picture of the way whose name holds (`avatarMediaId` of a bridged way, or the
 * account's `avatarUrl`), else the initials — no logo at the corner (the ways stand behind the name).
 */
@Composable
fun PersonAvatar(c: ChatConversation, size: Dp, modifier: Modifier = Modifier) {
    when {
        c.avatarMediaId != null -> Box(modifier) { ContactAvatar(c.avatarMediaId, c.title, size) }
        c.avatarUrl != null || c.peer == null -> GcAvatar(
            url = c.avatarUrl,
            initials = ChatPerson.initialsOf(c.title),
            colorHex = c.peer?.colorHex,
            ring = AvatarRing.None,
            size = size,
            modifier = modifier,
        )
        else -> ChatPersonAvatar(c.peer.copy(name = c.title ?: c.peer.name), size, modifier)
    }
}

/** The source chips of a person's chat: Alle · GateControl · WhatsApp (each with its logo). */
@Composable
fun PersonSourceChips(p: ChatMergedPerson, selected: String?, onPick: (String?) -> Unit) {
    val label = stringResource(R.string.chat_pp_src)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = label }.testTag("person-sources"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GcFilterChip(stringResource(R.string.chat_pp_src_all), selected == null, { onPick(null) })
        p.kinds.forEach { w ->
            GcFilterChip(w.label, selected == w.way, { onPick(if (selected == w.way) null else w.way) }, leading = { ChatNetLogo(w.way, 16.dp) })
        }
    }
}

/** The line above the composer when the answer goes over a bridge: where the encryption ends. */
@Composable
fun PersonWayHint(label: String) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().background(extra.panel2).padding(horizontal = 14.dp, vertical = 6.dp).testTag("person-way-hint"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = extra.muted, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.chat_pp_hint, label), style = MaterialTheme.typography.bodySmall, color = extra.muted)
    }
}

/** A picture with the logo of its way at the lower right (the contact with WhatsApp's, the account with the shield). */
@Composable
private fun FaceWithLogo(way: String, size: Dp, face: @Composable () -> Unit) {
    Box(Modifier.size(size + 4.dp)) {
        face()
        Box(Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp)) { ChatNetCorner(way, size) }
    }
}

@Composable
private fun SuggestionContact(s: ChatPersonSuggestion, size: Dp) {
    FaceWithLogo(s.network, size) {
        if (s.contactAvatarMediaId != null) {
            ContactAvatar(s.contactAvatarMediaId, s.contactName, size)
        } else {
            GcAvatar(url = s.contactAvatarUrl, initials = ChatPerson.initialsOf(s.contactName), colorHex = null, ring = AvatarRing.None, size = size)
        }
    }
}

@Composable
private fun SuggestionAccount(s: ChatPersonSuggestion, size: Dp) {
    FaceWithLogo(ChatPeople.WAY_GC, size) {
        GcAvatar(url = s.accountAvatarUrl, initials = s.accountInitials ?: ChatPerson.initialsOf(s.accountName), colorHex = s.accountColorHex, ring = AvatarRing.None, size = size)
    }
}

/** The card above the list (the first suggestion): the contact with its logo, the server's sentence, „Ansehen“. */
@Composable
fun PersonSuggestionCard(s: ChatPersonSuggestion, onOpen: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(extra.accentBg).border(1.dp, extra.border, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 12.dp, vertical = 10.dp).testTag("person-suggestion"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SuggestionContact(s, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.chat_pp_card_sub), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        Text(
            stringResource(R.string.chat_pp_card_open),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** „Name in deiner Liste“: one radio button per name ([selected] the value of the option that is picked). */
@Composable
private fun NameChoice(options: List<ChatNameOption>, selected: String?, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        GcSectionLabel(stringResource(R.string.chat_pp_name), Modifier.padding(top = 8.dp, bottom = 4.dp))
        options.forEach { o ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                    .selectable(selected = o.value == selected, role = Role.RadioButton, onClick = { onPick(o.value) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = o.value == selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(o.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * „Zu einer Person zusammenführen?“ for a suggestion: the pair (the contact with its logo, „WhatsApp-Chat“ + the account
 * with the shield, „GateControl“), the server's reason (a tick; a medium hint the info symbol), the text, the choice of
 * the name (the account's picked) and Zusammenführen · Nicht jetzt · Nicht mehr vorschlagen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonMergeSheet(
    s: ChatPersonSuggestion,
    busy: Boolean,
    onMerge: (name: String) -> Unit,
    onLater: () -> Unit,
    onNever: () -> Unit,
    onDismiss: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val options = remember(s) { ChatPeople.suggestionNames(s) }
    var name by remember(s) { mutableStateOf(options.firstOrNull()?.value ?: ChatPeople.NAME_ACCOUNT) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("person-merge"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.chat_pp_merge_title), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SuggestionContact(s, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(s.contactName.orEmpty(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.chat_pp_way_chat, s.label), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
                Text("+", style = MaterialTheme.typography.titleMedium, color = extra.faint)
                SuggestionAccount(s, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(s.accountName.orEmpty(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ChatPeople.labelOf(ChatPeople.WAY_GC), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            }
            s.reason?.let { why ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(extra.panel2).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (s.strong) Icons.Outlined.Check else Icons.Outlined.Info,
                        contentDescription = null,
                        tint = if (s.strong) MaterialTheme.colorScheme.primary else extra.muted,
                        modifier = Modifier.padding(top = 2.dp).size(16.dp),
                    )
                    Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Text(stringResource(R.string.chat_pp_merge_text), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
            if (options.size > 1) NameChoice(options, name) { name = it }
            GcPrimaryButton(stringResource(R.string.chat_pp_merge), onClick = { onMerge(name) }, loading = busy, enabled = !busy)
            GcSecondaryButton(stringResource(R.string.chat_pp_later), onClick = onLater, enabled = !busy)
            TextButton(onClick = onNever, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.chat_pp_never), color = extra.muted) }
        }
    }
}

/** „Zu einer Person zusammenführen?“ of a merge by hand: the text and the choice of the name. */
@Composable
fun PersonConfirmDialog(options: List<ChatNameOption>, initial: String?, busy: Boolean, onMerge: (name: String?) -> Unit, onDismiss: () -> Unit) {
    var name by remember(options) { mutableStateOf(initial?.takeIf { v -> options.any { it.value == v } } ?: options.firstOrNull()?.value) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_pp_merge_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.chat_pp_merge_text), style = MaterialTheme.typography.bodyMedium)
                if (options.size > 1) NameChoice(options, name) { name = it }
            }
        },
        confirmButton = { TextButton(onClick = { onMerge(name) }, enabled = !busy) { Text(stringResource(R.string.chat_pp_merge)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } },
    )
}

/** What was picked in „Mit Person zusammenführen“: an account or another chat. */
sealed interface PersonPick {
    data class Account(val a: com.gatecontrol.android.chat.ChatCandidateAccount) : PersonPick
    data class Chat(val c: com.gatecontrol.android.chat.ChatCandidateChat) : PersonPick
}

/**
 * „Mit Person zusammenführen“: the hint, a search, „GateControl-Konten“ (only while the chat has no account) and „Andere
 * Chats“ (bridged chats, each with its logo). [candidates] null while they load.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonPickSheet(candidates: ChatPersonCandidates?, onPick: (PersonPick) -> Unit, onDismiss: () -> Unit) {
    val extra = GateControlTheme.extraColors
    var q by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).testTag("person-pick")) {
            Text(
                stringResource(R.string.chat_pp_pick_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(stringResource(R.string.chat_pp_pick_hint), style = MaterialTheme.typography.bodyMedium, color = extra.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            GcTextField(value = q, onValueChange = { q = it.take(60) }, label = stringResource(R.string.chat_pp_pick_search), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            if (candidates == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                return@Column
            }
            val s = q.trim().lowercase()
            val accounts = candidates.accounts.filter { s.isEmpty() || it.name.orEmpty().lowercase().contains(s) }
            val chats = candidates.chats.filter { s.isEmpty() || it.title.orEmpty().lowercase().contains(s) }
            if (accounts.isNotEmpty()) {
                GcSectionLabel(stringResource(R.string.chat_pp_pick_accounts), Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp))
                accounts.forEach { a ->
                    PickRow(a.name.orEmpty(), ChatPeople.labelOf(ChatPeople.WAY_GC), ChatPeople.WAY_GC, { onPick(PersonPick.Account(a)) }) {
                        GcAvatar(url = a.avatarUrl, initials = a.initials ?: ChatPerson.initialsOf(a.name), colorHex = a.colorHex, ring = AvatarRing.None, size = 40.dp)
                    }
                }
            }
            if (chats.isNotEmpty()) {
                GcSectionLabel(stringResource(R.string.chat_pp_pick_chats), Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp))
                chats.forEach { c ->
                    PickRow(c.title.orEmpty(), stringResource(R.string.chat_pp_way_chat, c.label), c.network, { onPick(PersonPick.Chat(c)) }) {
                        if (c.avatarMediaId != null) ContactAvatar(c.avatarMediaId, c.title, 40.dp)
                        else GcAvatar(url = c.avatarUrl, initials = ChatPerson.initialsOf(c.title), colorHex = null, ring = AvatarRing.None, size = 40.dp)
                    }
                }
            }
            if (accounts.isEmpty() && chats.isEmpty()) {
                Text(stringResource(R.string.chat_pp_pick_none), style = MaterialTheme.typography.bodyMedium, color = extra.muted, modifier = Modifier.padding(24.dp))
            }
        }
    }
}

@Composable
private fun PickRow(title: String, sub: String, way: String, onClick: () -> Unit, face: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FaceWithLogo(way, 40.dp, face)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
        }
    }
}

/**
 * „Wege von …“ in the chat info of a person: each way with its logo, the service, „Konto …“ / „als „…““ and „Trennen“;
 * „Name in deiner Liste“ and the note („Mit Person zusammenführen …“ follows as [PersonAddRow]).
 */
@Composable
fun PersonWaysSection(
    p: ChatMergedPerson,
    title: String,
    onSplit: (com.gatecontrol.android.chat.ChatPersonWay) -> Unit,
    onName: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    GcSectionLabel(stringResource(R.string.chat_pp_ways_title, title), Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
    p.ways.forEach { w ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 6.dp).testTag("person-way-${w.conversationId}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChatNetLogo(w.way, 28.dp)
            Column(Modifier.weight(1f)) {
                Text(w.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(if (w.gatecontrol) R.string.chat_pp_way_account else R.string.chat_pp_way_as, w.title.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { onSplit(w) }) { Text(stringResource(R.string.chat_pp_split)) }
        }
    }
    val names = ChatPeople.nameOptions(p)
    if (names.size > 1) {
        com.gatecontrol.android.ui.components.GcListRow(
            title = stringResource(R.string.chat_pp_name),
            description = names.firstOrNull { it.value == ChatPeople.currentName(p) }?.label ?: p.name,
            onClick = onName,
            trailing = { com.gatecontrol.android.ui.components.GcChevron() },
        )
    }
    Text(stringResource(R.string.chat_pp_split_note), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

/** „Mit Person zusammenführen …“ in the chat info. */
@Composable
fun PersonAddRow(onClick: () -> Unit) {
    com.gatecontrol.android.ui.components.GcListRow(
        title = stringResource(R.string.chat_pp_add),
        onClick = onClick,
        leading = { Icon(com.gatecontrol.android.ui.components.GcIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.testTag("person-add"),
    )
}

/** After linking WhatsApp (1.23: or Signal): „Dürfen andere auf diesem Server dich an deinem WhatsApp erkennen?“ — Nein · Ja, erkennen lassen. */
@Composable
fun RecognitionDialog(busy: Boolean, onAnswer: (Boolean) -> Unit, onDismiss: () -> Unit, network: String = com.gatecontrol.android.chat.ChatBridges.WHATSAPP) {
    val extra = GateControlTheme.extraColors
    // Messenger 1.23: the question per service (Signal has its own)
    val signal = network == com.gatecontrol.android.chat.ChatBridges.SIGNAL
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (signal) R.string.chat_pp_rec_title_signal else R.string.chat_pp_rec_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(if (signal) R.string.chat_pp_rec_text_signal else R.string.chat_pp_rec_text), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.chat_pp_rec_note), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }, enabled = !busy) { Text(stringResource(R.string.chat_pp_rec_yes)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }, enabled = !busy) { Text(stringResource(R.string.chat_pp_rec_no)) } },
        modifier = Modifier.testTag("recognition"),
    )
}
