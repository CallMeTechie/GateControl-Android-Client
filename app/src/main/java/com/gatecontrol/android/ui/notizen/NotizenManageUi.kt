package com.gatecontrol.android.ui.notizen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NFeature
import com.gatecontrol.android.notizen.NGroup
import com.gatecontrol.android.notizen.NList
import com.gatecontrol.android.notizen.NListUsage
import com.gatecontrol.android.notizen.NMe
import com.gatecontrol.android.notizen.NNotebook
import com.gatecontrol.android.notizen.NNotebookUsage
import com.gatecontrol.android.notizen.NPerson
import com.gatecontrol.android.notizen.NPersonPick
import com.gatecontrol.android.notizen.NotizenJson
import com.gatecontrol.android.notizen.NotizenLists
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.google.gson.JsonObject

// ── small parts ─────────────────────────────────────────────

/** The icon of a list (plugin 1.2.0 `icon`; the plain list without one). */
internal fun listIconVector(icon: String?): ImageVector = when (icon) {
    "cart" -> GcIcons.Cart
    "home" -> GcIcons.House
    "tool" -> Icons.Outlined.Build
    "gift" -> GcIcons.Gift
    "plane" -> Icons.Outlined.Flight
    "heart" -> Icons.Outlined.FavoriteBorder
    "work" -> GcIcons.Briefcase
    else -> GcIcons.Tasks
}

/** A list's colour as a strong tint (its icon, its dot); null: none chosen. */
internal fun listTint(color: String?): Color? = when (color) {
    "green" -> Color(0xFF16A34A)
    "yellow" -> Color(0xFFCA8A04)
    "blue" -> Color(0xFF2563EB)
    "pink" -> Color(0xFFDB2777)
    "grey" -> Color(0xFF6B7280)
    "orange" -> Color(0xFFEA580C)
    "purple" -> Color(0xFF7C3AED)
    "red" -> Color(0xFFDC2626)
    "teal" -> Color(0xFF0D9488)
    else -> null
}

/** The little round picture of a person: the first letter on a soft colour. */
@Composable
internal fun NAvatar(name: String?, size: Dp = 24.dp, me: Boolean = false, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    Box(
        modifier.size(size).clip(CircleShape).background(if (me) x.accentBg else x.panel2).border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            NotizenLists.initial(name),
            style = if (size >= 32.dp) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (me) x.accentText else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The name of a week day (1 = Monday): short („Mo“) or whole („Montag“). */
@Composable
internal fun dayName(day: Int, short: Boolean): String {
    val names = stringArrayResource(if (short) R.array.notizen_days_short else R.array.notizen_days_long)
    return names.getOrNull(day - 1) ?: day.toString()
}

/** „Samstag“, „Mo, Do“ — the list days in words. */
@Composable
internal fun daysText(days: List<Int>): String? = when {
    days.isEmpty() -> null
    days.size == 1 -> dayName(days[0], short = false)
    else -> days.map { dayName(it, short = true) }.joinToString(", ")
}

/** A choice out of a few values as a field (`label  value ⌄`). */
@Composable
private fun <T> PickField(label: String, value: T, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    val x = GateControlTheme.extraColors
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GcSectionLabel(label)
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, x.border2, RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { open = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(options.firstOrNull { it.first == value }?.second.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(GcIcons.ChevronDown, null, tint = x.muted, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { open = false; onPick(v) }, leadingIcon = if (v == value) { { Icon(GcIcons.Check, null) } } else null) }
            }
        }
    }
}

