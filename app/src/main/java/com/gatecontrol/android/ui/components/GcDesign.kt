package com.gatecontrol.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily

// ---------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------

/** Panel with a hairline border and 20 dp corners — the mockup's `.card`. */
@Composable
fun GcCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = GateControlTheme.extraColors.border,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = color,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** Uppercase section label (`.lbl`). */
@Composable
fun GcSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = GateControlTheme.extraColors.faint,
        modifier = modifier,
    )
}

/** Small label over a monospace value, as used in the details grids. */
@Composable
fun GcLabeledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        GcSectionLabel(label)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.let {
                if (mono) it.copy(fontFamily = MonoFontFamily) else it
            },
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

enum class GcTone { Neutral, Ok, Warn, Error, Info, Pro }

@Composable
private fun toneColors(tone: GcTone): Pair<Color, Color> {
    val extra = GateControlTheme.extraColors
    return when (tone) {
        GcTone.Neutral -> extra.panel2 to extra.muted
        GcTone.Ok -> extra.accentBg to extra.accentText
        GcTone.Warn -> extra.warnBg to extra.warn
        GcTone.Error -> extra.errorBg to MaterialTheme.colorScheme.error
        GcTone.Info -> extra.blueBg to extra.blue
        GcTone.Pro -> extra.proBg to extra.pro
    }
}

/** Status chip (`.chip`, `.c-ok`, …). */
@Composable
fun GcChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: GcTone = GcTone.Neutral,
    small: Boolean = false,
) {
    val (bg, fg) = toneColors(tone)
    Box(
        modifier = modifier
            .height(if (small) 24.dp else 28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = if (small) 12.sp else 13.sp),
            maxLines = 1,
        )
    }
}

/** Tinted notice box (warning / error / info banners). */
@Composable
fun GcBanner(
    modifier: Modifier = Modifier,
    tone: GcTone = GcTone.Info,
    icon: ImageVector? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val (bg, fg) = toneColors(tone)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        }
        content()
    }
}

// ---------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------

/** 52×32 switch from the mockup (`.sw`); announces itself as a switch. */
@Composable
fun GcSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val track by animateColorAsState(if (checked) scheme.primary else extra.panel2, label = "sw_track")
    val border by animateColorAsState(if (checked) scheme.primary else extra.border2, label = "sw_border")
    val knob by animateColorAsState(if (checked) scheme.onPrimary else extra.faint, label = "sw_knob")
    val knobSize by animateDpAsState(if (checked) 22.dp else 18.dp, label = "sw_size")
    val knobX by animateDpAsState(if (checked) 25.dp else 7.dp, label = "sw_x")

    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(toggle)
            .background(track)
            .border(2.dp, border, RoundedCornerShape(16.dp)),
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobX, y = (32.dp - knobSize) / 2)
                .size(knobSize)
                .clip(CircleShape)
                .background(if (enabled) knob else knob.copy(alpha = 0.5f)),
        )
    }
}

/** Segmented control (`.seg`). */
@Composable
fun <T> GcSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = GateControlTheme.extraColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(extra.panel2)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val isSel = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .selectable(selected = isSel, role = Role.Tab, onClick = { onSelect(value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isSel) MaterialTheme.colorScheme.onSurface else extra.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Filter chip (`.fchip`). */
@Composable
fun GcFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = GateControlTheme.extraColors
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) extra.accentBg else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else extra.border2, RoundedCornerShape(10.dp))
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) extra.accentText else extra.muted,
        )
    }
}

/** 48 dp round icon button (`.icb`). */
@Composable
fun GcIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    tint: Color = GateControlTheme.extraColors.muted,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Quick-access tile on the start screen (`.tile`). */
@Composable
fun GcTile(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    iconModifier: Modifier = Modifier,
) {
    val extra = GateControlTheme.extraColors
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 104.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) extra.accentBg else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (active) Color.Transparent else extra.border, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = iconModifier.size(22.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 2)
    }
}

/** List row inside a card (`.li`): title + description, optional leading and trailing content. */
@Composable
fun GcListRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    descriptionMono: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.let {
                        if (descriptionMono) it.copy(fontFamily = MonoFontFamily, fontSize = 12.sp) else it
                    },
                    color = GateControlTheme.extraColors.muted,
                )
            }
        }
        trailing?.invoke()
    }
}

/** Chevron used as trailing element on navigation rows. */
@Composable
fun GcChevron() {
    Icon(
        GcIcons.ChevronRight,
        contentDescription = null,
        tint = GateControlTheme.extraColors.faint,
        modifier = Modifier.size(20.dp),
    )
}

/** Rounded square holding an icon or initial (list leading element). */
@Composable
fun GcIconSquare(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    background: Color = GateControlTheme.extraColors.panel2,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Large page title of a tab screen. */
@Composable
fun GcScreenTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted)
        }
    }
}

/** Back arrow + title for sub pages (Split-Tunneling, Protokoll, …). */
@Composable
fun GcSubpageBar(
    title: String,
    onBack: () -> Unit,
    backDescription: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GcIconButton(GcIcons.Back, contentDescription = backDescription, onClick = onBack, tint = MaterialTheme.colorScheme.onBackground)
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** Colored status dot. */
@Composable
fun GcDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Thin divider between rows inside a card. */
@Composable
fun GcRowDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(GateControlTheme.extraColors.border),
    )
}

/** Fixed spacer helper for rows. */
@Composable
fun GcHSpace(width: Dp) = Box(Modifier.width(width))
