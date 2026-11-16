package com.gatecontrol.android.ui.terminal

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.terminal.SshSheet
import com.gatecontrol.android.terminal.TermProtocol
import com.gatecontrol.android.terminal.TermText
import com.gatecontrol.android.terminal.TmuxChoice
import com.gatecontrol.android.terminal.TmuxSession
import com.gatecontrol.android.terminal.TmuxState
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily

/** The choice in the sheet: resume a session, a new one with a name, the device's default, or just the shell. */
private sealed interface Pick {
    data class Resume(val name: String) : Pick
    data object New : Pick
    data object Default : Pick
    data object Plain : Pick
}

/**
 * The sheet of an SSH device, like the RDP connect sheet: the tmux sessions to resume (the most recently active
 * preselected), „Neue Sitzung“ with a checked name, the default session, the switch „Diese Sitzung aufzeichnen“ for
 * an optional recording, and the hint before a recorded start. A changed host key locks it (no connect).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshConnectSheet(
    sheet: SshSheet,
    onConnect: (TmuxChoice?, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = GateControlTheme.extraColors.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        SshConnectContent(sheet, onConnect, onDismiss)
    }
}

@Composable
internal fun SshConnectContent(
    sheet: SshSheet,
    onConnect: (TmuxChoice?, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val device = sheet.device
    val loaded = (sheet.tmux as? TmuxState.Loaded)?.list
    val resumable = loaded?.let { TermProtocol.resumable(it) }.orEmpty()
    val taken = loaded?.sessions?.map { it.name }.orEmpty()
    val hasTmux = device.tmux != null && sheet.tmux != TmuxState.None && sheet.tmux != TmuxState.Missing

    var pick by remember(device.id, sheet.tmux) {
        mutableStateOf(
            when {
                !hasTmux -> Pick.Plain
                resumable.isNotEmpty() -> Pick.Resume(resumable.first().name)
                else -> Pick.Default
            },
        )
    }
    var newName by rememberSaveable(device.id, taken.size) { mutableStateOf(TermProtocol.suggestName(taken, device.tmux ?: "tmux")) }
    var record by rememberSaveable(device.id) { mutableStateOf(false) }
    var askRecording by remember { mutableStateOf(false) }
    val nameOk = TermProtocol.validName(newName)

    val choice: TmuxChoice? = when (val p = pick) {
        is Pick.Resume -> TmuxChoice.Attach(p.name)
        Pick.New -> TmuxChoice.Create(newName)
        Pick.Default, Pick.Plain -> null
    }
    val start = {
        if (device.recording == "always" || (device.recording == "optional" && record)) askRecording = true
        else onConnect(choice, false)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(extra.panel2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GcIcons.Terminal, contentDescription = null, tint = extra.muted, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(R.string.term_sheet_sub, device.description ?: if (device.online) stringResource(R.string.rdp_online) else stringResource(R.string.rdp_offline)),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                )
            }
        }

        when (val t = sheet.tmux) {
            TmuxState.Locked -> {
                Notice(stringResource(R.string.term_hk_mismatch_title), stringResource(R.string.term_hk_mismatch_text, device.name), GcTone.Error)
                GcSecondaryButton(text = stringResource(R.string.term_back_to_list), onClick = onDismiss)
                return@Column
            }
            TmuxState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.term_sheet_loading), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
            }
            TmuxState.Unpinned -> Notice(stringResource(R.string.term_first_title), stringResource(R.string.term_first_text), GcTone.Info)
            TmuxState.Missing -> Notice(stringResource(R.string.term_no_tmux_title), stringResource(R.string.term_no_tmux_text), GcTone.Warn)
            is TmuxState.Failed -> Text(stringResource(TermText.error(t.code), t.code), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            else -> Unit
        }

        if (sheet.tmux != TmuxState.Loading) {
            if (resumable.isNotEmpty()) Text(stringResource(R.string.term_sheet_list), style = MaterialTheme.typography.labelLarge, color = extra.muted)
            resumable.forEach { s ->
                Option(selected = pick == Pick.Resume(s.name), onClick = { pick = Pick.Resume(s.name) }, title = stringResource(R.string.term_resume, s.name), sub = sessionLine(s), tag = "resume_${s.name}") {
                    if (s.attached > 0) GcChip(stringResource(R.string.term_attached), tone = GcTone.Info, small = true)
                }
            }
            if (hasTmux) {
                Option(selected = pick == Pick.New, onClick = { pick = Pick.New }, title = stringResource(R.string.term_new), sub = stringResource(R.string.term_new_hint), tag = "new")
                if (pick == Pick.New) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(32) },
                        singleLine = true,
                        isError = !nameOk,
                        supportingText = if (!nameOk) ({ Text(stringResource(R.string.term_new_invalid)) }) else null,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tmux_name"),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = extra.blue, unfocusedBorderColor = extra.border2, cursorColor = extra.blue),
                    )
                }
                Option(selected = pick == Pick.Default, onClick = { pick = Pick.Default }, title = stringResource(R.string.term_default), sub = stringResource(R.string.term_default_hint, device.tmux ?: "tmux"), tag = "default")
            } else {
                Option(selected = true, onClick = {}, title = stringResource(R.string.term_plain), sub = stringResource(R.string.term_plain_hint), tag = "plain")
            }
            loaded?.let { TermProtocol.hidden(it) }?.takeIf { it > 0 }?.let {
                Text(stringResource(R.string.term_hidden, it), style = MaterialTheme.typography.bodySmall, color = extra.faint)
            }
            if (device.recording == "optional") {
                GcSwitchRow(label = stringResource(R.string.term_record), description = stringResource(R.string.term_record_hint), checked = record, onCheckedChange = { record = it })
            }
        }

        sheet.error?.let { code ->
            Text(stringResource(TermText.error(code), code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        GcPrimaryButton(
            text = stringResource(
                when {
                    sheet.tmux == TmuxState.Missing -> R.string.term_btn_anyway
                    pick is Pick.Resume -> R.string.term_btn_resume
                    else -> R.string.term_btn_connect
                },
            ),
            onClick = start,
            enabled = sheet.tmux != TmuxState.Loading && !sheet.connecting && (pick != Pick.New || nameOk),
            loading = sheet.connecting,
            modifier = Modifier.testTag("ssh_connect"),
        )
        GcSecondaryButton(text = stringResource(R.string.cancel), onClick = onDismiss)
    }

    if (askRecording) {
        RecordingDialog(
            always = device.recording == "always",
            onConfirm = {
                askRecording = false
                onConnect(choice, true)
            },
            onDismiss = { askRecording = false },
        )
    }
}

@Composable
private fun sessionLine(s: TmuxSession): String {
    val windows = if (s.windows == 1) stringResource(R.string.term_window_one) else stringResource(R.string.term_windows, s.windows)
    val second = when {
        s.attached > 0 -> stringResource(R.string.term_attached_hint)
        s.activity != null -> stringResource(
            R.string.term_active_ago,
            DateUtils.getRelativeTimeSpanString(s.activity, System.currentTimeMillis().coerceAtLeast(s.activity), DateUtils.MINUTE_IN_MILLIS).toString(),
        )
        else -> null
    }
    return listOfNotNull(windows, second).joinToString(" · ")
}

@Composable
private fun Option(selected: Boolean, onClick: () -> Unit, title: String, sub: String?, tag: String, trailing: @Composable () -> Unit = {}) {
    val extra = GateControlTheme.extraColors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else extra.border, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .testTag("tmux_$tag")
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary))
        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = extra.muted) }
        }
        trailing()
    }
}

@Composable
internal fun Notice(title: String, text: String, tone: GcTone) {
    val extra = GateControlTheme.extraColors
    val (bg, fg) = when (tone) {
        GcTone.Error -> extra.errorBg to MaterialTheme.colorScheme.error
        GcTone.Warn -> extra.warnBg to extra.warn
        else -> extra.blueBg to extra.blue
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = fg)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** „Diese Sitzung wird aufgezeichnet“ before a recorded start (always, or the switch on). */
@Composable
fun RecordingDialog(always: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.error),
            )
        },
        title = { Text(stringResource(R.string.term_rec_title)) },
        text = { Text(stringResource(if (always) R.string.term_rec_always else R.string.term_rec_optional)) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("rec_ok")) { Text(stringResource(R.string.term_rec_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
