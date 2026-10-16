package com.gatecontrol.android.ui.vpn

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * The connection as a 40 dp pill in the start tab's header: a filled
 * "Verbinden" while off, a tinted "Geschützt" while on (a tap disconnects),
 * amber with a spinner while busy (a tap cancels), red "Fehlgeschlagen"
 * after a failure (a tap retries). A long press opens the connection
 * details. [disconnectLocked]: client policy "always on" — the tap only
 * explains that.
 */
@Composable
fun ConnectionChip(
    state: TunnelState,
    disconnectLocked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val busy = state is TunnelState.Connecting || state is TunnelState.Reconnecting || state is TunnelState.Disconnecting
    val (targetBg, targetFg) = when (state) {
        is TunnelState.Connected -> extra.accentBg to extra.accentText
        is TunnelState.Error -> extra.errorBg to scheme.error
        TunnelState.Disconnected -> scheme.primary to scheme.onPrimary
        else -> extra.warnBg to extra.warn
    }
    val bg by animateColorAsState(targetBg, label = "chip_bg")
    val fg by animateColorAsState(targetFg, label = "chip_fg")
    // The filled "Verbinden" has no outline; the tinted states a faint one in their colour.
    val outline = if (state is TunnelState.Disconnected) Color.Transparent else targetFg.copy(alpha = 0.4f)

    val label = when (state) {
        is TunnelState.Connected -> stringResource(R.string.vpn_hero_on)
        is TunnelState.Connecting -> stringResource(R.string.vpn_connecting)
        is TunnelState.Reconnecting -> stringResource(R.string.vpn_reconnecting, state.attempt, state.maxAttempts)
        TunnelState.Disconnecting -> stringResource(R.string.vpn_disconnecting)
        is TunnelState.Error -> stringResource(R.string.vpn_hero_error)
        TunnelState.Disconnected -> stringResource(R.string.vpn_connect)
    }
    val action = when {
        disconnectLocked -> stringResource(R.string.policy_disconnect_locked)
        state is TunnelState.Connected -> stringResource(R.string.vpn_disconnect)
        busy -> stringResource(R.string.common_cancel)
        state is TunnelState.Error -> stringResource(R.string.vpn_retry)
        else -> stringResource(R.string.vpn_connect)
    }
    val details = stringResource(R.string.connection_details_title)
    val shape = RoundedCornerShape(20.dp)

    Row(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, outline, shape)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = action,
                onLongClickLabel = details,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(start = 12.dp, end = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = fg, strokeWidth = 2.dp)
        } else {
            val icon = when (state) {
                is TunnelState.Connected -> GcIcons.ShieldCheck
                is TunnelState.Error -> GcIcons.ShieldX
                else -> GcIcons.Power
            }
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
        )
    }
}