/** The colours to choose (none first): round swatches, the chosen one ringed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorChoice(value: String?, onPick: (String?) -> Unit) {
    val x = GateControlTheme.extraColors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GcSectionLabel(stringResource(R.string.notizen_color))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (listOf<String?>(null) + NotizenJson.COLORS).forEach { c ->
                val label = colorName(c)
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .selectable(selected = c == value, role = Role.RadioButton) { onPick(c) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(noteColor(c) ?: Color.Transparent)
                            .border(if (c == value) 3.dp else 1.dp, if (c == value) MaterialTheme.colorScheme.primary else x.border2, CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun colorName(c: String?): String = when (c) {
    null -> stringResource(R.string.notizen_color_none)
    "yellow" -> stringResource(R.string.notizen_color_yellow)
    "green" -> stringResource(R.string.notizen_color_green)
    "blue" -> stringResource(R.string.notizen_color_blue)
    "pink" -> stringResource(R.string.notizen_color_pink)
    "grey" -> stringResource(R.string.notizen_color_grey)
    "orange" -> stringResource(R.string.notizen_color_orange)
    "purple" -> stringResource(R.string.notizen_color_purple)
    "red" -> stringResource(R.string.notizen_color_red)
    else -> stringResource(R.string.notizen_color_teal)
}

@Composable
private fun iconName(i: String): String = when (i) {
    "cart" -> stringResource(R.string.notizen_icon_cart)
    "home" -> stringResource(R.string.notizen_icon_home)
    "tool" -> stringResource(R.string.notizen_icon_tool)
    "gift" -> stringResource(R.string.notizen_icon_gift)
    "plane" -> stringResource(R.string.notizen_icon_plane)
    "heart" -> stringResource(R.string.notizen_icon_heart)
    "work" -> stringResource(R.string.notizen_icon_work)
    else -> stringResource(R.string.notizen_icon_list)
}

/** A choice of one of several (a round radio and its text, with a hint below). */
@Composable
private fun RadioLine(selected: Boolean, title: String, hint: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 4.dp).size(40.dp))
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted) }
        }
    }
}

/** A red note with a warning sign (what deleting does). */
@Composable
private fun DangerNote(text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(scheme.errorContainer).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(GcIcons.Alert, null, tint = scheme.error, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = scheme.onErrorContainer)
    }
}

// ── the menu of a list, a group, a notebook (bottom sheets) ─

/** A row of a menu sheet: icon, text, a value at the end; red for deleting and leaving. */
@Composable
internal fun NSheetRow(icon: ImageVector, text: String, onClick: () -> Unit, value: String? = null, danger: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val x = GateControlTheme.extraColors
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = if (danger) color else x.muted, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color, modifier = Modifier.weight(1f))
        if (trailing != null) trailing() else value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = x.muted) }
    }
}

