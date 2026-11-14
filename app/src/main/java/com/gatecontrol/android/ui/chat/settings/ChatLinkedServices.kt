package com.gatecontrol.android.ui.chat.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatBridgeError
import com.gatecontrol.android.chat.ChatBridgeNetwork
import com.gatecontrol.android.chat.ChatBridgeRow
import com.gatecontrol.android.chat.ChatBridges
import com.gatecontrol.android.chat.ChatBridgesInfo
import com.gatecontrol.android.chat.ChatQrMatrix
import com.gatecontrol.android.ui.chat.ConfirmDialog
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.components.SecureScreenEffect
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import com.gatecontrol.android.ui.vault.VaultClipboard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What the page can do (the view model's functions; the pictures in the tests pass nothing). */
class LinkedActions(
    val refresh: () -> Unit = {},
    val begin: (String) -> Unit = {},
    val unlink: (String) -> Unit = {},
    val forget: (String) -> Unit = {},
    val confirmNotes: () -> Unit = {},
    val selectFlow: (String) -> Unit = {},
    val submitPhone: (String) -> Unit = {},
    val retry: () -> Unit = {},
    val copyCode: (String) -> Unit = {},
)

/**
 * Chat-Einstellungen › „Verknüpfte Dienste“ (Messenger 1.13.0; the portal's page of the same name is the model for
 * what it does and says): per service the state of the own account; before the first link the notes with a tick;
 * the link by pairing code (the phone in the hand) or QR code (WhatsApp runs on another phone). In this version
 * accounts are linked — the chats follow later, the page says so.
 *
 * The page shows a phone number and a pairing code: no screenshot, no recording, no picture in the recent apps.
 */
@Composable
fun ChatLinkedServicesPage(viewModel: LinkedServicesViewModel, chats: Boolean = false) {
    SecureScreenEffect()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val res = LocalResources.current
    var asking by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.notice.collect { n ->
            val text = when (n) {
                is LinkedNotice.Linked -> res.getString(R.string.chat_linked_linked, res.getString(networkName(n.network)))
                is LinkedNotice.Unlinked -> res.getString(R.string.chat_linked_unlinked, res.getString(networkName(n.network)))
                is LinkedNotice.Failed -> res.getString(errorText(n.error))
            }
            Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
        }
    }
    BackHandler(enabled = ui.view != LinkedView.Overview) { viewModel.back() }

    LinkedServicesContent(
        ui,
        LinkedActions(
            refresh = viewModel::refresh,
            begin = viewModel::begin,
            unlink = { asking = it },
            forget = { viewModel.unlink(it, quiet = true) },
            confirmNotes = viewModel::confirmNotes,
            selectFlow = viewModel::selectFlow,
            submitPhone = viewModel::submitPhone,
            retry = viewModel::retry,
            copyCode = { code ->
                // marked sensitive (no preview on Android 13+) and cleared again after a minute
                VaultClipboard.copy(ctx, code, secret = true, seconds = 60)
                Toast.makeText(ctx, R.string.chat_linked_code_copied, Toast.LENGTH_SHORT).show()
            },
        ),
        chats = chats,
    )

    asking?.let { net ->
        val name = stringResource(networkName(net))
        ConfirmDialog(
            title = stringResource(R.string.chat_linked_unlink),
            text = stringResource(R.string.chat_linked_unlink_confirm, name),
            confirm = stringResource(R.string.chat_linked_unlink),
            onConfirm = { asking = null; viewModel.unlink(net) },
            onDismiss = { asking = null },
        )
    }
}

// Today WhatsApp is the only service ([ChatBridges.NETWORKS]); a further one gets its name and mark here.
internal fun networkName(@Suppress("UNUSED_PARAMETER") id: String): Int = R.string.chat_linked_net_whatsapp

private fun networkMark(@Suppress("UNUSED_PARAMETER") id: String): Int = R.string.chat_linked_mark_whatsapp

internal fun errorText(e: ChatBridgeError): Int = when (e) {
    ChatBridgeError.OFFLINE -> R.string.chat_err_offline
    ChatBridgeError.OFF -> R.string.chat_linked_err_off
    ChatBridgeError.UNREACHABLE -> R.string.chat_linked_err_unreachable
    ChatBridgeError.UNSUPPORTED -> R.string.chat_linked_err_unsupported
    ChatBridgeError.FAILED -> R.string.chat_linked_err_failed
    ChatBridgeError.EXPIRED -> R.string.chat_linked_err_expired
    ChatBridgeError.LOCKED -> R.string.chat_linked_err_locked
    ChatBridgeError.CONSENT -> R.string.chat_linked_err_consent
    ChatBridgeError.RATE_LIMITED -> R.string.chat_err_rate_limited
    ChatBridgeError.FORBIDDEN -> R.string.chat_linked_err_forbidden
    ChatBridgeError.GENERIC -> R.string.chat_err_generic
}

