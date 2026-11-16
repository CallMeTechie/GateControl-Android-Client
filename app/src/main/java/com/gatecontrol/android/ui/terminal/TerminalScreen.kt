package com.gatecontrol.android.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gatecontrol.android.R
import com.gatecontrol.android.terminal.Latch
import com.gatecontrol.android.terminal.TermKey
import com.gatecontrol.android.terminal.TermKeys
import com.gatecontrol.android.terminal.TermPhase
import com.gatecontrol.android.terminal.TermPrefs
import com.gatecontrol.android.terminal.TermProtocol
import com.gatecontrol.android.terminal.TermSettings
import com.gatecontrol.android.terminal.TermText
import com.gatecontrol.android.terminal.TermUi
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import com.termux.view.TerminalView
import kotlinx.coroutines.delay

/** What the screen asks of [com.gatecontrol.android.terminal.TerminalActivity]. */
class TerminalScreenActions(
    val createView: (Context) -> TerminalView,
    val releaseView: () -> Unit,
    val onKey: (TermKey) -> Unit,
    val onPaste: () -> Unit,
    val onPasteConfirm: (String, Boolean) -> Unit,
    val onPasteCancel: () -> Unit,
    val onCopyAll: () -> Unit,
    val onFont: (Int) -> Unit,
    val onExtraKeys: (Boolean) -> Unit,
    val onTrust: (Boolean) -> Unit,
    val onReconnect: () -> Unit,
    val onLeave: () -> Unit,
    val onLeaveCancel: () -> Unit,
    val onDisconnect: () -> Unit,
)

/** The terminal's surface stays dark in both themes (decision 5): programs like htop, mc and tmux expect it. */
private val TermBackground = Color(0xFF000000)
private val TermOverlay = Color(0xCC000000)

/**
 * The terminal: a title row (name, state, the red dot while recorded, the menu), the dark terminal, the bar with the
 * extra keys (two rows upright, one row with „F“ in landscape, hidden with a hardware keyboard) and the cards and
 * dialogs of the states — connecting, host key, interrupted, no connection, ended, an error.
 */
@Composable
fun TerminalScreen(
    ui: TermUi,
    settings: TermSettings,
    ctrl: Latch,
    alt: Latch,
    pendingPaste: String?,
    fontHint: Long,
    confirmLeave: Boolean,
    actions: TerminalScreenActions,
    terminal: @Composable (Modifier) -> Unit = { m ->
        AndroidView(factory = actions.createView, modifier = m, onRelease = { actions.releaseView() })
    },
) {
    val config = LocalConfiguration.current
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val hardwareKeyboard = config.keyboard == Configuration.KEYBOARD_QWERTY && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    val phase = ui.phase
    var fnOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        TitleRow(ui, settings, actions)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TermBackground)
                .testTag("terminal_surface"),
        ) {
            terminal(Modifier.fillMaxSize())
            if (phase is TermPhase.Ready && phase.tmuxMissing) {
                TmuxMissingBanner(Modifier.align(Alignment.TopCenter))
            }
            FontHint(fontHint, ui, settings.fontSp, Modifier.align(Alignment.TopCenter))
            StateOverlay(ui, actions)
        }
        if (settings.extraKeys && !hardwareKeyboard) {
            KeyBar(landscape, ctrl, alt, fnOpen, onFn = { fnOpen = !fnOpen }, onKey = actions.onKey)
        }
    }

    if (phase is TermPhase.HostKey) HostKeyDialog(ui.deviceName, phase, actions.onTrust)
    if (phase is TermPhase.Failed && phase.verdict == TermProtocol.Verdict.LOCKED) MismatchDialog(ui.deviceName, actions.onDisconnect)
    pendingPaste?.let { PasteDialog(it, actions.onPasteConfirm, actions.onPasteCancel) }
    if (confirmLeave) LeaveDialog(ui, actions.onDisconnect, actions.onLeaveCancel)
}

