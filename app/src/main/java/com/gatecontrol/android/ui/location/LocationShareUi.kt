package com.gatecontrol.android.ui.location

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.location.LocationFailure
import com.gatecontrol.android.location.LocationShareState
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Satellite button next to the bell: grey when ready, red while the app
 * locates and sends, and for a minute after sending.
 */
@Composable
fun SatelliteButton(state: LocationShareState, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val red = MaterialTheme.colorScheme.error
    val busy = state is LocationShareState.Locating || state is LocationShareState.Sending
    val description = stringResource(
        when {
            busy -> R.string.location_button_busy
            state is LocationShareState.Sent -> R.string.location_button_sent
            else -> R.string.location_button_share
        },
    )
    if (!state.active) {
        GcIconButton(icon = GcIcons.Satellite, contentDescription = description, onClick = onClick, tint = extra.muted)
        return
    }
    val pulse by rememberInfiniteTransition(label = "satellite").animateFloat(
        initialValue = 1f,
        targetValue = if (busy) 0.45f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "satellitePulse",
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .border(2.dp, red.copy(alpha = 0.35f), CircleShape),
        )
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(extra.errorBg)
                .border(1.dp, red, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                GcIcons.Satellite,
                contentDescription = null,
                tint = red,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { alpha = pulse },
            )
        }
    }
}

/** Progress / result of the last tap, under the header. Nothing when idle. */
@Composable
fun LocationShareBanner(
    state: LocationShareState,
    host: String,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (state) {
        LocationShareState.Idle -> Unit
        LocationShareState.Locating, LocationShareState.Sending -> GcBanner(modifier = live, tone = GcTone.Error, icon = null) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(if (state is LocationShareState.Locating) R.string.location_locating else R.string.location_sending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        is LocationShareState.Sent -> GcBanner(
            modifier = live.clickable(onClick = onDismiss),
            tone = GcTone.Error,
            icon = GcIcons.Check,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.location_sent_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    sentLine(host, state.location.accuracy, state.location.at),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                )
            }
        }
        is LocationShareState.Failed -> GcBanner(
            modifier = live.clickable(onClick = if (state.reason.needsSettings) onOpenSettings else onDismiss),
            tone = GcTone.Warn,
            icon = GcIcons.Satellite,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.location_failed_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(state.reason.messageRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                )
            }
        }
    }
}

@Composable
private fun sentLine(host: String, accuracy: Double?, at: Long): String {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
    return if (accuracy != null) stringResource(R.string.location_sent_line, host, accuracy.roundToInt(), time)
    else stringResource(R.string.location_sent_line_no_accuracy, host, time)
}

private val LocationFailure.needsSettings: Boolean
    get() = this == LocationFailure.NO_PERMISSION || this == LocationFailure.NOT_ALLOWED

val LocationFailure.messageRes: Int
    get() = when (this) {
        LocationFailure.NO_PERMISSION -> R.string.location_fail_permission
        LocationFailure.LOCATION_OFF -> R.string.location_fail_off
        LocationFailure.NO_FIX -> R.string.location_fail_no_fix
        LocationFailure.NOT_ALLOWED -> R.string.location_fail_not_allowed
        LocationFailure.UNSUPPORTED -> R.string.location_fail_unsupported
        LocationFailure.NETWORK -> R.string.location_fail_network
        LocationFailure.NOT_CONFIGURED -> R.string.location_fail_not_configured
    }