/** The day of [iso] as the device writes dates; empty when it is no time. */
internal fun linkedDate(iso: String?, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
    runCatching { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(Instant.parse(iso).atZone(zone).toLocalDate()) }.getOrDefault("")

/**
 * The page without its view model: [ui] drawn, taps handed to [actions]. [chats]: the person has chats over a bridge
 * (Messenger 1.14, `features.bridge_chats`) — the note at the end says how they work instead of "follow later".
 */
@Composable
fun LinkedServicesContent(ui: LinkedUiState, actions: LinkedActions, chats: Boolean = false) {
    val info = ui.info
    when (val view = ui.view) {
        is LinkedView.Notes -> NotesView(view, actions)
        is LinkedView.Link -> LinkView(view, actions)
        LinkedView.Overview -> when {
            info == null && ui.loading -> Muted(stringResource(R.string.chat_linked_loading))
            info == null -> {
                Muted(stringResource(R.string.chat_linked_load_failed) + " " + stringResource(errorText(ui.loadError ?: ChatBridgeError.GENERIC)))
                GcOutlineButton(text = stringResource(R.string.chat_retry), onClick = actions.refresh, icon = GcIcons.Refresh)
            }
            !info.available || info.networks.isEmpty() -> Muted(stringResource(R.string.chat_linked_none_offered))
            else -> Overview(info, ui.unlinking, actions, chats)
        }
    }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted, modifier = modifier.padding(horizontal = 4.dp))
}

@Composable
private fun Small(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
    )
}

/** A tinted note with an icon and running text. */
@Composable
private fun Note(text: String, tone: GcTone = GcTone.Info, lock: Boolean = false) {
    GcBanner(tone = tone, icon = if (lock) GcIcons.Lock else GcIcons.Alert) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

// ─── overview ───────────────────────────────────────────────────────

@Composable
private fun Overview(info: ChatBridgesInfo, unlinking: String?, actions: LinkedActions, chats: Boolean) {
    val outdated = info.networks.filter { it.outdated }
    Muted(stringResource(R.string.chat_linked_lead))
    outdated.firstOrNull()?.let { Note(stringResource(R.string.chat_linked_outdated_banner, stringResource(networkName(it.id))), GcTone.Warn) }
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        info.networks.forEachIndexed { i, n ->
            if (i > 0) GcRowDivider()
            NetworkRow(n, busy = unlinking == n.id, actions = actions)
        }
    }
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        GcListRow(
            title = stringResource(R.string.chat_linked_runs_on),
            description = stringResource(if (info.onServer) R.string.chat_linked_runs_on_server else R.string.chat_linked_runs_on_gateway) + " · " +
                stringResource(if (outdated.isEmpty()) R.string.chat_linked_runs_current else R.string.chat_linked_runs_outdated),
            trailing = {
                if (outdated.isEmpty()) GcChip(stringResource(R.string.chat_linked_pill_ok), tone = GcTone.Ok, small = true)
                else GcChip(stringResource(R.string.chat_linked_pill_outdated), tone = GcTone.Warn, small = true)
            },
        )
    }
    if (outdated.isNotEmpty()) Small(stringResource(R.string.chat_linked_outdated_hint))
    Small(stringResource(R.string.chat_linked_foot_unlink))
    Note(stringResource(if (chats) R.string.chat_linked_foot_chats else R.string.chat_linked_foot_preview), lock = true)
}

