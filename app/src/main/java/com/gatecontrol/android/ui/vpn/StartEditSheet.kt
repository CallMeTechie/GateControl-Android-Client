package com.gatecontrol.android.ui.vpn

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * Menu from below after a long press on a free spot of the start tab:
 * "Schnellaktionen und Widgets" (the list, pinning, widget or button) and
 * "Inhalte der Info-Karte" (the picker).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartEditSheet(onActions: () -> Unit, onInfoCard: () -> Unit, onDismiss: () -> Unit) {
    val extra = GateControlTheme.extraColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.start_edit_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            GcListRow(
                title = stringResource(R.string.start_edit_actions),
                description = stringResource(R.string.start_edit_actions_desc),
                onClick = onActions,
                leading = { Icon(GcIcons.Zap, contentDescription = null, tint = extra.muted, modifier = Modifier.size(22.dp)) },
                trailing = { GcChevron() },
            )
            GcRowDivider(Modifier.padding(horizontal = 16.dp))
            GcListRow(
                title = stringResource(R.string.start_edit_info),
                onClick = onInfoCard,
                leading = { Icon(GcIcons.Sliders, contentDescription = null, tint = extra.muted, modifier = Modifier.size(22.dp)) },
                trailing = { GcChevron() },
            )
        }
    }
}