@Composable
private fun TitleRow(ui: TermUi, settings: TermSettings, actions: TerminalScreenActions) {
    val extra = GateControlTheme.extraColors
    val phase = ui.phase
    val recording = phase is TermPhase.Ready && phase.recording
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onLeave) {
            Icon(GcIcons.Back, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recording) RecordingDot()
                Text(ui.deviceName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(statusLine(ui), style = MaterialTheme.typography.bodySmall, color = if (recording) MaterialTheme.colorScheme.error else extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("term_status"))
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("term_menu")) {
                Icon(GcIcons.MoreVertical, contentDescription = stringResource(R.string.term_menu), tint = MaterialTheme.colorScheme.onSurface)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.term_menu_paste)) }, onClick = { menu = false; actions.onPaste() })
                DropdownMenuItem(text = { Text(stringResource(R.string.term_menu_copy_all)) }, onClick = { menu = false; actions.onCopyAll() })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.term_menu_font, settings.fontSp)) },
                    onClick = {},
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { actions.onFont(settings.fontSp - 1) }, enabled = settings.fontSp > TermPrefs.FONT_MIN) {
                                Icon(GcIcons.Minus, contentDescription = stringResource(R.string.term_font_smaller))
                            }
                            IconButton(onClick = { actions.onFont(settings.fontSp + 1) }, enabled = settings.fontSp < TermPrefs.FONT_MAX) {
                                Icon(GcIcons.Plus, contentDescription = stringResource(R.string.term_font_bigger))
                            }
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.term_menu_keys)) },
                    onClick = { actions.onExtraKeys(!settings.extraKeys) },
                    trailingIcon = { Checkbox(checked = settings.extraKeys, onCheckedChange = null) },
                )
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.term_reconnect)) }, onClick = { menu = false; actions.onReconnect() })
                DropdownMenuItem(text = { Text(stringResource(R.string.term_disconnect), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; actions.onLeave() })
            }
        }
    }
}

@Composable
private fun statusLine(ui: TermUi): String {
    val size = if (ui.cols > 0) "${ui.cols}×${ui.rows}" else null
    return when (val p = ui.phase) {
        is TermPhase.Connecting -> stringResource(if (p.attempt > 0) R.string.term_status_reconnecting else R.string.term_status_connecting)
        is TermPhase.HostKey -> stringResource(R.string.term_status_connecting)
        is TermPhase.Reconnecting -> stringResource(R.string.term_status_reconnecting)
        TermPhase.Offline -> stringResource(R.string.term_status_offline)
        is TermPhase.Exited, is TermPhase.Failed -> stringResource(R.string.term_status_ended)
        is TermPhase.Ready -> listOfNotNull(
            if (p.recording) stringResource(R.string.term_status_recording) else null,
            if (p.view) stringResource(R.string.term_status_view) else null,
            p.tmuxSession?.let { "tmux: $it" },
            size,
        ).joinToString(" · ")
    }
}

/** The red dot while the server reports `recording: true`; it pulses slightly, still with „Bewegung reduzieren“. */
@Composable
private fun RecordingDot() {
    val context = LocalContext.current
    val reduce = remember {
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val desc = stringResource(R.string.term_recording_dot)
    val a = if (reduce) 1f else rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.45f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "rec").value
    Box(
        Modifier
            .size(10.dp)
            .alpha(a)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error)
            .semantics { contentDescription = desc }
            .testTag("rec_dot"),
    )
}

@Composable
private fun TmuxMissingBanner(modifier: Modifier) {
    var shown by rememberSaveable { mutableStateOf(true) }
    if (!shown) return
    val extra = GateControlTheme.extraColors
    Row(
        modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(extra.warnBg)
            .clickable { shown = false }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(stringResource(R.string.term_tmux_missing_hint), style = MaterialTheme.typography.bodySmall, color = extra.warn)
    }
}

