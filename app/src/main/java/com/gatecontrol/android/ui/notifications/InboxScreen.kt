package com.gatecontrol.android.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.notify.model.PushPriority
import com.gatecontrol.android.push.PushText
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcDot
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.LocalDate

/**
 * "Mitteilungen": filter chips by topic, messages grouped Heute / Gestern /
 * date, unread ones bold with a dot. Opening one shows its detail.
 */
@Composable
fun InboxScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    /** Message shown in the detail pane next to the list (wide screens); highlighted. */
    selectedId: Long? = null,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(
            title = stringResource(R.string.push_inbox_title),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
            actions = {
                if (ui.unread > 0) {
                    TextButton(onClick = viewModel::markAllRead) {
                        Text(stringResource(R.string.push_inbox_mark_all), color = extra.accentText, style = MaterialTheme.typography.titleSmall)
                    }
                }
            },
        )

        if (ui.topics.size > 1 || ui.unread > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GcFilterChip(
                    text = if (ui.unread > 0) stringResource(R.string.push_inbox_filter_all_new, ui.unread) else stringResource(R.string.push_inbox_filter_all),
                    selected = ui.filter == null,
                    onClick = { viewModel.setFilter(null) },
                )
                ui.topics.forEach { topic ->
                    GcFilterChip(
                        text = topicLabel(topic, ui.topicLabels[topic]),
                        selected = ui.filter == topic,
                        onClick = { viewModel.setFilter(topic) },
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!ui.enabled) {
                item(key = "disabled") {
                    GcBanner(tone = GcTone.Info, icon = GcIcons.Bell) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.push_inbox_disabled), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.push_inbox_disabled_desc), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                            GcOutlineButton(text = stringResource(R.string.push_inbox_enable), onClick = onOpenSettings, minHeight = 40.dp)
                        }
                    }
                }
            }
            if (ui.offline) {
                item(key = "offline") {
                    Text(stringResource(R.string.push_inbox_offline), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            }
            if (ui.items.isEmpty()) {
                item(key = "empty") { EmptyInbox() }
            }
            val today = LocalDate.now()
            groupByDay(ui.items, today).forEach { (day, messages) ->
                item(key = "day-$day") {
                    GcSectionLabel(
                        when (day) {
                            today -> stringResource(R.string.push_inbox_today)
                            today.minusDays(1) -> stringResource(R.string.push_inbox_yesterday)
                            null -> "—"
                            else -> dateOf(day)
                        },
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                item(key = "group-$day") {
                    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                        messages.forEachIndexed { i, m ->
                            if (i > 0) GcRowDivider(Modifier.padding(start = 72.dp))
                            InboxRow(m, ui.topicLabels[m.topic], selected = m.id == selectedId, onClick = { onOpen(m.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxRow(message: PushMessage, serverLabel: String?, selected: Boolean = false, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val (fg, bg) = messageColors(message)
    val unread = message.isUnread
    val priority = message.priorityLevel
    val meta = buildList {
        message.body.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (priority == PushPriority.CRITICAL || priority == PushPriority.HIGH) add(stringResource(PushText.priorityLabel(priority)))
        else add(topicLabel(message.topic, serverLabel))
        add(timeOf(message))
    }.filter { it.isNotEmpty() }.joinToString(" · ")
    val unreadLabel = stringResource(R.string.push_inbox_unread)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (selected) Modifier.background(extra.accentBg) else Modifier)
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GcIconSquare(background = bg) {
            Icon(
                if (priority == PushPriority.CRITICAL) GcIcons.Alert else topicIcon(message.topic),
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    message.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unread) {
                    Box(Modifier.semantics { contentDescription = unreadLabel }) { GcDot(MaterialTheme.colorScheme.primary, size = 8.dp) }
                }
            }
            Text(meta, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyInbox() {
    val extra = GateControlTheme.extraColors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(GcIcons.Bell, contentDescription = null, tint = extra.faint, modifier = Modifier.size(40.dp))
        Text(stringResource(R.string.push_inbox_empty), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.push_inbox_empty_desc), style = MaterialTheme.typography.bodySmall, color = extra.muted)
    }
}