/** A menu that comes up from below: its head ([title], [summary]) and the rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NSheet(title: String, summary: String?, onDismiss: () -> Unit, lead: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp).padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                lead?.invoke()
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
                }
            }
            GcRowDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            content()
        }
    }
}

/** The icon of a list in a coloured square (the head of its menu, the card in „Mein Tag“). */
@Composable
internal fun NListBadge(list: NList, size: Dp = 40.dp) {
    val tint = listTint(list.color) ?: MaterialTheme.colorScheme.primary
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(listIconVector(list.icon), null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** „7 offen · geteilt mit Anna, Tom“ */
@Composable
internal fun listSummary(list: NList, meId: Long?): String {
    val others = NotizenLists.others(list, meId)
    return listOfNotNull(
        stringResource(R.string.notizen_open_n, list.open),
        when (NotizenLists.shared(list)) {
            NotizenLists.Shared.BY_ME -> stringResource(R.string.notizen_shared_with, others.joinToString(", "))
            NotizenLists.Shared.WITH_ME -> stringResource(R.string.notizen_shared_by, list.owner?.name.orEmpty())
            NotizenLists.Shared.NO -> null
        },
    ).joinToString(" · ")
}

/**
 * The menu of a list (a long press in the side menu, ⋯ on its page). The owner: Bearbeiten, Teilen, In Gruppe
 * verschieben, Einkaufstag, Liste löschen; a member: In Gruppe verschieben, In „Mein Tag“ zeigen, Liste verlassen.
 */
@Composable
internal fun ListSheet(
    list: NList,
    me: NMe?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onMyDay: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onLeave: () -> Unit,
) {
    val actions = NotizenLists.actions(list, me)
    NSheet(list.name, listSummary(list, me?.userId), onDismiss, lead = { NListBadge(list) }) {
        if (actions.isEmpty()) {
            Text(stringResource(R.string.notizen_msg_read_only), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
        }
        actions.forEach { a ->
            when (a) {
                NotizenLists.Action.EDIT -> NSheetRow(GcIcons.Edit, stringResource(R.string.notizen_edit_dots), { onDismiss(); onEdit() })
                NotizenLists.Action.SHARE -> NSheetRow(
                    GcIcons.Users,
                    stringResource(R.string.notizen_share_dots),
                    { onDismiss(); onShare() },
                    value = list.members.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.notizen_people_n, it, it) },
                )
                NotizenLists.Action.MOVE -> NSheetRow(GcIcons.FolderMove, stringResource(R.string.notizen_move_to_group), { onDismiss(); onMove() })
                NotizenLists.Action.DAYS -> NSheetRow(GcIcons.Calendar, stringResource(R.string.notizen_list_day), { onDismiss(); onEdit() }, value = daysText(list.days) ?: stringResource(R.string.notizen_list_day_none))
                NotizenLists.Action.MYDAY -> NSheetRow(GcIcons.Sun, stringResource(R.string.notizen_show_in_myday), { onMyDay(list.mydayOff) }, trailing = { GcSwitch(!list.mydayOff, null) })
                NotizenLists.Action.DELETE -> NSheetRow(GcIcons.Trash, stringResource(R.string.notizen_list_delete_dots), { onDismiss(); onDelete() }, danger = true)
                NotizenLists.Action.LEAVE -> NSheetRow(Icons.AutoMirrored.Outlined.Logout, stringResource(R.string.notizen_list_leave), { onDismiss(); onLeave() }, danger = true)
            }
        }
        if (NotizenLists.Action.MYDAY in actions) {
            Text(stringResource(R.string.notizen_member_own_hint), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(12.dp))
        }
        // plugin 1.6.0: no sharing, leaving or deleting here
        if (list.family) {
            Text(stringResource(R.string.notizen_msg_family_list), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(12.dp))
        }
    }
}

/** „Liste bearbeiten“: name, colour, icon (1.2.0), group, the list days and the morning message (1.2.0). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ListEditDialog(list: NList, me: NMe?, groups: List<NGroup>, onDismiss: () -> Unit, onSave: (JsonObject) -> Unit, onDelete: (() -> Unit)?) {
    val x = GateControlTheme.extraColors
    var name by rememberSaveable { mutableStateOf(list.name) }
    var color by rememberSaveable { mutableStateOf(list.color) }
    var icon by rememberSaveable { mutableStateOf(list.icon) }
    var group by rememberSaveable { mutableStateOf(list.groupId?.takeIf { g -> groups.any { it.id == g } }) }
    var days by rememberSaveable { mutableStateOf(list.days) }
    var remind by rememberSaveable { mutableStateOf(list.remind != null) }
    val icons = me?.listIcons.orEmpty().takeIf { me?.has(NFeature.LIST_ICONS) == true }.orEmpty()
    val withDays = me?.has(NFeature.LIST_DAYS) == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notizen_list_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GcTextField(name, { name = it.take(80) }, stringResource(R.string.notizen_name))
                ColorChoice(color) { color = it }
                if (icons.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GcSectionLabel(stringResource(R.string.notizen_icon))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            icons.forEach { i ->
                                val on = (icon ?: "list") == i
                                val label = iconName(i)
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (on) x.accentBg else Color.Transparent)
                                        .border(1.dp, if (on) MaterialTheme.colorScheme.primary else x.border2, RoundedCornerShape(12.dp))
                                        .selectable(selected = on, role = Role.RadioButton) { icon = if (i == "list") null else i }
                                        .semantics { contentDescription = label },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(listIconVector(i), null, tint = if (on) x.accentText else x.muted, modifier = Modifier.size(20.dp)) }
                            }
                        }
                    }
                }
                if (!list.isDefault) {
                    PickField(
                        stringResource(R.string.notizen_group),
                        group,
                        listOf<Pair<Long?, String>>(null to stringResource(R.string.notizen_no_group)) + groups.map { it.id to it.name },
                    ) { group = it }
                }
                if (withDays) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GcSectionLabel(stringResource(R.string.notizen_days_label))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            (1..7).forEach { d ->
                                val on = d in days
                                com.gatecontrol.android.ui.components.GcFilterChip(
                                    dayName(d, short = true),
                                    on,
                                    { days = if (on) days - d else (days + d).sorted() },
                                )
                            }
                        }
                        Text(
                            daysText(days)?.let { stringResource(if (list.shared) R.string.notizen_days_hint_shared else R.string.notizen_days_hint, it) } ?: stringResource(R.string.notizen_days_hint_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = x.muted,
                        )
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).toggleable(value = remind, enabled = days.isNotEmpty(), role = Role.Switch) { remind = it }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.notizen_remind_morning), style = MaterialTheme.typography.bodyLarge, color = if (days.isEmpty()) x.faint else MaterialTheme.colorScheme.onSurface)
                                Text(stringResource(R.string.notizen_remind_morning_hint, list.remind ?: NotizenLists.REMIND_DEFAULT), style = MaterialTheme.typography.bodySmall, color = x.muted)
                            }
                            GcSwitch(remind && days.isNotEmpty(), null, enabled = days.isNotEmpty())
                        }
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = { onDismiss(); onDelete() }) {
                        Icon(GcIcons.Trash, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.notizen_list_delete), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val wantRemind = remind && days.isNotEmpty()
                    val body = NotizenJson.listPatch(
                        name = name.trim().takeIf { it != list.name },
                        color = color.takeIf { it != list.color },
                        clearColor = color == null && list.color != null,
                        icon = icon.takeIf { icons.isNotEmpty() && it != list.icon },
                        clearIcon = icons.isNotEmpty() && icon == null && list.icon != null,
                        days = days.takeIf { withDays && it != list.days },
                        remind = if (withDays && wantRemind && list.remind == null) NotizenLists.REMIND_DEFAULT else null,
                        clearRemind = withDays && !wantRemind && list.remind != null,
                        groupId = group.takeIf { it != list.groupId && it != null },
                        clearGroup = group == null && list.groupId != null && !list.isDefault,
                    )
                    onDismiss()
                    onSave(body)
                },
            ) { Text(stringResource(R.string.notizen_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}

/** „In Gruppe verschieben“: no group or one of mine (in the list's area). */
@Composable
internal fun MoveToGroupDialog(list: NList, groups: List<NGroup>, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notizen_move_to_group)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                (listOf<Pair<Long?, String>>(null to stringResource(R.string.notizen_no_group)) + groups.map { it.id to it.name }).forEach { (id, n) ->
                    RadioLine(id == list.groupId, n, null) { onDismiss(); if (id != list.groupId) onPick(id) }
                }
                if (!list.isOwner) Text(stringResource(R.string.notizen_member_own_hint), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}

/**
 * „„Einkaufsliste“ löschen?“: what it has, who loses it, and (plugin 1.2.0) what happens to the open tasks — moved
 * to „Aufgaben“ or deleted with it. Without 1.2.0 all its tasks go, without „Rückgängig“.
 */
@Composable
internal fun ListDeleteDialog(list: NList, usage: NListUsage?, keep: Boolean, meId: Long?, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var mode by rememberSaveable { mutableStateOf("move") }
    val open = usage?.open ?: list.open
    val others = (usage?.members ?: list.members).filter { it.id != meId }.mapNotNull { it.name }
    val target = usage?.target?.name ?: stringResource(R.string.notizen_default_list)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notizen_list_delete_q, list.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val parts = buildList {
                    add(
                        if (usage != null) stringResource(R.string.notizen_list_has, pluralStringResource(R.plurals.notizen_open_tasks_n, open, open), pluralStringResource(R.plurals.notizen_done_tasks_n, usage.done, usage.done))
                        else stringResource(R.string.notizen_list_has_open, pluralStringResource(R.plurals.notizen_open_tasks_n, open, open)),
                    )
                    if (others.isNotEmpty()) add(stringResource(R.string.notizen_list_lose, others.joinToString(", ")))
                    if (!keep) add(stringResource(R.string.notizen_list_delete_all))
                }
                DangerNote(parts.joinToString(" "))
                if (keep && open > 0) {
                    GcSectionLabel(stringResource(R.string.notizen_delete_open_q))
                    RadioLine(mode == "move", stringResource(R.string.notizen_delete_move, target), pluralStringResource(R.plurals.notizen_delete_move_hint, open, open)) { mode = "move" }
                    RadioLine(mode == "delete", stringResource(R.string.notizen_delete_all_tasks), null) { mode = "delete" }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm(if (keep) (if (open > 0) mode else "delete") else null) }) {
                Text(stringResource(R.string.notizen_list_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}

/** „Liste verlassen?“ / „Notizbuch verlassen?“ */
@Composable
internal fun LeaveDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(stringResource(R.string.notizen_leave), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}

// ── sharing ─────────────────────────────────────────────────

/**
 * „„Einkaufsliste“ teilen“: find a person by name and add them, set what they may (plugin 1.2.0: Ansehen /
 * Bearbeiten), take them away again. Each change goes to the server at once.
 */
@Composable
internal fun ShareDialog(
    title: String,
    owner: NPerson?,
    members: List<NPerson>,
    people: List<NPersonPick>?,
    roles: Boolean,
    meId: Long?,
    busy: Boolean,
    notebook: Boolean,
    onDismiss: () -> Unit,
    onChange: (List<Pair<Long, String>>) -> Unit,
) {
    val x = GateControlTheme.extraColors
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notizen_share_title, title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.notizen_share_intro), style = MaterialTheme.typography.bodySmall, color = x.muted)
                GcTextField(query, { query = it.take(60) }, stringResource(R.string.notizen_share_search))
                if (people == null) {
                    NotizenLoading()
                } else {
                    val found = NotizenLists.candidates(people, members, owner?.id, query).take(if (query.isBlank()) 5 else 12)
                    if (found.isEmpty() && query.isNotBlank()) Text(stringResource(R.string.notizen_share_nobody), style = MaterialTheme.typography.bodySmall, color = x.muted)
                    found.forEach { p ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NAvatar(p.name, 32.dp)
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(enabled = !busy, onClick = { onChange(NotizenLists.membersAfter(members, add = p.id)) }) { Text(stringResource(R.string.notizen_add)) }
                        }
                    }
                }
                GcSectionLabel(stringResource(R.string.notizen_share_who), modifier = Modifier.padding(top = 6.dp))
                owner?.let { o ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NAvatar(o.name, 32.dp, me = o.id == meId)
                        Column(Modifier.weight(1f)) {
                            Text(if (o.id == meId) stringResource(R.string.notizen_me_name, o.name.orEmpty()) else o.name.orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(if (notebook) R.string.notizen_owner_hint_nb else R.string.notizen_owner_hint), style = MaterialTheme.typography.bodySmall, color = x.muted)
                        }
                        Text(stringResource(R.string.notizen_role_owner), style = MaterialTheme.typography.labelLarge, color = x.muted)
                    }
                }
                members.forEach { m ->
                    val id = m.id ?: return@forEach
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NAvatar(m.name, 32.dp)
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(m.name.orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            m.since?.let { s -> Text(stringResource(R.string.notizen_since, sinceText(s)), style = MaterialTheme.typography.bodySmall, color = x.muted) }
                        }
                        if (roles) {
                            RolePicker(m.role ?: "write", enabled = !busy) { r -> onChange(NotizenLists.membersAfter(members, role = id to r)) }
                        }
                        com.gatecontrol.android.ui.components.GcIconButton(
                            GcIcons.Close,
                            stringResource(R.string.notizen_share_remove, m.name.orEmpty()),
                            { onChange(NotizenLists.membersAfter(members, remove = id)) },
                            size = 44.dp,
                            iconSize = 18.dp,
                            enabled = !busy,
                        )
                    }
                }
                Text(
                    stringResource(if (roles) (if (notebook) R.string.notizen_share_roles_hint_nb else R.string.notizen_share_roles_hint) else R.string.notizen_share_all_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = x.muted,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_done)) } },
    )
}