@Composable
private fun FontHint(at: Long, ui: TermUi, font: Int, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(at) {
        if (at == 0L) return@LaunchedEffect
        visible = true
        delay(1500)
        visible = false
    }
    if (!visible) return
    Text(
        stringResource(R.string.term_font_toast, font, ui.cols, ui.rows),
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = modifier
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(TermOverlay)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** The cards over the dimmed terminal: interrupted, no connection, ended, an error (the last screen stays). */
@Composable
private fun StateOverlay(ui: TermUi, actions: TerminalScreenActions) {
    val p = ui.phase
    val tmux = ui.tmux ?: (p as? TermPhase.Ready)?.tmuxSession
    when (p) {
        is TermPhase.Connecting -> if (p.attempt == 0) Hint(stringResource(R.string.term_connecting_to, ui.deviceName))
        is TermPhase.Reconnecting -> {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(p.at) {
                while (System.currentTimeMillis() < p.at) {
                    now = System.currentTimeMillis()
                    delay(250)
                }
                now = System.currentTimeMillis()
            }
            val secs = ((p.at - now + 999) / 1000).toInt().coerceAtLeast(0)
            StateCard(
                title = stringResource(R.string.term_lost_title),
                text = listOfNotNull(stringResource(R.string.term_lost_text, secs, p.attempt), tmux?.let { stringResource(R.string.term_tmux_keeps, it) }).joinToString(" "),
                secondary = stringResource(R.string.term_disconnect) to actions.onLeave,
                primary = stringResource(R.string.term_try_now) to actions.onReconnect,
            )
        }
        TermPhase.Offline -> StateCard(
            title = stringResource(R.string.term_offline_title, ui.deviceName),
            text = listOfNotNull(stringResource(R.string.term_offline_text), tmux?.let { stringResource(R.string.term_offline_tmux, it) }).joinToString(" "),
            secondary = stringResource(R.string.term_close) to actions.onDisconnect,
            primary = stringResource(R.string.term_reconnect) to actions.onReconnect,
        )
        is TermPhase.Exited -> {
            val admin = TermProtocol.endedByAdmin(p.reason)
            StateCard(
                title = stringResource(R.string.term_exit_title),
                text = stringResource(
                    when {
                        admin -> R.string.term_exit_admin
                        p.reason == "connection_lost" -> R.string.term_exit_lost
                        tmux != null -> R.string.term_exit_tmux
                        else -> R.string.term_exit_shell
                    },
                ),
                secondary = stringResource(R.string.term_close) to actions.onDisconnect,
                primary = if (admin) null else stringResource(R.string.term_reconnect) to actions.onReconnect,
            )
        }
        is TermPhase.Failed -> if (p.verdict != TermProtocol.Verdict.LOCKED) {
            val again = p.code == "host_key_rejected" || p.verdict == TermProtocol.Verdict.LIMIT
            StateCard(
                title = stringResource(R.string.term_status_ended),
                text = stringResource(TermText.error(p.code), p.code),
                secondary = stringResource(if (p.verdict == TermProtocol.Verdict.EXPIRED) R.string.term_back_to_list else R.string.term_close) to actions.onDisconnect,
                primary = if (again) stringResource(R.string.term_reconnect) to actions.onReconnect else null,
            )
        }
        else -> Unit
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB8C0CC), fontFamily = MonoFontFamily)
    }
}

@Composable
private fun StateCard(title: String, text: String, secondary: Pair<String, () -> Unit>, primary: Pair<String, () -> Unit>?) {
    Box(
        Modifier
            .fillMaxSize()
            .background(TermOverlay)
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(18.dp)
                .testTag("term_state_card"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = secondary.second) { Text(secondary.first) }
                primary?.let { (label, go) -> TextButton(onClick = go) { Text(label, fontWeight = FontWeight.SemiBold) } }
            }
        }
    }
}

// ─── The bar ───

@Composable
private fun KeyBar(landscape: Boolean, ctrl: Latch, alt: Latch, fnOpen: Boolean, onFn: () -> Unit, onKey: (TermKey) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .testTag("term_keys"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (landscape && fnOpen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TermKeys.functions.forEach { k -> KeyButton(keyLabel(k), Latch.OFF, Modifier.widthIn(min = 48.dp)) { onKey(k) } }
            }
        }
        (if (landscape) TermKeys.landscape else TermKeys.portrait).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { k ->
                    val latch = when (k.modifier) {
                        TermKey.CTRL -> ctrl
                        TermKey.ALT -> alt
                        TermKey.FN -> if (fnOpen) Latch.LOCKED else Latch.OFF
                        else -> Latch.OFF
                    }
                    KeyButton(keyLabel(k), latch, Modifier.weight(1f)) { if (k.modifier == TermKey.FN) onFn() else onKey(k) }
                }
            }
        }
    }
}

@Composable
private fun keyLabel(k: TermKey): String = when {
    k.modifier == TermKey.CTRL -> stringResource(R.string.term_key_ctrl)
    k.code == KeyEvent.KEYCODE_PAGE_UP -> stringResource(R.string.term_key_pgup)
    k.code == KeyEvent.KEYCODE_PAGE_DOWN -> stringResource(R.string.term_key_pgdn)
    else -> k.label
}

