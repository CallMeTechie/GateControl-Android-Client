package com.gatecontrol.android.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.terminal.SshDevice
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * An SSH device of „Fernzugriff“ in the Remote list, in the style of [com.gatecontrol.android.ui.rdp.RdpHostCard]: the
 * terminal glyph with the status dot, the marks SSH and tmux. `engine: guac` shows „Nur Browser“ and opens the portal;
 * a changed host key shows „Gesperrt“; without the right `ssh` there is no connect.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SshDeviceCard(
    device: SshDevice,
    locked: Boolean,
    sshRight: Boolean,
    isSessionActive: Boolean,
    onClick: () -> Unit,
    onBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = GateControlTheme.extraColors
    val (statusLabel, statusTone, dotColor) = when {
        device.maintenance -> Triple(stringResource(R.string.rdp_maintenance), GcTone.Warn, extra.warn)
        device.online -> Triple(stringResource(R.string.rdp_online), GcTone.Ok, MaterialTheme.colorScheme.primary)
        else -> Triple(stringResource(R.string.rdp_offline), GcTone.Neutral, extra.faint)
    }
    val tappable = device.native && sshRight
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(if (isSessionActive) 2.dp else 1.dp, if (isSessionActive) MaterialTheme.colorScheme.primary else extra.border, shape)
            .padding(4.dp),
    ) {
        val desc = stringResource(R.string.term_card_desc, device.name, statusLabel)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .then(if (tappable) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .semantics { contentDescription = desc }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(44.dp)) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(extra.panel2),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GcIcons.Terminal, contentDescription = null, tint = extra.muted, modifier = Modifier.size(22.dp))
                }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                (device.description ?: device.tags.takeIf { it.isNotEmpty() }?.joinToString(" · "))?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    GcChip(statusLabel, tone = statusTone, small = true)
                    GcChip(stringResource(R.string.term_badge_ssh), tone = GcTone.Info, small = true)
                    when {
                        locked -> GcChip(stringResource(R.string.term_badge_locked), tone = GcTone.Error, small = true)
                        !device.native -> GcChip(stringResource(R.string.term_badge_browser), small = true)
                        isSessionActive -> GcChip(stringResource(R.string.term_session_running), tone = GcTone.Ok, small = true)
                        device.tmux != null -> GcChip(stringResource(R.string.term_badge_tmux), small = true)
                    }
                }
            }
            if (tappable) GcChevron()
        }
        when {
            !device.native -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 70.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.term_browser_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.weight(1f))
                GcOutlineButton(text = stringResource(R.string.term_browser_open), onClick = onBrowser, icon = GcIcons.External, fillWidth = false, minHeight = 40.dp)
            }
            !sshRight -> Text(
                stringResource(R.string.term_no_right),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(start = 70.dp, end = 12.dp, bottom = 10.dp),
            )
        }
    }
}
