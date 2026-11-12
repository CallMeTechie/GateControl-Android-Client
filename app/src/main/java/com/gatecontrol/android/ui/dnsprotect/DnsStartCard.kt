package com.gatecontrol.android.ui.dnsprotect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.dnsprotect.DnsLogic
import com.gatecontrol.android.dnsprotect.DnsStatus
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.util.findComponentActivity

/**
 * Tile "DNS-Schutz" on the start tab, while the server reports a DNS
 * protection: the state, today's counters and the button. "Unterbrechen"
 * and a tap on the tile open the screen in the Netzwerk tab ([onOpen]);
 * "Einschalten" switches back on right here.
 */
@Composable
fun DnsStartCard(
    onOpen: () -> Unit,
    viewModel: DnsProtectViewModel = hiltViewModel(LocalContext.current.findComponentActivity()),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val remaining = rememberDnsRemaining(ui.snapshot, onEnded = { viewModel.refresh() })
    // Back on Start: the counters and the state as they are now.
    val shown = ui.coarse != null && !ui.gone
    LifecycleResumeEffect(shown) {
        if (shown) viewModel.refresh()
        onPauseOrDispose { }
    }
    DnsStartTile(ui = ui, remaining = remaining, onOpen = onOpen, onResume = { viewModel.resume() })
}

/** The tile for a state; nothing without a reported DNS protection. */
@Composable
internal fun DnsStartTile(ui: DnsUiState, remaining: Long?, onOpen: () -> Unit, onResume: () -> Unit) {
    val coarse = ui.coarse ?: return
    if (ui.gone) return
    val extra = GateControlTheme.extraColors
    val status = ui.snapshot?.status
    // Before the first status only what the permissions tell: down, or nothing certain yet.
    val state = status?.state ?: DnsLogic.coarseState(coarse).takeIf { it != DnsStatus.ACTIVE }
    if (state == DnsStatus.OFF) return

    val title: String
    val subtitle: String
    when (state) {
        DnsStatus.ACTIVE -> {
            title = stringResource(R.string.dns_tile_active)
            val c = status?.counters
            subtitle = if (c == null) "" else stringResource(
                R.string.dns_tile_counts,
                dnsCount(c.blocked),
                dnsCount(c.queries),
                DnsLogic.percent(c.percent, appLocale()),
            )
        }
        DnsStatus.PAUSED -> {
            title = stringResource(R.string.dns_tile_paused)
            val until = DnsLogic.timeOfDay(status?.pause?.until)
            subtitle = if (remaining != null && until != null) {
                stringResource(R.string.dns_tile_paused_sub, DnsLogic.clock(remaining), until)
            } else {
                stringResource(if (status?.pause?.byMe == true) R.string.dns_state_paused_open_me else R.string.dns_state_paused_open_other)
            }
        }
        DnsStatus.FALLBACK -> {
            title = stringResource(R.string.dns_tile_fallback)
            subtitle = stringResource(R.string.dns_state_fallback_sub)
        }
        DnsStatus.UNFILTERED -> {
            title = stringResource(R.string.nav_dns_protect)
            subtitle = stringResource(R.string.dns_tile_unfiltered)
        }
        DnsStatus.STARTING -> {
            title = stringResource(R.string.nav_dns_protect)
            subtitle = stringResource(R.string.dns_state_starting_title)
        }
        else -> {
            title = stringResource(R.string.nav_dns_protect)
            subtitle = stringResource(R.string.dns_tile_loading)
        }
    }
    val (border, iconBg, tint) = when (state) {
        DnsStatus.ACTIVE -> Triple(extra.border, extra.accentBg, extra.accentText)
        DnsStatus.PAUSED -> Triple(extra.warn.copy(alpha = 0.55f), extra.warnBg, extra.warn)
        DnsStatus.FALLBACK -> Triple(MaterialTheme.colorScheme.error.copy(alpha = 0.55f), extra.errorBg, MaterialTheme.colorScheme.error)
        else -> Triple(extra.border, extra.panel2, extra.muted)
    }
    GcCard(
        modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onOpen),
        borderColor = border,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GcIconSquare(background = iconBg) {
                Icon(dnsStateIcon(state.orEmpty()), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
            when {
                state == DnsStatus.PAUSED && status?.may?.resume == true ->
                    TilePill(stringResource(R.string.dns_tile_resume), primary = true, enabled = !ui.working, onClick = onResume)
                state == DnsStatus.ACTIVE && status?.may?.pause == true ->
                    TilePill(stringResource(R.string.dns_tile_pause), primary = false, icon = GcIcons.Pause, onClick = onOpen)
                else -> GcChevron()
            }
        }
    }
}

/** The tile's small button (the mockup's `.go`): filled for "Einschalten", plain for "Unterbrechen". */
@Composable
private fun TilePill(text: String, primary: Boolean, onClick: () -> Unit, icon: ImageVector? = null, enabled: Boolean = true) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val content = if (primary) scheme.onPrimary else scheme.onSurface
    Row(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(if (primary) scheme.primary else extra.panel2)
            .then(if (primary) Modifier else Modifier.border(1.dp, extra.border2, CircleShape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}
