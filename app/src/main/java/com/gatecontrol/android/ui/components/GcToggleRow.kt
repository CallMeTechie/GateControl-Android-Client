package com.gatecontrol.android.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Stand-alone card holding one switch row. */
@Composable
fun GcToggleRow(
    icon: ImageVector?,
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    GcCard(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
        GcSwitchRow(
            label = label,
            description = description,
            checked = checked,
            onCheckedChange = onCheckedChange,
            icon = icon,
        )
    }
}

/** Switch row for use inside a [GcCard] (`.li` with `.sw`). */
@Composable
fun GcSwitchRow(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    GcListRow(
        title = label,
        description = description,
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        leading = icon?.let {
            {
                Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
        },
        trailing = {
            Row {
                GcSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
            }
        },
    )
}
