package com.gatecontrol.android.tv.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.frame.TvFrameSchedule
import com.gatecontrol.android.tv.frame.TvFrameSettings
import com.gatecontrol.android.tv.ui.components.TvOptionRow
import com.gatecontrol.android.tv.ui.components.TvRowEnd
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme

/**
 * Einstellungen › Fotorahmen: the Drive folder (chosen on the Drive page, "Als Fotorahmen verwenden"), how often the
 * picture changes, shuffled or by name, the time on top and whether the frame starts when the TV sat idle in
 * GateControl. OK on a row switches or goes to the next choice.
 */
@Composable
fun TvFrameOptions(frame: TvFrameSettings, onChange: (TvFrameSettings) -> Unit, onStart: () -> Unit) {
    val colors = GateControlTvTheme.colors
    Text(stringResource(R.string.tv_frame_intro), style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp))
    TvOptionRow(
        title = stringResource(R.string.tv_frame_folder),
        description = frame.folderName ?: stringResource(R.string.tv_frame_folder_none),
        end = TvRowEnd.None,
        onClick = {},
    )
    TvOptionRow(
        title = stringResource(R.string.tv_frame_start),
        description = if (frame.ready) null else stringResource(R.string.tv_frame_folder_none_short),
        end = TvRowEnd.Arrow,
        onClick = { if (frame.ready) onStart() },
    )
    TvOptionRow(
        title = stringResource(R.string.tv_frame_seconds),
        description = secondsText(frame.seconds),
        end = TvRowEnd.Arrow,
        onClick = { onChange(frame.copy(seconds = TvFrameSchedule.cycle(TvFrameSchedule.SECONDS, frame.seconds))) },
    )
    TvOptionRow(
        title = stringResource(R.string.tv_frame_shuffle),
        description = stringResource(if (frame.shuffle) R.string.tv_frame_shuffle_on else R.string.tv_frame_shuffle_off),
        end = TvRowEnd.Switch(frame.shuffle),
        onClick = { onChange(frame.copy(shuffle = !frame.shuffle)) },
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
private fun secondsText(seconds: Int): String =
    if (seconds % 60 == 0) pluralStringResource(R.plurals.tv_frame_minutes, seconds / 60, seconds / 60)
    else pluralStringResource(R.plurals.tv_frame_seconds_every, seconds, seconds)
