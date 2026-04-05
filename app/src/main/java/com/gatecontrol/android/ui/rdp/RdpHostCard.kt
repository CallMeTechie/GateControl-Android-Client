package com.gatecontrol.android.ui.rdp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.R
import com.gatecontrol.android.network.RdpRoute
import com.gatecontrol.android.ui.theme.DarkAccent
import com.gatecontrol.android.ui.theme.GateControlTheme

@Composable
fun RdpHostCard(
    route: RdpRoute,
    isSessionActive: Boolean,
    onConnect: () -> Unit,
    onWol: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isOnline = route.status?.online == true
    val inMaintenance = route.maintenanceEnabled == true
    val wolEnabled = route.wolEnabled == true
    val extra = GateControlTheme.extraColors

    val sessionBorder = if (isSessionActive) {
        BorderStroke(2.dp, DarkAccent.copy(alpha = 0.7f))
    } else {
        BorderStroke(1.dp, extra.border)
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = sessionBorder,
        colors = CardDefaults.cardColors(
            containerColor = if (isSessionActive) {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // --- Top row: name + status badge + WoL button ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status dot
                StatusDot(isOnline = isOnline, inMaintenance = inMaintenance)

                Spacer(modifier = Modifier.width(8.dp))

                // Host name
                Text(
                    text = route.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                // WoL button: visible only when offline and wolEnabled
                if (!isOnline && wolEnabled) {
                    IconButton(onClick = onWol, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Filled.PowerSettingsNew,
                            contentDescription = stringResource(R.string.rdp_wol),
                            tint = extra.warn,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // --- Host:port (monospace, muted) ---
            val displayHost = if (route.accessMode == "gateway" && route.externalHostname != null) {
                "${route.externalHostname}:${route.externalPort ?: route.port}"
            } else {
                "${route.host}:${route.port}"
            }
            Text(
                text = displayHost,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                color = extra.text3,
                modifier = Modifier.padding(top = 2.dp, start = 20.dp)
            )

            // --- Tags row: access mode + credential mode + session active ---
            Row(
                modifier = Modifier.padding(top = 8.dp, start = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Access mode tag
                TagChip(
                    text = when (route.accessMode.lowercase()) {
                        "gateway" -> "gateway"
                        else -> "direct"
                    },
                    containerColor = extra.blue.copy(alpha = 0.15f),
                    contentColor = extra.blue
                )

                // Credential mode tag
                val credLabel = when (route.credentialMode.lowercase()) {
                    "full" -> stringResource(R.string.rdp_credential_full)
                    "user_only" -> stringResource(R.string.rdp_credential_user)
                    else -> stringResource(R.string.rdp_credential_none)
                }
                TagChip(
                    text = credLabel,
                    containerColor = extra.accentDim.copy(alpha = 0.15f),
                    contentColor = extra.accentDim
                )

                // Session active badge
                if (isSessionActive) {
                    TagChip(
                        text = stringResource(R.string.rdp_session_active),
                        containerColor = DarkAccent.copy(alpha = 0.2f),
                        contentColor = DarkAccent
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

@Composable
private fun StatusDot(isOnline: Boolean, inMaintenance: Boolean) {
    val extra = GateControlTheme.extraColors
    val color = when {
        inMaintenance -> extra.warn
        isOnline -> Color(0xFF22C55E)
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = CircleShape,
        color = color,
        modifier = Modifier.size(10.dp)
    ) {}
}

@Composable
private fun TagChip(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .padding(0.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = containerColor
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}
