package com.gatecontrol.android.ui.quickactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.quickactions.QuickActionDeviceGroup
import com.gatecontrol.android.quickactions.QuickActionIcons
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.StartWidgetPin
import com.gatecontrol.android.quickactions.WidgetPart
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.start.StartIcons
import com.gatecontrol.android.ui.theme.GateControlTheme

private const val TAB_BUTTONS = "buttons"
private const val TAB_WIDGET = "widget"

/**
 * Sheet from below after a tap on a device in "Schnellaktionen und
 * Widgets": the device on the start tab as **Schnellaktion** (a tick per
 * action; each ticked one becomes a button) or as **Widget** (a preview,
 * half or full width and a tick per part the card shows). Opens on the
 * widget side when the device already is one. "Lösen" takes the widget off.
 *
 * [fromStart]: opened by a long press on the start tab (mockup
 * StartWidgetSettings) — only the side of what was pressed ([startOnWidget]:
 * the widget, else the buttons), "Vom Start lösen" and "Fertig". Both kinds
 * offer "Erinnerung für dieses Gerät" when [onReminder] is given (the
 * reminder editor with the device chosen) and "Start-Tab anordnen" when
 * [onArrange] is given (off with a hint while [arrangeLocked]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinSheet(
    device: QuickActionDeviceGroup,
    pinnedActionIds: Set<String>,
    widgetPin: StartWidgetPin?,
    onSaveButtons: (Set<String>) -> Unit,
    onSaveWidget: (parts: List<String>, wide: Boolean) -> Unit,
    onRemoveWidget: () -> Unit,
    onDismiss: () -> Unit,
    fromStart: Boolean = false,
    startOnWidget: Boolean? = null,
    onReminder: (() -> Unit)? = null,
    onArrange: (() -> Unit)? = null,
    arrangeLocked: Boolean = false,
) {
    val extra = GateControlTheme.extraColors
    val allParts = remember(device) { device.parts() }
    var tab by rememberSaveable(device.key) {
        mutableStateOf(if (startOnWidget ?: (widgetPin != null)) TAB_WIDGET else TAB_BUTTONS)
    }
    var chosenActions by rememberSaveable(device.key) {
        mutableStateOf(
            pinnedActionIds.ifEmpty { if (device.actions.size == 1) setOf(device.actions.first().id) else emptySet() }.toList(),
        )
    }
    var chosenParts by rememberSaveable(device.key) {
        mutableStateOf(widgetPin?.shownParts(device)?.map { it.id } ?: allParts.map { it.id })
    }
    var wide by rememberSaveable(device.key) { mutableStateOf(widgetPin?.wide ?: false) }
    val icon = remember(device.icon) { QuickActionIcons.vector(device.icon) }
    val saveParts = { onSaveWidget(allParts.map { it.id }.filter { it in chosenParts }, wide) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GcIconSquare(size = 40.dp, background = extra.accentBg) {
                    Icon(icon, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(device.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (fromStart) {
                            stringResource(if (tab == TAB_WIDGET) R.string.pin_sheet_widget_on_start else R.string.pin_sheet_button_on_start)
                        } else {
                            listOfNotNull(device.pluginName, device.subtitle).joinToString(" · ")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!fromStart) {
                GcSegmented(
                    options = listOf(
                        TAB_BUTTONS to stringResource(R.string.pin_sheet_as_button),
                        TAB_WIDGET to stringResource(R.string.quick_actions_as_widget),
                    ),
                    selected = tab,
                    onSelect = { tab = it },
                )
            }

            if (tab == TAB_BUTTONS) {
                GcSectionLabel(stringResource(R.string.pin_sheet_buttons_label))
                Column {
                    device.actions.forEach { action ->
                        ActionCheckRow(
                            action = action,
                            checked = action.id in chosenActions,
                            onChange = { on -> chosenActions = if (on) chosenActions + action.id else chosenActions - action.id },
                        )
                    }
                }
                StartRows(onReminder, onArrange, arrangeLocked)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (fromStart) {
                        GcOutlineButton(text = stringResource(R.string.pin_sheet_unpin_start), onClick = { onSaveButtons(emptySet()) }, modifier = Modifier.weight(1f))
                        GcPrimaryButton(
                            text = stringResource(R.string.common_done),
                            onClick = { onSaveButtons(chosenActions.toSet()) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        GcOutlineButton(text = stringResource(R.string.common_cancel), onClick = onDismiss, fillWidth = false)
                        GcPrimaryButton(
                            text = if (chosenActions.isEmpty() && pinnedActionIds.isNotEmpty()) stringResource(R.string.pin_sheet_unpin_buttons)
                            else pluralStringResource(R.plurals.pin_sheet_save_buttons, chosenActions.size, chosenActions.size),
                            onClick = { onSaveButtons(chosenActions.toSet()) },
                            enabled = chosenActions.isNotEmpty() || pinnedActionIds.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else if (fromStart) {
                // Mockup StartWidgetSettings: width, the parts in two columns, the rows, "Vom Start lösen" / "Fertig".
                GcSegmented(
                    options = listOf(false to stringResource(R.string.pin_sheet_half), true to stringResource(R.string.pin_sheet_wide)),
                    selected = wide,
                    onSelect = { wide = it },
                )
                GcSectionLabel(stringResource(R.string.pin_sheet_show))
                Column {
                    allParts.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { part ->
                                PartCheckRow(
                                    label = partLabel(part),
                                    hint = null,
                                    checked = part.id in chosenParts,
                                    onChange = { on -> chosenParts = if (on) chosenParts + part.id else chosenParts - part.id },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
                StartRows(onReminder, onArrange, arrangeLocked)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GcOutlineButton(text = stringResource(R.string.pin_sheet_unpin_start), onClick = onRemoveWidget, modifier = Modifier.weight(1f))
                    GcPrimaryButton(
                        text = stringResource(R.string.common_done),
                        onClick = saveParts,
                        enabled = chosenParts.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    QuickActionWidgetCard(
                        device = device,
                        parts = allParts.filter { it.id in chosenParts },
                        running = null,
                        adjusting = emptySet(),
                        onRun = {},
                        onLongPress = null,
                        onControl = { _, _, _ -> },
                        modifier = Modifier.width(if (wide) 240.dp else 168.dp),
                        interactive = false,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GcSectionLabel(stringResource(R.string.pin_sheet_size))
                        SizeOption(stringResource(R.string.pin_sheet_half), selected = !wide, onClick = { wide = false })
                        SizeOption(stringResource(R.string.pin_sheet_wide), selected = wide, onClick = { wide = true })
                    }
                }
                GcSectionLabel(stringResource(R.string.pin_sheet_parts_label))
                Column {
                    allParts.forEach { part ->
                        PartCheckRow(
                            label = partLabel(part),
                            hint = partHint(part, device),
                            checked = part.id in chosenParts,
                            onChange = { on -> chosenParts = if (on) chosenParts + part.id else chosenParts - part.id },
                        )
                    }
                }
                StartRows(onReminder, onArrange, arrangeLocked)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (widgetPin != null) {
                        GcOutlineButton(text = stringResource(R.string.pin_sheet_remove), onClick = onRemoveWidget, fillWidth = false)
                    } else {
                        GcOutlineButton(text = stringResource(R.string.common_cancel), onClick = onDismiss, fillWidth = false)
                    }
                    GcPrimaryButton(
                        text = stringResource(if (widgetPin != null) R.string.pin_sheet_save else R.string.pin_sheet_pin_widget),
                        onClick = saveParts,
                        enabled = chosenParts.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** "Erinnerung für dieses Gerät" and "Start-Tab anordnen", each only with its callback. */