@Composable
private fun NetworkRow(n: ChatBridgeNetwork, busy: Boolean, actions: LinkedActions) {
    val extra = GateControlTheme.extraColors
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val name = stringResource(networkName(n.id))
    val login = ChatBridges.mainLogin(n)
    val since = linkedDate(login?.since, locale)
    val row = ChatBridges.row(n)
    val sub = when (row) {
        ChatBridgeRow.STARTING -> stringResource(R.string.chat_linked_sub_starting)
        ChatBridgeRow.UNREACHABLE -> stringResource(R.string.chat_linked_sub_unreachable)
        ChatBridgeRow.LINKED ->
            (if (since.isNotEmpty()) stringResource(R.string.chat_linked_sub_linked_since, since) else stringResource(R.string.chat_linked_sub_linked)) +
                (login?.name?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        ChatBridgeRow.LOGGED_OUT ->
            (if (since.isNotEmpty()) stringResource(R.string.chat_linked_sub_logged_out_at, since) else stringResource(R.string.chat_linked_sub_logged_out)) +
                " " + stringResource(R.string.chat_linked_sub_logged_out_whatsapp)
        ChatBridgeRow.ERROR -> stringResource(R.string.chat_linked_sub_error)
        ChatBridgeRow.LOCKED -> stringResource(R.string.chat_linked_sub_locked)
        ChatBridgeRow.NONE -> stringResource(R.string.chat_linked_sub_none)
    }
    GcListRow(
        title = name,
        description = sub,
        modifier = Modifier.testTag("linked-row-${n.id}"),
        leading = {
            GcIconSquare(size = 40.dp, background = extra.accentBg) {
                Text(stringResource(networkMark(n.id)), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = extra.accentText)
            }
        },
        trailing = {
            when (row) {
                ChatBridgeRow.STARTING -> GcChip(stringResource(R.string.chat_linked_pill_starting), tone = GcTone.Warn, small = true)
                ChatBridgeRow.UNREACHABLE -> GcChip(stringResource(R.string.chat_linked_pill_unreachable), tone = GcTone.Warn, small = true)
                ChatBridgeRow.LINKED ->
                    if (n.outdated) GcChip(stringResource(R.string.chat_linked_pill_outdated), tone = GcTone.Warn, small = true)
                    else GcChip(stringResource(R.string.chat_linked_pill_linked), tone = GcTone.Ok, small = true)
                ChatBridgeRow.LOGGED_OUT -> GcChip(stringResource(R.string.chat_linked_pill_logged_out), tone = GcTone.Error, small = true)
                ChatBridgeRow.ERROR -> GcChip(stringResource(R.string.chat_linked_pill_error), tone = GcTone.Error, small = true)
                ChatBridgeRow.LOCKED -> GcChip(stringResource(R.string.chat_linked_pill_locked), tone = GcTone.Neutral, small = true)
                ChatBridgeRow.NONE -> Unit
            }
        },
    )
    val pad = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
    when (row) {
        ChatBridgeRow.NONE -> GcPrimaryButton(text = stringResource(R.string.chat_linked_link), onClick = { actions.begin(n.id) }, modifier = pad)
        ChatBridgeRow.LINKED, ChatBridgeRow.ERROR ->
            GcOutlineButton(text = stringResource(R.string.chat_linked_unlink), onClick = { actions.unlink(n.id) }, modifier = pad, loading = busy)
        else -> Unit
    }
    // signed out or failed: a new code is enough (the notes were confirmed before)
    if (ChatBridges.relinkOffered(n)) {
        GcRowDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chat_linked_relink), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(if (n.allowed) R.string.chat_linked_relink_hint else R.string.chat_linked_sub_locked),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
            if (n.allowed) GcPrimaryButton(text = stringResource(R.string.chat_linked_relink_btn, name), onClick = { actions.begin(n.id) })
            if (login?.state == ChatBridges.LOGIN_LOGGED_OUT) {
                GcSecondaryButton(text = stringResource(R.string.chat_linked_forget), onClick = { actions.forget(n.id) }, enabled = !busy)
            }
        }
    }
}

// ─── the notes before the first link ────────────────────────────────

private val NOTES = mapOf(
    ChatBridges.WHATSAPP to listOf(
        R.string.chat_linked_note_whatsapp_unofficial,
        R.string.chat_linked_note_whatsapp_ban,
        R.string.chat_linked_note_whatsapp_phone,
        R.string.chat_linked_note_whatsapp_e2e,
    ),
)

