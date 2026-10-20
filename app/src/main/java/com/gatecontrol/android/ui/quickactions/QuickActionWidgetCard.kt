package com.gatecontrol.android.ui.quickactions

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.quickactions.QuickActionControlItem
import com.gatecontrol.android.quickactions.QuickActionDeviceGroup
import com.gatecontrol.android.quickactions.QuickActionIcons
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionReadingItem
import com.gatecontrol.android.quickactions.QuickActionWidgetItem
import com.gatecontrol.android.quickactions.WidgetPart
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.start.StartIcons
import com.gatecontrol.android.ui.start.longPressAnywhere
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlin.math.roundToInt

/**
 * A device pinned as a widget: icon, name and — when chosen — its power
 * switch in the head; then, as the person chose them ([parts]), the main
 * value (a temperature with the house symbol, the outdoor reading beside it
 * with sun and cloud), the small readings (an icon alone shows a state,
 * e.g. a green lock), the status line, the plugin's controls (stepper,
 * slider, choice) and the device's other actions as icon buttons. A control
 * change goes to [onControl] for the action carrying the widget (the view
 * model sends it debounced); an action button calls [onRun] (which asks
 * first where the action wants that). [running] is the key of the action
 * running now, [adjusting] the controls whose value is on its way. A long
 * press anywhere on it calls [onLongPress] (the widget's settings, the
 * door screen for a door), announced as [longPressLabel]. [fillHeight]:
 * the card takes the height of its row (beside a taller widget) and grows
 * its buttons and gaps instead of leaving the space below empty.
 * [interactive] false: a preview (the pin sheet), nothing reacts. [alert]: a Familie reminder
 * about the device is open — an exclamation mark on its icon; [planned]: one is set — a small
 * clock there instead (the „!“ wins).
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
    longPressLabel: String? = null,
    fillHeight: Boolean = false,
    alert: Boolean = false,
    planned: Boolean = false,
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
    val valueShown = widget != null && (widget.value != null || widget.label != null) && WidgetPart.VALUE in shown
    val outdoor = widget?.readings?.firstOrNull { it.id == OUTDOOR && it.value != null && WidgetPart.reading(it.id) in shown }
    val temperature = widget?.value != null && isTemperature(widget.unit)
    val climate = (valueShown && temperature) || (outdoor != null && (!valueShown || temperature))

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
            .clip(MaterialTheme.shapes.medium)
            .longPressAnywhere(longPressLabel, onLongPress?.takeIf { interactive }),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surface,
        border = BorderStroke(1.dp, extra.border),
    ) {
        Column(
            modifier = Modifier.padding(12.dp).then(if (fillHeight) Modifier.fillMaxHeight() else Modifier),
            verticalArrangement = if (fillHeight) SpaceBetweenAtLeast(8.dp) else Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    GcIconSquare(size = 32.dp, background = background) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    }
                    if (alert) {
                        AlertMark(Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp))
                    } else if (planned) {
                        PlannedMark(Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp))
                    }
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
            if (climate) {
                ClimateLine(widget?.takeIf { valueShown && temperature }, outdoor)
            } else if (valueShown) {
                MainValue(widget)
            }
            val readings = widget?.readings.orEmpty().filter { WidgetPart.reading(it.id) in shown && !(climate && it == outdoor) }
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
                // Beside a taller widget the buttons take the room (up to a size), not empty space below.
                Column(
                    modifier = if (fillHeight) Modifier.weight(1f, fill = false) else Modifier,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    actionButtons.chunked(buttonsPerRow(actionButtons.size)).forEach { row ->
                        Row(
                            (if (fillHeight) Modifier.weight(1f, fill = false) else Modifier).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            row.forEach { action ->
                                ActionButton(
                                    action,
                                    running = action.key == running,
                                    onClick = { if (interactive) onRun(action) },
                                    modifier = Modifier.weight(1f),
                                    grow = fillHeight,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Reading id of the outdoor temperature (shown beside the main value with sun and cloud). */
internal const val OUTDOOR = "outdoor"

/** Whether [unit] is a temperature's (°C, °F, °): then the value gets the house symbol instead of its name. */
internal fun isTemperature(unit: String?): Boolean = unit != null && (unit.startsWith("°") || unit == "℃" || unit == "℉")

/** "21,5" + "°C" → "21,5°": a temperature's unit as the degree sign alone, others after a space. */
internal fun compactValue(value: String, unit: String?): String = when {
    unit == null -> value
    isTemperature(unit) -> "$value°"
    else -> "$value $unit"
}

