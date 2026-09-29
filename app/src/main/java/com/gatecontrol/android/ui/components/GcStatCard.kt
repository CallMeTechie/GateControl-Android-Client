package com.gatecontrol.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.ui.theme.GateControlTheme

/** Small card with a label over a big display number (Pi-hole stats). */
@Composable
fun GcStatCard(
    label: String,
    value: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    GcCard(
        modifier = modifier,
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        GcSectionLabel(label)
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = GateControlTheme.extraColors.muted,
            )
        }
    }
}
