package com.gatecontrol.android.ui.quickactions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.quickactions.QuickActionControlItem
import com.gatecontrol.android.quickactions.QuickActionDeviceGroup
import com.gatecontrol.android.quickactions.QuickActionIcons
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionReadingItem
import com.gatecontrol.android.quickactions.QuickActionWidgetItem
import com.gatecontrol.android.quickactions.WidgetPart
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlin.math.roundToInt

/**
 * A device pinned as a widget: icon, name and — when chosen — its power
 * switch in the head; then, as the person chose them ([parts]), the main
 * value, the small readings (an icon alone shows a state, e.g. a green
 * lock), the status line, the plugin's controls (stepper, slider, choice)
 * and the device's other actions as icon buttons. A control change goes to
 * [onControl] for the action carrying the widget (the view model sends it
 * debounced); an action button calls [onRun] (which asks first where the
 * action wants that). [running] is the key of the action running now,
 * [adjusting] the controls whose value is on its way. [interactive] false:
 * a preview (the pin sheet), nothing reacts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickActionWidgetCard(
    device: QuickActionDeviceGroup,
    parts: List<WidgetPart>,
    running: String?,
    adjusting: Set<String>,
    onRun: (QuickActionItem) -> Unit,
    onLongPress: (() -> Unit)?,
    onControl: (carrier: QuickActionItem, controlId: String, value: Any) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val widget = device.widget
    val carrier = device.carrier
    val icon = remember(device.icon) { QuickActionIcons.vector(device.icon) }
    val (tint, background) = stateColors(widget?.state ?: device.powerAction?.state)
    val shown = parts.map { it.id }.toSet()
    val power = device.powerAction?.takeIf { WidgetPart.action(it.id) in shown }
    val actionButtons = device.actions.filter { it != power && WidgetPart.action(it.id) in shown }
    val busy = device.actions.any { it.key == running }

    GcCard(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .then(
                if (interactive && onLongPress != null) {
                    Modifier.combinedClickable(
                        role = Role.Button,
                        onLongClickLabel = stringResource(R.string.door_open_screen),
                        onLongClick = onLongPress,
                        onClick = {},
                    )
                } else {
                    Modifier
                },
            ),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GcIconSquare(size = 32.dp, background = background) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Text(
                device.title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            widget?.badge?.let { BadgeChip(it) }
            when {
                busy -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = extra.accentText)
                power != null -> GcSwitch(checked = power.isOn, onCheckedChange = if (interactive) ({ onRun(power) }) else null)
            }
        }
        if (widget != null && (widget.value != null || widget.label != null) && WidgetPart.VALUE in shown) MainValue(widget)
        val readings = widget?.readings.orEmpty().filter { WidgetPart.reading(it.id) in shown }
        if (readings.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                readings.forEach { ReadingChip(it) }
            }
        }
        if (widget?.status != null && WidgetPart.STATUS in shown) {
            Text(widget.status, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (carrier != null && widget != null) {
            widget.controls.filter { WidgetPart.control(it.id) in shown }.forEach { control ->
                val busyControl = "${carrier.key}/${control.id}" in adjusting
                val send: (Any) -> Unit = { if (interactive) onControl(carrier, control.id, it) }
                when (control.kind) {
                    QuickActionControlItem.KIND_STEPPER -> StepperControl(control, busyControl, onChange = send)
                    QuickActionControlItem.KIND_SLIDER -> SliderControl(control, busyControl, onChange = send)
                    QuickActionControlItem.KIND_CHOICE -> ChoiceControl(control, onChange = send)
                }
            }
        }
        if (actionButtons.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                actionButtons.chunked(buttonsPerRow(actionButtons.size)).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { action ->
                            ActionButton(
                                action,
                                running = action.key == running,
                                onClick = { if (interactive) onRun(action) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The main value big with its unit, its name small below. */
