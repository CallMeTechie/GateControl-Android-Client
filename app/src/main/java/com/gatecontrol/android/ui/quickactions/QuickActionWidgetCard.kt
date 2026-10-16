package com.gatecontrol.android.ui.quickactions

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.quickactions.QuickActionControlItem
import com.gatecontrol.android.quickactions.QuickActionIcons
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionWidgetItem
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlin.math.roundToInt

/**
 * A pinned quick action as a widget on the start tab (half the width):
 * icon, title and — for a switch — the switch; the reading (value and
 * unit) big, the status line small, then the plugin's controls: a stepper
 * (− value +), a slider or a row of choice chips. A control change goes to
 * [onControl] (the view model sends it debounced); [adjusting] marks the
 * controls whose value is still on its way. A tap on a plain button
 * widget runs it; a long press does what the button's would ([onLongPress]).
 */
@Composable
fun QuickActionWidgetCard(
    item: QuickActionItem,
    running: Boolean,
    adjusting: Set<String>,
    onRun: () -> Unit,
    onLongPress: (() -> Unit)?,
    onControl: (controlId: String, value: Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val widget = item.widget ?: return
    val icon = remember(item.icon) { QuickActionIcons.vector(item.icon) }
    val (tint, background) = widgetColors(widget.state ?: item.state)
    val stateText = if (item.toggle) stringResource(if (item.isOn) R.string.quick_action_on else R.string.quick_action_off) else null

    GcCard(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                enabled = !running && (!item.toggle || onLongPress != null),
                role = Role.Button,
                onLongClickLabel = onLongPress?.let { stringResource(R.string.door_open_screen) },
                onLongClick = onLongPress,
                // A switch widget switches with its switch, a button widget with a tap anywhere.
                onClick = { if (!item.toggle) onRun() },
            )
            .semantics(mergeDescendants = false) { if (stateText != null) stateDescription = stateText },
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GcIconSquare(size = 32.dp, background = background) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                running -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = extra.accentText)
                item.toggle -> GcSwitch(checked = item.isOn, onCheckedChange = { onRun() })
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                widget.value ?: "–",
                style = MaterialTheme.typography.headlineMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (widget.unit != null && widget.value != null) {
                Text(widget.unit, style = MaterialTheme.typography.bodyMedium, color = extra.muted, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        if (widget.status != null) {
            Text(widget.status, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        widget.controls.forEach { control ->
            val busy = "${item.key}/${control.id}" in adjusting
            when (control.kind) {
                QuickActionControlItem.KIND_STEPPER -> StepperControl(control, busy, onChange = { onControl(control.id, it) })
                QuickActionControlItem.KIND_SLIDER -> SliderControl(control, busy, onChange = { onControl(control.id, it) })
                QuickActionControlItem.KIND_CHOICE -> ChoiceControl(control, onChange = { onControl(control.id, it) })
            }
        }
    }
}

/** Accent of a widget's state: on/good green, warn amber, crit red, off and none neutral. */
@Composable
private fun widgetColors(state: String?): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
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

/** Small chips, one per option, the chosen one filled; scrolls sideways when they do not fit. */
@Composable
private fun ChoiceControl(control: QuickActionControlItem, onChange: (String) -> Unit) {
    val extra = GateControlTheme.extraColors
    var local by remember(control.choice) { mutableStateOf(control.choice) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        control.options.forEach { option ->
            val selected = option.id == local
            Box(
                Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) extra.accentBg else extra.panel2)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { local = option.id; onChange(option.id) })
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) extra.accentText else extra.muted,
                    maxLines = 1,
                )
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
