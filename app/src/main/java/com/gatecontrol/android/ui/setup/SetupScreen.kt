package com.gatecontrol.android.ui.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatecontrol.android.R
import com.gatecontrol.android.common.EnrollmentLink
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.theme.GateControlTheme

@Composable
fun SetupScreen(
    viewModel: SetupViewModel = hiltViewModel(),
    onSetupComplete: () -> Unit,
    onNavigateToQr: () -> Unit,
    qrResult: String? = null,
    /** Opened from Settings to upgrade an existing (VPN-only) setup. */
    upgradeMode: Boolean = false,
    /** Jump straight into the QR scanner once (Settings → "connect with setup QR"). */
    autoOpenScanner: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsState()
    val extra = GateControlTheme.extraColors

    var scannerOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoOpenScanner && !scannerOpened && qrResult == null) {
            scannerOpened = true
            onNavigateToQr()
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Handle QR scan result
    LaunchedEffect(qrResult) {
        if (qrResult != null && qrResult.isNotEmpty()) {
            val enrollmentLink = EnrollmentLink.parse(qrResult)
            if (enrollmentLink != null) {
                // One-scan setup: VPN + API access, confirmed by the user first
                viewModel.onEnrollmentLink(enrollmentLink)
            } else if (qrResult.contains("[Interface]")) {
                // WireGuard config
                viewModel.importConfig(qrResult)
            } else if (qrResult.startsWith("gatecontrol://")) {
                // Deep link
                val uri = android.net.Uri.parse(qrResult)
                val url = uri.getQueryParameter("url") ?: ""
                val token = uri.getQueryParameter("token") ?: ""
                if (url.isNotEmpty() && token.isNotEmpty()) {
                    viewModel.handleDeepLink(url, token)
                }
            } else if (qrResult.startsWith("http")) {
                // Plain server URL
                viewModel.onServerUrlChanged(qrResult)
                viewModel.toggleManualExpanded()
            }
        }
    }

    val configFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val configText = inputStream?.bufferedReader()?.readText() ?: ""
                inputStream?.close()
                viewModel.importConfig(configText)
            } catch (_: Exception) {
                // Error reading file
            }
        }
    }

    // In upgrade mode the app is already set up — only leave once the new
    // setup has actually been applied.
    val done = if (upgradeMode) uiState.completedNow else uiState.isSetupComplete
    LaunchedEffect(done) {
        if (done) {
            onSetupComplete()
        }
    }

    uiState.pendingEnrollment?.let { link ->
        AlertDialog(
            onDismissRequest = viewModel::cancelEnrollment,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.setup_enroll_confirm_title), style = MaterialTheme.typography.headlineMedium) },
            text = { Text(stringResource(R.string.setup_enroll_confirm_body, link.serverUrl.removePrefix("https://"))) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmEnrollment) {
                    Text(stringResource(R.string.setup_enroll_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelEnrollment) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val minHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight)
                .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            GcIconSquare(size = 64.dp, background = extra.accentBg) {
                Icon(
                    imageVector = GcIcons.ShieldCheck,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = extra.accentText,
                )
            }

            Text(
                text = stringResource(R.string.setup_title),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.setup_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = extra.muted,
            )

            AnimatedVisibility(
                visible = uiState.isManualExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                ManualEntrySection(
                    serverUrl = uiState.serverUrl,
                    apiToken = uiState.apiToken,
                    isLoading = uiState.isLoading,
                    onServerUrlChanged = viewModel::onServerUrlChanged,
                    onApiTokenChanged = viewModel::onApiTokenChanged,
                    onTestConnection = viewModel::testConnection,
                    onSaveAndRegister = viewModel::saveAndRegister,
                )
            }

            if (uiState.statusMessage.isNotEmpty()) {
                SetupStatusMessage(
                    message = uiState.statusMessage,
                    type = uiState.statusType,
                )
            }

            Spacer(Modifier.weight(1f))

            GcPrimaryButton(
                text = stringResource(R.string.setup_qr),
                onClick = onNavigateToQr,
                enabled = !uiState.isLoading,
                icon = GcIcons.Qr,
            )
            GcOutlineButton(
                text = stringResource(R.string.setup_manual),
                onClick = { viewModel.toggleManualExpanded() },
                enabled = !uiState.isLoading,
                icon = GcIcons.Key,
            )
            GcSecondaryButton(
                text = stringResource(R.string.setup_import),
                onClick = { configFileLauncher.launch("*/*") },
                enabled = !uiState.isLoading,
            )
        }
    }
}

@Composable
private fun ManualEntrySection(
    serverUrl: String,
    apiToken: String,
    isLoading: Boolean,
    onServerUrlChanged: (String) -> Unit,
    onApiTokenChanged: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSaveAndRegister: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var tokenVisible by remember { mutableStateOf(false) }

    GcCard(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GcTextField(
            value = serverUrl,
            onValueChange = onServerUrlChanged,
            label = stringResource(R.string.settings_server_url),
            placeholder = stringResource(R.string.settings_server_url_hint),
            prefix = "https://",
            mono = true,
            enabled = !isLoading,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
            ),
        )
        GcTextField(
            value = apiToken,
            onValueChange = onApiTokenChanged,
            label = stringResource(R.string.setup_token_or_code),
            placeholder = stringResource(R.string.setup_token_or_code_hint),
            mono = true,
            enabled = !isLoading,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                    Icon(
                        imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(if (tokenVisible) R.string.common_hide else R.string.common_show),
                        tint = GateControlTheme.extraColors.muted,
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() },
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GcOutlineButton(
                text = stringResource(R.string.settings_test_connection),
                onClick = onTestConnection,
                enabled = !isLoading,
                modifier = Modifier.weight(1f),
            )
            GcPrimaryButton(
                text = stringResource(R.string.settings_save_register),
                onClick = onSaveAndRegister,
                enabled = !isLoading,
                loading = isLoading,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SetupStatusMessage(
    message: String,
    type: StatusType,
) {
    val (icon, tone) = when (type) {
        StatusType.SUCCESS -> GcIcons.Check to GcTone.Ok
        StatusType.ERROR -> GcIcons.Alert to GcTone.Error
        StatusType.INFO -> GcIcons.Alert to GcTone.Info
    }
    GcBanner(tone = tone, icon = icon) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
