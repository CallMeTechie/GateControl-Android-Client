package com.gatecontrol.android.tv.ui.messages

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.chat.TvChatLogic
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme

/** The two tabs of „Nachrichten“ (mockup „5 · Messenger“, „4 · Mail“). */
enum class TvMessagesTab(@param:StringRes val label: Int) {
    Chats(R.string.tv_messages_tab_chats),
    Mail(R.string.tv_messages_tab_mail),
}

/**
 * The page „Nachrichten“ of the rail: the tabs „Chats“ and „Mail“ on top, the chosen one below. [chats] is stage 3e's
 * page; [mail] is where stage 3f hangs its page in (null: a placeholder says Mail follows). [chatsUnread] /
 * [mailUnread]: the number at each tab.
 */
@Composable
fun TvMessagesPage(
    chats: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    chatsUnread: Int = 0,
    mail: (@Composable () -> Unit)? = null,
    mailUnread: Int = 0,
) {
    var tab by rememberSaveable { mutableStateOf(TvMessagesTab.Chats) }
    Column(modifier.fillMaxSize().padding(end = 30.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in TvMessagesTab.entries) {
                TabChip(t, selected = t == tab, count = if (t == TvMessagesTab.Chats) chatsUnread else mailUnread, onClick = { tab = t })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                TvMessagesTab.Chats -> chats()
                TvMessagesTab.Mail -> if (mail != null) mail() else MailPlaceholder()
            }
        }
    }
}

@Composable
private fun TabChip(tab: TvMessagesTab, selected: Boolean, count: Int, onClick: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(8.dp)
    val label = stringResource(tab.label)
    val badge = TvChatLogic.badge(count)
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = listOfNotNull(label, badge).joinToString(", ") },
        shape = SelectableSurfaceDefaults.shape(shape),
        colors = SelectableSurfaceDefaults.colors(
            containerColor = colors.panel,
            contentColor = colors.muted,
            focusedContainerColor = colors.panel2,
            focusedContentColor = colors.text,
            selectedContainerColor = colors.accentBg,
            selectedContentColor = colors.accentText,
            focusedSelectedContainerColor = colors.accentBg,
            focusedSelectedContentColor = colors.accentText,
        ),
        border = SelectableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) Color.Transparent else colors.line), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.focus), shape = shape),
            focusedSelectedBorder = Border(BorderStroke(2.dp, colors.focus), shape = shape),
        ),
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (badge != null) TvCountBadge(badge)
        }
    }
}

/** Stage 3f brings Mail here (the [TvMessagesPage] parameter `mail`). */
@Composable
private fun MailPlaceholder() {
    Text(
        stringResource(R.string.tv_messages_mail_soon),
        style = MaterialTheme.typography.bodyLarge,
        color = GateControlTvTheme.colors.muted,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** The small green count (rail, tabs, start row). */
@Composable
fun TvCountBadge(text: String, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = colors.onAccent,
        modifier = modifier.background(colors.accent, RoundedCornerShape(9.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