private fun sinceText(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))

/** „Bearbeiten ⌄“ / „Ansehen ⌄“ of a member. */
@Composable
private fun RolePicker(role: String, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(if (role == "read") R.string.notizen_role_read else R.string.notizen_role_write)
    Box {
        Row(
            Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, role = Role.Button) { open = true }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Icon(GcIcons.ChevronDown, null, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("write" to R.string.notizen_role_write, "read" to R.string.notizen_role_read).forEach { (r, l) ->
                DropdownMenuItem(text = { Text(stringResource(l)) }, onClick = { open = false; if (r != role) onPick(r) }, leadingIcon = if (r == role) { { Icon(GcIcons.Check, null) } } else null)
            }
        }
    }
}

// ── new list or group, the menu of a group ──────────────────

/**
 * „Neue Liste oder Gruppe“: above the choice; a list with its name and group, a group with its name and (freely)
 * the lists to put into it.
 */
@Composable
internal fun NewListOrGroupDialog(groups: List<NGroup>, lists: List<NList>, initialGroup: Long?, onDismiss: () -> Unit, onList: (String, Long?) -> Unit, onGroup: (String, List<Long>) -> Unit) {
    val x = GateControlTheme.extraColors
    var kind by rememberSaveable { mutableStateOf("list") }
    var name by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf(initialGroup) }
    var picked by rememberSaveable { mutableStateOf(listOf<Long>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notizen_new_list_or_group_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GcSegmented(listOf("list" to stringResource(R.string.notizen_kind_list), "group" to stringResource(R.string.notizen_kind_group)), kind, { kind = it })
                GcTextField(name, { name = it.take(80) }, stringResource(if (kind == "list") R.string.notizen_list_name else R.string.notizen_group_name))
                if (kind == "list") {
                    if (groups.isNotEmpty()) {
                        PickField(stringResource(R.string.notizen_in_group), group, listOf<Pair<Long?, String>>(null to stringResource(R.string.notizen_no_group)) + groups.map { it.id to it.name }) { group = it }
                    }
                    Text(stringResource(R.string.notizen_new_list_hint), style = MaterialTheme.typography.bodySmall, color = x.muted)
                } else {
                    val candidates = lists.filterNot { it.isDefault }
                    if (candidates.isNotEmpty()) {
                        GcSectionLabel(stringResource(R.string.notizen_group_put_in))
                        Column {
                            candidates.forEach { l ->
                                val on = l.id in picked
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                                        .toggleable(value = on, role = Role.Checkbox) { picked = if (on) picked - l.id else picked + l.id }
                                        .padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    androidx.compose.material3.Checkbox(checked = on, onCheckedChange = null)
                                    Icon(listIconVector(l.icon), null, tint = listTint(l.color) ?: x.muted, modifier = Modifier.size(18.dp))
                                    Text(l.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.notizen_group_hint), style = MaterialTheme.typography.bodySmall, color = x.muted)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onDismiss()
                if (kind == "list") onList(name.trim(), group) else onGroup(name.trim(), picked)
            }) { Text(stringResource(if (kind == "list") R.string.notizen_create_list else R.string.notizen_create_group)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}

/** The menu of a group: Neue Liste in dieser Gruppe, Umbenennen, Gruppe auflösen (its lists stay). */
@Composable
internal fun GroupSheet(group: NGroup, lists: Int, open: Int, onDismiss: () -> Unit, onNewList: () -> Unit, onRename: () -> Unit, onDissolve: () -> Unit) {
    NSheet(
        group.name,
        listOf(pluralStringResource(R.plurals.notizen_lists_n, lists, lists), stringResource(R.string.notizen_open_n, open)).joinToString(" · "),
        onDismiss,
        lead = {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(GateControlTheme.extraColors.panel2), contentAlignment = Alignment.Center) {
                Icon(GcIcons.Folder, null, tint = GateControlTheme.extraColors.muted, modifier = Modifier.size(20.dp))
            }
        },
    ) {
        NSheetRow(GcIcons.Plus, stringResource(R.string.notizen_group_new_list), { onDismiss(); onNewList() })
        NSheetRow(GcIcons.Edit, stringResource(R.string.notizen_rename), { onDismiss(); onRename() })
        NSheetRow(GcIcons.Trash, stringResource(R.string.notizen_group_dissolve_dots), { onDismiss(); onDissolve() }, danger = true)
    }
}

@Composable
internal fun DissolveDialog(group: NGroup, listNames: List<String>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notizen_group_dissolve_q, group.name)) },
        text = {
            Text(
                if (listNames.isEmpty()) stringResource(R.string.notizen_group_dissolve_empty)
                else stringResource(R.string.notizen_group_dissolve_text, listNames.joinToString(", ")),
            )
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(stringResource(R.string.notizen_group_dissolve)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}

// ── notebooks ───────────────────────────────────────────────

/** The menu of a notebook: Bearbeiten, Teilen, Notizbuch löschen (owner) or Verlassen (member). */
@Composable
internal fun NotebookSheet(nb: NNotebook, name: String, me: NMe?, onDismiss: () -> Unit, onEdit: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit, onLeave: () -> Unit) {
    val actions = NotizenLists.notebookActions(nb, me)
    val summary = listOfNotNull(
        pluralStringResource(R.plurals.notizen_notes_n, nb.count, nb.count),
        if (nb.vault != null) stringResource(R.string.notizen_tresor) else null,
        if (nb.shared && nb.isOwner) stringResource(R.string.notizen_shared_with, nb.members.mapNotNull { it.name }.joinToString(", ")) else null,
        if (nb.shared && !nb.isOwner) stringResource(R.string.notizen_shared_by, nb.owner?.name.orEmpty()) else null,
    ).joinToString(" · ")
    NSheet(name, summary, onDismiss) {
        if (actions.isEmpty()) Text(stringResource(R.string.notizen_nb_no_actions), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
        actions.forEach { a ->
            when (a) {
                NotizenLists.NotebookAction.EDIT -> NSheetRow(GcIcons.Edit, stringResource(R.string.notizen_edit_dots), { onDismiss(); onEdit() })
                NotizenLists.NotebookAction.SHARE -> NSheetRow(
                    GcIcons.Users,
                    stringResource(R.string.notizen_share_dots),
                    { onDismiss(); onShare() },
                    value = nb.members.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.notizen_people_n, it, it) },
                )
                NotizenLists.NotebookAction.DELETE -> NSheetRow(GcIcons.Trash, stringResource(R.string.notizen_nb_delete_dots), { onDismiss(); onDelete() }, danger = true)
                NotizenLists.NotebookAction.LEAVE -> NSheetRow(Icons.AutoMirrored.Outlined.Logout, stringResource(R.string.notizen_nb_leave), { onDismiss(); onLeave() }, danger = true)
            }
        }
    }
}

/** „Notizbuch bearbeiten“: name (not a Tresor's, which only its devices can read), colour, who it is shared with. */
@Composable
internal fun NotebookEditDialog(nb: NNotebook, canShare: Boolean, roles: Boolean, onDismiss: () -> Unit, onSave: (JsonObject) -> Unit, onShare: () -> Unit, onDelete: (() -> Unit)?) {
    val x = GateControlTheme.extraColors
    var name by rememberSaveable { mutableStateOf(nb.name.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(nb.color) }
    val plain = nb.vault == null && nb.bound == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notizen_nb_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (plain) GcTextField(name, { name = it.take(80) }, stringResource(R.string.notizen_name))
                ColorChoice(color) { color = it }
                if (canShare) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GcSectionLabel(stringResource(R.string.notizen_shared_label))
                        nb.members.forEach { m ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NAvatar(m.name, 28.dp)
                                Text(m.name.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (roles) Text(stringResource(if (m.role == "read") R.string.notizen_role_read else R.string.notizen_role_write), style = MaterialTheme.typography.labelLarge, color = x.muted)
                            }
                        }
                        TextButton(onClick = { onDismiss(); onShare() }) {
                            Icon(GcIcons.Plus, null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.notizen_add_person), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = { onDismiss(); onDelete() }) {
                        Icon(GcIcons.Trash, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.notizen_nb_delete), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (nb.isDefault) Text(stringResource(R.string.notizen_nb_default_hint), style = MaterialTheme.typography.bodySmall, color = x.muted)
            }
        },
        confirmButton = {
            TextButton(enabled = !plain || name.isNotBlank(), onClick = {
                val body = JsonObject().apply {
                    if (plain && name.trim() != nb.name) addProperty("name", name.trim())
                    if (color != nb.color) color?.let { addProperty("color", it) } ?: add("color", com.google.gson.JsonNull.INSTANCE)
                }
                onDismiss()
                onSave(body)
            }) { Text(stringResource(R.string.notizen_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}

/**
 * „„Rezepte“ löschen?“: how many notes, who loses them, and (plugin 1.2.0) whether they move to „Notizen“ or go
 * with it. A Tresor asks for its name first and cannot move its notes.
 */
@Composable
internal fun NotebookDeleteDialog(nb: NNotebook, name: String?, usage: NNotebookUsage?, keep: Boolean, meId: Long?, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    val vault = nb.vault != null
    var mode by rememberSaveable { mutableStateOf(if (vault) "delete" else "move") }
    var typed by rememberSaveable { mutableStateOf("") }
    val notes = usage?.notes ?: nb.count
    val others = (usage?.members ?: nb.members).filter { it.id != meId }.mapNotNull { it.name }
    val target = usage?.target?.name ?: stringResource(R.string.notizen_default_notebook)
    val ok = !vault || NotizenLists.nameConfirmed(typed, name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notizen_list_delete_q, name ?: stringResource(R.string.notizen_tresor))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val parts = buildList {
                    val n = pluralStringResource(R.plurals.notizen_notes_n, notes, notes)
                    add(if (usage != null && usage.withImages > 0) stringResource(R.string.notizen_nb_has_images, n, usage.withImages) else stringResource(R.string.notizen_nb_has, n))
                    if (others.isNotEmpty()) add(stringResource(R.string.notizen_nb_lose, others.joinToString(", ")))
                    if (!keep || vault) add(stringResource(R.string.notizen_nb_delete_all))
                }
                DangerNote(parts.joinToString(" "))
                if (keep && !vault && notes > 0) {
                    RadioLine(mode == "move", stringResource(R.string.notizen_nb_move, target), null) { mode = "move" }
                    RadioLine(mode == "delete", stringResource(R.string.notizen_nb_delete_notes), null) { mode = "delete" }
                }
                if (vault) {
                    Text(stringResource(R.string.notizen_nb_type_name), style = MaterialTheme.typography.bodyMedium)
                    GcTextField(typed, { typed = it.take(80) }, stringResource(R.string.notizen_name), enabled = name != null)
                    if (name == null) Text(stringResource(R.string.notizen_nb_unlock_first), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onDismiss(); onConfirm(if (keep) (if (vault || notes == 0) "delete" else mode) else null) }) {
                Text(stringResource(R.string.notizen_delete), color = if (ok) MaterialTheme.colorScheme.error else GateControlTheme.extraColors.faint)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}
