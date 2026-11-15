package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatBridgeChat
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * The note at the top of a channel (Messenger 1.20, draft „Kanäle“): it is the service's channel, only read here — and,
 * while its posts come without a push, „Einschalten“. With the push on, two lines until „Mehr“ shows all of it.
 */
@Composable
internal fun ChannelHint(network: String, push: Boolean, onPush: () -> Unit) {
    val extra = GateControlTheme.extraColors
    // while the push is off, all of it with „Einschalten“ (the draft); else two lines until „Mehr“
    var open by rememberSaveable(push) { mutableStateOf(!push) }
    val text = stringResource(R.string.chat_ch_hint, network) + if (push) "" else " " + stringResource(R.string.chat_ch_hint_off)
    Row(
        Modifier.fillMaxWidth().background(extra.panel2).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).testTag("channel-hint"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(GcIcons.Info, contentDescription = null, tint = extra.muted, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            maxLines = if (open) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        if (!push) TextButton(onClick = onPush) { Text(stringResource(R.string.chat_ch_hint_on)) }
        else if (!open) TextButton(onClick = { open = true }) { Text(stringResource(R.string.chat_ch_hint_more)) }
    }
}

/**
 * Instead of the input (Messenger 1.20): an eye and „Nur lesen. In Kanälen schreibt nur der Kanal.“ in a channel, a
 * lock and „Nur Admins schreiben in dieser Gruppe.“ in a group where only the admins write.
 */
@Composable
internal fun ReadOnlyBar(kind: ChatBridgeChat.ReadOnly) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().background(extra.panel2).padding(16.dp).testTag("read-only-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (kind == ChatBridgeChat.ReadOnly.Channel) GcIcons.Eye else GcIcons.Lock, contentDescription = null, tint = extra.muted, modifier = Modifier.size(18.dp))
        Text(
            stringResource(if (kind == ChatBridgeChat.ReadOnly.Channel) R.string.chat_ch_read_only else R.string.chat_admins_only),
            style = MaterialTheme.typography.bodyMedium,
            color = extra.muted,
        )
    }
}
