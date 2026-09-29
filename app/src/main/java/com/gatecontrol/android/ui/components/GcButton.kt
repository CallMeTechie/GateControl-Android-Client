package com.gatecontrol.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.ui.theme.GateControlTheme

private val PillShape = RoundedCornerShape(24.dp)
private val ButtonPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp)

@Composable
private fun RowScope.ButtonContent(text: String, icon: ImageVector?, loading: Boolean, loadingColor: androidx.compose.ui.graphics.Color) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = loadingColor,
            strokeWidth = 2.dp,
        )
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Filled accent button (`.btn-pri`). */
@Composable
fun GcPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    fillWidth: Boolean = true,
    minHeight: Dp = 48.dp,
) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = minHeight),
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.primary,
            contentColor = scheme.onPrimary,
            disabledContainerColor = scheme.primary.copy(alpha = 0.45f),
            disabledContentColor = scheme.onPrimary.copy(alpha = 0.7f),
        ),
        contentPadding = ButtonPadding,
    ) {
        ButtonContent(text, icon, loading, scheme.onPrimary)
    }
}

/** Neutral button on the second panel tone with a border (`.btn-sec`). */
@Composable
fun GcOutlineButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    fillWidth: Boolean = true,
    minHeight: Dp = 48.dp,
    loading: Boolean = false,
) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = minHeight),
        shape = PillShape,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = extra.panel2,
            contentColor = scheme.onSurface,
            disabledContainerColor = extra.panel2.copy(alpha = 0.45f),
            disabledContentColor = scheme.onSurface.copy(alpha = 0.45f),
        ),
        border = BorderStroke(1.dp, extra.border2),
        contentPadding = ButtonPadding,
    ) {
        ButtonContent(text, icon, loading, scheme.onSurface)
    }
}

/** Text-only button in muted color (`.btn-ghost`). */
@Composable
fun GcSecondaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    fillWidth: Boolean = true,
) {
    val extra = GateControlTheme.extraColors
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = 48.dp),
        shape = PillShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = extra.muted,
            disabledContentColor = extra.muted.copy(alpha = 0.45f),
        ),
        contentPadding = ButtonPadding,
    ) {
        ButtonContent(text, icon, false, extra.muted)
    }
}
