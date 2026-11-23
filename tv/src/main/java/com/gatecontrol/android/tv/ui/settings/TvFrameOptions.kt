package com.gatecontrol.android.tv.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.frame.TvFrameFeed
import com.gatecontrol.android.tv.frame.TvFrameOrder
import com.gatecontrol.android.tv.frame.TvFrameSchedule
import com.gatecontrol.android.tv.frame.TvFrameSettings
import com.gatecontrol.android.tv.frame.TvFrameStatus
import com.gatecontrol.android.tv.frame.TvFrameTexts
import com.gatecontrol.android.tv.ui.components.TvOptionRow
import com.gatecontrol.android.tv.ui.components.TvRowEnd
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme

/**
 * Einstellungen › Fotorahmen: what is shared for the photo frame (only photos shared on purpose in the phone or Windows
 * app), how often the picture changes and in which order (each "as the server" or the TV's own), the time on top and
 * whether the frame starts when the TV sat idle in GateControl. OK on a row switches or goes to the next choice.
 * [server] the last `GET frame` (its seconds and order), null while not known.
 */
@Composable
fun TvFrameOptions(
    frame: TvFrameSettings,
    status: TvFrameStatus,
    server: TvFrameFeed?,
    onChange: (TvFrameSettings) -> Unit,
    onStart: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = GateControlTvTheme.colors
    LaunchedEffect(Unit) { onRefresh() }
    Text(stringResource(R.string.tv_frame_intro), style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp))
    TvOptionRow(
        title = stringResource(R.string.tv_frame_photos),
        description = if (status is TvFrameStatus.Photos) pluralStringResource(R.plurals.tv_frame_photos_count, status.count, status.count)
        else stringResource(TvFrameTexts.message(status)),
        end = TvRowEnd.Arrow,
        onClick = onRefresh,
    )
    TvOptionRow(
        title = stringResource(R.string.tv_frame_start),
        description = null,
        end = TvRowEnd.Arrow,
        onClick = onStart,
    )
    TvOptionRow(
        title = stringResource(R.string.tv_frame_seconds),
        description = if (frame.seconds > 0) secondsText(frame.seconds)
        else stringResource(R.string.tv_frame_as_server, secondsText(TvFrameSchedule.effectiveSeconds(frame, server))),
        end = TvRowEnd.Arrow,
        onClick = { onChange(frame.copy(seconds = TvFrameSchedule.cycle(TvFrameSchedule.SECONDS, frame.seconds))) },
    )
    TvOptionRow(
        title = stringResource(R.string.tv_frame_order),
        description = frame.order?.let { orderText(it) }
            ?: stringResource(R.string.tv_frame_as_server, orderText(TvFrameSchedule.effectiveOrder(frame, server))),
        end = TvRowEnd.Arrow,
        onClick = { onChange(frame.copy(order = TvFrameSchedule.cycle(TvFrameSchedule.ORDERS, frame.order))) },
    )
    TvOptionRow(
        title = stringResource(R.string.tv_frame_clock),
        description = null,
        end = TvRowEnd.Switch(frame.clock),
        onClick = { onChange(frame.copy(clock = !frame.clock)) },
    )
    TvOptionRow(
        title = stringResource(R.string.tv_frame_idle),
        description = if (frame.idleMinutes == 0) stringResource(R.string.tv_frame_idle_off) else
            pluralStringResource(R.plurals.tv_frame_idle_after, frame.idleMinutes, frame.idleMinutes),
        end = TvRowEnd.Arrow,
        onClick = { onChange(frame.copy(idleMinutes = TvFrameSchedule.cycle(TvFrameSchedule.IDLE_MINUTES, frame.idleMinutes))) },
    )
}

@Composable
private fun orderText(order: TvFrameOrder): String =
    stringResource(if (order == TvFrameOrder.RANDOM) R.string.tv_frame_order_random else R.string.tv_frame_order_date)

@Composable
private fun secondsText(seconds: Int): String =
    if (seconds % 60 == 0) pluralStringResource(R.plurals.tv_frame_minutes, seconds / 60, seconds / 60)
    else pluralStringResource(R.plurals.tv_frame_seconds_every, seconds, seconds)
