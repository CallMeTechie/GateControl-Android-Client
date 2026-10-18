package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatApproverChoice
import com.gatecontrol.android.chat.ChatApprovers
import com.gatecontrol.android.chat.ChatHomeAction
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatRequestable
import com.gatecontrol.android.quickactions.QuickActionIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle

// ─── shared pieces ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeSheet(title: String, subtitle: String?, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted) }
            }
            content()
        }
    }
}

/** A choice with icon, label, subtitle and a radio dot (mockup `.opt`). */
@Composable
internal fun OptionRow(icon: ImageVector, label: String, subtitle: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else extra.border, RoundedCornerShape(16.dp))
            .background(if (selected) extra.accentBg else Color.Transparent)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(extra.warnBg), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = extra.warn, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = if (enabled) scheme.onSurface else extra.faint)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = extra.muted) }
        }
        Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) scheme.primary else extra.border2, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(scheme.primary))
        }
    }
}

@Composable
private fun InfoLine(text: String) {
    val extra = GateControlTheme.extraColors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = extra.muted, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = extra.muted)
    }
}

private fun firstName(ctx: android.content.Context, p: ChatPerson): String = ChatText.name(ctx, p).substringBefore(' ')

// ─── door request (mockup ChatDoorRequest) ──────────────────────────