@Composable
private fun NotesView(view: LinkedView.Notes, actions: LinkedActions) {
    val extra = GateControlTheme.extraColors
    // not kept across a rotation on purpose: the tick is a decision of this moment
    var read by remember(view.network) { mutableStateOf(false) }
    Heading(stringResource(R.string.chat_linked_notes_title, stringResource(networkName(view.network))))
    GcCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NOTES[view.network].orEmpty().forEach { note ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("•", style = MaterialTheme.typography.bodyMedium, color = extra.warn, modifier = Modifier.clearAndSetSemantics { })
                Text(stringResource(note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .toggleable(value = read, role = Role.Checkbox, enabled = !view.saving, onValueChange = { read = it })
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .testTag("linked-notes-check"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = read, onCheckedChange = null)
        Text(stringResource(R.string.chat_linked_notes_confirm), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
    }
    Small(stringResource(R.string.chat_linked_notes_free))
    GcPrimaryButton(text = stringResource(R.string.chat_linked_next), onClick = actions.confirmNotes, enabled = read, loading = view.saving)
}

// ─── the link itself ────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkView(view: LinkedView.Link, actions: LinkedActions) {
    val name = stringResource(networkName(view.network))
    Heading(stringResource(R.string.chat_linked_link_title, name))
    // side by side where they fit, else one under the other (the labels are long)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GcFilterChip(stringResource(R.string.chat_linked_flow_phone), selected = view.flow == ChatBridges.FLOW_PHONE, onClick = { actions.selectFlow(ChatBridges.FLOW_PHONE) })
        GcFilterChip(stringResource(R.string.chat_linked_flow_qr), selected = view.flow == ChatBridges.FLOW_QR, onClick = { actions.selectFlow(ChatBridges.FLOW_QR) })
    }
    when (val p = view.phase) {
        LinkPhase.Starting -> Waiting(stringResource(R.string.chat_linked_loading))
        is LinkPhase.Phone -> PhoneStep(name, p, actions)
        is LinkPhase.Code -> {
            Muted(stringResource(R.string.chat_linked_code_lead, name))
            CodeBox(p.code)
            GcOutlineButton(text = stringResource(R.string.chat_linked_copy_code), onClick = { actions.copyCode(p.code) })
            Steps(listOf(R.string.chat_linked_code_step1_whatsapp, R.string.chat_linked_code_step2_whatsapp, R.string.chat_linked_code_step3))
            Small(stringResource(R.string.chat_linked_code_note))
            Waiting(stringResource(R.string.chat_linked_waiting))
        }
        is LinkPhase.Qr -> {
            Muted(stringResource(R.string.chat_linked_qr_lead_whatsapp))
            QrBox(p.matrix)
            Steps(listOf(R.string.chat_linked_qr_step1_whatsapp, R.string.chat_linked_qr_step2_whatsapp))
            Note(stringResource(R.string.chat_linked_qr_one_phone))
            Waiting(stringResource(R.string.chat_linked_waiting))
        }
        is LinkPhase.Failed -> {
            Note(stringResource(errorText(p.error)), GcTone.Warn)
            GcPrimaryButton(
                text = stringResource(if (view.flow == ChatBridges.FLOW_PHONE) R.string.chat_linked_new_code else R.string.chat_linked_new_qr),
                onClick = actions.retry,
            )
        }
    }
}

@Composable
private fun PhoneStep(name: String, p: LinkPhase.Phone, actions: LinkedActions) {
    // in memory while the field shows — not in the saved state, not in the view model
    var number by remember(p.stepId) { mutableStateOf("") }
    GcTextField(
        value = number,
        onValueChange = { v -> number = v.take(32) },
        label = stringResource(R.string.chat_linked_phone_label, name),
        placeholder = stringResource(R.string.chat_linked_phone_placeholder),
        hint = stringResource(R.string.chat_linked_phone_hint),
        error = when (p.error) {
            PhoneError.INVALID -> stringResource(R.string.chat_linked_phone_invalid)
            PhoneError.REFUSED -> stringResource(R.string.chat_linked_phone_refused)
            null -> null
        },
        enabled = !p.sending,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { actions.submitPhone(number) }),
        modifier = Modifier.testTag("linked-phone"),
    )
    GcPrimaryButton(text = stringResource(R.string.chat_linked_get_code), onClick = { actions.submitPhone(number) }, loading = p.sending)
}

/** The pairing code, large and selectable; TalkBack reads it as one label. */
@Composable
private fun CodeBox(code: String) {
    val extra = GateControlTheme.extraColors
    val label = stringResource(R.string.chat_linked_code_label) + " " + code
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, extra.border2, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 18.dp)
            .semantics { contentDescription = label }
            .testTag("linked-code"),
        contentAlignment = Alignment.Center,
    ) {
        SelectionContainer {
            Text(
                code,
                style = MaterialTheme.typography.headlineLarge.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The QR code drawn from its modules: black on white with a quiet zone of four modules, in both themes. */
@Composable
private fun QrBox(matrix: ChatQrMatrix) {
    val alt = stringResource(R.string.chat_linked_qr_alt)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .semantics { contentDescription = alt }
                .testTag("linked-qr"),
        ) {
            val quiet = 4
            val cell = size.minDimension / (matrix.size + 2 * quiet)
            matrix.rows.forEachIndexed { y, row ->
                var x = 0
                while (x < row.length) {
                    if (row[x] != '1') { x++; continue }
                    // one rectangle per run of dark modules: no hairlines between neighbours
                    var end = x
                    while (end + 1 < row.length && row[end + 1] == '1') end++
                    drawRect(Color.Black, topLeft = Offset((x + quiet) * cell, (y + quiet) * cell), size = Size((end - x + 1) * cell, cell + 0.5f))
                    x = end + 1
                }
            }
        }
    }
}

@Composable
private fun Steps(steps: List<Int>) {
    val extra = GateControlTheme.extraColors
    GcCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEachIndexed { i, s ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.chat_linked_step_n, i + 1),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = extra.accentText,
                    modifier = Modifier.width(20.dp),
                )
                Text(stringResource(s), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Waiting(text: String) {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = GateControlTheme.extraColors.accentText)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted)
    }
}