/**
 * Children spaced at least [spacing] apart; room left over goes between
 * them (top and bottom child at the edges). Intrinsic measurements count
 * [spacing], so a row of widgets gets the height the tallest needs.
 */
private class SpaceBetweenAtLeast(override val spacing: Dp) : Arrangement.Vertical {
    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        if (sizes.isEmpty()) return
        val min = spacing.roundToPx()
        val gap = if (sizes.size > 1) maxOf(min, (totalSize - sizes.sum()) / (sizes.size - 1)) else 0
        var y = 0
        sizes.forEachIndexed { i, size ->
            outPositions[i] = y
            y += size + gap
        }
    }
}

/**
 * The temperature inside (house with thermometer, [main] null: not shown)
 * and, beside it in the same line, the outdoor reading (sun and cloud).
 */
@Composable
private fun ClimateLine(main: QuickActionWidgetItem?, outdoor: QuickActionReadingItem?) {
    val extra = GateControlTheme.extraColors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (main?.value != null) {
            val text = compactValue(main.value, main.unit)
            TemperaturePart(
                icon = StartIcons.HouseThermo,
                iconTint = extra.accentText,
                text = text,
                textColor = MaterialTheme.colorScheme.onSurface,
                description = stringResource(R.string.start_climate_inside, listOfNotNull(main.value, main.unit).joinToString(" ")),
            )
        }
        if (outdoor?.value != null) {
            TemperaturePart(
                icon = StartIcons.CloudSun,
                iconTint = extra.blue,
                text = compactValue(outdoor.value, outdoor.unit),
                textColor = extra.muted,
                description = stringResource(R.string.start_climate_outside, listOfNotNull(outdoor.value, outdoor.unit).joinToString(" ")),
            )
        }
    }
}

@Composable
private fun TemperaturePart(icon: ImageVector, iconTint: Color, text: String, textColor: Color, description: String) {
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.titleLarge, color = textColor, maxLines = 1)
    }
}

/** The main value big with its unit, its name small below. */
@Composable
private fun MainValue(widget: QuickActionWidgetItem) {
    val extra = GateControlTheme.extraColors
    // core 1.187: an icon beside the value says what it is (a calendar for „this month“) instead of a text line
    val valueIcon = remember(widget.icon) { QuickActionIcons.vectorOrNull(widget.icon) }
    Column {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (valueIcon != null) {
                Icon(
                    valueIcon,
                    contentDescription = widget.label,
                    tint = extra.muted,
                    modifier = Modifier.padding(end = 2.dp, bottom = 5.dp).size(18.dp),
                )
            }
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
        if (widget.label != null && valueIcon == null) {
            Text(widget.label, style = MaterialTheme.typography.labelSmall, color = extra.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The „!“ of an open reminder (not a bell: a reminder waits for the person, it does not ring). */
@Composable
private fun AlertMark(modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    val description = stringResource(R.string.rules_open_alert)
    Box(
        modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(extra.warn)
            .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text("!", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black), color = Color.White, maxLines = 1)
    }
}

/** A reminder about the device is set: a small, quiet clock. */
@Composable
private fun PlannedMark(modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    val description = stringResource(R.string.rules_planned_hint)
    Box(
        modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, extra.border, CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(GcIcons.Clock, contentDescription = null, tint = extra.muted, modifier = Modifier.size(11.dp))
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
private fun ActionButton(action: QuickActionItem, running: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, grow: Boolean = false) {
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
            // Growing: as tall as the row allows, from 44 up to 64 dp (size first, then fill).
            .then(if (grow) Modifier.heightIn(min = 44.dp, max = 64.dp).fillMaxHeight() else Modifier.height(44.dp))
            .clip(shape)
            .background(fill)
            .border(1.dp, if (toned != null) toned.first.copy(alpha = 0.4f) else if (action.isOn) Color.Transparent else extra.border2, shape)
            .combinedClickable(enabled = !running, role = if (action.toggle) Role.Switch else Role.Button, onClick = onClick)
            .semantics { contentDescription = action.title },
        contentAlignment = Alignment.Center,
    ) {
        if (running) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = glyph)
        else Icon(icon, contentDescription = null, tint = glyph, modifier = Modifier.size(if (grow) 22.dp else 20.dp))
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
