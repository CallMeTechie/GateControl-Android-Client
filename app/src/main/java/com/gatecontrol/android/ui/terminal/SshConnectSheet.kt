package com.gatecontrol.android.ui.terminal

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.terminal.SshSheet
import com.gatecontrol.android.terminal.TermProtocol
import com.gatecontrol.android.terminal.TermText
import com.gatecontrol.android.terminal.TmuxChoice
import com.gatecontrol.android.terminal.TmuxKill
import com.gatecontrol.android.terminal.TmuxList
import com.gatecontrol.android.terminal.TmuxSession
import com.gatecontrol.android.terminal.TmuxState
import com.gatecontrol.android.terminal.TmuxWindow
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
 *
 * With a server that can end tmux sessions (`kill`, plugin 1.8 — design T5) every session opens to its windows and has
 * a menu ⋮: „Fenster öffnen“, „Fenster schließen“, „Sitzung beenden“, plus „Alle getrennten Sitzungen beenden“ from
 * two detached sessions on. Each asks first (T2–T4); sessions the app cannot attach are listed to be ended.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshConnectSheet(
    sheet: SshSheet,
    onConnect: (TmuxChoice?, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOpenWindow: (String, Int, Boolean) -> Unit = { _, _, _ -> },
    onKill: (TmuxKill) -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = GateControlTheme.extraColors.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        SshConnectContent(sheet, onConnect, onDismiss, onOpenWindow, onKill)
    }
}