@Composable
private fun KeyButton(label: String, latch: Latch, modifier: Modifier, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val shape = RoundedCornerShape(10.dp)
    val (bg, fg) = when (latch) {
        Latch.OFF -> extra.panel2 to MaterialTheme.colorScheme.onSurface
        Latch.ONCE -> extra.accentBg to extra.accentText
        Latch.LOCKED -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    }
    Box(
        modifier
            .height(42.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (latch == Latch.OFF) extra.border else Color.Transparent, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("key_$label"),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontFamily = MonoFontFamily, fontSize = 15.sp), color = fg, maxLines = 1)
    }
}

// ─── Dialogs ───

private const val HOST_KEY_COMMAND = "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub"

/** First connection: the fingerprint big and in groups of four, the command to compare, 60 s to answer. */
@Composable
private fun HostKeyDialog(device: String, p: TermPhase.HostKey, onTrust: (Boolean) -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p.until) {
        while (System.currentTimeMillis() < p.until) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }
    val left = ((p.until - now) / 1000).toInt().coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.term_hk_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.term_hk_text, device), style = MaterialTheme.typography.bodyMedium)
                SelectionContainer {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(GateControlTheme.extraColors.panel2)
                            .padding(12.dp),
                    ) {
                        Text("${p.key.alg} · SHA256", style = MaterialTheme.typography.labelMedium, color = GateControlTheme.extraColors.muted)
                        Text(
                            TermProtocol.groups(p.key.sha256),
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFontFamily, lineHeight = 26.sp),
                            modifier = Modifier.testTag("hk_fingerprint"),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(HOST_KEY_COMMAND, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily), modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("ssh-keygen", HOST_KEY_COMMAND))
                    }) {
                        Icon(GcIcons.Copy, contentDescription = stringResource(R.string.term_hk_cmd_copy))
                    }
                }
                Text(stringResource(R.string.term_hk_left, left), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
            }
        },
        confirmButton = { TextButton(onClick = { onTrust(true) }, modifier = Modifier.testTag("hk_trust")) { Text(stringResource(R.string.term_hk_trust)) } },
        dismissButton = { TextButton(onClick = { onTrust(false) }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A changed host key: a clear warning, no way on (the admin resets the key in the plugin). */
@Composable
private fun MismatchDialog(device: String, onBack: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(GcIcons.ShieldX, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.term_hk_mismatch_title)) },
        text = { Text(stringResource(R.string.term_hk_mismatch_text, device)) },
        confirmButton = { TextButton(onClick = onBack, modifier = Modifier.testTag("hk_back")) { Text(stringResource(R.string.term_back_to_list)) } },
    )
}

@Composable
private fun PasteDialog(text: String, onConfirm: (String, Boolean) -> Unit, onCancel: () -> Unit) {
    var never by remember { mutableStateOf(false) }
    val lines = TermProtocol.pasteLines(text)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.term_paste_title, lines)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.term_paste_text))
                Text(
                    text.lineSequence().take(4).joinToString("\n") { it.take(80) },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = GateControlTheme.extraColors.muted,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { never = !never }) {
                    Checkbox(checked = never, onCheckedChange = { never = it })
                    Text(stringResource(R.string.term_paste_never))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text, never) }, modifier = Modifier.testTag("paste_ok")) { Text(stringResource(R.string.term_paste_ok)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/** „Verbindung trennen?“ — with tmux the session keeps running; without, the button is red. */
@Composable
private fun LeaveDialog(ui: TermUi, onDisconnect: () -> Unit, onCancel: () -> Unit) {
    val tmux = (ui.phase as? TermPhase.Ready)?.let { if (it.tmuxMissing) null else it.tmuxSession } ?: ui.tmux
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.term_disconnect_title)) },
        text = { Text(if (tmux != null) stringResource(R.string.term_disconnect_tmux, tmux, ui.deviceName) else stringResource(R.string.term_disconnect_shell)) },
        confirmButton = {
            TextButton(onClick = onDisconnect, modifier = Modifier.testTag("leave_ok")) {
                Text(stringResource(R.string.term_disconnect), color = if (tmux == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
