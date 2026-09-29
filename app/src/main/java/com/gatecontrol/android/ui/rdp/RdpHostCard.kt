package com.gatecontrol.android.ui.rdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.R
import com.gatecontrol.android.network.RdpRoute
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily

/** One RDP host: monitor glyph with status dot, name, address, status/credential chips, WoL row. */
@Composable
fun RdpHostCard(
    route: RdpRoute,
    isSessionActive: Boolean,
    onConnect: () -> Unit,
    onWol: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOnline = route.status?.online == true
    val inMaintenance = route.maintenanceEnabled == true
    val wolEnabled = route.wolEnabled == true
    val extra = GateControlTheme.extraColors

    val (statusLabel, statusTone, dotColor) = when {
        inMaintenance -> Triple(stringResource(R.string.rdp_maintenance), GcTone.Warn, extra.warn)
        isOnline -> Triple(stringResource(R.string.rdp_online), GcTone.Ok, MaterialTheme.colorScheme.primary)
        else -> Triple(stringResource(R.string.rdp_offline), GcTone.Neutral, extra.faint)
    }
    val credLabel = when (route.credentialMode.lowercase()) {
        "full" -> stringResource(R.string.rdp_credential_full)
        "user_only" -> stringResource(R.string.rdp_credential_user)
        else -> stringResource(R.string.rdp_credential_none)
    }
    val displayHost = if (route.accessMode == "gateway" && route.externalHostname != null) {
        "${route.externalHostname}:${route.externalPort ?: route.port}"
    } else {
        "${route.host}:${route.port}"
    }
    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(if (isSessionActive) 2.dp else 1.dp, if (isSessionActive) MaterialTheme.colorScheme.primary else extra.border, shape)
            .padding(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "${route.name}, $statusLabel" }
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
                    Icon(GcIcons.Monitor, contentDescription = null, tint = extra.muted, modifier = Modifier.size(22.dp))
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
                    route.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    displayHost,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily, fontSize = 12.sp),
                    color = extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    GcChip(statusLabel, tone = statusTone, small = true)
                    if (isSessionActive) {
                        GcChip(stringResource(R.string.rdp_session_active), tone = GcTone.Ok, small = true)
                    } else {
                        GcChip(credLabel, small = true)
                    }
                }
            }
            GcChevron()
        }

        if (!isOnline && wolEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 70.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.rdp_wol_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    modifier = Modifier.weight(1f),
                )
                GcOutlineButton(
                    text = stringResource(R.string.rdp_wol),
                    onClick = onWol,
                    icon = GcIcons.Zap,
                    fillWidth = false,
                    minHeight = 40.dp,
                )
            }
        }
    }
}
