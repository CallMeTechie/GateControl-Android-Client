package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatBridgeNetwork
import com.gatecontrol.android.chat.ChatBridgeRow
import com.gatecontrol.android.chat.ChatBridges
import com.gatecontrol.android.chat.ChatChannelSummary
import com.gatecontrol.android.chat.ChatChannels
import com.gatecontrol.android.chat.ChatList
import com.gatecontrol.android.ui.chat.settings.ChatSettingsPage
import com.gatecontrol.android.ui.chat.settings.networkName
import com.gatecontrol.android.ui.components.GcDrawerBar
import com.gatecontrol.android.ui.components.GcDrawerHead
import com.gatecontrol.android.ui.components.GcDrawerIcon
import com.gatecontrol.android.ui.components.GcDrawerLabel
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme

/** What the side menu of „Chats“ shows (Chats v2, draft „Seitenmenü“, without the parts about people). */
data class ChatDrawerState(
    val name: String?,
    val status: String?,
    /** Messenger 1.20: the channels kept apart (null: no `bridge_channels`, no row „Kanäle“). */
    val channels: ChatChannelSummary?,
    val archived: Int,
    /** „Status“ only where the server has it (Messenger 1.4 `status` / `stories`). */
    val status14: Boolean,
    /** Messenger 1.4 `msg_tools`: the own lists (null: none on this server). */
    val lists: List<ChatList>?,
    val listId: String?,
    val showArchived: Boolean,
    /** Messenger 1.13 `linked_services`: the services (null: not offered — no section). */
    val linked: List<ChatBridgeNetwork>?,
)

/** Taps of the side menu. */
class ChatDrawerActions(
    val all: () -> Unit,
    val channels: () -> Unit,
    val archive: () -> Unit,
    val status: () -> Unit,
    val list: (String) -> Unit,
    /** A settings page ([ChatSettingsPage]): „+“ of the lists, a service, the icons at the foot. */
    val page: (String) -> Unit,
)

/**
 * The side menu of „Chats“ (GcDrawerSheet pattern, as Drive and Notizen): the head with the person and their status,
 * „Ansichten“ (Alle Chats · Kanäle · Archiv · Status), „Meine Listen“ with „+“, „Verknüpfte Dienste“ with each
 * service's logo and state, and at the foot Blockiert · Speicher · Geräte · Hilfe and the gear of the settings.
 */
@Composable
fun ChatDrawer(s: ChatDrawerState, a: ChatDrawerActions) {
    Column {
        LazyColumn(Modifier.weight(1f, fill = false).testTag("chat-drawer"), contentPadding = PaddingValues(bottom = 8.dp)) {
            item(key = "head") { GcDrawerHead(stringResource(R.string.chat_title), s.name, s.status) }
            item(key = "views") { GcDrawerLabel(stringResource(R.string.chat_dr_views)) }
            item(key = "all") { DrawerRow(GcIcons.MessageSquare, stringResource(R.string.chat_dr_all), null, selected = !s.showArchived && s.listId == null, onClick = a.all) }
            if (ChatChannels.showTv(s.channels)) {
                item(key = "channels") {
                    val n = s.channels?.unread ?: 0
                    DrawerRow(GcIcons.Tv, stringResource(R.string.chat_dr_channels), if (n > 0) stringResource(R.string.chat_dr_new_n, n) else null, selected = false, onClick = a.channels)
                }
            }
            item(key = "archive") { DrawerRow(GcIcons.Archive, stringResource(R.string.chat_dr_archive), s.archived.takeIf { it > 0 }?.toString(), selected = s.showArchived, onClick = a.archive) }
            if (s.status14) item(key = "status") { DrawerRow(GcIcons.StatusRing, stringResource(R.string.chat_dr_status), null, selected = false, onClick = a.status) }
            s.lists?.let { lists ->
                item(key = "lists") {
                    GcDrawerLabel(stringResource(R.string.chat_dr_lists)) {
                        GcIconButton(GcIcons.Plus, stringResource(R.string.chat_dr_new_list), { a.page(ChatSettingsPage.LISTS) }, size = 44.dp, iconSize = 20.dp, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                items(lists, key = { "l:" + it.id }) { l ->
                    DrawerRow(GcIcons.ListBullets, l.name, l.conversationIds.size.takeIf { it > 0 }?.toString(), selected = s.listId == l.id) { a.list(l.id) }
                }
            }
            s.linked?.let { nets ->
                item(key = "linked") { GcDrawerLabel(stringResource(R.string.chat_dr_linked)) }
                items(nets.filter { ChatBridges.row(it) != ChatBridgeRow.NONE }, key = { "n:" + it.id }) { n ->
                    ServiceRow(n) { a.page(ChatSettingsPage.LINKED) }
                }
                item(key = "link") { DrawerRow(GcIcons.Link, stringResource(R.string.chat_dr_link), null, selected = false) { a.page(ChatSettingsPage.LINKED) } }
            }
        }
        GcDrawerBar(
            listOf(
                GcDrawerIcon(GcIcons.Ban, R.string.chat_dr_blocked) { a.page(ChatSettingsPage.BLOCKS) },
                GcDrawerIcon(GcIcons.Database, R.string.chat_dr_storage) { a.page(ChatSettingsPage.STORAGE) },
                GcDrawerIcon(GcIcons.Smartphone, R.string.chat_dr_devices) { a.page(ChatSettingsPage.DEVICES) },
                GcDrawerIcon(GcIcons.HelpCircle, R.string.chat_dr_help) { a.page(ChatSettingsPage.HELP) },
                GcDrawerIcon(GcIcons.Settings, R.string.chat_dr_settings) { a.page(ChatSettingsPage.MAIN) },
            ),
        )
    }
}

/** A row of the menu: icon, name, what it counts at the end; highlighted when it is shown now. */
@Composable
private fun DrawerRow(icon: ImageVector, name: String, trailing: String?, selected: Boolean, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (selected) Modifier.background(x.accentBg) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = if (selected) x.accentText else x.muted, modifier = Modifier.size(20.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) x.accentText else MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = x.muted) }
    }
}

/** A linked service: its logo, its name, its state („verbunden“, „abgemeldet“, …). */
@Composable
private fun ServiceRow(n: ChatBridgeNetwork, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    val row = ChatBridges.row(n)
    val (state, tint) = when (row) {
        ChatBridgeRow.LINKED -> R.string.chat_dr_state_linked to MaterialTheme.colorScheme.primary
        ChatBridgeRow.LOGGED_OUT -> R.string.chat_dr_state_logged_out to MaterialTheme.colorScheme.error
        ChatBridgeRow.STARTING -> R.string.chat_dr_state_starting to x.muted
        ChatBridgeRow.UNREACHABLE -> R.string.chat_dr_state_unreachable to x.warn
        ChatBridgeRow.ERROR -> R.string.chat_dr_state_error to MaterialTheme.colorScheme.error
        ChatBridgeRow.LOCKED -> R.string.chat_dr_state_locked to x.muted
        ChatBridgeRow.NONE -> R.string.chat_dr_state_none to x.muted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChatNetLogo(n.id, 20.dp)
        Text(stringResource(networkName(n.id)), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        Text(stringResource(state), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = tint)
    }
}