@Composable
private fun MainValue(widget: QuickActionWidgetItem) {
    val extra = GateControlTheme.extraColors
    Column {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                widget.value ?: "–",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (widget.unit != null && widget.value != null) {
                Text(widget.unit, style = MaterialTheme.typography.bodyMedium, color = extra.muted, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        if (widget.label != null) {
            Text(widget.label, style = MaterialTheme.typography.labelSmall, color = extra.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The head's badge (e.g. battery and charge level): icon tinted by its state, then the value. */
@Composable
private fun BadgeChip(badge: QuickActionReadingItem) {
    val extra = GateControlTheme.extraColors
    val icon = remember(badge.icon) { QuickActionIcons.vectorOrNull(badge.icon) } ?: return
    val (tint, _) = stateColors(badge.state)
    val text = listOfNotNull(badge.value, badge.unit).joinToString(" ")
    val description = listOf(badge.label, text).filter { it.isNotBlank() }.joinToString(": ")
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (badge.state != null) tint else extra.muted, modifier = Modifier.size(18.dp))
        if (text.isNotEmpty()) {
            Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

/** A small reading: its icon (tinted by its state) and value; an icon alone sits in a tinted circle. */
@Composable
internal fun ReadingChip(reading: QuickActionReadingItem) {
    val extra = GateControlTheme.extraColors
    val icon = remember(reading.icon) { reading.icon?.let { QuickActionIcons.vectorOrNull(it) } }
    val (tint, background) = stateColors(reading.state)
    val description = listOfNotNull(reading.label, reading.value?.let { v -> reading.unit?.let { "$v $it" } ?: v }).joinToString(": ")
    if (reading.value == null && icon != null) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(background).semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        }
        return
    }
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (reading.state != null) tint else extra.muted, modifier = Modifier.size(16.dp))
        Text(
            listOfNotNull(reading.value, reading.unit).joinToString(" "),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * The action buttons of a widget share its width: up to 3 in a row, 4 as
 * two rows of 2; every row spans the whole widget (5 → 3 + 2, 6 → 3 + 3).
 */
internal fun buttonsPerRow(count: Int): Int = if (count == 4) 2 else count.coerceIn(1, 3)

/** One of the device's actions as an icon button: its look per state, filled while a plain switch is on. */
@Composable
private fun ActionButton(action: QuickActionItem, running: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val toned = toneColors(action.currentLook?.tone)
    val icon = remember(action.displayIcon) { QuickActionIcons.vector(action.displayIcon) }
    val shape = RoundedCornerShape(10.dp)
    val (glyph, fill) = when {
        toned != null -> toned.first to toned.second
        action.isOn -> scheme.onPrimary to scheme.primary
        else -> scheme.onSurface to Color.Transparent
    }
    Box(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, if (toned != null) toned.first.copy(alpha = 0.4f) else if (action.isOn) Color.Transparent else extra.border2, shape)
            .combinedClickable(enabled = !running, role = if (action.toggle) Role.Switch else Role.Button, onClick = onClick)
            .semantics { contentDescription = action.title },
        contentAlignment = Alignment.Center,
    ) {
        if (running) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = glyph)
        else Icon(icon, contentDescription = null, tint = glyph, modifier = Modifier.size(20.dp))
    }
}

/** Accent of a state: on/good green, warn amber, crit red, off and none neutral. */
@Composable
internal fun stateColors(state: String?): Pair<Color, Color> {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    return when (state) {
        "on", "good" -> extra.accentText to extra.accentBg
        "warn" -> extra.warn to extra.warnBg
        "crit" -> scheme.error to extra.errorBg
        else -> extra.muted to extra.panel2
    }
}

/** − value + ; the value is kept locally until the server's answer replaces it. */
@Composable
private fun StepperControl(control: QuickActionControlItem, busy: Boolean, onChange: (Double) -> Unit) {
    val extra = GateControlTheme.extraColors
    var local by remember(control.number) { mutableStateOf(control.number) }
    val label = control.label ?: ""
    fun set(v: Double) {
        local = snap(v, control)
        onChange(local)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (control.label != null) {
            Text(control.label, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, modifier = Modifier.weight(1f))
        } else {
            Box(Modifier.weight(1f))
        }
        GcIconButton(
            icon = GcIcons.Minus,
            contentDescription = stringResource(R.string.widget_decrease, label),
            onClick = { set(local - control.step) },
            enabled = local > control.min,
            size = 32.dp,
            iconSize = 18.dp,
        )
        Text(
            formatValue(local, control),
            style = MaterialTheme.typography.titleSmall,
            color = if (busy) extra.accentText else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        GcIconButton(
            icon = GcIcons.Plus,
            contentDescription = stringResource(R.string.widget_increase, label),
            onClick = { set(local + control.step) },
            enabled = local < control.max,
            size = 32.dp,
            iconSize = 18.dp,
        )
    }
}

/** Label and value above a slider; sent when the finger lifts. */
@Composable
private fun SliderControl(control: QuickActionControlItem, busy: Boolean, onChange: (Double) -> Unit) {
    val extra = GateControlTheme.extraColors
    var local by remember(control.number) { mutableStateOf(control.number) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(control.label ?: "", style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, modifier = Modifier.weight(1f))
            Text(
                formatValue(local, control),
                style = MaterialTheme.typography.titleSmall,
                color = if (busy) extra.accentText else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        val span = control.max - control.min
        val steps = ((span / control.step).roundToInt() - 1).coerceIn(0, 200)
        Slider(
            value = local.toFloat(),
            onValueChange = { local = snap(it.toDouble(), control) },
            onValueChangeFinished = { onChange(local) },
            valueRange = control.min.toFloat()..control.max.toFloat(),
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = extra.border2,
                activeTickColor = MaterialTheme.colorScheme.primary,
                inactiveTickColor = extra.border2,
            ),
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}

/**
 * The options as icon buttons sharing the widget's width, the chosen one
 * filled; an option without an icon (unknown mode) shows its label instead.
 */
@Composable
private fun ChoiceControl(control: QuickActionControlItem, onChange: (String) -> Unit) {
    val extra = GateControlTheme.extraColors
    var local by remember(control.choice) { mutableStateOf(control.choice) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        control.options.forEach { option ->
            val selected = option.id == local
            val icon = remember(option.glyph) { QuickActionIcons.vectorOrNull(option.glyph) }
            val tint = if (selected) extra.accentText else extra.muted
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) extra.accentBg else extra.panel2)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { local = option.id; onChange(option.id) })
                    .semantics { contentDescription = option.label },
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                } else {
                    Text(option.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Onto the control's grid and into its range. */
private fun snap(v: Double, c: QuickActionControlItem): Double {
    val stepped = c.min + ((v - c.min) / c.step).roundToInt() * c.step
    val rounded = if (c.decimals == 0) stepped.roundToInt().toDouble() else (stepped * 10).roundToInt() / 10.0
    return rounded.coerceIn(c.min, c.max)
}

private fun formatValue(v: Double, c: QuickActionControlItem): String =
    QuickActionWidgetItem.formatNumber(v) + (c.unit?.let { " $it" } ?: "")