@Composable
internal fun SshConnectContent(
    sheet: SshSheet,
    onConnect: (TmuxChoice?, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOpenWindow: (String, Int, Boolean) -> Unit = { _, _, _ -> },
    onKill: (TmuxKill) -> Unit = {},
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
    /** „Fenster öffnen“ waiting for the recording hint: session and window index. */
    var openWindow by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var ask by remember { mutableStateOf<TmuxKill?>(null) }
    val expanded = remember(device.id) { mutableStateListOf<String>() }
    val nameOk = TermProtocol.validName(newName)
    val canKill = loaded?.kill == true && !sheet.connecting
    val unattachable = if (loaded?.kill == true) loaded.sessions.filter { !it.attachable }.sortedByDescending { it.activity ?: Long.MIN_VALUE } else emptyList()

    val choice: TmuxChoice? = when (val p = pick) {
        is Pick.Resume -> TmuxChoice.Attach(p.name)
        Pick.New -> TmuxChoice.Create(newName)
        Pick.Default, Pick.Plain -> null
    }
    val recordingDue = device.recording == "always" || (device.recording == "optional" && record)
    val start = {
        openWindow = null
        if (recordingDue) askRecording = true else onConnect(choice, false)
    }
    val openWin = { session: String, index: Int ->
        if (recordingDue) {
            openWindow = session to index
            askRecording = true
        } else {
            onOpenWindow(session, index, false)
        }
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
            if (resumable.isEmpty() && unattachable.isNotEmpty()) Text(stringResource(R.string.term_sheet_list), style = MaterialTheme.typography.labelLarge, color = extra.muted)
            sheet.killError?.let { code ->
                Text(stringResource(TermText.error(code), code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (sheet.killing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.term_kill_running), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            }
            (resumable + unattachable).forEach { s ->
                val killable = canKill && loaded?.killable(s) == true
                val isOpen = s.name in expanded
                val tools: @Composable () -> Unit = {
                    if (s.attached > 0) GcChip(stringResource(R.string.term_attached), tone = GcTone.Info, small = true)
                    if (killable && s.windowList.isNotEmpty()) {
                        IconButton(onClick = { if (isOpen) expanded.remove(s.name) else expanded.add(s.name) }, modifier = Modifier.testTag("tmux_expand_${s.name}")) {
                            Icon(
                                if (isOpen) GcIcons.ChevronDown else GcIcons.ChevronRight,
                                contentDescription = stringResource(if (isOpen) R.string.term_windows_hide else R.string.term_windows_show),
                                tint = extra.muted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    if (killable) {
                        RowMenu(tag = "tmux_menu_${s.name}", enabled = !sheet.killing) { close ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.term_kill_session), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(GcIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    close()
                                    ask = TmuxKill.Session(s)
                                },
                                modifier = Modifier.testTag("tmux_kill_${s.name}"),
                            )
                        }
                    }
                }
                if (s.attachable) {
                    Option(selected = pick == Pick.Resume(s.name), onClick = { pick = Pick.Resume(s.name) }, title = stringResource(R.string.term_resume, s.name), sub = sessionLine(s), tag = "resume_${s.name}", trailing = tools)
                } else {
                    Option(selected = false, onClick = {}, title = s.name, sub = listOf(sessionLine(s), stringResource(R.string.term_not_resumable)).joinToString(" · "), tag = "other_${s.name}", selectable = false, trailing = tools)
                }
                if (killable && isOpen) {
                    s.windowList.forEach { w ->
                        WindowRow(
                            w = w,
                            tag = "${s.name}_${w.index}",
                            enabled = !sheet.killing,
                            onOpen = if (s.attachable) ({ openWin(s.name, w.index) }) else null,
                            onClose = { ask = TmuxKill.Window(s, w) },
                        )
                    }
                }
            }
            loaded?.detached?.takeIf { canKill && it.size >= 2 }?.let { detached ->
                TextButton(
                    onClick = { ask = TmuxKill.Detached(detached) },
                    enabled = !sheet.killing,
                    modifier = Modifier.testTag("tmux_kill_detached"),
                ) {
                    Icon(GcIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.term_kill_detached_btn, detached.size), color = MaterialTheme.colorScheme.error)
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
            enabled = sheet.tmux != TmuxState.Loading && !sheet.connecting && !sheet.killing && (pick != Pick.New || nameOk),
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
                val w = openWindow
                openWindow = null
                if (w != null) onOpenWindow(w.first, w.second, true) else onConnect(choice, true)
            },
            onDismiss = {
                askRecording = false
                openWindow = null
            },
        )
    }

    ask?.let { k ->
        KillDialog(
            kill = k,
            list = loaded,
            deviceName = device.name,
            onConfirm = { chosen ->
                ask = null
                onKill(chosen)
            },
            onDismiss = { ask = null },
        )
    }
}

/** The menu ⋮ of a row; [items] get a function that closes it. */
@Composable
private fun RowMenu(tag: String, enabled: Boolean, items: @Composable (close: () -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.testTag(tag)) {
            Icon(GcIcons.MoreVertical, contentDescription = stringResource(R.string.term_menu), tint = GateControlTheme.extraColors.muted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { items { menu = false } }
    }
}

/** One window of an open session: index, name, what runs in it, „aktiv“, and its menu ⋮ (design T5). */
@Composable
private fun WindowRow(w: TmuxWindow, tag: String, enabled: Boolean, onOpen: (() -> Unit)?, onClose: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp)
            .heightIn(min = 44.dp)
            .testTag("tmux_win_$tag"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(w.index.toString(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily), color = extra.faint)
        Column(Modifier.weight(1f)) {
            Text(w.name.ifEmpty { "–" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            w.command?.takeIf { it != w.name }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily), color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (w.active) GcChip(stringResource(R.string.term_window_active), tone = GcTone.Info, small = true)
        RowMenu(tag = "tmux_win_menu_$tag", enabled = enabled) { close ->
            if (onOpen != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.term_window_open, w.index)) },
                    leadingIcon = { Icon(GcIcons.Terminal, contentDescription = null) },
                    onClick = {
                        close()
                        onOpen()
                    },
                    modifier = Modifier.testTag("tmux_win_open_$tag"),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.term_window_close), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(GcIcons.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    close()
                    onClose()
                },
                modifier = Modifier.testTag("tmux_win_close_$tag"),
            )
        }
    }
}

/**
 * The questions before ending (design T2–T4): „Laufende Programme darin werden beendet“, red when someone is attached,
 * the last window ends the session, and for all detached sessions a list to deselect single ones.
 */
@Composable
private fun KillDialog(kill: TmuxKill, list: TmuxList?, deviceName: String, onConfirm: (TmuxKill) -> Unit, onDismiss: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val red = MaterialTheme.colorScheme.error
    when (kill) {
        is TmuxKill.Session -> {
            val s = kill.session
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.term_kill_session_title, s.name)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(deviceName, style = MaterialTheme.typography.bodySmall, color = extra.muted)
                        Text(stringResource(R.string.term_kill_session_text))
                        if (s.attached > 0) AttachedWarning(s.attached)
                        Text(listOf(sessionLine(s), TermProtocol.commands(s)).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onConfirm(kill) }, modifier = Modifier.testTag("kill_ok")) {
                        Text(stringResource(if (s.attached > 0) R.string.term_kill_anyway else R.string.term_kill_session), color = red)
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("kill_cancel")) { Text(stringResource(R.string.cancel)) } },
            )
        }
        is TmuxKill.Window -> {
            val s = kill.session
            val w = kill.window
            val last = TermProtocol.lastWindow(s)
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.term_kill_window_title, w.name.ifEmpty { w.index.toString() })) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.term_kill_window_sub, s.name, w.index), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                        val others = (s.windowList.size - 1).coerceAtLeast(0)
                        Text(
                            listOfNotNull(
                                w.command?.let { stringResource(R.string.term_kill_window_text_cmd, it) } ?: stringResource(R.string.term_kill_session_text_short),
                                if (!last && others > 0) pluralStringResource(R.plurals.term_kill_window_others, others, others) else null,
                            ).joinToString(" "),
                        )
                        if (last) Notice(stringResource(R.string.term_kill_window_last_title), stringResource(R.string.term_kill_window_last), GcTone.Warn)
                        if (last && s.attached > 0) AttachedWarning(s.attached)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onConfirm(kill) }, modifier = Modifier.testTag("kill_ok")) {
                        Text(stringResource(R.string.term_window_close), color = red)
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("kill_cancel")) { Text(stringResource(R.string.cancel)) } },
            )
        }
        is TmuxKill.Detached -> {
            val chosen = remember(kill) { mutableStateListOf<String>().apply { addAll(kill.sessions.mapNotNull { it.id }) } }
            val staying = list?.sessions?.filter { it.attached > 0 }.orEmpty()
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(pluralStringResource(R.plurals.term_kill_detached_title, kill.sessions.size, kill.sessions.size)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(deviceName, style = MaterialTheme.typography.bodySmall, color = extra.muted)
                        Text(
                            listOfNotNull(
                                stringResource(R.string.term_kill_session_text_short),
                                staying.takeIf { it.isNotEmpty() }?.let {
                                    pluralStringResource(R.plurals.term_kill_detached_keep, it.size, it.size, it.joinToString(", ") { s -> "„${s.name}“" })
                                },
                            ).joinToString(" "),
                        )
                        kill.sessions.forEach { s ->
                            val id = s.id ?: return@forEach
                            val on = id in chosen
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(role = Role.Checkbox) { if (on) chosen.remove(id) else chosen.add(id) }
                                    .testTag("kill_pick_${s.name}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                                ) {
                                    Text(s.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Text(listOf(sessionLine(s), TermProtocol.commands(s)).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { onConfirm(TmuxKill.Detached(kill.sessions.filter { it.id in chosen })) },
                        enabled = chosen.isNotEmpty(),
                        modifier = Modifier.testTag("kill_ok"),
                    ) {
                        Text(pluralStringResource(R.plurals.term_kill_detached_ok, chosen.size, chosen.size), color = if (chosen.isNotEmpty()) red else extra.faint)
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("kill_cancel")) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

/** Red: „Gerade ist jemand verbunden“ — the terminal there ends at once. */
@Composable
private fun AttachedWarning(devices: Int) {
    Notice(stringResource(R.string.term_kill_attached_title), pluralStringResource(R.plurals.term_kill_attached_text, devices, devices), GcTone.Error)
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
private fun Option(selected: Boolean, onClick: () -> Unit, title: String, sub: String?, tag: String, selectable: Boolean = true, trailing: @Composable () -> Unit = {}) {
    val extra = GateControlTheme.extraColors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else extra.border, shape)
            .clickable(enabled = selectable, role = Role.RadioButton, onClick = onClick)
            .testTag("tmux_$tag")
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = selectable, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary))
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