@Composable
private fun StartRows(onReminder: (() -> Unit)?, onArrange: (() -> Unit)?, arrangeLocked: Boolean) {
    if (onReminder == null && onArrange == null) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onReminder != null) {
            SheetLinkRow(
                icon = StartIcons.Reminder,
                title = stringResource(R.string.pin_sheet_reminder),
                hint = stringResource(R.string.pin_sheet_reminder_hint),
                onClick = onReminder,
            )
        }
        if (onArrange != null) {
            SheetLinkRow(
                icon = StartIcons.Arrange,
                title = stringResource(R.string.start_arrange),
                hint = if (arrangeLocked) stringResource(R.string.arrange_locked) else null,
                onClick = onArrange,
                enabled = !arrangeLocked,
            )
        }
    }
}

/** A tinted row leading elsewhere: icon, title (hint below), chevron. */
@Composable
private fun SheetLinkRow(icon: ImageVector, title: String, hint: String?, onClick: () -> Unit, enabled: Boolean = true) {
    val extra = GateControlTheme.extraColors
    val content = if (enabled) MaterialTheme.colorScheme.onSurface else extra.faint
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(extra.panel2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) extra.accentText else extra.faint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = content)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        if (enabled) GcChevron()
    }
}

/** An action to tick: its icon (current state's), name, and that it asks first where it does. */
@Composable
private fun ActionCheckRow(action: QuickActionItem, checked: Boolean, onChange: (Boolean) -> Unit) {
    val extra = GateControlTheme.extraColors
    val icon = remember(action.displayIcon) { QuickActionIcons.vector(action.displayIcon) }
    val toned = toneColors(action.currentLook?.tone)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
        GcIconSquare(size = 36.dp, background = toned?.second ?: extra.panel2) {
            Icon(icon, contentDescription = null, tint = toned?.first ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(action.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            val hint = when {
                action.toggle && action.confirmWhen != null -> stringResource(R.string.pin_sheet_asks_when, stringResource(if (action.confirmWhen == "off") R.string.quick_action_off else R.string.quick_action_on))
                action.confirm -> stringResource(R.string.pin_sheet_asks_first)
                else -> null
            }
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
    }
}

@Composable
private fun PartCheckRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = extra.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(120.dp))
    }
}

