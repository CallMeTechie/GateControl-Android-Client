package com.gatecontrol.android.ui.mail.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MCalendar
import com.gatecontrol.android.mail.MCalendars
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MPimSide
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.mail.SheetRow
import com.gatecontrol.android.ui.theme.GateControlTheme

/** The colors a calendar can take (the mockups' palette). */
internal val CAL_COLORS = listOf("#46E0A0", "#79B8FF", "#F5B544", "#FF8080", "#B79CFF", "#3DDC97", "#FF9F6B", "#8C96A5")

/** What the calendar menu asks for (long press, add, subscribe). */
@Stable
class CalendarMenuState {
    var options by mutableStateOf<MCalendar?>(null)
    var overlay by mutableStateOf<String?>(null)
    var renaming by mutableStateOf<MCalendar?>(null)
    var deleting by mutableStateOf<MCalendar?>(null)
    var adding by mutableStateOf(false)
    var subscribing by mutableStateOf(false)
}

/** A row of the menu with a check square in [color] (filled: shown), the name and its state or count. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CheckRow(label: String, color: Color, checked: Boolean, trailing: String?, trailingWarn: Boolean = false, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val extra = GateControlTheme.extraColors
    val state = stringResource(if (checked) R.string.cal_shown else R.string.cal_hidden)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(role = Role.Checkbox, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClick?.let { stringResource(R.string.cal_options) })
            .semantics { stateDescription = state }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                .then(if (checked) Modifier.background(color) else Modifier.border(2.dp, color, RoundedCornerShape(5.dp))),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(GcIcons.Check, contentDescription = null, tint = Color(0xFF0D1015), modifier = Modifier.size(13.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (trailingWarn) extra.warn else extra.muted)
    }
}

@Composable
private fun stateLabel(c: MCalendar): Pair<String?, Boolean> = when (c.state) {
    MPimSide.STATE_SYNCING -> stringResource(R.string.pim_state_syncing) to false
    MPimSide.STATE_AUTH_FAILED -> stringResource(R.string.pim_state_auth) to true
    MPimSide.STATE_UNREACHABLE -> stringResource(R.string.pim_state_unreachable) to true
    MPimSide.STATE_CONSENT -> stringResource(R.string.pim_relogin) to true
    else -> (if (c.kind == "business") stringResource(R.string.cal_kind_business) else null) to false
}

/**
 * The calendar's part of the menu (mockup "Seitenmenü neu"): "Posteingänge" back to the mail, the calendars with a
 * check and their color grouped "GateControl", per account and "Eingeblendet · nur ansehen", their state; long press:
 * color, reminders, rename and delete (own ones); "Kalender hinzufügen" and "iCal-Abo" below.
 */
internal fun LazyListScope.calendarDrawerItems(
    me: MMe?,
    cals: MCalendars?,
    menu: CalendarMenuState,
    inboxRow: @Composable () -> Unit,
    label: @Composable (String) -> Unit,
    onToggle: (MCalendar) -> Unit,
    onToggleOverlay: (String) -> Unit,
) {
    item(key = "cal-inbox") { inboxRow() }
    cals ?: return
    val local = cals.calendars.filter { it.accountId == null }
    if (local.isNotEmpty()) {
        item(key = "cal-gc") { label(stringResource(R.string.cal_group_gatecontrol)) }
        local.forEach { c -> item(key = "cal${c.id}") { CalRow(c, menu, onToggle) } }
    }
    cals.calendars.filter { it.accountId != null }.groupBy { it.accountId }.forEach { (accountId, list) ->
        item(key = "cal-a$accountId") { label(me?.account(accountId)?.label ?: stringResource(R.string.cal_group_account)) }
        list.forEach { c -> item(key = "cal${c.id}") { CalRow(c, menu, onToggle) } }
    }
    if (cals.overlays.isNotEmpty()) {
        item(key = "cal-ov") { label(stringResource(R.string.cal_group_overlays)) }
        cals.overlays.forEach { o ->
            item(key = "ov${o.id}") {
                CheckRow(o.name, calColor(o.color), o.visible, null, onClick = { onToggleOverlay(o.id) }, onLongClick = { menu.overlay = o.id })
            }
        }
    }
    item(key = "cal-add") {
        Column(Modifier.padding(top = 6.dp)) {
            AddRow(stringResource(R.string.cal_add)) { menu.adding = true }
            AddRow(stringResource(R.string.cal_subscribe)) { menu.subscribing = true }
        }
    }
}