/**
 * "Tür-Anfrage": what to open (the actions the caller may ask for, Messenger
 * C2), who may approve (parents, everyone allowed, one person) and an
 * optional message.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoorRequestSheet(
    requestable: ChatRequestable?,
    fallback: Boolean,
    others: List<ChatPerson>,
    /** Messenger 1.5 `action_requests`: the approvers can be chosen. */
    chooseApprovers: Boolean,
    parentsOffered: Boolean,
    defaultApprovers: String?,
    ttlMin: Int,
    /** false: the administrator does not let the caller ask (a child). */
    canRequest: Boolean?,
    initial: ChatHomeAction?,
    busy: Boolean,
    onApprovers: (ChatApproverChoice) -> Unit,
    onSend: (ChatHomeAction, ChatApproverChoice, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val entries = requestable?.actions
    var chosen by remember { mutableStateOf(initial) }
    val current = entries?.firstOrNull { it.action.id == chosen?.id && it.action.plugin == chosen?.plugin }
        ?: entries?.firstOrNull { it.action.needsConfirm && (it.self || it.approverIds.isNotEmpty() || fallback) }
        ?: entries?.firstOrNull()
    val start = (requestable?.approversDefault ?: defaultApprovers)?.takeIf { it != ChatApprovers.PARENTS || parentsOffered }
        ?: if (parentsOffered) ChatApprovers.PARENTS else ChatApprovers.RIGHTS
    var approvers by remember { mutableStateOf(ChatApproverChoice(start)) }
    var note by rememberSaveable { mutableStateOf("") }
    val pick: (ChatApproverChoice) -> Unit = { c -> approvers = c; onApprovers(c) }
    HomeSheet(stringResource(R.string.chat_req_title), stringResource(R.string.chat_req_sub), onDismiss) {
        GcSectionLabel(stringResource(R.string.chat_req_what))
        when {
            entries == null -> CircularProgressIndicator(Modifier.size(24.dp))
            entries.isEmpty() -> Text(stringResource(R.string.chat_req_none), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
            else -> entries.forEach { e ->
                val a = e.action
                val icon = remember(a.icon) { QuickActionIcons.vector(a.icon) }
                val nobody = !fallback && !e.self && e.approverIds.isEmpty()
                val sub = when {
                    nobody -> stringResource(R.string.chat_req_no_approver)
                    e.approverIds.isNotEmpty() -> stringResource(
                        R.string.chat_req_approvers,
                        e.approverIds.take(3).joinToString(", ") { id -> others.firstOrNull { it.userId == id }?.let { firstName(ctx, it) } ?: "#$id" } +
                            if (e.approverIds.size > 3) " …" else "",
                    )
                    e.self && !fallback -> stringResource(R.string.chat_req_self)
                    else -> null
                }
                OptionRow(icon, a.label, sub, current?.action?.id == a.id && current.action.plugin == a.plugin, enabled = !nobody) { chosen = a }
            }
        }
        if (fallback) InfoLine(stringResource(R.string.chat_req_fallback))
        if (chooseApprovers) {
            GcSectionLabel(stringResource(R.string.chat_req_who))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (parentsOffered) PickChip(stringResource(R.string.chat_req_parents), approvers.keyword == ChatApprovers.PARENTS) { pick(ChatApproverChoice(ChatApprovers.PARENTS)) }
                PickChip(stringResource(R.string.chat_req_rights), approvers.keyword == ChatApprovers.RIGHTS) { pick(ChatApproverChoice(ChatApprovers.RIGHTS)) }
                others.take(6).forEach { p ->
                    PickChip(stringResource(R.string.chat_req_only, firstName(ctx, p)), approvers.keyword == null && approvers.userIds == listOf(p.userId)) {
                        pick(ChatApproverChoice(null, listOf(p.userId)))
                    }
                }
            }
        }
        GcTextField(
            value = note,
            onValueChange = { note = it.take(200) },
            label = stringResource(R.string.chat_req_note),
            placeholder = stringResource(R.string.chat_req_note_hint),
        )
        val noRight = canRequest == false || requestable?.canRequest == false
        if (noRight) Text(stringResource(R.string.chat_req_not_allowed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        InfoLine(stringResource(R.string.chat_req_info, requestable?.requestTtlMin ?: ttlMin))
        GcPrimaryButton(
            text = stringResource(R.string.chat_req_send),
            onClick = { current?.let { onSend(it.action, if (chooseApprovers) approvers else ChatApproverChoice(ChatApprovers.RIGHTS), note.trim().takeIf { n -> n.isNotEmpty() }) } },
            enabled = current != null && !noRight && (fallback || current.self || current.approverIds.isNotEmpty()),
            loading = busy,
        )
    }
}

// ─── share a device (mockup ChatShareDevice) ────────────────────────

/** "Bis wann" choices of a shared device. */
private enum class Until { HOUR, TONIGHT, MORNING, DATE }

/**
 * "Gerät teilen": a grid of the devices (doors only as "nur Anfrage" — a
 * tap there leads to the door request), the person, until when and
 * "Am Ende ausschalten" (Messenger C3).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareDeviceSheet(
    actions: List<ChatHomeAction>?,
    others: List<ChatPerson>,
    maxDays: Int,
    offAtEndDefault: Boolean,
    initial: ChatHomeAction?,
    busy: Boolean,
    onShare: (ChatHomeAction, Long, Long, Boolean) -> Unit,
    onRequestInstead: (ChatHomeAction) -> Unit,
    onDismiss: () -> Unit,
    /** Messenger 1.5 `grant_off`: "Am Ende ausschalten" (toggles only). */
    grantOff: Boolean = false,
) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    var chosen by remember(actions) { mutableStateOf(initial?.takeIf { !it.needsConfirm } ?: actions?.firstOrNull { !it.needsConfirm }) }
    var to by remember { mutableStateOf(others.singleOrNull()?.userId ?: others.firstOrNull()?.userId) }
    val zone = ZoneId.systemDefault()
    val now = remember { ZonedDateTime.now(zone) }
    val tonight = remember { now.withHour(22).withMinute(0).withSecond(0).withNano(0) }
    val morning = remember { now.plusDays(1).withHour(7).withMinute(0).withSecond(0).withNano(0) }
    var until by remember { mutableStateOf(if (tonight.isAfter(now.plusMinutes(30))) Until.TONIGHT else Until.MORNING) }
    var customMs by remember { mutableLongStateOf(now.plusDays(1).toInstant().toEpochMilli()) }
    var picking by remember { mutableStateOf(false) }
    var offAtEnd by rememberSaveable { mutableStateOf(offAtEndDefault) }
    val untilMs = when (until) {
        Until.HOUR -> now.plusHours(1).toInstant().toEpochMilli()
        Until.TONIGHT -> tonight.toInstant().toEpochMilli()
        Until.MORNING -> morning.toInstant().toEpochMilli()
        Until.DATE -> customMs
    }
    val tooLong = untilMs > System.currentTimeMillis() + maxDays * 86_400_000L
    HomeSheet(stringResource(R.string.chat_share_title), stringResource(R.string.chat_share_sub), onDismiss) {
        GcSectionLabel(stringResource(R.string.chat_share_device))
        when {
            actions == null -> CircularProgressIndicator(Modifier.size(24.dp))
            actions.isEmpty() -> Text(stringResource(R.string.chat_share_none), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
            else -> actions.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { a ->
                        DeviceTile(a, chosen?.id == a.id && chosen?.plugin == a.plugin, Modifier.weight(1f)) {
                            if (a.needsConfirm) onRequestInstead(a) else chosen = a
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        GcSectionLabel(stringResource(R.string.chat_share_with))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            others.forEach { p -> PickChip(ChatText.name(ctx, p), to == p.userId) { to = p.userId } }
        }
        GcSectionLabel(stringResource(R.string.chat_share_until))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PickChip(stringResource(R.string.chat_share_1h), until == Until.HOUR) { until = Until.HOUR }
            if (tonight.isAfter(now.plusMinutes(30))) PickChip(stringResource(R.string.chat_share_tonight), until == Until.TONIGHT) { until = Until.TONIGHT }
            PickChip(stringResource(R.string.chat_share_morning), until == Until.MORNING) { until = Until.MORNING }
            PickChip(if (until == Until.DATE) dateTimeLabel(ctx, customMs) else stringResource(R.string.chat_share_date), until == Until.DATE) { picking = true }
        }
        if (tooLong) Text(stringResource(R.string.chat_share_too_long, maxDays), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        val canOff = grantOff && chosen?.kind == "toggle"
        if (canOff) GcSwitchRow(stringResource(R.string.chat_share_off_end), stringResource(R.string.chat_share_off_end_desc), offAtEnd, { offAtEnd = it })
        Text(stringResource(R.string.chat_share_doors), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        val person = others.firstOrNull { it.userId == to }
        GcPrimaryButton(
            text = person?.let { stringResource(R.string.chat_share_send, firstName(ctx, it)) } ?: stringResource(R.string.chat_share_send_plain),
            onClick = { val a = chosen; val t = to; if (a != null && t != null) onShare(a, t, untilMs, offAtEnd && canOff) },
            enabled = chosen != null && to != null && !tooLong && untilMs > System.currentTimeMillis(),
            loading = busy,
        )
    }
    if (picking) {
        DateTimePickerDialog(customMs, onPick = { customMs = it; until = Until.DATE; picking = false }, onDismiss = { picking = false })
    }
}

@Composable
private fun DeviceTile(a: ChatHomeAction, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val icon = remember(a.icon) { QuickActionIcons.vector(a.icon) }
    Column(
        modifier.heightIn(min = 96.dp).clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else extra.border, RoundedCornerShape(16.dp))
            .background(if (selected) extra.accentBg else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (a.needsConfirm) extra.panel2 else extra.warnBg), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (a.needsConfirm) extra.faint else extra.warn, modifier = Modifier.size(20.dp))
        }
        Text(a.label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (a.needsConfirm) Text(stringResource(R.string.chat_share_request_only), style = MaterialTheme.typography.labelSmall, color = extra.muted)
    }
}

// ─── task (mockup ChatTask) ─────────────────────────────────────────

/** "Bis wann" of a task. */
private enum class Due { TODAY, TOMORROW, WEEKLY, DATE }

/**
 * "Aufgabe" (Familie B4): what, for whom ("Wer zuerst kann" = nobody in
 * particular) and until when; "Jeden Mo" repeats weekly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskSheet(
    others: List<ChatPerson>,
    me: ChatPerson?,
    busy: Boolean,
    onSend: (title: String, assigneeId: Long?, dueMs: Long?, repeat: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val zone = ZoneId.systemDefault()
    val now = remember { ZonedDateTime.now(zone) }
    val today19 = remember { now.withHour(19).withMinute(0).withSecond(0).withNano(0).let { if (it.isAfter(now)) it else now.plusHours(1).withMinute(0).withSecond(0).withNano(0) } }
    val tomorrow = remember { now.plusDays(1).withHour(18).withMinute(0).withSecond(0).withNano(0) }
    val weekday = remember { now.dayOfWeek }
    var title by rememberSaveable { mutableStateOf("") }
    var assignee by remember { mutableStateOf<Long?>(others.firstOrNull()?.userId) }
    var due by remember { mutableStateOf(Due.TODAY) }
    var customMs by remember { mutableLongStateOf(tomorrow.toInstant().toEpochMilli()) }
    var picking by remember { mutableStateOf(false) }
    val locale = com.gatecontrol.android.ui.rules.ruleLocale()
    val dayName = weekday.getDisplayName(TextStyle.SHORT, locale).trimEnd('.')
    HomeSheet(stringResource(R.string.chat_task_title), null, onDismiss) {
        GcTextField(value = title, onValueChange = { title = it.take(120) }, label = stringResource(R.string.chat_task_what), placeholder = stringResource(R.string.chat_task_what_hint))
        GcSectionLabel(stringResource(R.string.chat_task_for))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            others.forEach { p -> PickChip(ChatText.name(ctx, p), assignee == p.userId) { assignee = p.userId } }
            me?.let { m -> PickChip(stringResource(R.string.familie_shop_assign_me), assignee == m.userId) { assignee = m.userId } }
            PickChip(stringResource(R.string.chat_task_anyone), assignee == null) { assignee = null }
        }
        GcSectionLabel(stringResource(R.string.chat_task_until))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PickChip(stringResource(R.string.chat_task_today, "%02d:%02d".format(today19.hour, today19.minute)), due == Due.TODAY) { due = Due.TODAY }
            PickChip(stringResource(R.string.chat_task_tomorrow), due == Due.TOMORROW) { due = Due.TOMORROW }
            PickChip(stringResource(R.string.chat_task_weekly, dayName), due == Due.WEEKLY) { due = Due.WEEKLY }
            PickChip(if (due == Due.DATE) dateTimeLabel(ctx, customMs) else stringResource(R.string.chat_share_date), due == Due.DATE) { picking = true }
        }
        Text(stringResource(R.string.chat_task_info), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        GcPrimaryButton(
            text = stringResource(R.string.chat_task_send),
            onClick = {
                val (ms, repeat) = when (due) {
                    Due.TODAY -> today19.toInstant().toEpochMilli() to null
                    Due.TOMORROW -> tomorrow.toInstant().toEpochMilli() to null
                    Due.WEEKLY -> today19.toInstant().toEpochMilli() to weeklyRepeat(weekday)
                    Due.DATE -> customMs to null
                }
                onSend(title.trim(), assignee, ms, repeat)
            },
            enabled = title.isNotBlank(),
            loading = busy,
        )
    }
    if (picking) {
        DateTimePickerDialog(customMs, onPick = { customMs = it; due = Due.DATE; picking = false }, onDismiss = { picking = false })
    }
}

/** `repeat` of a task every [day] (B4: `weekly:<1..7>`). */
internal fun weeklyRepeat(day: DayOfWeek): String = "weekly:${day.value}"

// ─── shopping list (mockup ShoppingChat) ────────────────────────────

/**
 * "Einkaufsliste" from the chat (Familie B3): pick a list, then "Ich gehe
 * einkaufen" (a card for the family) or "<Name> bitten" — into this direct
 * chat ([direct]) or the family chat.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShoppingShareSheet(
    lists: List<ChatShoppingEntry>,
    others: List<ChatPerson>,
    direct: Boolean,
    busy: Boolean,
    onGoing: (listId: String) -> Unit,
    onAsk: (listId: String, userId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    var chosen by remember(lists) { mutableStateOf(lists.maxByOrNull { it.open }?.list?.id) }
    var ask by remember { mutableStateOf<Long?>(if (direct) others.singleOrNull()?.userId else null) }
    HomeSheet(stringResource(R.string.chat_shop_title), null, onDismiss) {
        if (lists.isEmpty()) Text(stringResource(R.string.chat_shop_none), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
        lists.forEach { e ->
            OptionRow(
                com.gatecontrol.android.ui.components.GcIcons.Cart,
                e.list.name,
                listOf(stringResource(R.string.chat_shop_open, e.open), e.preview.joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · "),
                chosen == e.list.id,
            ) { chosen = e.list.id }
        }
        if (others.isNotEmpty() && lists.isNotEmpty()) {
            GcSectionLabel(stringResource(R.string.familie_shop_assign_who))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PickChip(stringResource(R.string.familie_shop_assign_me), ask == null) { ask = null }
                others.forEach { p -> PickChip(ChatText.name(ctx, p), ask == p.userId) { ask = p.userId } }
            }
            Text(
                stringResource(if (direct) R.string.chat_shop_info_direct else R.string.chat_shop_info_family),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
        val person = others.firstOrNull { it.userId == ask }
        GcPrimaryButton(
            text = person?.let { stringResource(R.string.familie_shop_ask, firstName(ctx, it)) } ?: stringResource(R.string.familie_shopping_going),
            onClick = { val l = chosen ?: return@GcPrimaryButton; if (person != null) onAsk(l, person.userId) else onGoing(l) },
            enabled = chosen != null,
            loading = busy,
        )
    }
}

// ─── live arrival destinations (mockup ChatArrival) ─────────────────

/** The radio rows of the arrival destinations: Familie places, then "Anderer Ort …". */
@Composable
internal fun ArrivalDestinations(
    places: List<com.gatecontrol.android.chat.ChatPlace>,
    pickedPlaceId: String?,
    mapMode: Boolean,
    onPlace: (com.gatecontrol.android.chat.ChatPlace) -> Unit,
    onMap: () -> Unit,
) {
    val family = stringResource(R.string.chat_arrival_family_place)
    places.forEach { p -> OptionRow(Icons.Outlined.Place, p.name, family, !mapMode && pickedPlaceId == p.id) { onPlace(p) } }
    OptionRow(Icons.Outlined.Map, stringResource(R.string.chat_arrival_other), stringResource(R.string.chat_arrival_other_desc), mapMode, onClick = onMap)
}