@Composable
private fun SizeOption(text: String, selected: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) extra.accentBg else extra.panel2)
            .toggleable(value = selected, role = Role.RadioButton, onValueChange = { onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) extra.accentText else extra.muted)
    }
}

/** The name of a part in the tick list. */
@Composable
private fun partLabel(part: WidgetPart): String = when (part.kind) {
    WidgetPart.Kind.POWER -> stringResource(R.string.pin_sheet_part_power)
    WidgetPart.Kind.VALUE -> part.label ?: stringResource(R.string.pin_sheet_part_value)
    WidgetPart.Kind.STATUS -> stringResource(R.string.pin_sheet_part_status)
    WidgetPart.Kind.ACTION -> stringResource(R.string.pin_sheet_part_action, part.label ?: "")
    else -> part.label ?: part.id
}

/** A short hint on the right: the current value, or the control's kind. */
@Composable
private fun partHint(part: WidgetPart, device: QuickActionDeviceGroup): String? {
    val w = device.widget
    return when (part.kind) {
        WidgetPart.Kind.VALUE -> w?.value?.let { v -> w.unit?.let { "$v $it" } ?: v }
        WidgetPart.Kind.READING -> w?.readings?.firstOrNull { WidgetPart.reading(it.id) == part.id }?.let { r -> r.value?.let { v -> r.unit?.let { "$v $it" } ?: v } }
        WidgetPart.Kind.STATUS -> w?.status
        WidgetPart.Kind.CONTROL -> w?.controls?.firstOrNull { WidgetPart.control(it.id) == part.id }?.let { c ->
            if (c.isNumeric) "− ${com.gatecontrol.android.quickactions.QuickActionWidgetItem.formatNumber(c.number)}${c.unit?.let { " $it" } ?: ""} +"
            else c.options.joinToString(" · ") { it.label }
        }
        else -> null
    }
}