@Composable
private fun CalRow(c: MCalendar, menu: CalendarMenuState, onToggle: (MCalendar) -> Unit) {
    val (state, warn) = stateLabel(c)
    CheckRow(c.name, calColor(c.color), c.visible, state, warn, onClick = { onToggle(c) }, onLongClick = { menu.options = c })
}

@Composable
private fun AddRow(label: String, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickableCompat(onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(GcIcons.Plus, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = extra.accentText, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = combinedClickable(role = Role.Button, onClick = onClick)

/** The sheets and dialogs of the calendar menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarMenuDialogs(menu: CalendarMenuState, viewModel: MailCalendarViewModel, cals: MCalendars?) {
    val extra = GateControlTheme.extraColors
    menu.options?.let { c0 ->
        val c = cals?.calendar(c0.id) ?: c0
        ModalBottomSheet(onDismissRequest = { menu.options = null }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() })
                ColorRow(c.color) { viewModel.setColor(c, it) }
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(GcIcons.Bell, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                    Text(stringResource(R.string.cal_reminders_on), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 16.dp))
                    GcSwitch(checked = c.notify, onCheckedChange = { viewModel.setNotify(c, it) })
                }
                if (c.local) SheetRow(GcIcons.Edit, stringResource(R.string.cal_rename)) { menu.options = null; menu.renaming = c }
                if (c.local || c.source == MCalendar.SOURCE_ICS) {
                    SheetRow(GcIcons.Trash, stringResource(R.string.cal_delete), MaterialTheme.colorScheme.error) { menu.options = null; menu.deleting = c }
                }
                if (!c.local) {
                    Text(stringResource(R.string.cal_account_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }
        }
    }
    menu.overlay?.let { id ->
        val o = cals?.overlays?.firstOrNull { it.id == id }
        if (o == null) {
            menu.overlay = null
        } else {
            ModalBottomSheet(onDismissRequest = { menu.overlay = null }, containerColor = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(bottom = 24.dp)) {
                    Text(o.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() })
                    ColorRow(o.color) { viewModel.setOverlayColor(o.id, it) }
                }
            }
        }
    }
    menu.renaming?.let { c ->
        NameDialog(stringResource(R.string.cal_rename), c.name, onDone = { viewModel.rename(c, it) }, onDismiss = { menu.renaming = null })
    }
    menu.deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { menu.deleting = null },
            title = { Text(stringResource(R.string.cal_delete_title, c.name)) },
            text = { Text(stringResource(R.string.cal_delete_text)) },
            confirmButton = { TextButton(onClick = { menu.deleting = null; viewModel.delete(c) }) { Text(stringResource(R.string.mail_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { menu.deleting = null }) { Text(stringResource(R.string.mail_cancel)) } },
        )
    }
    if (menu.adding) {
        NameDialog(stringResource(R.string.cal_add), "", onDone = viewModel::add, onDismiss = { menu.adding = false })
    }
    if (menu.subscribing) {
        var url by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { menu.subscribing = false },
            title = { Text(stringResource(R.string.cal_subscribe)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.cal_subscribe_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    GcTextField(url, { url = it }, stringResource(R.string.cal_subscribe_url), placeholder = "https://… / webcal://…")
                    GcTextField(name, { name = it }, stringResource(R.string.cal_name))
                }
            },
            confirmButton = {
                TextButton(enabled = url.isNotBlank(), onClick = { menu.subscribing = false; viewModel.subscribe(url.trim(), name.trim().ifEmpty { null }) }) {
                    Text(stringResource(R.string.cal_subscribe_do))
                }
            },
            dismissButton = { TextButton(onClick = { menu.subscribing = false }) { Text(stringResource(R.string.mail_cancel)) } },
        )
    }
}

@Composable
private fun ColorRow(current: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CAL_COLORS.forEach { hex ->
            val on = hex.equals(current, ignoreCase = true)
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(calColor(hex))
                    .then(if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .combinedClickableCompat { onPick(hex) }
                    .semantics { stateDescription = if (on) "✓" else "" },
            )
        }
    }
}

@Composable
internal fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { GcTextField(name, { name = it.take(80) }, stringResource(R.string.cal_name)) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onDismiss(); onDone(name.trim()) }) { Text(stringResource(R.string.mail_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mail_cancel)) } },
    )
}
