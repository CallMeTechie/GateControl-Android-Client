package com.gatecontrol.android.ui.rdp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.network.RdpRoute
import com.gatecontrol.android.rdp.RdpProgress
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RdpConnectSheet(
    route: RdpRoute,
    connectState: ConnectState,
    onConnect: (password: String?, forceBypass: Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
    onWol: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        when (val state = connectState) {
            is ConnectState.Idle -> DetailView(
                route = route,
                onConnect = { onConnect(null, false) },
                onWol = onWol
            )
            is ConnectState.Connecting -> ConnectingView(
                progress = state.progress,
                onCancel = onDismiss
            )
            is ConnectState.NeedsPassword -> PasswordView(
                username = state.username,
                domain = state.domain,
                onConnect = { pwd -> onConnect(pwd, false) },
                onCancel = onDismiss
            )
            is ConnectState.MaintenanceWarning -> MaintenanceView(
                onConnectAnyway = { onConnect(null, true) },
                onCancel = onDismiss
            )
            is ConnectState.Connected -> ConnectedView(
                session = state.session,
                onDisconnect = onDisconnect
            )
            is ConnectState.Error -> ErrorView(
                message = state.message,
                onRetry = { onConnect(null, false) },
                onClose = onDismiss
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Detail view (Idle)
// ---------------------------------------------------------------------------

@Composable
private fun DetailView(
    route: RdpRoute,
    onConnect: () -> Unit,
    onWol: () -> Unit
) {
    val extra = GateControlTheme.extraColors
    val isOnline = route.status?.online == true
    val wolEnabled = route.wolEnabled == true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = route.name,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        HorizontalDivider(color = extra.border)

        // Address
        SheetInfoRow(
            label = "Address",
            value = "${route.host}:${route.port}",
            mono = true
        )

        // Access mode
        SheetInfoRow(
            label = "Access",
            value = route.accessMode.replaceFirstChar { it.uppercaseChar() }
        )

        // Credential mode
        val credLabel = when (route.credentialMode.lowercase()) {
            "full" -> stringResource(R.string.rdp_credential_full)
            "user_only" -> stringResource(R.string.rdp_credential_user)
            else -> stringResource(R.string.rdp_credential_none)
        }
        SheetInfoRow(label = "Credentials", value = credLabel)

        // Resolution
        val resolutionText = buildString {
            when {
                route.multiMonitor == true -> append("Multi-monitor")
                route.resolutionWidth != null && route.resolutionHeight != null ->
                    append("${route.resolutionWidth} x ${route.resolutionHeight}")
                route.resolutionMode != null -> {
                    val mode = route.resolutionMode!!
                    append(mode.substring(0, 1).uppercase() + mode.substring(1))
                }
                else -> append("Default")
            }
        }
        SheetInfoRow(label = "Resolution", value = resolutionText)

        // Color depth
        route.colorDepth?.let { depth ->
            SheetInfoRow(label = "Color depth", value = "$depth-bit")
        }

        // Redirects
        val redirects = buildList {
            if (route.redirectClipboard == true) add("Clipboard")
            if (route.redirectPrinters == true) add("Printers")
            if (route.redirectDrives == true) add("Drives")
        }
        if (redirects.isNotEmpty()) {
            SheetInfoRow(label = "Redirects", value = redirects.joinToString(", "))
        }

        // Audio
        route.audioMode?.let { audio ->
            SheetInfoRow(
                label = "Audio",
                value = audio.replaceFirstChar { it.uppercaseChar() }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // WoL button if offline
        if (!isOnline && wolEnabled) {
            GcOutlineButton(
                text = stringResource(R.string.rdp_wol),
                onClick = onWol
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        GcPrimaryButton(
            text = stringResource(R.string.rdp_connect),
            onClick = onConnect
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Connecting view (progress steps)
// ---------------------------------------------------------------------------

@Composable
private fun ConnectingView(
    progress: RdpProgress,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.rdp_connect),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        val steps = listOf(
            RdpProgress.VPN_CHECK to stringResource(R.string.rdp_progress_vpn),
            RdpProgress.TCP_CHECK to stringResource(R.string.rdp_progress_tcp),
            RdpProgress.CREDENTIALS to stringResource(R.string.rdp_progress_creds),
            RdpProgress.CLIENT_LAUNCH to stringResource(R.string.rdp_progress_launch),
            RdpProgress.SESSION_START to stringResource(R.string.rdp_progress_session),
            RdpProgress.COMPLETE to stringResource(R.string.rdp_progress_done)
        )

        steps.forEach { (step, label) ->
            val isActive = step == progress
            val isCompleted = step.step < progress.step

            ProgressStepRow(
                label = label,
                isActive = isActive,
                isCompleted = isCompleted
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        GcSecondaryButton(
            text = stringResource(R.string.cancel),
            onClick = onCancel
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ProgressStepRow(label: String, isActive: Boolean, isCompleted: Boolean) {
    val extra = GateControlTheme.extraColors
    val textColor = when {
        isCompleted -> extra.accentDim
        isActive -> MaterialTheme.colorScheme.onSurface
        else -> extra.text3
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        when {
            isCompleted -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = extra.accentDim,
                modifier = Modifier.size(20.dp)
            )
            isActive -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            else -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = extra.border2,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = label,
            style = if (isActive) {
                MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = textColor
        )
    }
}

// ---------------------------------------------------------------------------
// Password view (NeedsPassword)
// ---------------------------------------------------------------------------

@Composable
private fun PasswordView(
    username: String,
    domain: String?,
    onConnect: (String) -> Unit,
    onCancel: () -> Unit
) {
    var passwordInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.rdp_enter_password),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        // Username (read-only)
        val displayUser = if (!domain.isNullOrEmpty()) "$domain\\$username" else username
        SheetInfoRow(label = "Username", value = displayUser, mono = true)

        OutlinedTextField(
            value = passwordInput,
            onValueChange = { passwordInput = it },
            label = { Text(stringResource(R.string.rdp_password_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        GcPrimaryButton(
            text = stringResource(R.string.rdp_connect),
            onClick = { onConnect(passwordInput) },
            enabled = passwordInput.isNotEmpty()
        )

        GcSecondaryButton(
            text = stringResource(R.string.cancel),
            onClick = onCancel
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Maintenance warning view
// ---------------------------------------------------------------------------

@Composable
private fun MaintenanceView(
    onConnectAnyway: () -> Unit,
    onCancel: () -> Unit
) {
    val extra = GateControlTheme.extraColors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = extra.warn,
            modifier = Modifier.size(48.dp)
        )

        Text(
            text = stringResource(R.string.rdp_maintenance),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = extra.warn
        )

        Text(
            text = stringResource(R.string.rdp_maintenance_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(4.dp))

        GcPrimaryButton(
            text = stringResource(R.string.rdp_maintenance_bypass),
            onClick = onConnectAnyway
        )

        GcSecondaryButton(
            text = stringResource(R.string.cancel),
            onClick = onCancel
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Connected view
// ---------------------------------------------------------------------------

@Composable
private fun ConnectedView(
    session: com.gatecontrol.android.rdp.RdpSession,
    onDisconnect: () -> Unit
) {
    val extra = GateControlTheme.extraColors

    // Session duration timer (updates every second)
    var elapsed by remember { mutableLongStateOf(System.currentTimeMillis() - session.startTime) }
    LaunchedEffect(session.sessionId) {
        while (true) {
            delay(1_000)
            elapsed = System.currentTimeMillis() - session.startTime
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF22C55E),
            modifier = Modifier.size(48.dp)
        )

        Text(
            text = stringResource(R.string.rdp_session_active),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = formatElapsed(elapsed),
            style = MaterialTheme.typography.bodyMedium,
            color = extra.text3
        )

        Spacer(modifier = Modifier.height(4.dp))

        GcPrimaryButton(
            text = stringResource(R.string.rdp_disconnect),
            onClick = onDisconnect
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Error view
// ---------------------------------------------------------------------------

@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp)
        )

        Text(
            text = stringResource(R.string.error),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.error
        )

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(4.dp))

        GcPrimaryButton(
            text = stringResource(R.string.retry),
            onClick = onRetry
        )

        GcSecondaryButton(
            text = stringResource(R.string.close),
            onClick = onClose
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

@Composable
private fun SheetInfoRow(label: String, value: String, mono: Boolean = false) {
    val extra = GateControlTheme.extraColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = extra.text3
        )
        Text(
            text = value,
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatElapsed(elapsedMs: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(elapsedMs)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMs) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs) % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
