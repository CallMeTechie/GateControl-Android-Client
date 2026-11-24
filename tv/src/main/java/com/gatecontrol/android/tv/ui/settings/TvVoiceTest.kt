package com.gatecontrol.android.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.TvStartRow
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvOptionRow
import com.gatecontrol.android.tv.ui.components.TvRowEnd
import com.gatecontrol.android.tv.ui.drive.requestSoon
import com.gatecontrol.android.tv.ui.home.TvIcons
import com.gatecontrol.android.tv.ui.input.LocalTvInputVariant
import com.gatecontrol.android.tv.ui.input.TvInputField
import com.gatecontrol.android.tv.ui.input.TvInputVariant
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme

/** Einstellungen › Startseite: each row of the start page on or off (quick actions always show). */
@Composable
fun TvStartRowOptions(off: Set<TvStartRow>, onSet: (TvStartRow, Boolean) -> Unit) {
    Text(stringResource(R.string.tv_set_start_desc), style = MaterialTheme.typography.bodySmall, color = GateControlTvTheme.colors.muted)
    for (row in TvStartRow.entries) {
        val on = row !in off
        TvOptionRow(title = stringResource(rowTitle(row)), description = null, end = TvRowEnd.Switch(on), onClick = { onSet(row, !on) })
    }
}

private fun rowTitle(row: TvStartRow): Int = when (row) {
    TvStartRow.CAMERAS -> R.string.tv_row_cameras
    TvStartRow.HOME_TODAY -> R.string.tv_row_home_today
    TvStartRow.MEDIA -> R.string.tv_row_media_resume
    TvStartRow.DRIVE -> R.string.tv_row_drive
    TvStartRow.GATEDROP -> R.string.tv_row_gatedrop
}

/** "Spracheingabe testen" (Einstellungen › Gerät): opens [TvVoiceTestDialog]. */
@Composable
fun TvVoiceTestOption() {
    var open by remember { mutableStateOf(false) }
    TvOptionRow(
        title = stringResource(R.string.tv_dictation),
        description = stringResource(R.string.tv_dictation_desc),
        end = TvRowEnd.Arrow,
        onClick = { open = true },
    )
    if (open) TvVoiceTestDialog(onDismiss = { open = false })
}

/**
 * Tries the dictation of Fire OS on this TV (stage 3a, done at the stick): the input field of the TV ([TvInputField])
 * in both variants, Compose and a classic EditText, since the reports on Fire TV's dictation are about classic fields. OK opens
 * the system keyboard, then hold the microphone button and speak; what arrived shows large. Nothing is sent or kept:
 * the texts live in this dialog only. Fire OS and Alexa do the dictation (no microphone permission here).
 */
@Composable
fun TvVoiceTestDialog(onDismiss: () -> Unit) {
    val colors = GateControlTvTheme.colors
    var compose by remember { mutableStateOf("") }
    var classic by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.86f).background(colors.panel, RoundedCornerShape(12.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.tv_dictation), style = MaterialTheme.typography.headlineSmall, color = colors.text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(TvIcons.Mic, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.tv_home_voice_hint), style = MaterialTheme.typography.bodyMedium, color = colors.text)
            }
            // the same field as the composer and the pages, once in each variant (TvInput.DEFAULT picks one)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CompositionLocalProvider(LocalTvInputVariant provides TvInputVariant.COMPOSE) {
                    TvInputField(
                        value = compose,
                        onValueChange = { compose = it },
                        label = stringResource(R.string.tv_dictation_compose),
                        maxLength = MAX,
                        modifier = Modifier.weight(1f),
                        fieldModifier = Modifier.focusRequester(first),
                    )
                }
                CompositionLocalProvider(LocalTvInputVariant provides TvInputVariant.CLASSIC) {
                    TvInputField(
                        value = classic,
                        onValueChange = { classic = it },
                        label = stringResource(R.string.tv_dictation_classic),
                        maxLength = MAX,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(stringResource(R.string.tv_dictation_result), style = MaterialTheme.typography.labelMedium, color = colors.muted)
            Column(
                Modifier.fillMaxWidth().heightIn(min = 72.dp).background(colors.bg, RoundedCornerShape(8.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Result("1", compose)
                Result("2", classic)
            }
            Text(stringResource(R.string.tv_dictation_privacy), style = MaterialTheme.typography.bodySmall, color = colors.warn)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(stringResource(R.string.tv_dictation_clear), onClick = {
                    compose = ""
                    classic = ""
                })
                TvButton(stringResource(R.string.tv_dictation_close), onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { first.requestSoon() }
}

@Composable
private fun Result(field: String, text: String) {
    val colors = GateControlTvTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(field, style = MaterialTheme.typography.titleMedium, color = colors.faint)
        Text(
            text.ifEmpty { stringResource(R.string.tv_dictation_empty) },
            style = MaterialTheme.typography.headlineMedium,
            color = if (text.isEmpty()) colors.faint else colors.text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val MAX = 2_000
