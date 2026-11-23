package com.gatecontrol.android.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.gatecontrol.android.ui.theme.MonoFontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.ui.theme.TV_FOCUS_SCALE

// Building blocks of the mockup "GateControl TV" (.btn, .opt, .sw, .pill, input): 1 u = 10 dp.

enum class TvButtonStyle { Primary, Secondary, Warn }

/** `.btn`: 26 dp high, 7 dp corners; focus ring, glow and zoom of the TV. */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TvButtonStyle = TvButtonStyle.Secondary,
    enabled: Boolean = true,
) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(7.dp)
    val (bg, fg) = when (style) {
        TvButtonStyle.Primary -> colors.accent to colors.onAccent
        TvButtonStyle.Secondary -> colors.panel2 to colors.text
        TvButtonStyle.Warn -> colors.warnBg to colors.warn
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 34.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = bg,
            contentColor = fg,
            focusedContainerColor = bg,
            focusedContentColor = fg,
            disabledContainerColor = bg.copy(alpha = 0.45f),
            disabledContentColor = fg.copy(alpha = 0.6f),
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (style == TvButtonStyle.Secondary) Border(BorderStroke(1.dp, colors.line2), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, colors.focus), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(elevationColor = colors.focusGlow, elevation = 12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = TV_FOCUS_SCALE),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Trailing part of a settings row. */
sealed interface TvRowEnd {
    data class Switch(val on: Boolean) : TvRowEnd
    data object Arrow : TvRowEnd
    data object None : TvRowEnd
}

/**
 * `.opt`: a settings row (panel, 9 dp corners) with title, description and a switch or arrow. Focused it zooms a
 * little (1.02, the mockup's rows) with the ring.
 */
@Composable
fun TvOptionRow(
    title: String,
    description: String?,
    end: TvRowEnd,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(9.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                if (end is TvRowEnd.Switch) {
                    role = Role.Switch
                    stateDescription = if (end.on) "on" else "off"
                }
            },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.panel,
            contentColor = colors.text,
            focusedContainerColor = colors.panel2,
            focusedContentColor = colors.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.line), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.focus), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(elevationColor = colors.focusGlow, elevation = 10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = 41.dp).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = colors.text)
                    badge?.invoke()
                }
                if (!description.isNullOrBlank()) {
                    Text(description, style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
                }
            }
            when (end) {
                is TvRowEnd.Switch -> TvSwitch(end.on)
                TvRowEnd.Arrow -> Text("›", style = MaterialTheme.typography.titleMedium, color = colors.faint)
                TvRowEnd.None -> Unit
            }
        }
    }
}

/** `.sw`: the switch of a row (shown only; the row takes OK). */
@Composable
fun TvSwitch(on: Boolean) {
    val colors = GateControlTvTheme.colors
    Box(
        Modifier
            .size(width = 31.dp, height = 19.dp)
            .background(if (on) colors.accent else colors.line2, RoundedCornerShape(50)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 2.5.dp)
                .size(14.dp)
                .background(if (on) colors.onAccent else colors.faint, CircleShape),
        )
    }
}

enum class TvPillTone { Neutral, Accent, Info, Warn }

/** `.pill` with its dot. */
@Composable
fun TvPill(text: String, tone: TvPillTone = TvPillTone.Neutral, dot: Boolean = true) {
    val colors = GateControlTvTheme.colors
    val (bg, fg) = when (tone) {
        TvPillTone.Neutral -> colors.panel2 to colors.muted
        TvPillTone.Accent -> colors.accentBg to colors.accentText
        TvPillTone.Info -> colors.infoBg to colors.info
        TvPillTone.Warn -> colors.warnBg to colors.warn
    }
    Row(
        Modifier.height(22.dp).background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(6.dp).background(fg, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/**
 * A text field for the remote: focused with the D-pad (ring like every other element); OK opens the system keyboard
 * of Fire OS / Android TV. Mono for addresses and codes.
 */
@Composable
fun TvTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    mono: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    /** Digits shown as dots (the TV PIN). */
    password: Boolean = false,
    /** On the field itself (focus requests), [modifier] is on the column with the label. */
    fieldModifier: Modifier = Modifier,
) {
    val colors = GateControlTvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val style = MaterialTheme.typography.bodyLarge.let { if (mono) it.copy(fontFamily = MonoFontFamily) else it }
        .copy(color = colors.text)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.muted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = fieldModifier
                .fillMaxWidth()
                .semantics { contentDescription = label }
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.panel, RoundedCornerShape(8.dp))
                        .border(
                            if (focused) BorderStroke(2.dp, colors.focus) else BorderStroke(1.dp, colors.line2),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = style.copy(color = colors.faint))
                    inner()
                }
            },
        )
    }
}

